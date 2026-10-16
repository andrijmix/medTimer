package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.feature.mcp.BERLIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DateRangeTest {

    @Test
    fun `should start at local midnight of the first day and end at local midnight after the last day`() {
        val range = DateRange(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), BERLIN)

        assertEquals(Instant.parse("2026-10-09T22:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-10-11T22:00:00Z"), range.endExclusive)
    }

    @Test
    fun `should contain the first second and exclude the second after the last day`() {
        val range = DateRange(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10), BERLIN)

        assertTrue(Instant.parse("2026-10-09T22:00:00Z") in range)
        assertTrue(Instant.parse("2026-10-10T21:59:59Z") in range)
        assertFalse(Instant.parse("2026-10-09T21:59:59Z") in range)
        assertFalse(Instant.parse("2026-10-10T22:00:00Z") in range)
    }

    @Test
    fun `should cover all 25 hours of the day on which daylight saving time ends`() {
        val range = DateRange(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), BERLIN)

        assertEquals(Duration.ofHours(25), Duration.between(range.start, range.endExclusive))
    }

    @Test
    fun `should not allow a start after the end`() {
        assertThrows(IllegalArgumentException::class.java) { DateRange(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 10), BERLIN) }
    }
}
