package com.google.ai.edge.gallery.server

import android.util.Log
import com.ngrok.Forwarder
import com.ngrok.Session
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val NGROK_TUNNEL_TAG = "NgrokTunnel"

enum class NgrokTunnelStatus {
  STOPPED,
  STARTING,
  RUNNING,
  ERROR,
}

data class NgrokTunnelState(
  val status: NgrokTunnelStatus = NgrokTunnelStatus.STOPPED,
  val publicUrl: String? = null,
  val errorMessage: String? = null,
)

@Singleton
class NgrokTunnelCoordinator @Inject constructor() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val _tunnelState = MutableStateFlow(NgrokTunnelState())

  val tunnelState = _tunnelState.asStateFlow()

  private var generation = 0L
  private var startJob: Job? = null
  private var session: Session? = null
  private var forwarder: Forwarder.Endpoint? = null

  fun startTunnel() {
    val startGeneration =
      synchronized(lock) {
        val status = _tunnelState.value.status
        if (status == NgrokTunnelStatus.STARTING || status == NgrokTunnelStatus.RUNNING) return

        generation += 1
        startJob?.cancel()
        _tunnelState.value = NgrokTunnelState(status = NgrokTunnelStatus.STARTING)
        generation
      }

    val job = scope.launch { openTunnel(startGeneration) }
    synchronized(lock) {
      if (generation == startGeneration) {
        startJob = job
      } else {
        job.cancel()
      }
    }
  }

  fun stopTunnel() {
    val resources =
      synchronized(lock) {
        generation += 1
        startJob?.cancel()
        startJob = null
        val currentForwarder = forwarder
        val currentSession = session
        forwarder = null
        session = null
        _tunnelState.value = NgrokTunnelState(status = NgrokTunnelStatus.STOPPED)
        TunnelResources(currentForwarder, currentSession)
      }

    resources.close()
  }

  private fun openTunnel(startGeneration: Long) {
    val token = NgrokRuntimeConfig.authtoken
    if (token.isEmpty()) {
      publishErrorIfCurrent(
        startGeneration,
        "Ngrok token is missing. Add ngrok.key and rebuild the app.",
      )
      return
    }

    NgrokRuntimeConfig.unsupportedAbiErrorMessage()?.let { message ->
      publishErrorIfCurrent(startGeneration, message)
      return
    }

    var openedSession: Session? = null
    var openedForwarder: Forwarder.Endpoint? = null
    try {
      NgrokNativeRuntimeLoader.load()
      openedSession = Session.withAuthtoken(token).connect()
      openedForwarder = openedSession.forwardHttp(
        openedSession.httpEndpoint(),
        URL(NgrokRuntimeConfig.LOCAL_FORWARD_URL),
      )
      val publicUrl = openedForwarder.getUrl()
      if (!publishRunningIfCurrent(startGeneration, openedSession, openedForwarder, publicUrl)) {
        TunnelResources(openedForwarder, openedSession).close()
      }
    } catch (throwable: Throwable) {
      TunnelResources(openedForwarder, openedSession).close()
      publishErrorIfCurrent(startGeneration, throwable.toSafeTunnelErrorMessage())
    }
  }

  private fun publishRunningIfCurrent(
    startGeneration: Long,
    openedSession: Session,
    openedForwarder: Forwarder.Endpoint,
    publicUrl: String,
  ): Boolean =
    synchronized(lock) {
      if (generation != startGeneration) return@synchronized false

      session = openedSession
      forwarder = openedForwarder
      startJob = null
      _tunnelState.value =
        NgrokTunnelState(status = NgrokTunnelStatus.RUNNING, publicUrl = publicUrl)
      true
    }

  private fun publishErrorIfCurrent(startGeneration: Long, message: String) {
    synchronized(lock) {
      if (generation != startGeneration) return

      startJob = null
      _tunnelState.value =
        NgrokTunnelState(status = NgrokTunnelStatus.ERROR, errorMessage = message)
    }
    Log.w(NGROK_TUNNEL_TAG, "Ngrok tunnel unavailable: $message")
  }

  private fun Throwable.toSafeTunnelErrorMessage(): String {
    val rawMessage = message?.take(300)?.ifBlank { null } ?: javaClass.simpleName
    return "Ngrok tunnel failed: ${rawMessage.redactNgrokSecret()}"
  }

  private fun String.redactNgrokSecret(): String {
    val token = NgrokRuntimeConfig.authtoken
    var sanitized = this
    if (token.isNotEmpty()) {
      sanitized = sanitized.replace(token, "[redacted]")
    }
    return sanitized.replace(
      Regex("(?i)(authtoken|authorization|bearer|token)(\\s*[=:]\\s*)\\S+"),
      "$1$2[redacted]",
    )
  }
}

private object NgrokNativeRuntimeLoader {
  @Volatile private var loaded = false

  fun load() {
    if (loaded) return

    synchronized(this) {
      if (loaded) return

      System.loadLibrary("ngrok_java")
      initializeRuntime()
      loaded = true
    }
  }

  private fun initializeRuntime() {
    val runtimeClass = Class.forName("com.ngrok.Runtime")
    val loggerClass = Class.forName("com.ngrok.Runtime\$Logger")
    val logger =
      runtimeClass.getDeclaredMethod("getLogger").apply { isAccessible = true }.invoke(null)
    runtimeClass
      .getDeclaredMethod("init", loggerClass)
      .apply { isAccessible = true }
      .invoke(null, logger)
  }
}

private data class TunnelResources(
  val forwarder: Forwarder.Endpoint?,
  val session: Session?,
) {
  fun close() {
    try {
      forwarder?.close()
    } catch (_: Throwable) {
      Log.w(NGROK_TUNNEL_TAG, "Failed to close ngrok forwarder.")
    }
    try {
      session?.close()
    } catch (_: Throwable) {
      Log.w(NGROK_TUNNEL_TAG, "Failed to close ngrok session.")
    }
  }
}
