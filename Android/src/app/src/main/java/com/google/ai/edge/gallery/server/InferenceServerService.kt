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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
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
        embeddedServer(CIO, port = SERVER_PORT) { configureServer(modelRepository, gson) }
      ktorServer?.start(wait = false)
      Log.i(TAG, "Ktor server started on port $SERVER_PORT")
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
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

private fun Application.configureServer(modelRepository: ModelRepository, gson: Gson) {
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
        val formatted = PromptFormatter.format(request.messages)
        val isStreaming = request.stream == true
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000L

        // Reset conversation with optional system instruction before each request.
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
                    )
                  ),
                usage = CompletionUsage(),
              )
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
