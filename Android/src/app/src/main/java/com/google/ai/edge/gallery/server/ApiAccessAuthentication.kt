package com.google.ai.edge.gallery.server

import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8

private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "
private const val AUTHENTICATION_ERROR_MESSAGE =
  "Missing or invalid API key. Provide Authorization: Bearer <access-code>."

suspend fun ApplicationCall.requireApiAccessCode(
  accessCodeCoordinator: ApiAccessCodeCoordinator,
  gson: Gson,
): Boolean {
  val token = request.header(AUTHORIZATION_HEADER)?.bearerTokenOrNull()
  if (token != null && accessCodeCoordinator.matchesCurrentCode(token)) {
    return true
  }

  respondAuthenticationError(gson)
  return false
}

private fun String.bearerTokenOrNull(): String? {
  if (!startsWith(BEARER_PREFIX)) return null
  return removePrefix(BEARER_PREFIX).takeIf { it.isNotEmpty() }
}

private suspend fun ApplicationCall.respondAuthenticationError(gson: Gson) {
  val body =
    gson.toJson(
      ErrorResponse(
        ErrorDetail(
          message = AUTHENTICATION_ERROR_MESSAGE,
          type = "authentication_error",
          code = 401,
        )
      )
    )
  respondBytesWriter(
    contentType = ContentType.Application.Json,
    status = HttpStatusCode.Unauthorized,
  ) {
    writeStringUtf8(body)
  }
}
