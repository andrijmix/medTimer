package com.futsch1.medtimer.feature.mcp.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

private const val MAX_ECHOED_VALUE_LENGTH = 40
private val TIME_PATTERN = Regex("([01]\\d|2[0-3]):([0-5]\\d)")

// A trailing "Z" or "+hh:mm" / "-hh:mm" after the time marks a date-time that carries its own offset.
private val OFFSET_PATTERN = Regex("(Z|[+-]\\d{2}:\\d{2})$")

/**
 * A tool argument that is missing, has the wrong type or is out of range. The message is shown to the AI model, so it names the argument
 * and says what was expected.
 */
class ToolArgumentException(message: String) : Exception(message)

/**
 * Typed, validating access to the arguments of one tool call. Unknown argument names are rejected, because silently ignoring a misspelled
 * name would let a tool report success without having done what was asked.
 *
 * Getters without `OrNull` require the argument. Getters with `OrNull` return null when the argument is absent or JSON null; use
 * [isExplicitNull] to tell the two apart where null means "clear this value".
 */
class ToolArgs(private val arguments: JsonObject, allowedNames: Set<String>) {

    init {
        val unknown = arguments.keys - allowedNames
        if (unknown.isNotEmpty()) {
            throw ToolArgumentException("Unknown argument ${unknown.joinToString { "'$it'" }}. Allowed arguments: ${allowedNames.sorted().joinToString()}")
        }
    }

    fun isPresent(name: String): Boolean = arguments[name].let { it != null && it !is JsonNull }

    fun isExplicitNull(name: String): Boolean = arguments[name] is JsonNull

    fun int(name: String, range: IntRange? = null): Int = intOrNull(name, range) ?: throw missing(name)

    fun intOrNull(name: String, range: IntRange? = null): Int? {
        val primitive = primitiveOrNull(name) ?: return null
        val value = if (primitive.isString) null else primitive.intOrNull
        if (value == null) {
            throw ToolArgumentException("$name must be an integer, got ${describe(primitive)}")
        }
        checkRange(name, value, range)
        return value
    }

    fun double(name: String, min: Double? = null): Double = doubleOrNull(name, min) ?: throw missing(name)

    fun doubleOrNull(name: String, min: Double? = null): Double? {
        val primitive = primitiveOrNull(name) ?: return null
        val value = if (primitive.isString) null else primitive.doubleOrNull
        if (value == null || !value.isFinite()) {
            throw ToolArgumentException("$name must be a number, got ${describe(primitive)}")
        }
        if (min != null && value < min) {
            throw ToolArgumentException("$name must be at least $min, got $value")
        }
        return value
    }

    fun boolean(name: String): Boolean = booleanOrNull(name) ?: throw missing(name)

    fun booleanOrNull(name: String): Boolean? {
        val primitive = primitiveOrNull(name) ?: return null
        val value = if (primitive.isString) null else primitive.booleanOrNull
        return value ?: throw ToolArgumentException("$name must be true or false, got ${describe(primitive)}")
    }

    fun string(name: String, maxLength: Int = DEFAULT_MAX_STRING_LENGTH, allowBlank: Boolean = false): String =
        stringOrNull(name, maxLength, allowBlank) ?: throw missing(name)

    fun stringOrNull(name: String, maxLength: Int = DEFAULT_MAX_STRING_LENGTH, allowBlank: Boolean = true): String? {
        val primitive = primitiveOrNull(name) ?: return null
        if (!primitive.isString) {
            throw ToolArgumentException("$name must be a string, got ${describe(primitive)}")
        }
        val value = primitive.content.trim()
        if (value.isEmpty() && !allowBlank) {
            throw ToolArgumentException("$name must not be empty")
        }
        if (value.length > maxLength) {
            throw ToolArgumentException("$name must be at most $maxLength characters long")
        }
        return value
    }

    /** A date as `YYYY-MM-DD`. */
    fun date(name: String): LocalDate = dateOrNull(name) ?: throw missing(name)

    fun dateOrNull(name: String): LocalDate? {
        val value = stringOrNull(name) ?: return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw ToolArgumentException("$name must be a date in the form YYYY-MM-DD, got ${describe(JsonPrimitive(value))}")
        }
    }

    /**
     * A moment as an ISO-8601 date-time. With an offset (`2026-10-10T08:00:00+03:00`) it is exact; without one (`2026-10-10T08:00`) it is
     * taken to be in [zone], the phone's time zone.
     */
    fun instantOrNull(name: String, zone: ZoneId): Instant? {
        val value = stringOrNull(name) ?: return null
        return try {
            if (OFFSET_PATTERN.containsMatchIn(value)) OffsetDateTime.parse(value).toInstant() else LocalDateTime.parse(value).atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
            throw ToolArgumentException("$name must be a date and time such as 2026-10-10T08:00, got ${describe(JsonPrimitive(value))}")
        }
    }

    /** A time of day as `HH:mm` (24 hours). */
    fun time(name: String): LocalTime = timeOrNull(name) ?: throw missing(name)

    fun timeOrNull(name: String): LocalTime? {
        val value = stringOrNull(name) ?: return null
        val match = TIME_PATTERN.matchEntire(value)
            ?: throw ToolArgumentException("$name must be a time in the form HH:mm (00:00 to 23:59), got ${describe(JsonPrimitive(value))}")
        return try {
            LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        } catch (_: DateTimeException) {
            throw ToolArgumentException("$name must be a time in the form HH:mm (00:00 to 23:59), got ${describe(JsonPrimitive(value))}")
        }
    }

    /** A value of [entries], matched case-insensitively by name. */
    fun <E : Enum<E>> enum(name: String, entries: Collection<E>): E = enumOrNull(name, entries) ?: throw missing(name)

    fun <E : Enum<E>> enumOrNull(name: String, entries: Collection<E>): E? {
        val value = stringOrNull(name) ?: return null
        return matchEnum(name, value, entries)
    }

    fun <E : Enum<E>> enumListOrNull(name: String, entries: Collection<E>): List<E>? =
        arrayOrNull(name)?.map { element ->
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ToolArgumentException("$name must be a list of strings, got ${describe(element)}")
            matchEnum(name, text, entries)
        }

    fun intListOrNull(name: String, range: IntRange? = null): List<Int>? =
        arrayOrNull(name)?.map { element ->
            val primitive = element as? JsonPrimitive
            val value = if (primitive == null || primitive.isString) null else primitive.intOrNull
            if (value == null) {
                throw ToolArgumentException("$name must be a list of integers, got ${describe(element)}")
            }
            checkRange(name, value, range)
            value
        }

    fun stringListOrNull(name: String, maxLength: Int = DEFAULT_MAX_STRING_LENGTH): List<String>? =
        arrayOrNull(name)?.map { element ->
            val primitive = (element as? JsonPrimitive)?.takeIf { it.isString }
                ?: throw ToolArgumentException("$name must be a list of strings, got ${describe(element)}")
            val value = primitive.content.trim()
            if (value.length > maxLength) {
                throw ToolArgumentException("Each entry of $name must be at most $maxLength characters long")
            }
            value
        }

    private fun <E : Enum<E>> matchEnum(name: String, value: String, entries: Collection<E>): E =
        entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ToolArgumentException("$name must be one of ${entries.joinToString { it.name }}, got ${describe(JsonPrimitive(value))}")

    private fun arrayOrNull(name: String): JsonArray? {
        val element = arguments[name]
        return when (element) {
            null, JsonNull -> null
            is JsonArray -> element
            else -> throw ToolArgumentException("$name must be a list, got ${describe(element)}")
        }
    }

    private fun primitiveOrNull(name: String): JsonPrimitive? {
        val element = arguments[name]
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element
            else -> throw ToolArgumentException("$name must be a single value, got ${describe(element)}")
        }
    }

    private fun checkRange(name: String, value: Int, range: IntRange?) {
        if (range != null && value !in range) {
            throw ToolArgumentException("$name must be between ${range.first} and ${range.last}, got $value")
        }
    }

    private fun missing(name: String) = ToolArgumentException("$name is required")

    // Echoes what was received so the model can correct it, but never more than a short excerpt.
    private fun describe(element: JsonElement): String = when (element) {
        is JsonArray -> "a list"
        is JsonObject -> "an object"
        is JsonPrimitive -> if (element.isString) "\"${element.content.take(MAX_ECHOED_VALUE_LENGTH)}\"" else element.content.take(MAX_ECHOED_VALUE_LENGTH)
    }

    companion object {
        const val DEFAULT_MAX_STRING_LENGTH = 200
    }
}
