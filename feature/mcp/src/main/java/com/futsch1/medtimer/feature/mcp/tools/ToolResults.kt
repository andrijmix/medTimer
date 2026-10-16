package com.futsch1.medtimer.feature.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ToolResults {
    /**
     * A successful result. The JSON is sent both as text, which every client understands, and as structured content.
     * [warnings] are things the model should relay to the user although the call succeeded; they are added as a `warnings` list.
     */
    fun ok(json: JsonObject, warnings: List<String> = emptyList()): CallToolResult {
        val result = if (warnings.isEmpty()) json else JsonObject(json + ("warnings" to JsonArray(warnings.map(::JsonPrimitive))))
        return CallToolResult(content = listOf(TextContent(result.toString())), structuredContent = result)
    }

    /**
     * A failed tool call. Reported as a result with `isError` rather than as a protocol error, so the model sees the message and can retry.
     */
    fun error(message: String): CallToolResult = CallToolResult(content = listOf(TextContent(message)), isError = true)
}
