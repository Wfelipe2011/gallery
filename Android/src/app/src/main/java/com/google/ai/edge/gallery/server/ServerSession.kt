package com.google.ai.edge.gallery.server

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litertlm.Conversation

private const val TAG = "ServerSession"
private const val SESSION_TTL_MS = 10L * 60 * 1000 // 10 minutes

/**
 * Holds an active server-side inference session with its own dedicated [Conversation].
 *
 * Each session is created on the first request from an agent (when no `X-Session-Id` header is
 * present) and evicted after [SESSION_TTL_MS] ms of inactivity.
 *
 * [model] is stored so the session manager can restore the UI [Conversation] when this session
 * ends.
 */
data class ServerSession(
  val id: String,
  val conversation: Conversation,
  val model: Model,
  @Volatile var lastUsedAt: Long = System.currentTimeMillis(),
) {
  fun touch() {
    lastUsedAt = System.currentTimeMillis()
  }

  fun isExpired(): Boolean = System.currentTimeMillis() - lastUsedAt > SESSION_TTL_MS

  fun closeConversation() {
    try {
      conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Error closing conversation for session $id", e)
    }
  }
}
