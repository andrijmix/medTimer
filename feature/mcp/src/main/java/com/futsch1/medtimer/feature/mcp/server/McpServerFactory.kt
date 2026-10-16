package com.futsch1.medtimer.feature.mcp.server

import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject

// Informational: the version of the MCP server implementation reported in the initialize handshake, independent of the app version.
internal const val MCP_SERVER_VERSION = "1.0.0"

// Read by the AI model, so it is deliberately English and describes the conventions every tool shares.
private const val SERVER_INSTRUCTIONS = """
This server exposes the user's medication reminders from the medTimer Android app.
- Dates are YYYY-MM-DD, times are HH:mm, and date-times are ISO-8601 with an offset in the phone's time zone.
- A reminder's "amount" is free text in the unit of its medicine (for example "1" or "2.5"). An empty list of days means every day.
- Reminders of type OUT_OF_STOCK and EXPIRATION_DATE are created by the app itself and cannot be changed here.
- Deleting is permanent. Always confirm with the user before calling a delete tool.
"""

/**
 * Builds the single [Server] shared by all requests and registers [tools] on it.
 */
fun createMcpServer(tools: List<ToolDefinition>, version: String): Server {
    val server = Server(
        serverInfo = Implementation(name = "medtimer", version = version),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = SERVER_INSTRUCTIONS.trimIndent().trim()
    )
    tools.forEach { tool ->
        server.addTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema,
            toolAnnotations = tool.annotations
        ) { request -> tool.handler(request.arguments ?: JsonObject(emptyMap())) }
    }
    return server
}
