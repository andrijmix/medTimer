package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.text
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

class DefineToolTest {

    private fun tool(handler: suspend (ToolArgs) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) = defineTool(
        name = "demo",
        description = "A tool for the tests of the tool definition",
        annotations = ToolAnnotations(readOnlyHint = true),
        properties = mapOf("count" to intProp("A number for the tests")),
        required = listOf("count"),
        handler = handler
    )

    @Test
    fun `should hand validated arguments to the handler`() = runTest {
        val result = tool { args -> ToolResults.ok(buildJsonObject { put("double", args.int("count") * 2) }) }.call("""{"count":21}""")

        assertEquals(42, result.json().getValue("double").toString().toInt())
    }

    @Test
    fun `should report an invalid argument as an error that names it`() = runTest {
        val result = tool { args -> ToolResults.ok(buildJsonObject { put("count", args.int("count")) }) }.call("""{"count":"many"}""")

        assertEquals(true, result.isError)
        assertEquals("count must be an integer, got \"many\"", result.text())
    }

    @Test
    fun `should not pass the message of an unexpected failure on to the model`() = runTest {
        val result = tool { error("UNIQUE constraint failed: Medicine.name = Vitamin X 500 mg") }.call("""{"count":1}""")

        assertEquals(true, result.isError)
        assertFalse(result.text(), result.text().contains("Vitamin"))
        assertFalse(result.text(), result.text().contains("constraint"))
        assertTrue(result.text(), result.text().contains("IllegalStateException"))
        assertTrue(result.text(), result.text().contains("Check the current state"))
    }

    @Test
    fun `should let cancellation through`() = runTest {
        assertFailsWith<CancellationException> { tool { throw CancellationException("stop") }.call("""{"count":1}""") }
    }

    @Test
    fun `should refuse a tool that requires an argument it does not have`() {
        assertFailsWith<IllegalArgumentException> {
            defineTool("demo", "A tool for the tests of the tool definition", ToolAnnotations(), properties = emptyMap(), required = listOf("count")) {
                ToolResults.error("never called")
            }
        }
    }

    @Test
    fun `should describe the arguments in the schema`() {
        val definition = tool { ToolResults.error("never called") }

        assertEquals(listOf("count"), definition.inputSchema.required)
        assertEquals(setOf("count"), definition.inputSchema.properties!!.keys)
        assertNull(tool { ToolResults.error("x") }.annotations.destructiveHint)
    }
}
