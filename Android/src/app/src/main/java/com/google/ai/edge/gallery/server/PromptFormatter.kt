package com.google.ai.edge.gallery.server

data class FormattedPrompt(
  val systemInstruction: String?,
  val userPrompt: String,
)

object PromptFormatter {

  /**
   * Converts an OpenAI messages array into a FormattedPrompt suitable for LiteRT LM.
   *
   * System message → systemInstruction (passed to ConversationConfig).
   * All other turns → concatenated with Gemma turn markers.
   * If only a single user message is present, it is returned as-is without turn markers.
   */
  fun format(messages: List<ChatMessage>): FormattedPrompt {
    val systemMessage = messages.firstOrNull { it.role == "system" }
    val conversationMessages = messages.filter { it.role != "system" }

    val systemInstruction = systemMessage?.content?.takeIf { it.isNotEmpty() }

    val userPrompt = if (conversationMessages.size == 1 && conversationMessages[0].role == "user") {
      // Single user message — pass through directly.
      conversationMessages[0].content ?: ""
    } else {
      // Multi-turn: format with Gemma turn markers.
      // Handles role:"tool" messages by rendering them with their role label.
      val sb = StringBuilder()
      for (msg in conversationMessages) {
        sb.append("<start_of_turn>${msg.role}\n${msg.content ?: ""}<end_of_turn>\n")
      }
      sb.append("<start_of_turn>model\n")
      sb.toString()
    }

    return FormattedPrompt(systemInstruction = systemInstruction, userPrompt = userPrompt)
  }
}
