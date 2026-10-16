package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import com.futsch1.medtimer.feature.reminders.api.command.ReminderCommandBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

class WriteCoordinatorTest {

    private val commandBus = mock<ReminderCommandBus>()
    private val coordinator = WriteCoordinator(commandBus)

    @Test
    fun `should reschedule once after a change and hand the result on`() = runTest {
        val result = coordinator.write { Warned("done", listOf("careful")) }

        assertEquals("done", result.value)
        assertEquals(listOf("careful"), result.warnings)
        verifyBlocking(commandBus) { scheduleNextNotification() }
    }

    @Test
    fun `should reschedule only after the change was made`() = runTest {
        val events = mutableListOf<String>()
        whenever(commandBus.scheduleNextNotification()).then { events.add("rescheduled") }

        coordinator.write {
            events.add("changed")
            Warned(Unit)
        }

        assertEquals(listOf("changed", "rescheduled"), events)
    }

    @Test
    fun `should not reschedule when the change was refused before anything was written`() = runTest {
        assertFailsWith<ToolArgumentException> { coordinator.write<Unit> { throw ToolArgumentException("bad") } }

        verifyBlocking(commandBus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should reschedule when a change failed half way and pass the failure on`() = runTest {
        assertFailsWith<IllegalStateException> { coordinator.write<Unit> { error("disk full") } }

        verifyBlocking(commandBus) { scheduleNextNotification() }
    }

    @Test
    fun `should report a failed reschedule as a warning instead of as a failed change`() = runTest {
        whenever(commandBus.scheduleNextNotification()).thenThrow(IllegalStateException("no alarm permission"))

        val result = coordinator.write { Warned(7) }

        assertEquals(7, result.value)
        assertTrue(result.warnings.single(), result.warnings.single().startsWith("The change was saved"))
    }

    @Test
    fun `should run overlapping changes one after the other`() = runTest {
        val events = mutableListOf<String>()

        launch {
            coordinator.write {
                events.add("first start")
                delay(100)
                events.add("first end")
                Warned(Unit)
            }
        }
        launch {
            coordinator.write {
                events.add("second start")
                delay(100)
                events.add("second end")
                Warned(Unit)
            }
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("first start", "first end", "second start", "second end"), events)
        verifyBlocking(commandBus, times(2)) { scheduleNextNotification() }
    }
}
