package com.google.ai.edge.gallery.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.lang.reflect.Type

data class ChatMessage(
  val role: String,
  val content: ChatMessageContent? = null,
  val tool_calls: List<Map<String, Any>>? = null,
  val tool_call_id: String? = null,
) {
  val textContent: String?
    get() = content?.textValue()
}

sealed class ChatMessageContent {
  data class Text(val text: String) : ChatMessageContent()

  data class Parts(val parts: List<ChatCompletionContentPart>) : ChatMessageContent()

  fun textValue(): String? =
    when (this) {
      is Text -> text
      is Parts ->
        parts
          .filterIsInstance<ChatCompletionContentPart.Text>()
          .joinToString(separator = "") { it.text.orEmpty() }
    }
}

sealed class ChatCompletionContentPart {
  abstract val type: String?

  data class Text(
    val text: String? = null,
    override val type: String = "text",
  ) : ChatCompletionContentPart()

  data class ImageUrl(
    val image_url: ChatCompletionImageUrl? = null,
    override val type: String = "image_url",
  ) : ChatCompletionContentPart()

  data class InputAudio(
    val input_audio: ChatCompletionInputAudio? = null,
    override val type: String = "input_audio",
  ) : ChatCompletionContentPart()

  data class Unsupported(
    override val type: String? = null,
    val raw: JsonObject? = null,
  ) : ChatCompletionContentPart()
}

data class ChatCompletionImageUrl(
  val url: String? = null,
  val detail: String? = null,
)

data class ChatCompletionInputAudio(
  val data: String? = null,
  val format: String? = null,
)

object ChatMessageContentDeserializer : JsonDeserializer<ChatMessageContent> {
  override fun deserialize(
    json: JsonElement?,
    typeOfT: Type?,
    context: JsonDeserializationContext,
  ): ChatMessageContent? {
    if (json == null || json.isJsonNull) return null

    if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
      return ChatMessageContent.Text(json.asString)
    }

    if (json.isJsonArray) {
      return ChatMessageContent.Parts(json.asJsonArray.map { deserializePart(it, context) })
    }

    throw JsonParseException("ChatMessage.content must be a string or an array of content parts.")
  }

  private fun deserializePart(
    json: JsonElement,
    context: JsonDeserializationContext,
  ): ChatCompletionContentPart {
    if (!json.isJsonObject) {
      return ChatCompletionContentPart.Unsupported()
    }

    val obj = json.asJsonObject
    val type = obj.getStringOrNull("type")
    return when (type) {
      "text" -> context.deserialize(obj, ChatCompletionContentPart.Text::class.java)
      "image_url" -> context.deserialize(obj, ChatCompletionContentPart.ImageUrl::class.java)
      "input_audio" -> context.deserialize(obj, ChatCompletionContentPart.InputAudio::class.java)
      else -> ChatCompletionContentPart.Unsupported(type = type, raw = obj.deepCopy())
    }
  }
}

fun GsonBuilder.registerServerJsonAdapters(): GsonBuilder =
  apply { registerTypeAdapter(ChatMessageContent::class.java, ChatMessageContentDeserializer) }

fun createServerGson(): Gson = GsonBuilder().registerServerJsonAdapters().create()

private fun JsonObject.getStringOrNull(name: String): String? {
  val element = get(name) ?: return null
  return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
}
