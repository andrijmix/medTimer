package com.futsch1.medtimer.feature.mcp.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A range of whole local days, both ends inclusive, in the phone's time zone.
 */
internal class DateRange(val from: LocalDate, val to: LocalDate, zone: ZoneId) {
    init {
        require(from <= to) { "from must not be after to" }
    }

    /** The first instant of [from]. */
    val start: Instant = from.atStartOfDay(zone).toInstant()

    /** The first instant after [to], so that a whole last day is covered even across a daylight saving change. */
    val endExclusive: Instant = to.plusDays(1).atStartOfDay(zone).toInstant()

    operator fun contains(instant: Instant): Boolean = instant >= start && instant < endExclusive

    companion object {
        const val MAX_DAYS = 366L
    }
}
