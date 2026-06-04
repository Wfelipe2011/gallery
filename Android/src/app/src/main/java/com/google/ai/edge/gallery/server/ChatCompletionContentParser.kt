package com.google.ai.edge.gallery.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_COUNT
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT
import java.util.Locale

enum class RequiredRuntimeProfile {
  TEXT,
  IMAGE,
  AUDIO,
  IMAGE_AUDIO;

  val supportImage: Boolean
    get() = this == IMAGE || this == IMAGE_AUDIO

  val supportAudio: Boolean
    get() = this == AUDIO || this == IMAGE_AUDIO

  fun satisfies(required: RequiredRuntimeProfile): Boolean =
    (!required.supportImage || supportImage) && (!required.supportAudio || supportAudio)
}

data class ParsedChatCompletionContent(
  val orderedSegments: List<ParsedChatContentSegment>,
  val orderedTextSegments: List<ParsedTextSegment>,
  val orderedTextPrompt: String,
  val imageDataUrls: List<String>,
  val decodedImages: List<DecodedImageInput>,
  val audioBase64Payloads: List<String>,
  val decodedAudioClips: List<DecodedAudioInput>,
  val imageCount: Int,
  val audioClipCount: Int,
  val requiredProfile: RequiredRuntimeProfile,
  val validationErrors: List<ChatContentValidationError>,
) {
  val imageBytes: List<ByteArray>
    get() = decodedImages.map { it.bytes }

  val imageBitmaps: List<Bitmap>
    get() = decodedImages.map { it.bitmap }

  val audioBytes: List<ByteArray>
    get() = decodedAudioClips.map { it.bytes }

  val isValid: Boolean
    get() = validationErrors.isEmpty()
}

sealed class ParsedChatContentSegment {
  abstract val messageIndex: Int
  abstract val partIndex: Int?
  abstract val role: String

  data class Text(
    override val messageIndex: Int,
    override val partIndex: Int?,
    override val role: String,
    val text: String,
  ) : ParsedChatContentSegment()

  data class Image(
    override val messageIndex: Int,
    override val partIndex: Int?,
    override val role: String,
    val url: String,
  ) : ParsedChatContentSegment()

  data class Audio(
    override val messageIndex: Int,
    override val partIndex: Int?,
    override val role: String,
    val base64Data: String,
    val format: String?,
  ) : ParsedChatContentSegment()
}

data class ParsedTextSegment(
  val messageIndex: Int,
  val partIndex: Int?,
  val role: String,
  val text: String,
)

data class DecodedImageInput(
  val dataUrl: String,
  val mimeType: String,
  val bytes: ByteArray,
  val bitmap: Bitmap,
)

data class DecodedAudioInput(
  val base64Data: String,
  val bytes: ByteArray,
)

data class ChatContentValidationError(
  val code: ChatContentValidationErrorCode,
  val message: String,
  val messageIndex: Int? = null,
  val partIndex: Int? = null,
)

enum class ChatContentValidationErrorCode {
  UNSUPPORTED_CONTENT_PART,
  MISSING_TEXT,
  MISSING_IMAGE_URL,
  UNSUPPORTED_IMAGE_URL,
  INVALID_IMAGE_DATA_URL,
  INVALID_IMAGE_BASE64,
  INVALID_IMAGE_DATA,
  MISSING_AUDIO_DATA,
  UNSUPPORTED_AUDIO_FORMAT,
  INVALID_AUDIO_BASE64,
  INVALID_WAV_AUDIO,
  TOO_MANY_IMAGES,
  TOO_MANY_AUDIO_CLIPS,
}

data class MediaDecodeError(
  val code: ChatContentValidationErrorCode,
  val message: String,
)

sealed class MediaDecodeResult<out T> {
  data class Success<T>(val value: T) : MediaDecodeResult<T>()

  data class Failure(val error: MediaDecodeError) : MediaDecodeResult<Nothing>()
}

object ChatCompletionContentParser {
  fun parseMessages(
    messages: List<ChatMessage>,
    maxImageCount: Int = MAX_IMAGE_COUNT,
    maxAudioClipCount: Int = MAX_AUDIO_CLIP_COUNT,
  ): ParsedChatCompletionContent {
    val orderedSegments = mutableListOf<ParsedChatContentSegment>()
    val orderedTextSegments = mutableListOf<ParsedTextSegment>()
    val imageSegments = mutableListOf<ParsedChatContentSegment.Image>()
    val audioSegments = mutableListOf<ParsedChatContentSegment.Audio>()
    val validationErrors = mutableListOf<ChatContentValidationError>()

    messages.forEachIndexed { messageIndex, message ->
      when (val content = message.content) {
        null -> Unit
        is ChatMessageContent.Text -> {
          val textSegment = ParsedTextSegment(
            messageIndex = messageIndex,
            partIndex = null,
            role = message.role,
            text = content.text,
          )
          orderedTextSegments.add(textSegment)
          orderedSegments.add(
            ParsedChatContentSegment.Text(
              messageIndex = messageIndex,
              partIndex = null,
              role = message.role,
              text = content.text,
            )
          )
        }
        is ChatMessageContent.Parts -> {
          content.parts.forEachIndexed { partIndex, part ->
            parsePart(
              message = message,
              messageIndex = messageIndex,
              partIndex = partIndex,
              part = part,
              orderedSegments = orderedSegments,
              orderedTextSegments = orderedTextSegments,
              imageSegments = imageSegments,
              audioSegments = audioSegments,
              validationErrors = validationErrors,
            )
          }
        }
      }
    }

    if (imageSegments.size > maxImageCount) {
      validationErrors.add(
        ChatContentValidationError(
          code = ChatContentValidationErrorCode.TOO_MANY_IMAGES,
          message = "At most $maxImageCount image input(s) are supported.",
        )
      )
    }
    if (audioSegments.size > maxAudioClipCount) {
      validationErrors.add(
        ChatContentValidationError(
          code = ChatContentValidationErrorCode.TOO_MANY_AUDIO_CLIPS,
          message = "At most $maxAudioClipCount audio clip input(s) are supported.",
        )
      )
    }

    val decodedImages = mutableListOf<DecodedImageInput>()
    val decodedAudioClips = mutableListOf<DecodedAudioInput>()
    val limitExceeded = validationErrors.any {
      it.code == ChatContentValidationErrorCode.TOO_MANY_IMAGES ||
        it.code == ChatContentValidationErrorCode.TOO_MANY_AUDIO_CLIPS
    }

    if (!limitExceeded) {
      imageSegments.forEach { segment ->
        when (val decoded = ServerMediaDecoders.decodeImageDataUrl(segment.url)) {
          is MediaDecodeResult.Success -> decodedImages.add(decoded.value)
          is MediaDecodeResult.Failure ->
            validationErrors.add(
              ChatContentValidationError(
                code = decoded.error.code,
                message = decoded.error.message,
                messageIndex = segment.messageIndex,
                partIndex = segment.partIndex,
              )
            )
        }
      }

      audioSegments.forEach { segment ->
        if (!segment.format.equals("wav", ignoreCase = true)) return@forEach
        when (val decoded = ServerMediaDecoders.decodeWavBase64(segment.base64Data)) {
          is MediaDecodeResult.Success -> decodedAudioClips.add(decoded.value)
          is MediaDecodeResult.Failure ->
            validationErrors.add(
              ChatContentValidationError(
                code = decoded.error.code,
                message = decoded.error.message,
                messageIndex = segment.messageIndex,
                partIndex = segment.partIndex,
              )
            )
        }
      }
    }

    return ParsedChatCompletionContent(
      orderedSegments = orderedSegments,
      orderedTextSegments = orderedTextSegments,
      orderedTextPrompt = orderedTextSegments.joinToString(separator = "") { it.text },
      imageDataUrls = imageSegments.map { it.url }.filter { it.isImageDataUrl() },
      decodedImages = decodedImages,
      audioBase64Payloads = audioSegments.map { it.base64Data },
      decodedAudioClips = decodedAudioClips,
      imageCount = imageSegments.size,
      audioClipCount = audioSegments.size,
      requiredProfile = requiredProfile(imageSegments.isNotEmpty(), audioSegments.isNotEmpty()),
      validationErrors = validationErrors,
    )
  }

  private fun parsePart(
    message: ChatMessage,
    messageIndex: Int,
    partIndex: Int,
    part: ChatCompletionContentPart,
    orderedSegments: MutableList<ParsedChatContentSegment>,
    orderedTextSegments: MutableList<ParsedTextSegment>,
    imageSegments: MutableList<ParsedChatContentSegment.Image>,
    audioSegments: MutableList<ParsedChatContentSegment.Audio>,
    validationErrors: MutableList<ChatContentValidationError>,
  ) {
    when (part) {
      is ChatCompletionContentPart.Text -> {
        val text = part.text
        if (text == null) {
          validationErrors.add(
            ChatContentValidationError(
              code = ChatContentValidationErrorCode.MISSING_TEXT,
              message = "Text content part must include a string 'text' field.",
              messageIndex = messageIndex,
              partIndex = partIndex,
            )
          )
          return
        }
        val textSegment = ParsedTextSegment(
          messageIndex = messageIndex,
          partIndex = partIndex,
          role = message.role,
          text = text,
        )
        orderedTextSegments.add(textSegment)
        orderedSegments.add(
          ParsedChatContentSegment.Text(
            messageIndex = messageIndex,
            partIndex = partIndex,
            role = message.role,
            text = text,
          )
        )
      }
      is ChatCompletionContentPart.ImageUrl -> {
        val url = part.image_url?.url
        if (url.isNullOrBlank()) {
          validationErrors.add(
            ChatContentValidationError(
              code = ChatContentValidationErrorCode.MISSING_IMAGE_URL,
              message = "Image content part must include image_url.url.",
              messageIndex = messageIndex,
              partIndex = partIndex,
            )
          )
          return
        }
        val imageSegment = ParsedChatContentSegment.Image(
          messageIndex = messageIndex,
          partIndex = partIndex,
          role = message.role,
          url = url,
        )
        imageSegments.add(imageSegment)
        orderedSegments.add(imageSegment)
      }
      is ChatCompletionContentPart.InputAudio -> {
        val inputAudio = part.input_audio
        val data = inputAudio?.data
        if (data.isNullOrBlank()) {
          validationErrors.add(
            ChatContentValidationError(
              code = ChatContentValidationErrorCode.MISSING_AUDIO_DATA,
              message = "Audio content part must include input_audio.data.",
              messageIndex = messageIndex,
              partIndex = partIndex,
            )
          )
          return
        }
        if (!inputAudio.format.equals("wav", ignoreCase = true)) {
          validationErrors.add(
            ChatContentValidationError(
              code = ChatContentValidationErrorCode.UNSUPPORTED_AUDIO_FORMAT,
              message = "Only WAV audio input is supported.",
              messageIndex = messageIndex,
              partIndex = partIndex,
            )
          )
        }
        val audioSegment = ParsedChatContentSegment.Audio(
          messageIndex = messageIndex,
          partIndex = partIndex,
          role = message.role,
          base64Data = data,
          format = inputAudio.format,
        )
        audioSegments.add(audioSegment)
        orderedSegments.add(audioSegment)
      }
      is ChatCompletionContentPart.Unsupported -> {
        validationErrors.add(
          ChatContentValidationError(
            code = ChatContentValidationErrorCode.UNSUPPORTED_CONTENT_PART,
            message = "Unsupported content part type '${part.type ?: "unknown"}'.",
            messageIndex = messageIndex,
            partIndex = partIndex,
          )
        )
      }
    }
  }

  private fun requiredProfile(hasImages: Boolean, hasAudio: Boolean): RequiredRuntimeProfile =
    when {
      hasImages && hasAudio -> RequiredRuntimeProfile.IMAGE_AUDIO
      hasImages -> RequiredRuntimeProfile.IMAGE
      hasAudio -> RequiredRuntimeProfile.AUDIO
      else -> RequiredRuntimeProfile.TEXT
    }
}

object ServerMediaDecoders {
  fun decodeImageDataUrl(dataUrl: String): MediaDecodeResult<DecodedImageInput> {
    val parsed = parseImageDataUrl(dataUrl)
    if (parsed is MediaDecodeResult.Failure) return parsed
    parsed as MediaDecodeResult.Success

    val bytes = decodeBase64(
      base64Data = parsed.value.base64Data,
      invalidCode = ChatContentValidationErrorCode.INVALID_IMAGE_BASE64,
      invalidMessage = "Image data URL contains invalid base64 data.",
    ) ?: return MediaDecodeResult.Failure(
      MediaDecodeError(
        code = ChatContentValidationErrorCode.INVALID_IMAGE_BASE64,
        message = "Image data URL contains invalid base64 data.",
      )
    )

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      ?: return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.INVALID_IMAGE_DATA,
          message = "Image data URL does not contain a decodable image.",
        )
      )

    return MediaDecodeResult.Success(
      DecodedImageInput(
        dataUrl = dataUrl,
        mimeType = parsed.value.mimeType,
        bytes = bytes,
        bitmap = bitmap,
      )
    )
  }

  fun decodeWavBase64(base64Data: String): MediaDecodeResult<DecodedAudioInput> {
    val bytes = decodeBase64(
      base64Data = base64Data,
      invalidCode = ChatContentValidationErrorCode.INVALID_AUDIO_BASE64,
      invalidMessage = "Audio input contains invalid base64 data.",
    ) ?: return MediaDecodeResult.Failure(
      MediaDecodeError(
        code = ChatContentValidationErrorCode.INVALID_AUDIO_BASE64,
        message = "Audio input contains invalid base64 data.",
      )
    )

    if (!bytes.hasWavHeader()) {
      return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.INVALID_WAV_AUDIO,
          message = "Audio input must decode to a WAV payload.",
        )
      )
    }

    return MediaDecodeResult.Success(DecodedAudioInput(base64Data = base64Data, bytes = bytes))
  }

  private fun parseImageDataUrl(dataUrl: String): MediaDecodeResult<ParsedImageDataUrl> {
    if (!dataUrl.isImageDataUrl()) {
      return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.UNSUPPORTED_IMAGE_URL,
          message = "Only image data URLs are supported.",
        )
      )
    }

    val commaIndex = dataUrl.indexOf(',')
    if (commaIndex < 0) {
      return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.INVALID_IMAGE_DATA_URL,
          message = "Image data URL must include base64 data after a comma.",
        )
      )
    }

    val metadata = dataUrl.substring(startIndex = "data:".length, endIndex = commaIndex)
    val metadataParts = metadata.split(';')
    val mimeType = metadataParts.firstOrNull().orEmpty().lowercase(Locale.US)
    val isBase64 = metadataParts.any { it.equals("base64", ignoreCase = true) }

    if (!mimeType.startsWith("image/")) {
      return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.INVALID_IMAGE_DATA_URL,
          message = "Image data URL must use an image MIME type.",
        )
      )
    }
    if (!isBase64) {
      return MediaDecodeResult.Failure(
        MediaDecodeError(
          code = ChatContentValidationErrorCode.INVALID_IMAGE_DATA_URL,
          message = "Image data URL must be base64 encoded.",
        )
      )
    }

    return MediaDecodeResult.Success(
      ParsedImageDataUrl(
        mimeType = mimeType,
        base64Data = dataUrl.substring(startIndex = commaIndex + 1),
      )
    )
  }

  private fun decodeBase64(
    base64Data: String,
    invalidCode: ChatContentValidationErrorCode,
    invalidMessage: String,
  ): ByteArray? =
    try {
      Base64.decode(base64Data, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
      null
    }
}

private data class ParsedImageDataUrl(
  val mimeType: String,
  val base64Data: String,
)

private fun String.isImageDataUrl(): Boolean = startsWith("data:image/", ignoreCase = true)

private fun ByteArray.hasWavHeader(): Boolean =
  size >= 44 && matchesAscii(offset = 0, value = "RIFF") && matchesAscii(offset = 8, value = "WAVE")

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
  if (size < offset + value.length) return false
  return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}