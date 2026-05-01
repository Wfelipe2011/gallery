package com.google.ai.edge.gallery.server

data class ChatMessage(
  val role: String,
  val content: String? = null,
  val tool_calls: List<Map<String, Any>>? = null,
  val tool_call_id: String? = null,
)
