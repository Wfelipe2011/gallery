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
import com.google.ai.edge.gallery.BuildConfig
import java.time.Instant
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
  @Inject lateinit var apiServerRuntimeCoordinator: ApiServerRuntimeCoordinator

  private var ktorServer: EmbeddedServer<*, *>? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private val gson = createServerGson()
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
        embeddedServer(CIO, port = SERVER_PORT) {
          configureServer(modelRepository, apiServerRuntimeCoordinator, gson, sessionManager)
        }
      ktorServer?.start(wait = false)
      Log.i(TAG, "Ktor server started on port $SERVER_PORT")
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    apiServerRuntimeCoordinator.cancelPendingIdleUnload()
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
  apiServerRuntimeCoordinator: ApiServerRuntimeCoordinator,
  gson: Gson,
  sessionManager: SessionManager,
) {
  install(ContentNegotiation) { gson { registerServerJsonAdapters() } }

  routing {
    post("/v1/chat/completions") {
      val requestStartMs = System.currentTimeMillis()
      var logStatusCode = 500
      var logError: String? = null
      var logRequestSummary = RequestSummary(0, false, false)
      var logResponseSummary: ResponseSummary? = null
      var inferenceLockHeld = false
      var acceptedApiRequest = false
      fun finishAcceptedApiRequest() {
        if (inferenceLockHeld) {
          modelRepository.releaseInference()
          inferenceLockHeld = false
        }
        if (acceptedApiRequest) {
          apiServerRuntimeCoordinator.scheduleIdleUnload { sessionManager.hasActiveSessions() }
          acceptedApiRequest = false
        }
      }
      try {
      val request =
        try {
          call.receive<ChatCompletionRequest>()
        } catch (e: Exception) {
          logStatusCode = 400
          logError = "Invalid request body: ${e.message}"
          call.respondError(
            HttpStatusCode.BadRequest,
            "Invalid request body: ${e.message}",
            "invalid_request_error",
            gson,
          )
          return@post
        }

      logRequestSummary = RequestSummary(
        messageCount = request.messages.size,
        hasTools = !request.tools.isNullOrEmpty(),
        hasSystemMessage = request.messages.any { it.role == "system" },
      )

      if (request.messages.isEmpty()) {
        logStatusCode = 400
        logError = "Field 'messages' is required and must not be empty."
        call.respondError(
          HttpStatusCode.BadRequest,
          "Field 'messages' is required and must not be empty.",
          "invalid_request_error",
          gson,
        )
        return@post
      }

      if (request.requestsUnsupportedAudioOutput()) {
        logStatusCode = 400
        logError = "Audio output is not supported by this API server."
        call.respondError(
          HttpStatusCode.BadRequest,
          "Audio output is not supported by this API server.",
          "invalid_request_error",
          gson,
        )
        return@post
      }

      val hasTools = !request.tools.isNullOrEmpty()
      if (hasTools && request.messages.hasMediaContentParts()) {
        logStatusCode = 400
        logError = "Multimodal tool-calling requests are not supported."
        call.respondError(
          HttpStatusCode.BadRequest,
          "Multimodal tool-calling requests are not supported by this API server.",
          "invalid_request_error",
          gson,
        )
        return@post
      }

      val parsedContent = ChatCompletionContentParser.parseMessages(request.messages)
      logRequestSummary = logRequestSummary.copy(
        imageCount = parsedContent.imageCount,
        audioClipCount = parsedContent.audioClipCount,
        requiredProfile = parsedContent.requiredProfile.name,
      )
      if (!parsedContent.isValid) {
        val errorMessage = parsedContent.validationErrors.firstOrNull()?.message ?: "Invalid message content."
        logStatusCode = 400
        logError = errorMessage
        call.respondError(
          HttpStatusCode.BadRequest,
          errorMessage,
          "invalid_request_error",
          gson,
        )
        return@post
      }

      if (!modelRepository.tryAcquireInference()) {
        logStatusCode = 503
        logError = "Server is busy. Another inference is in progress."
        call.respondError(
          HttpStatusCode.ServiceUnavailable,
          "Server is busy. Another inference is in progress.",
          "server_error",
          gson,
          code = 503,
        )
        return@post
      }
      inferenceLockHeld = true
      acceptedApiRequest = true
      apiServerRuntimeCoordinator.onRequestAccepted()

      val selectedModel = apiServerRuntimeCoordinator.getSelectedModel()
      if (
        parsedContent.requiredProfile.supportImage &&
          selectedModel != null &&
          !selectedModel.llmSupportImage
      ) {
        logStatusCode = 400
        logError = "Image input is not supported by the selected model."
        try {
          call.respondError(
            HttpStatusCode.BadRequest,
            "Image input is not supported by the selected model.",
            "invalid_request_error",
            gson,
          )
        } finally {
          finishAcceptedApiRequest()
        }
        return@post
      }
      if (
        parsedContent.requiredProfile.supportAudio &&
          selectedModel != null &&
          !selectedModel.llmSupportAudio
      ) {
        logStatusCode = 400
        logError = "Audio input is not supported by the selected model."
        try {
          call.respondError(
            HttpStatusCode.BadRequest,
            "Audio input is not supported by the selected model.",
            "invalid_request_error",
            gson,
          )
        } finally {
          finishAcceptedApiRequest()
        }
        return@post
      }

      val modelLoadResult = apiServerRuntimeCoordinator.ensureModelLoaded(parsedContent.requiredProfile)
      if (modelLoadResult is ApiServerModelLoadResult.Failure) {
        logStatusCode = 503
        logError = modelLoadResult.message
        try {
          call.respondError(
            HttpStatusCode.ServiceUnavailable,
            modelLoadResult.message,
            "server_error",
            gson,
            code = 503,
          )
        } finally {
          finishAcceptedApiRequest()
        }
        return@post
      }
      val loadedRuntime = modelLoadResult as ApiServerModelLoadResult.Success
      val model = loadedRuntime.model
      val runtimeProfile = loadedRuntime.profile

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
            supportImage = runtimeProfile.supportImage,
            supportAudio = runtimeProfile.supportAudio,
            systemInstruction = formatted.systemInstruction?.let { Contents.of(it) },
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
            images = parsedContent.imageBitmaps,
            audioClips = parsedContent.audioBytes,
          )

          if (isStreaming) {
            val streamingResponseSummary = StringBuilder()
            logStatusCode = 200
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
                  streamingResponseSummary.append(token)
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
                logResponseSummary = ResponseSummary(
                  content = streamingResponseSummary.toString().take(500),
                  finishReason = "stop",
                  toolCallCount = 0,
                )
                writeStringUtf8("data: [DONE]\n\n")
                flush()
              } finally {
                finishAcceptedApiRequest()
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
              logStatusCode = 200
              logResponseSummary = ResponseSummary(
                content = sb.toString().take(500),
                finishReason = "stop",
                toolCallCount = 0,
              )
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
                finishAcceptedApiRequest()
            }
          }
        } else {
          // ===== Tool-calling path =====
          val instance = model.instance as? LlmModelInstance
          if (instance == null) {
            // Model was unloaded — clean up any stale session keyed by the incoming ID.
            incomingSessionId?.let { sid ->
              sessionManager.getStaleSession(sid)?.let { stale ->
                stale.closeConversation()
                sessionManager.removeSession(sid)
              }
            }
            logStatusCode = 503
            logError = "Model instance not initialized."
            try {
              call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "Model instance not initialized.",
                "server_error",
                gson,
                code = 503,
              )
            } finally {
              finishAcceptedApiRequest()
            }
            return@post
          }

          val toolProviders: List<ToolProvider> = tools.map { toolMap ->
            tool(DynamicOpenApiTool(toolMap))
          }

          // Extract system instruction before filtering — it will be applied when a new
          // session is created. Existing sessions already have the instruction applied.
          val systemInstruction = request.messages.firstOrNull { it.role == "system" }?.textContent

          // Determine turn type BEFORE session lookup so we can recover the correct
          // session via tool_call_id when clients don't echo X-Session-Id.
          val nonSystemMessages = request.messages.filter { it.role != "system" }
          val isToolResultTurn = nonSystemMessages.lastOrNull()?.role == "tool"

          // For tool-result turns, extract the assistant's tool_call IDs so
          // SessionManager can find the session from the previous turn.
          val incomingCallIds: List<String> = if (isToolResultTurn) {
            nonSystemMessages
              .lastOrNull { msg -> msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty() }
              ?.tool_calls
              ?.mapNotNull { tc -> tc["id"] as? String }
              ?: emptyList()
          } else {
            emptyList()
          }

          val session = sessionManager.getOrCreate(
            sessionId = incomingSessionId,
            toolCallIds = incomingCallIds,
            model = model,
            toolProviders = toolProviders,
            systemInstruction = systemInstruction,
          )

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
              Content.ToolResponse(name = name, response = msg.textContent ?: "")
            }
            contentsToSend = Contents.of(toolResponses)
          } else {
            val userText = nonSystemMessages.lastOrNull { it.role == "user" }?.textContent ?: ""
            contentsToSend = Contents.of(listOf(Content.Text(userText)))
          }

          // Run inference on session conversation (non-streaming; tool calls are never streamed).
          val tokenChannel = Channel<String?>(capacity = Channel.UNLIMITED)
          var lastMessage: Message? = null

          val inferenceCallback = object : MessageCallback {
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
              logError = "LiteRT inference error: ${throwable.message ?: throwable.javaClass.simpleName}"
              tokenChannel.trySend(null)
            }
          }
          val messageToSend = if (isToolResultTurn) Message.tool(contentsToSend) else Message.user(contentsToSend)
          session.conversation.sendMessageAsync(messageToSend, inferenceCallback, emptyMap())

          var inferenceReleased = false
          try {
            val sb = StringBuilder()
            for (token in tokenChannel) {
              if (token == null) break
              sb.append(token)
            }

            val toolCallsList = lastMessage?.toolCalls?.takeIf { it.isNotEmpty() }

            val assistantMessage: AssistantMessage
            val finishReason: String

            // Generate call IDs once so both the JSON response and SSE chunks use
            // the same IDs, and the SessionManager can recover the session on
            // the tool-result turn even when X-Session-Id is not echoed back.
            val generatedCallIds: List<String> = toolCallsList?.map {
              "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"
            } ?: emptyList()

            if (toolCallsList != null) {
              val toolCallsJson = toolCallsList.mapIndexed { idx, tc ->
                mapOf(
                  "id" to generatedCallIds[idx],
                  "type" to "function",
                  "function" to mapOf(
                    "name" to tc.name,
                    "arguments" to gson.toJson(tc.arguments),
                  ),
                )
              }
              assistantMessage = AssistantMessage(content = null, tool_calls = toolCallsJson)
              finishReason = "tool_calls"
              // Register call IDs so the session can be recovered in the next turn.
              sessionManager.registerCallIds(generatedCallIds, session)
            } else {
              assistantMessage = AssistantMessage(content = sb.toString())
              finishReason = "stop"
            }

            call.response.headers.append("X-Session-Id", session.id)

            if (request.stream == true) {
              // ===== Streaming (SSE) response =====
              logStatusCode = 200
              logResponseSummary = ResponseSummary(
                content = (assistantMessage.content ?: "").take(500),
                finishReason = finishReason,
                toolCallCount = toolCallsList?.size ?: 0,
              )
              val includeUsage =
                (request.stream_options?.get("include_usage") as? Boolean) == true
              call.respondBytesWriter(
                contentType = ContentType.parse("text/event-stream"),
              ) {
                try {
                  if (toolCallsList != null) {
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
                            ChunkChoice(
                              delta = Delta(role = "assistant"),
                              finish_reason = null,
                            )
                          ),
                      ),
                    )
                    // 2. Per-tool: name chunk then arguments chunk (reuse pre-generated IDs)
                    toolCallsList.forEachIndexed { idx, tc ->
                      val callId = generatedCallIds[idx]
                      val argsJson = gson.toJson(tc.arguments)
                      writeChunk(
                        this,
                        gson,
                        ChatCompletionChunk(
                          id = requestId,
                          created = created,
                          model = request.model,
                          choices =
                            listOf(
                              ChunkChoice(
                                delta =
                                  Delta(
                                    tool_calls =
                                      listOf(
                                        ToolCallChunk(
                                          index = idx,
                                          id = callId,
                                          type = "function",
                                          function =
                                            ToolCallFunction(
                                              name = tc.name,
                                              arguments = "",
                                            ),
                                        )
                                      )
                                  ),
                                finish_reason = null,
                              )
                            ),
                        ),
                      )
                      writeChunk(
                        this,
                        gson,
                        ChatCompletionChunk(
                          id = requestId,
                          created = created,
                          model = request.model,
                          choices =
                            listOf(
                              ChunkChoice(
                                delta =
                                  Delta(
                                    tool_calls =
                                      listOf(
                                        ToolCallChunk(
                                          index = idx,
                                          function = ToolCallFunction(arguments = argsJson),
                                        )
                                      )
                                  ),
                                finish_reason = null,
                              )
                            ),
                        ),
                      )
                    }
                    // 3. Finish chunk
                    writeChunk(
                      this,
                      gson,
                      ChatCompletionChunk(
                        id = requestId,
                        created = created,
                        model = request.model,
                        choices =
                          listOf(ChunkChoice(delta = Delta(), finish_reason = "tool_calls")),
                      ),
                    )
                  } else {
                    // stop path
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
                            ChunkChoice(
                              delta = Delta(role = "assistant", content = ""),
                              finish_reason = null,
                            )
                          ),
                      ),
                    )
                    // 2. Content chunk
                    val content = sb.toString()
                    if (content.isNotEmpty()) {
                      writeChunk(
                        this,
                        gson,
                        ChatCompletionChunk(
                          id = requestId,
                          created = created,
                          model = request.model,
                          choices =
                            listOf(
                              ChunkChoice(
                                delta = Delta(content = content),
                                finish_reason = null,
                              )
                            ),
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
                  }
                  // Usage chunk (if requested)
                  if (includeUsage) {
                    writeChunk(
                      this,
                      gson,
                      ChatCompletionChunk(
                        id = requestId,
                        created = created,
                        model = request.model,
                        choices = emptyList(),
                        usage = CompletionUsage(),
                      ),
                    )
                  }
                  writeStringUtf8("data: [DONE]\n\n")
                  flush()
                } finally {
                  if (finishReason == "stop") {
                    sessionManager.closeSession(session)
                  }
                  finishAcceptedApiRequest()
                  inferenceReleased = true
                }
              }
            } else {
              // ===== Non-streaming (plain JSON) — unchanged behaviour =====
              logStatusCode = 200
              logResponseSummary = ResponseSummary(
                content = (assistantMessage.content ?: "").take(500),
                finishReason = finishReason,
                toolCallCount = toolCallsList?.size ?: 0,
              )
              val response =
                ChatCompletionResponse(
                  id = requestId,
                  created = created,
                  model = request.model,
                  choices =
                    listOf(CompletionChoice(message = assistantMessage, finish_reason = finishReason)),
                  usage = CompletionUsage(),
                )
              call.respond(response)
              // Session ends on final stop turn — restore the UI Conversation.
              if (finishReason == "stop") {
                sessionManager.closeSession(session)
              }
            }
          } finally {
            if (!inferenceReleased) finishAcceptedApiRequest()
          }
        }
      } catch (e: Exception) {
        logStatusCode = 500
        logError = "Unhandled error: ${e.message ?: e.javaClass.simpleName}"
        finishAcceptedApiRequest()
        Log.e(TAG, "Unhandled error in /v1/chat/completions", e)
        call.respondError(
          HttpStatusCode.InternalServerError,
          "Internal server error: ${e.message}",
          "server_error",
          gson,
        )
      }
      } finally {
        DebugLogRepository.add(
          LogEntry(
            timestamp = Instant.now().toString(),
            method = "POST",
            path = "/v1/chat/completions",
            statusCode = logStatusCode,
            durationMs = System.currentTimeMillis() - requestStartMs,
            requestSummary = logRequestSummary,
            responseSummary = logResponseSummary,
            error = logError,
          )
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

    get("/debug/logs") {
      if (!BuildConfig.DEBUG) {
        call.respond(HttpStatusCode.NotFound)
        return@get
      }
      val entries = DebugLogRepository.getAll()
      call.respond(mapOf("count" to entries.size, "entries" to entries))
    }
  }
}

// ---------------------------------------------------------------------------
// Session manager
// ---------------------------------------------------------------------------

private class SessionManager {
  private val sessions = ConcurrentHashMap<String, ServerSession>()
  // Maps tool_call_id → session_id for recovering sessions on tool-result turns
  // when the client (e.g. Hermes Agent) does not echo X-Session-Id.
  private val callIdToSessionId = ConcurrentHashMap<String, String>()

  fun getOrCreate(
    sessionId: String?,
    toolCallIds: List<String> = emptyList(),
    model: com.google.ai.edge.gallery.data.Model,
    toolProviders: List<ToolProvider>,
    systemInstruction: String? = null,
  ): ServerSession {
    evictExpired(model)

    // 1. Try matching by explicit X-Session-Id header.
    val existing = sessionId?.let { sessions[it] }
    if (existing != null) {
      existing.touch()
      return existing
    }

    // 2. Try matching by tool_call_id so tool-result turns reuse the correct
    //    conversation context from the preceding user/tool-calls turn.
    for (callId in toolCallIds) {
      val sid = callIdToSessionId[callId]
      if (sid != null) {
        val byCallId = sessions[sid]
        if (byCallId != null) {
          byCallId.touch()
          Log.d(TAG, "Recovered session $sid via tool_call_id $callId")
          return byCallId
        }
      }
    }

    // 3. No matching session — create a fresh one.
    val instance = model.instance as LlmModelInstance
    // Close the existing UI Conversation to satisfy LiteRT's one-session constraint.
    instance.conversation.close()
    val serverConv = instance.engine.createConversation(
      ConversationConfig(
        tools = toolProviders,
        automaticToolCalling = false,
        systemInstruction = systemInstruction?.let { Contents.of(it) },
      )
    )
    // Keep instance.conversation pointing at the active conversation.
    instance.conversation = serverConv
    val newId = UUID.randomUUID().toString()
    val session = ServerSession(id = newId, conversation = serverConv, model = model)
    sessions[newId] = session
    Log.d(TAG, "Created new server session: $newId (${sessions.size} total)")
    return session
  }

  /** Associates tool call IDs with this session for later recovery. */
  fun registerCallIds(callIds: List<String>, session: ServerSession) {
    callIds.forEach { callId -> callIdToSessionId[callId] = session.id }
    Log.d(TAG, "Registered ${callIds.size} call_id(s) for session ${session.id}")
  }

  /** Closes the server [Conversation] and restores the plain UI conversation. */
  fun closeSession(session: ServerSession) {
    sessions.remove(session.id)
    // Remove any call_id → session_id mappings for this session.
    callIdToSessionId.entries.removeIf { it.value == session.id }
    try {
      LlmChatModelHelper.resetConversation(
        model = session.model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = null,
        tools = emptyList(),
      )
      Log.d(TAG, "Restored UI conversation after session ${session.id}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore UI conversation after session ${session.id}", e)
    }
  }

  private fun evictExpired(model: com.google.ai.edge.gallery.data.Model) {
    val iter = sessions.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      if (entry.value.isExpired()) {
        Log.d(TAG, "Evicted expired session: ${entry.key}")
        iter.remove()
        try {
          LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = false,
            supportAudio = false,
            systemInstruction = null,
            tools = emptyList(),
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restore UI conversation on eviction", e)
        }
      }
    }
  }

  /** Returns a session by ID without touching it (for stale-session cleanup). */
  fun getStaleSession(sessionId: String): ServerSession? = sessions[sessionId]

  /** Removes a session by ID without restoring the UI conversation (used for stale cleanup). */
  fun removeSession(sessionId: String) {
    sessions.remove(sessionId)
  }

  fun hasActiveSessions(): Boolean {
    val iter = sessions.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      if (entry.value.isExpired()) {
        entry.value.closeConversation()
        iter.remove()
        callIdToSessionId.entries.removeIf { it.value == entry.key }
      }
    }
    return sessions.isNotEmpty()
  }

  fun closeAll() {
    sessions.values.forEach { session ->
      try {
        LlmChatModelHelper.resetConversation(
          model = session.model,
          supportImage = false,
          supportAudio = false,
          systemInstruction = null,
          tools = emptyList(),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to restore UI conversation in closeAll", e)
      }
    }
    sessions.clear()
    callIdToSessionId.clear()
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

private fun ChatCompletionRequest.requestsUnsupportedAudioOutput(): Boolean =
  audio != null || modalities.orEmpty().any { it.equals("audio", ignoreCase = true) }

private fun List<ChatMessage>.hasMediaContentParts(): Boolean =
  any { message ->
    val content = message.content as? ChatMessageContent.Parts ?: return@any false
    content.parts.any { part ->
      part is ChatCompletionContentPart.ImageUrl || part is ChatCompletionContentPart.InputAudio
    }
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
