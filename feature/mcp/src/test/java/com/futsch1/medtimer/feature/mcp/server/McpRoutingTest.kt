package com.futsch1.medtimer.feature.mcp.server

import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

private const val SECRET = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEF"
private const val SECRET_URL = "/mcp/$SECRET"

private const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
private const val LIST_TOOLS = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
private const val CALL_ECHO =
    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"Vitamin X 500 mg"}}}"""

class McpRoutingTest {

    private val echoTool = ToolDefinition(
        name = "echo",
        description = "Returns the given text",
        inputSchema = ToolSchema(properties = buildJsonObject { put("text", buildJsonObject { put("type", "string") }) }, required = listOf("text")),
        annotations = ToolAnnotations(readOnlyHint = true),
        handler = { arguments -> CallToolResult(content = listOf(TextContent(arguments.getValue("text").jsonPrimitive.content))) }
    )

    private fun withMcp(config: McpServerConfig = McpServerConfig(port = 8765, secret = SECRET), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { mcpModule(config, createMcpServer(listOf(echoTool), "test")) }
            block()
        }

    // The test client does not set a Host header, and the SDK's DNS rebinding protection rejects requests without one, so it is set explicitly.
    private suspend fun HttpClient.rpc(path: String, body: String, host: String = "localhost"): HttpResponse = post(path) {
        header(HttpHeaders.Host, host)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun assertStatus(expected: HttpStatusCode, response: HttpResponse) = assertEquals(response.bodyAsText(), expected, response.status)

    @Test
    fun `should return an empty 404 when the path is not the secret path`() = withMcp {
        listOf("/", "/mcp", "/mcp/", "/mcp/wrong", "$SECRET_URL" + "x", "$SECRET_URL/", "/.well-known/oauth-protected-resource").forEach { path ->
            val response = client.rpc(path, INITIALIZE)

            assertEquals(path, HttpStatusCode.NotFound, response.status)
            assertEquals(path, "", response.bodyAsText())
        }
    }

    @Test
    fun `should return an empty 404 for GET on a wrong path`() = withMcp {
        val response = client.get("/mcp/wrong")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `should answer initialize on the secret path`() = withMcp {
        val response = client.rpc(SECRET_URL, INITIALIZE)

        assertStatus(HttpStatusCode.OK, response)
        assertEquals("medtimer", response.json().getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `should answer tools list without a prior initialize`() = withMcp {
        val response = client.rpc(SECRET_URL, LIST_TOOLS)

        assertStatus(HttpStatusCode.OK, response)
        val tools = response.json().getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals("echo", tools.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `should run a tool call without a prior initialize`() = withMcp {
        val response = client.rpc(SECRET_URL, CALL_ECHO)

        assertStatus(HttpStatusCode.OK, response)
        val content = response.json().getValue("result").jsonObject.getValue("content").jsonArray
        assertEquals("Vitamin X 500 mg", content.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `should answer GET on the secret path with 405`() = withMcp {
        val response = client.get(SECRET_URL) { header(HttpHeaders.Host, "localhost") }

        assertStatus(HttpStatusCode.MethodNotAllowed, response)
    }

    @Test
    fun `should return 403 when the host header is not allowed`() = withMcp {
        val response = client.rpc(SECRET_URL, INITIALIZE, host = "evil.example")

        assertStatus(HttpStatusCode.Forbidden, response)
    }

    @Test
    fun `should accept the public host of the tunnel`() = withMcp(McpServerConfig(port = 8765, secret = SECRET, publicHost = "meds.example.com")) {
        val response = client.rpc(SECRET_URL, INITIALIZE, host = "meds.example.com")

        assertStatus(HttpStatusCode.OK, response)
    }

    @Test
    fun `should return 413 when the declared body size exceeds the limit`() = withMcp {
        val response = client.rpc(SECRET_URL, "x".repeat((MAX_REQUEST_BODY_BYTES + 1).toInt()))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }
}
