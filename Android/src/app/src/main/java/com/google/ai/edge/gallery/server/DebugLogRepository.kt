package com.google.ai.edge.gallery.server

import java.time.Instant

data class RequestSummary(
    val messageCount: Int,
    val hasTools: Boolean,
    val hasSystemMessage: Boolean,
)

data class ResponseSummary(
    val content: String,
    val finishReason: String?,
    val toolCallCount: Int,
)

data class LogEntry(
    val timestamp: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestSummary: RequestSummary,
    val responseSummary: ResponseSummary?,
    val error: String?,
)

object DebugLogRepository {
    private const val MAX_ENTRIES = 100
    private val buffer = ArrayDeque<LogEntry>()

    @Synchronized
    fun add(entry: LogEntry) {
        if (buffer.size >= MAX_ENTRIES) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
    }

    @Synchronized
    fun getAll(): List<LogEntry> = buffer.reversed()

    @Synchronized
    fun clear() = buffer.clear()
}
