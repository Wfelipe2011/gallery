package com.google.ai.edge.gallery.server

data class ErrorDetail(
  val message: String,
  val type: String,
  val code: Int? = null,
)

data class ErrorResponse(
  val error: ErrorDetail,
)
