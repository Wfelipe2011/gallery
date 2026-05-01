package com.google.ai.edge.gallery.server

data class ChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<CompletionChoice>,
  val usage: CompletionUsage,
)

data class CompletionChoice(
  val index: Int = 0,
  val message: AssistantMessage,
  val finish_reason: String = "stop",
)

data class AssistantMessage(
  val role: String = "assistant",
  val content: String,
)

data class CompletionUsage(
  val prompt_tokens: Int = 0,
  val completion_tokens: Int = 0,
  val total_tokens: Int = 0,
)
