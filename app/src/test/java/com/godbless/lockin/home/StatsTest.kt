package com.godbless.lockin.home

import com.godbless.lockin.data.SessionRow
import com.godbless.lockin.data.Totals
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val utc = ZoneId.of("UTC")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, utc).toInstant().toEpochMilli()

    private fun row(id: String, mine: Boolean, at: Long, minutes: Int, pickups: Int = 0) =
        SessionRow(id, mine, at, minutes * 60, pickups, "Focus session")

    // Thursday 12 November 2026, 17:00.
    private val now = ms(2026, 11, 12, 17)

    private val rows = listOf(
        row("a", true, ms(2026, 11, 12, 9, 10), 85),
        row("b", true, ms(2026, 11, 11, 19, 30), 120),
        row("c", true, ms(2026, 11, 11, 14), 50, 1),
        row("d", true, ms(2026, 11, 10, 18), 45),
        row("e", true, ms(2026, 11, 5, 11, 30), 95), // last Thursday
        row("f", true, ms(2026, 10, 8, 10), 60), // last month, before the 12th
        row("g", false, ms(2026, 11, 12, 9, 5), 70),
        row("h", false, ms(2026, 11, 11, 19), 30),
        row("i", false, ms(2026, 11, 10, 8), 30),
    )

    private val stats = buildStats(rows, Totals(100 * 3600L, 50, 80 * 3600L, 40), emptyList(), "Kofi", ms(2026, 11, 2, 12), now, utc)

    @Test fun todayCountsEachPersonSeparately() {
        assertEquals(85 * 60, stats.todaySeconds)
        assertEquals(70 * 60, stats.partnerTodaySeconds)
    }

    @Test fun streakCountsBackFromToday() {
        assertEquals(3, stats.streak)
        assertEquals(3, stats.bestTogetherStreak)
    }

    @Test fun weekRunsMondayToSunday() {
        assertEquals(0f, stats.week[0], 0.001f)
        assertEquals(0.75f, stats.week[1], 0.001f)
        assertEquals(170 / 60f, stats.week[2], 0.001f)
        assertEquals(85 / 60f, stats.week[3], 0.001f)
        assertEquals(0f, stats.week[4], 0.001f)
        assertEquals("+3h 25m", stats.weekDelta)
        assertTrue(stats.weekUp)
    }

    @Test fun monthNumbers() {
        assertEquals(5, stats.monthSessions)
        assertEquals(2, stats.monthSummits)
        assertEquals("2h", stats.longestSession)
        assertEquals("+558% on last month", stats.monthDelta)
        assertEquals(30, stats.monthHours.size)
    }

    @Test fun historyIsGroupedByDayNewestFirst() {
        assertEquals(listOf("Today", "Yesterday", "Tuesday", "5 Nov", "8 Oct"), stats.history.map { it.label })
        assertEquals("9:10 to 10:35 am", stats.history[0].sessions[0].timeText)
        assertEquals("11:30 am to 1:05 pm", stats.history[3].sessions[0].timeText)
    }

    @Test fun activityNamesWhoDidIt() {
        assertEquals("You locked in for 1h 25m", stats.activity[0].text)
        assertEquals("Kofi locked in for 1h 10m", stats.activity[1].text)
    }

    @Test fun togetherNumbers() {
        assertEquals(10, stats.daysTogether)
        assertEquals(90, stats.sessionsTogether)
        assertEquals(180f, stats.hoursTogether, 0.01f)
    }

    @Test fun unsentSessionsAddToTheTotals() {
        val extra = row("z", true, ms(2026, 11, 12, 16), 30)
        val withExtra = buildStats(rows + extra, Totals(100 * 3600L, 50, 80 * 3600L, 40), listOf(extra), "Kofi", null, now, utc)
        assertEquals(100.5f, withExtra.totalHours, 0.01f)
        assertEquals(91, withExtra.sessionsTogether)
    }

    @Test fun noSessionsGivesFriendlyEmptyValues() {
        val e = buildStats(emptyList(), Totals.Empty, emptyList(), "Kofi", null, now, utc)
        assertEquals(0, e.streak)
        assertEquals("Start your first climb", e.monthDelta)
        assertEquals("None yet", e.longestSession)
        assertTrue(e.history.isEmpty())
    }
}
