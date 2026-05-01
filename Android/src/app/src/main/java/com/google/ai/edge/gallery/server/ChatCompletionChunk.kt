package com.google.ai.edge.gallery.server

data class ChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoice>,
)

data class ChunkChoice(
  val index: Int = 0,
  val delta: Delta,
  val finish_reason: String? = null,
)

data class Delta(
  val role: String? = null,
  val content: String? = null,
)
