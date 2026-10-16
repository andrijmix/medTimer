package com.futsch1.medtimer.feature.mcp.domain

import android.util.Log
import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import com.futsch1.medtimer.feature.reminders.api.command.ReminderCommandBus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "MedTimerMcp"

/**
 * A result together with things the user should hear about although the operation succeeded.
 */
class Warned<out T>(val value: T, val warnings: List<String> = emptyList())

/**
 * Runs every change to medicines and reminders: one at a time, and followed by a new reminder schedule.
 *
 * The app only refreshes its schedule by itself while its screen is open, so a change made through MCP has to ask for it. Without that the
 * next alarm would keep pointing at the old reminders.
 */
@Singleton
class WriteCoordinator @Inject constructor(private val commandBus: ReminderCommandBus) {
    private val mutex = Mutex()

    /**
     * Runs [block] and then reschedules.
     *
     * [block] must do all its checks, and throw [ToolArgumentException] if one fails, before it changes anything: that exception is taken to
     * mean that nothing was written, and no reschedule follows. Any other exception reschedules too, as a half-applied change may exist.
     */
    suspend fun <T> write(block: suspend () -> Warned<T>): Warned<T> = mutex.withLock {
        val result = try {
            block()
        } catch (e: ToolArgumentException) {
            throw e
        } catch (e: Exception) {
            // Includes cancellation: a change that was cancelled half way may be partly there.
            reschedule()
            throw e
        }
        val rescheduleWarning = reschedule()
        Warned(result.value, result.warnings + listOfNotNull(rescheduleWarning))
    }

    // The change itself is saved at this point, so a failure here must not be reported as a failed change: the caller would retry it.
    private suspend fun reschedule(): String? = withContext(NonCancellable) {
        try {
            commandBus.scheduleNextNotification()
            null
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Rescheduling after a change failed: ${e.javaClass.simpleName}")
            "The change was saved, but the reminder schedule could not be refreshed. Open MedTimer once so that the next reminder is up to date."
        }
    }
}
