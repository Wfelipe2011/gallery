package com.google.ai.edge.gallery.server

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson

/**
 * Wraps an OpenAI Chat Completions tool schema (from a request's `tools` array) as a LiteRT
 * [OpenApiTool].
 *
 * Hermes sends tools as `{type:"function", function:{name, description, parameters}}`. This class
 * extracts the inner `function` object and serializes it as the flat
 * `{name, description, parameters}` JSON string that LiteRT's `tool()` factory expects.
 *
 * [execute] is never called because [ConversationConfig.automaticToolCalling] is set to `false`;
 * tool dispatch is handled externally by the agent.
 */
class DynamicOpenApiTool(toolMap: Map<String, Any>) : OpenApiTool {
  private val gson = Gson()

  @Suppress("UNCHECKED_CAST")
  private val functionObject: Map<String, Any> =
    (toolMap["function"] as? Map<String, Any>) ?: toolMap

  override fun getToolDescriptionJsonString(): String = gson.toJson(functionObject)

  override fun execute(paramsJsonString: String): String =
    throw UnsupportedOperationException(
      "DynamicOpenApiTool.execute() must not be called with automaticToolCalling=false"
    )
}
