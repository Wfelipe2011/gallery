package com.google.ai.edge.gallery.server

data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatMessage> = emptyList(),
  val stream: Boolean? = null,
  val stream_options: Map<String, Any>? = null,
  val temperature: Float? = null,
  val max_tokens: Int? = null,
  val tools: List<Map<String, Any>>? = null,
)
