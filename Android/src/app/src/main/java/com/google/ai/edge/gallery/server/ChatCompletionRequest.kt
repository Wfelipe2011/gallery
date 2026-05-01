package com.google.ai.edge.gallery.server

data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatMessage> = emptyList(),
  val stream: Boolean? = null,
  val temperature: Float? = null,
  val max_tokens: Int? = null,
)
