package com.futsch1.medtimer.feature.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * One MCP tool, decoupled from the SDK's registration API so handlers can be unit-tested with a plain [JsonObject] of arguments.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
    val annotations: ToolAnnotations,
    val handler: suspend (arguments: JsonObject) -> CallToolResult
)
