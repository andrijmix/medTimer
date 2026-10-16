package com.futsch1.medtimer.feature.mcp

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

internal fun medicine(id: Int, name: String, reminders: List<Reminder> = emptyList(), amount: Double = 0.0): Medicine =
    Medicine.default().copy(id = id, name = name, reminders = reminders, amount = amount, unit = "mg")

internal fun reminder(id: Int, medicineId: Int, amount: String = "1"): Reminder =
    Reminder.default().copy(id = id, medicineRelId = medicineId, amount = amount)

internal fun event(
    id: Int,
    reminderId: Int,
    medicineName: String,
    status: ReminderStatus,
    remindedAt: Instant,
    type: ReminderType = ReminderType.TIME_BASED
): ReminderEvent = ReminderEvent.default().copy(
    reminderEventId = id,
    reminderId = reminderId,
    medicineName = medicineName,
    amount = "1",
    status = status,
    remindedTimestamp = remindedAt,
    processedTimestamp = if (status == ReminderStatus.RAISED) Instant.EPOCH else remindedAt.plusSeconds(60),
    reminderType = type
)

/** A time source fixed to [zone], [today] and [now]. */
internal fun fixedTime(zone: ZoneId = BERLIN, today: LocalDate = LocalDate.of(2026, 10, 10), now: Instant = Instant.parse("2026-10-10T12:00:00Z")): TimeAccess {
    val timeAccess = mock<TimeAccess>()
    whenever(timeAccess.systemZone()).thenReturn(zone)
    whenever(timeAccess.localDate()).thenReturn(today)
    whenever(timeAccess.now()).thenReturn(now)
    return timeAccess
}

/** Calls the tool the way the MCP server does, with the arguments given as JSON text. */
internal suspend fun ToolDefinition.call(arguments: String = "{}"): CallToolResult = handler(Json.parseToJsonElement(arguments).jsonObject)

internal fun CallToolResult.text(): String = (content.single() as TextContent).text

/** The structured content of a successful call; fails with the error text if the call failed. */
internal fun CallToolResult.json(): JsonObject = checkNotNull(structuredContent) { "Tool call failed: ${text()}" }

internal fun List<ToolDefinition>.named(name: String): ToolDefinition = single { it.name == name }
