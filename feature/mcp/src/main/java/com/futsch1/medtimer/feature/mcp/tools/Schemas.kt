package com.futsch1.medtimer.feature.mcp.tools

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

private const val LOG_TAG = "MedTimerMcp"

internal fun stringProp(description: String, format: String? = null, enum: Collection<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    format?.let { put("format", it) }
    enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

/** A string property that may also be null, which the update tools use to clear the value. */
internal fun nullableStringProp(description: String, format: String? = null): JsonObject = buildJsonObject {
    put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
    put("description", description)
    format?.let { put("format", it) }
}

internal fun intProp(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
}

internal fun numberProp(description: String, minimum: Double? = null): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
    minimum?.let { put("minimum", it) }
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun arrayProp(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

/** Annotations for a tool that only reads. */
internal fun readOnly(title: String) = ToolAnnotations(title = title, readOnlyHint = true, openWorldHint = false)

/** Annotations for a tool that adds data and never modifies or removes existing data. */
internal fun additive(title: String) = ToolAnnotations(title = title, readOnlyHint = false, destructiveHint = false, openWorldHint = false)

/** Annotations for a tool that changes or removes existing data. Repeating the same call has the same effect. */
internal fun destructive(title: String) =
    ToolAnnotations(title = title, readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false)

/**
 * Defines a tool whose arguments are described by [properties] and [required].
 *
 * The handler gets validated access to the arguments through [ToolArgs]. An invalid argument becomes an error result carrying the reason,
 * so each handler only has to throw [ToolArgumentException] (which [ToolArgs] does) instead of formatting errors itself.
 */
internal fun defineTool(
    name: String,
    description: String,
    annotations: ToolAnnotations,
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
    handler: suspend (ToolArgs) -> CallToolResult
): ToolDefinition {
    require(properties.keys.containsAll(required)) { "Tool $name requires arguments that are not in its schema" }
    return ToolDefinition(
        name = name,
        description = description,
        inputSchema = ToolSchema(properties = JsonObject(properties), required = required.ifEmpty { null }),
        annotations = annotations,
        handler = { arguments ->
            try {
                handler(ToolArgs(arguments, properties.keys))
            } catch (e: ToolArgumentException) {
                ToolResults.error(e.message.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The SDK would send the message of the exception to the model. Those of the database can carry the values being stored, so
                // only the type is reported, and logged.
                Log.w(LOG_TAG, "Tool $name failed: ${e.javaClass.simpleName}")
                ToolResults.error(
                    "The tool failed unexpectedly (${e.javaClass.simpleName}). A change may have been applied in part: " +
                        "Check the current state with the read tools before trying again."
                )
            }
        }
    )
}
