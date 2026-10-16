package com.futsch1.medtimer.feature.mcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private enum class Color { RED, GREEN }

class ToolArgsTest {

    private fun args(json: String, vararg names: String) = ToolArgs(Json.parseToJsonElement(json).jsonObject, names.toSet())

    private fun messageOf(block: () -> Unit): String = assertThrows(ToolArgumentException::class.java) { block() }.message.orEmpty()

    @Test
    fun `should read values of every type`() {
        val args = args(
            """{"i":7,"d":2.5,"b":true,"s":" Vitamin X ","date":"2026-10-10","time":"08:30","day":"monday"}""",
            "i", "d", "b", "s", "date", "time", "day"
        )

        assertEquals(7, args.int("i"))
        assertEquals(2.5, args.double("d"), 0.0)
        assertTrue(args.boolean("b"))
        assertEquals("Vitamin X", args.string("s"))
        assertEquals(LocalDate.of(2026, 10, 10), args.date("date"))
        assertEquals(LocalTime.of(8, 30), args.time("time"))
        assertEquals(DayOfWeek.MONDAY, args.enum("day", DayOfWeek.entries))
    }

    @Test
    fun `should reject an argument name that the tool does not have`() {
        val message = messageOf { args("""{"medicine":3}""", "medicine_id", "name") }

        assertTrue(message, message.contains("'medicine'"))
        assertTrue(message, message.contains("medicine_id, name"))
    }

    @Test
    fun `should name the missing required argument`() {
        assertEquals("medicine_id is required", messageOf { args("{}", "medicine_id").int("medicine_id") })
    }

    @Test
    fun `should treat json null as absent and still tell it apart`() {
        val args = args("""{"date":null}""", "date", "other")

        assertNull(args.dateOrNull("date"))
        assertTrue(args.isExplicitNull("date"))
        assertFalse(args.isPresent("date"))
        assertFalse(args.isExplicitNull("other"))
    }

    @Test
    fun `should reject values of the wrong type and say what was received`() {
        assertEquals("id must be an integer, got \"3\"", messageOf { args("""{"id":"3"}""", "id").int("id") })
        assertEquals("id must be an integer, got 2.5", messageOf { args("""{"id":2.5}""", "id").int("id") })
        assertEquals("name must be a string, got 5", messageOf { args("""{"name":5}""", "name").string("name") })
        assertEquals("flag must be true or false, got \"true\"", messageOf { args("""{"flag":"true"}""", "flag").boolean("flag") })
        assertEquals("id must be a single value, got a list", messageOf { args("""{"id":[1]}""", "id").int("id") })
    }

    @Test
    fun `should reject an integer outside its range`() {
        assertEquals("days must be between 1 and 31, got 40", messageOf { args("""{"days":40}""", "days").int("days", 1..31) })
    }

    @Test
    fun `should reject a number below its minimum`() {
        assertEquals("stock must be at least 0.0, got -1.0", messageOf { args("""{"stock":-1}""", "stock").double("stock", min = 0.0) })
    }

    @Test
    fun `should reject a blank string when it is required to have content`() {
        assertEquals("name must not be empty", messageOf { args("""{"name":"  "}""", "name").string("name") })
    }

    @Test
    fun `should reject a string that is too long`() {
        val message = messageOf { args("""{"name":"${"x".repeat(201)}"}""", "name").string("name") }

        assertEquals("name must be at most 200 characters long", message)
    }

    @Test
    fun `should reject malformed dates and times`() {
        assertTrue(messageOf { args("""{"d":"10.10.2026"}""", "d").date("d") }.startsWith("d must be a date in the form YYYY-MM-DD"))
        assertTrue(messageOf { args("""{"t":"25:00"}""", "t").time("t") }.startsWith("t must be a time in the form HH:mm"))
        assertTrue(messageOf { args("""{"t":"8:00"}""", "t").time("t") }.startsWith("t must be a time in the form HH:mm"))
    }

    @Test
    fun `should reject a value of an enum that does not exist and list the choices`() {
        val message = messageOf { args("""{"c":"blue"}""", "c").enum("c", Color.entries) }

        assertEquals("c must be one of RED, GREEN, got \"blue\"", message)
    }

    @Test
    fun `should read lists`() {
        val args = args("""{"ids":[1,2],"names":["a","b"],"colors":["red","GREEN"]}""", "ids", "names", "colors")

        assertEquals(listOf(1, 2), args.intListOrNull("ids"))
        assertEquals(listOf("a", "b"), args.stringListOrNull("names"))
        assertEquals(listOf(Color.RED, Color.GREEN), args.enumListOrNull("colors", Color.entries))
    }

    @Test
    fun `should reject a list entry of the wrong type and a non-list`() {
        assertEquals("ids must be a list of integers, got \"x\"", messageOf { args("""{"ids":[1,"x"]}""", "ids").intListOrNull("ids") })
        assertEquals("ids must be a list, got 5", messageOf { args("""{"ids":5}""", "ids").intListOrNull("ids") })
    }

    @Test
    fun `should echo only a short excerpt of a long value`() {
        val message = messageOf { args("""{"id":"${"9".repeat(500)}"}""", "id").int("id") }

        assertTrue(message, message.length < 100)
    }
}
