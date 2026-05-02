package com.google.ai.edge.gallery.server

data class ChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoice>,
  val usage: CompletionUsage? = null,
)

data class ChunkChoice(
  val index: Int = 0,
  val delta: Delta,
  val finish_reason: String? = null,
)

data class Delta(
  val role: String? = null,
  val content: String? = null,
  val tool_calls: List<ToolCallChunk>? = null,
)

data class ToolCallChunk(
  val index: Int,
  val id: String? = null,
  val type: String? = null,
  val function: ToolCallFunction? = null,
)

data class ToolCallFunction(
  val name: String? = null,
  val arguments: String? = null,
)
