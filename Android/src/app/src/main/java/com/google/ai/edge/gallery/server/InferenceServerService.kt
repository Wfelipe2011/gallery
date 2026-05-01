package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.google.ai.edge.gallery.data.ModelRepository
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

private const val TAG = "InferenceServerService"
private const val SERVER_PORT = 8080
private const val NOTIFICATION_CHANNEL_ID = "local_api_server"
private const val NOTIFICATION_ID = 1001

@AndroidEntryPoint
class InferenceServerService : Service() {

  @Inject lateinit var modelRepository: ModelRepository

  private var ktorServer: EmbeddedServer<*, *>? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private val gson = Gson()
  private val sessionManager = SessionManager()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    createNotificationChannel()
    val notification =
      Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Local API Server")
        .setContentText("Listening on port $SERVER_PORT")
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .setOngoing(true)
        .build()
    startForeground(NOTIFICATION_ID, notification)

    // Acquire WifiLock to keep the radio alive when the screen is off.
    val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    wifiLock =
      wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InferenceServerService")
    wifiLock?.acquire()

    if (ktorServer == null) {
      ktorServer =
        embeddedServer(CIO, port = SERVER_PORT) { configureServer(modelRepository, gson, sessionManager) }
      ktorServer?.start(wait = false)
      Log.i(TAG, "Ktor server started on port $SERVER_PORT")
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    sessionManager.closeAll()
    runBlocking { ktorServer?.stop(1_000, 5_000) }
    ktorServer = null
    wifiLock?.release()
    wifiLock = null
    Log.i(TAG, "Ktor server stopped")
  }

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "Local API Server",
        NotificationManager.IMPORTANCE_LOW,
      )
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(channel)
  }
}

// ---------------------------------------------------------------------------
// Ktor application module
// ---------------------------------------------------------------------------

private fun Application.configureServer(
  modelRepository: ModelRepository,
  gson: Gson,
  sessionManager: SessionManager,
) {
  install(ContentNegotiation) { gson() }

  routing {
    post("/v1/chat/completions") {
      val request =
        try {
          call.receive<ChatCompletionRequest>()
        } catch (e: Exception) {
          call.respondError(
            HttpStatusCode.BadRequest,
            "Invalid request body: ${e.message}",
            "invalid_request_error",
            gson,
          )
          return@post
        }

      if (request.messages.isEmpty()) {
        call.respondError(
          HttpStatusCode.BadRequest,
          "Field 'messages' is required and must not be empty.",
          "invalid_request_error",
          gson,
        )
        return@post
      }

      val model = modelRepository.getActiveModel()
      if (model == null) {
        call.respondError(
          HttpStatusCode.ServiceUnavailable,
          "No model is currently loaded.",
          "server_error",
          gson,
          code = 503,
        )
        return@post
      }

      if (!modelRepository.tryAcquireInference()) {
        call.respondError(
          HttpStatusCode.ServiceUnavailable,
          "Server is busy. Another inference is in progress.",
          "server_error",
          gson,
          code = 503,
        )
        return@post
      }

      try {
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000L
        val incomingSessionId = call.request.header("X-Session-Id")
        val tools = request.tools

        if (tools.isNullOrEmpty()) {
          // ===== Existing path (no tools) =====
          val formatted = PromptFormatter.format(request.messages)
          val isStreaming = request.stream == true

          // Reset conversation before each stateless request.
          LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = false,
            supportAudio = false,
            systemInstruction = null,
          )

          // Channel bridges LiteRT's callback thread → Ktor coroutine.
          // null sentinel signals end-of-stream.
          val channel = Channel<String?>(capacity = Channel.UNLIMITED)

          val resultListener: (String, Boolean, String?) -> Unit = { token, done, _ ->
            if (done) {
              channel.trySend(null)
            } else if (token.isNotEmpty() && !token.startsWith("<ctrl")) {
              channel.trySend(token)
            }
          }

          LlmChatModelHelper.runInference(
            model = model,
            input = formatted.userPrompt,
            resultListener = resultListener,
            cleanUpListener = {},
            onError = { errorMsg ->
              Log.e(TAG, "Inference error: $errorMsg")
              channel.trySend(null)
            },
          )

          if (isStreaming) {
            call.respondBytesWriter(
              contentType = ContentType.parse("text/event-stream"),
            ) {
              try {
                // 1. Role chunk
                writeChunk(
                  this,
                  gson,
                  ChatCompletionChunk(
                    id = requestId,
                    created = created,
                    model = request.model,
                    choices =
                      listOf(
                        ChunkChoice(delta = Delta(role = "assistant", content = ""), finish_reason = null)
                      ),
                  ),
                )

                // 2. Content chunks
                for (token in channel) {
                  if (token == null) break
                  writeChunk(
                    this,
                    gson,
                    ChatCompletionChunk(
                      id = requestId,
                      created = created,
                      model = request.model,
                      choices = listOf(ChunkChoice(delta = Delta(content = token), finish_reason = null)),
                    ),
                  )
                }

                // 3. Stop chunk
                writeChunk(
                  this,
                  gson,
                  ChatCompletionChunk(
                    id = requestId,
                    created = created,
                    model = request.model,
                    choices = listOf(ChunkChoice(delta = Delta(), finish_reason = "stop")),
                  ),
                )

                // 4. [DONE]
                writeStringUtf8("data: [DONE]\n\n")
                flush()
              } finally {
                modelRepository.releaseInference()
              }
            }
          } else {
            // Non-streaming: collect all tokens then respond.
            try {
              val sb = StringBuilder()
              for (token in channel) {
                if (token == null) break
                sb.append(token)
              }
              val response =
                ChatCompletionResponse(
                  id = requestId,
                  created = created,
                  model = request.model,
                  choices =
                    listOf(
                      CompletionChoice(
                        message = AssistantMessage(content = sb.toString()),
                        finish_reason = "stop",
                      )
                    ),
                  usage = CompletionUsage(),
                )
              call.respond(response)
            } finally {
              modelRepository.releaseInference()
            }
          }
        } else {
          // ===== Tool-calling path =====
          val instance = model.instance as? LlmModelInstance
          if (instance == null) {
            modelRepository.releaseInference()
            call.respondError(
              HttpStatusCode.ServiceUnavailable,
              "Model instance not initialized.",
              "server_error",
              gson,
              code = 503,
            )
            return@post
          }

          val toolProviders: List<ToolProvider> = tools.map { toolMap ->
            tool(DynamicOpenApiTool(toolMap))
          }

          val session = sessionManager.getOrCreate(
            sessionId = incomingSessionId,
            engine = instance.engine,
            toolProviders = toolProviders,
          )

          // Determine turn type: tool-result turn vs normal user turn.
          val nonSystemMessages = request.messages.filter { it.role != "system" }
          val isToolResultTurn = nonSystemMessages.lastOrNull()?.role == "tool"

          val contentsToSend: Contents
          if (isToolResultTurn) {
            // Find the preceding assistant message with tool_calls to build id→name mapping.
            val assistantMsg = nonSystemMessages.lastOrNull { msg ->
              msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()
            }
            val idToName: Map<String, String> =
              assistantMsg?.tool_calls?.associate { tc ->
                val id = tc["id"] as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val fn = tc["function"] as? Map<String, Any>
                id to (fn?.get("name") as? String ?: "")
              } ?: emptyMap()

            val toolMessages = nonSystemMessages.filter { it.role == "tool" }
            val toolResponses: List<Content> = toolMessages.map { msg ->
              val name = idToName[msg.tool_call_id] ?: (msg.tool_call_id ?: "unknown")
              Content.ToolResponse(name = name, response = msg.content ?: "")
            }
            contentsToSend = Contents.of(toolResponses)
          } else {
            val userText = nonSystemMessages.lastOrNull { it.role == "user" }?.content ?: ""
            contentsToSend = Contents.of(listOf(Content.Text(userText)))
          }

          // Run inference on session conversation (non-streaming; tool calls are never streamed).
          val tokenChannel = Channel<String?>(capacity = Channel.UNLIMITED)
          var lastMessage: Message? = null

          session.conversation.sendMessageAsync(
            contentsToSend,
            object : MessageCallback {
              override fun onMessage(message: Message) {
                lastMessage = message
                val text = message.toString()
                if (text.isNotEmpty() && !text.startsWith("<ctrl")) {
                  tokenChannel.trySend(text)
                }
              }

              override fun onDone() {
                tokenChannel.trySend(null)
              }

              override fun onError(throwable: Throwable) {
                Log.e(TAG, "Tool session inference error (session=${session.id})", throwable)
                tokenChannel.trySend(null)
              }
            },
            emptyMap(),
          )

          try {
            val sb = StringBuilder()
            for (token in tokenChannel) {
              if (token == null) break
              sb.append(token)
            }

            val toolCallsList = lastMessage?.toolCalls?.takeIf { it.isNotEmpty() }

            val assistantMessage: AssistantMessage
            val finishReason: String

            if (toolCallsList != null) {
              val toolCallsJson = toolCallsList.map { tc ->
                mapOf(
                  "id" to "call_${UUID.randomUUID().toString().replace("-", "").take(24)}",
                  "type" to "function",
                  "function" to mapOf(
                    "name" to tc.name,
                    "arguments" to gson.toJson(tc.arguments),
                  ),
                )
              }
              assistantMessage = AssistantMessage(content = null, tool_calls = toolCallsJson)
              finishReason = "tool_calls"
            } else {
              assistantMessage = AssistantMessage(content = sb.toString())
              finishReason = "stop"
            }

            val response = ChatCompletionResponse(
              id = requestId,
              created = created,
              model = request.model,
              choices = listOf(
                CompletionChoice(message = assistantMessage, finish_reason = finishReason)
              ),
              usage = CompletionUsage(),
            )
            call.response.headers.append("X-Session-Id", session.id)
            call.respond(response)
          } finally {
            modelRepository.releaseInference()
          }
        }
      } catch (e: Exception) {
        modelRepository.releaseInference()
        Log.e(TAG, "Unhandled error in /v1/chat/completions", e)
        call.respondError(
          HttpStatusCode.InternalServerError,
          "Internal server error: ${e.message}",
          "server_error",
          gson,
        )
      }
    }

    get("/v1/models") {
      val models = modelRepository.getDownloadedModels().map { model ->
        ModelObject(id = model.name, created = System.currentTimeMillis() / 1000L)
      }
      call.respond(ModelsListResponse(data = models))
    }

    get("/v1/models/{modelId}") {
      val modelId = call.parameters["modelId"] ?: ""
      val model = modelRepository.getDownloadedModels().find { it.name == modelId }
      if (model != null) {
        call.respond(ModelObject(id = model.name, created = System.currentTimeMillis() / 1000L))
      } else {
        call.respondError(
          HttpStatusCode.NotFound,
          "Model '$modelId' not found",
          "invalid_request_error",
          gson,
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Session manager
// ---------------------------------------------------------------------------

private class SessionManager {
  private val sessions = ConcurrentHashMap<String, ServerSession>()

  fun getOrCreate(
    sessionId: String?,
    engine: com.google.ai.edge.litertlm.Engine,
    toolProviders: List<ToolProvider>,
  ): ServerSession {
    evictExpired()
    val existing = sessionId?.let { sessions[it] }
    if (existing != null) {
      existing.touch()
      return existing
    }
    val newId = UUID.randomUUID().toString()
    val conversation = engine.createConversation(
      ConversationConfig(
        tools = toolProviders,
        automaticToolCalling = false,
      )
    )
    val session = ServerSession(id = newId, conversation = conversation)
    sessions[newId] = session
    Log.d(TAG, "Created new server session: $newId (${sessions.size} total)")
    return session
  }

  private fun evictExpired() {
    val iter = sessions.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      if (entry.value.isExpired()) {
        entry.value.close()
        iter.remove()
        Log.d(TAG, "Evicted expired session: ${entry.key}")
      }
    }
  }

  fun closeAll() {
    sessions.values.forEach { it.close() }
    sessions.clear()
    Log.d(TAG, "All server sessions closed")
  }
}

// ---------------------------------------------------------------------------
// SSE helpers
// ---------------------------------------------------------------------------

private suspend fun writeChunk(channel: ByteWriteChannel, gson: Gson, chunk: ChatCompletionChunk) {
  channel.writeStringUtf8("data: ${gson.toJson(chunk)}\n\n")
  channel.flush()
}

// ---------------------------------------------------------------------------
// Error response helper
// ---------------------------------------------------------------------------

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
  status: HttpStatusCode,
  message: String,
  type: String,
  gson: Gson,
  code: Int? = null,
) {
  val body = gson.toJson(ErrorResponse(ErrorDetail(message = message, type = type, code = code)))
  respondBytesWriter(
    contentType = ContentType.Application.Json,
    status = status,
  ) {
    writeStringUtf8(body)
  }
}
