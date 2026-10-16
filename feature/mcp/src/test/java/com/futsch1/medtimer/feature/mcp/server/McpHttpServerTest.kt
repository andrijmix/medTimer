package com.futsch1.medtimer.feature.mcp.server

import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

private const val SECRET = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEF"
private const val LIST_TOOLS = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
private const val LEAK_REPORT_WINDOW_MILLIS = 500L

/**
 * Starts the real CIO engine on loopback sockets, which McpRoutingTest (Ktor's in-memory test host) cannot cover.
 */
class McpHttpServerTest {

    private val running = mutableListOf<McpHttpServer>()

    private val pingTool = ToolDefinition(
        name = "ping",
        description = "Answers pong",
        inputSchema = ToolSchema(),
        annotations = ToolAnnotations(readOnlyHint = true),
        handler = { CallToolResult(content = listOf(TextContent("pong"))) }
    )

    @After
    fun tearDown() {
        running.forEach { it.stop() }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(port: Int): McpHttpServer {
        val httpServer = McpHttpServer()
        httpServer.start(McpServerConfig(port, SECRET), createMcpServer(listOf(pingTool), "test"))
        running += httpServer
        return httpServer
    }

    private fun post(port: Int, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
        return code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    @Test
    fun `should serve the secret path over a real loopback socket`() {
        val port = freePort()
        start(port)

        val (code, body) = post(port, "/mcp/$SECRET", LIST_TOOLS)

        assertEquals(body, 200, code)
        assertTrue(body, body.contains("\"ping\""))
    }

    @Test
    fun `should answer 404 for any other path over a real socket`() {
        val port = freePort()
        start(port)

        val (code, body) = post(port, "/mcp/nope", LIST_TOOLS)

        assertEquals(404, code)
        assertEquals("", body)
    }

    @Test
    fun `should fail to start when the port is already taken`() {
        val port = freePort()
        start(port)

        assertThrows(IOException::class.java) { start(port) }
    }

    @Test
    fun `should not let a failed start reach the uncaught exception handler`() {
        // On Android an exception that nothing handled ends the app. kotlinx-coroutines-test collects such exceptions and reports them to
        // the next runTest, but only once a first runTest has switched the collecting on, which is what this one is for.
        runTest { }
        val port = freePort()
        start(port)
        assertThrows(IOException::class.java) { start(port) }

        // The report comes from a Ktor thread some moments after start() returned. An empty runTest fails if the failed start leaked it.
        Thread.sleep(LEAK_REPORT_WINDOW_MILLIS)
        runTest { }
    }

    @Test
    fun `should release the port when stopped`() {
        val port = freePort()
        val httpServer = start(port)

        httpServer.stop()

        ServerSocket(port).close()
    }
}
