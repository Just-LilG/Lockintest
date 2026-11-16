package com.godbless.lockin.home

import androidx.compose.runtime.compositionLocalOf
import com.godbless.lockin.data.SessionRow
import com.godbless.lockin.data.Totals
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A single session that lasted at least this long counts as reaching a summit. */
internal const val SummitSeconds = 90 * 60

/** Shorter than this and a session is a tap, not a climb: Home groups these instead of listing them. */
internal const val QuickSeconds = 120

/**
 * Every number the pages show, worked out from the real sessions. Pages read it with [LocalStats] so
 * none of them needs to know where it came from.
 */
internal class Stats(
    val todaySeconds: Int,
    val partnerTodaySeconds: Int,
    val streak: Int,
    val bestTogetherStreak: Int,
    val monthSessions: Int,
    val week: FloatArray,
    val weekDelta: String,
    val weekUp: Boolean,
    /** This week matches last week to the minute (usually: nothing yet on either side). */
    val weekFlat: Boolean = false,
    /** Your partner's focus on each day of this week, in hours (Monday first). */
    val partnerWeek: FloatArray = FloatArray(7),
    /** Today already has a session of two minutes or more (the kind that counts toward a streak). */
    val todayCounted: Boolean = false,
    val monthHours: FloatArray,
    val monthDelta: String,
    val longestSession: String,
    val monthSummits: Int,
    val history: List<DayGroup>,
    val recent: List<Climb>,
    val activity: List<Activity>,
    val totalHours: Float,
    val sessionsTogether: Int,
    val hoursTogether: Float,
    val daysTogether: Int,
    /** This month's longest session, in seconds (0 when there is none). */
    val longestSeconds: Int = 0,
    /** Sessions under two minutes from today. Only counted, never listed. */
    val quickSessions: Int = 0,
    /** Your longest run of days, ever (a run the freeze or rest days kept alive still counts as one). */
    val longestStreak: Int = 0,
    /** Every session of 90 minutes or more, all time. */
    val allSummits: Int = 0,
    val longestEverSeconds: Int = 0,
    val bestDaySeconds: Int = 0,
    /** The date of your best day, as an epoch day (-1 if none). */
    val bestDayEpoch: Long = -1L,
    val bestWeekSeconds: Int = 0,
    /** Seconds focused on each day (key: epoch day) and how many sessions that day had. */
    val dayTotals: Map<Long, Int> = emptyMap(),
    val dayCounts: Map<Long, Int> = emptyMap(),
    /** Seconds focused in each hour of the day, 0 to 23. */
    val hourBuckets: IntArray = IntArray(24),
    /** The day a streak freeze saved your streak in the last week (epoch day), or -1. */
    val freezeUsedOn: Long = -1L,
    /** Today is one of your rest days, so a missed session doesn't break the streak. */
    val restToday: Boolean = false,
    val weekSeconds: Int = 0,
    val lastWeekSeconds: Int = 0,
    /** Your sessions as the server holds them, for "Export my data". */
    val exportRows: List<SessionRow> = emptyList(),
) {
    companion object {
        val Empty = Stats(
            todaySeconds = 0, partnerTodaySeconds = 0, streak = 0, bestTogetherStreak = 0, monthSessions = 0,
            week = FloatArray(7), weekDelta = "+0m", weekUp = true,
            monthHours = FloatArray(31), monthDelta = "Start your first climb", longestSession = "None yet", monthSummits = 0,
            history = emptyList(), recent = emptyList(), activity = emptyList(), totalHours = 0f,
            sessionsTogether = 0, hoursTogether = 0f, daysTogether = 0,
        )
    }
}

internal val LocalStats = compositionLocalOf { Stats.Empty }

/** Whether the phone shows 24-hour times. Home sets it from the system setting; the default is 12-hour. */
internal object ClockPrefs {
    @Volatile var is24h: Boolean = false
}

private val Clock get() = java.time.format.DateTimeFormatter.ofPattern(if (ClockPrefs.is24h) "HH:mm" else "h:mm", Locale.getDefault())
private val ClockAm get() = java.time.format.DateTimeFormatter.ofPattern(if (ClockPrefs.is24h) "HH:mm" else "h:mm a", Locale.getDefault())
private val ShortDate get() = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun meridiem(h: Int) = if (h < 12) "am" else "pm"

/** "9:10 to 10:35 am", or "11:30 am to 1:05 pm" when the two ends are on different sides of noon. */
internal fun timeSpan(start: java.time.ZonedDateTime, seconds: Int): String {
    val end = start.plusSeconds(seconds.toLong())
    val startText = start.format(Clock)
    val endText = end.format(Clock)
    if (ClockPrefs.is24h) return "$startText to $endText"
    return if (meridiem(start.hour) == meridiem(end.hour)) "$startText to $endText ${meridiem(end.hour)}"
    else "$startText ${meridiem(start.hour)} to $endText ${meridiem(end.hour)}"
}

private fun dayLabel(day: LocalDate, today: LocalDate): String {
    val ago = today.toEpochDay() - day.toEpochDay()
    return when {
        ago == 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> day.format(ShortDate)
    }
}

private fun whenText(at: java.time.ZonedDateTime, today: LocalDate): String =
    dayLabel(at.toLocalDate(), today) + ", " + at.format(ClockAm).lowercase(Locale.getDefault())

/** "+24m", "-1h 5m": how far this week is from last week, in the same units the rest of Home uses. */
private fun signedMinutes(deltaHours: Float): String {
    val mins = (abs(deltaHours) * 60f).roundToInt()
    return if (mins == 0) "Same" else (if (deltaHours < 0f) "-" else "+") + formatMinutes(mins)
}

private fun longestRun(days: Collection<LocalDate>): Int {
    var best = 0
    var run = 0
    var prev: LocalDate? = null
    for (d in days.sorted()) {
        run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
        if (run > best) best = run
        prev = d
    }
    return best
}

/**
 * @param rows both your sessions and your partner's, including any not yet confirmed by the server
 * @param totals the server's all-time numbers
 * @param unsent your sessions the server has not confirmed (added on top of [totals])
 * @param pairedAt when you paired, or null if you have no partner
 */
internal fun buildStats(
    rows: List<SessionRow>,
    totals: Totals,
    unsent: List<SessionRow>,
    partnerName: String,
    pairedAt: Long?,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Days of the week you don't lock in on purpose: they never break your streak. */
    restDays: Set<DayOfWeek> = emptySet(),
): Stats {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun day(r: SessionRow) = Instant.ofEpochMilli(r.startedAt).atZone(zone).toLocalDate()
    fun at(r: SessionRow) = Instant.ofEpochMilli(r.startedAt).atZone(zone)

    val mine = rows.filter { it.mine }.sortedByDescending { it.startedAt }
    val theirs = rows.filter { !it.mine }.sortedByDescending { it.startedAt }
    val mineByDay = mine.groupBy { day(it) }.mapValues { (_, v) -> v.sumOf { it.seconds } }
    // Only sessions of two minutes or more count toward a streak or the month's climbs: a tap on Start then End
    // is not a climb (Home's list already treats them that way).
    val countedDays = mine.filter { it.seconds >= QuickSeconds }.map { day(it) }.toSet()
    val theirDays = theirs.map { day(it) }.toSet()

    // Streak: days in a row with a session, counting back from today (or yesterday if today has none yet).
    // A rest day never breaks it, and one missed day a week is forgiven (the "streak freeze").
    fun has(d: LocalDate) = countedDays.contains(d)
    var cursor = if (has(today)) today else today.minusDays(1)
    var streak = 0
    var freezeOn: LocalDate? = null
    var guard = 0
    while (guard++ < 800) {
        when {
            has(cursor) -> { streak += 1; cursor = cursor.minusDays(1) }
            cursor.dayOfWeek in restDays && (streak > 0 || today.toEpochDay() - cursor.toEpochDay() < 7) -> cursor = cursor.minusDays(1)
            streak > 0 && has(cursor.minusDays(1)) && (freezeOn == null || freezeOn.toEpochDay() - cursor.toEpochDay() >= 7) -> {
                freezeOn = cursor
                cursor = cursor.minusDays(1)
            }
            else -> break
        }
    }
    val freezeRecent = freezeOn?.takeIf { today.toEpochDay() - it.toEpochDay() < 7 }

    // This week (Monday to Sunday) and the same stretch of last week.
    val monday = today.with(DayOfWeek.MONDAY)
    val week = FloatArray(7) { i ->
        val d = monday.plusDays(i.toLong())
        if (d > today) 0f else (mineByDay[d] ?: 0) / 3600f
    }
    val daysSoFar = today.dayOfWeek.value // 1 for Monday
    val lastMonday = monday.minusWeeks(1)
    // Last week only up to this moment a week ago, so a Monday morning isn't measured against all of last Monday.
    val lastWeekStart = lastMonday.atStartOfDay(zone).toInstant().toEpochMilli()
    val sameMomentLastWeek = now - 7L * 24 * 3600 * 1000
    val lastSoFar = mine.filter { it.startedAt in lastWeekStart until sameMomentLastWeek }.sumOf { it.seconds } / 3600f
    val thisSoFar = week.sum()
    val weekDelta = thisSoFar - lastSoFar
    val partnerByDay = theirs.groupBy { day(it) }.mapValues { (_, v) -> v.sumOf { it.seconds } }
    val partnerWeek = FloatArray(7) { i ->
        val d = monday.plusDays(i.toLong())
        if (d > today) 0f else (partnerByDay[d] ?: 0) / 3600f
    }

    // This month, day by day, and against last month up to the same day.
    val month = YearMonth.from(today)
    val monthHours = FloatArray(month.lengthOfMonth()) { i ->
        val d = month.atDay(i + 1)
        if (d > today) 0f else (mineByDay[d] ?: 0) / 3600f
    }
    val thisMonth = mine.filter { YearMonth.from(day(it)) == month && it.seconds >= QuickSeconds }
    val prev = month.minusMonths(1)
    val prevLimit = minOf(today.dayOfMonth, prev.lengthOfMonth())
    val thisTotal = thisMonth.sumOf { it.seconds }
    val lastTotal = mine.filter { YearMonth.from(day(it)) == prev && day(it).dayOfMonth <= prevLimit && it.seconds >= QuickSeconds }.sumOf { it.seconds }
    val monthDelta = when {
        lastTotal > 0 -> {
            val pct = ((thisTotal - lastTotal) * 100f / lastTotal).roundToInt()
            (if (pct >= 0) "+" else "") + pct + "% on last month"
        }
        thisTotal > 0 -> "Your first month"
        else -> "Start your first climb"
    }
    val longest = thisMonth.maxOfOrNull { it.seconds } ?: 0

    // Lists
    val history = mine.groupBy { day(it) }.entries.sortedByDescending { it.key }.map { (d, list) ->
        DayGroup(
            dayLabel(d, today),
            list.map {
                Session(it.title, timeSpan(at(it), it.seconds), it.seconds / 60, it.seconds >= SummitSeconds, it.pickups)
            },
        )
    }
    val recent = mine.filter { it.seconds >= QuickSeconds }.take(3).map { Climb(it.title, whenText(at(it), today), it.seconds / 60, it.seconds >= SummitSeconds) }
    val activity = (mine + theirs).sortedByDescending { it.startedAt }.take(6).map {
        val who = if (it.mine) "You" else partnerName
        Activity("$who locked in for ${formatMinutes(maxOf(it.seconds / 60, 1))}", whenText(at(it), today), !it.mine)
    }

    // Records, the year, and what time of day you focus.
    val bestDay = mineByDay.maxByOrNull { it.value }
    val weekTotals = mine.groupBy { day(it).with(DayOfWeek.MONDAY) }.mapValues { (_, v) -> v.sumOf { it.seconds } }
    val hourBuckets = IntArray(24)
    for (r in mine) {
        var t = at(r)
        var left = r.seconds
        while (left > 0) {
            val toNextHour = (3600 - t.minute * 60 - t.second).coerceIn(1, 3600)
            val slice = minOf(left, toNextHour)
            hourBuckets[t.hour] += slice
            left -= slice
            t = t.plusSeconds(slice.toLong())
        }
    }
    val lastWeekAll = (0 until 7).sumOf { mineByDay[lastMonday.plusDays(it.toLong())] ?: 0 }

    // Together
    val both = mineByDay.keys.intersect(theirDays)
    val unsentSeconds = unsent.sumOf { it.seconds.toLong() }
    val allMine = totals.mineSeconds + unsentSeconds
    val together = if (pairedAt == null) 0 else
        maxOf(0, (today.toEpochDay() - Instant.ofEpochMilli(pairedAt).atZone(zone).toLocalDate().toEpochDay()).toInt())

    return Stats(
        todaySeconds = mineByDay[today] ?: 0,
        partnerTodaySeconds = theirs.filter { day(it) == today }.sumOf { it.seconds },
        streak = streak,
        bestTogetherStreak = longestRun(both),
        monthSessions = thisMonth.size,
        week = week,
        weekDelta = signedMinutes(weekDelta),
        weekUp = weekDelta > 0f,
        weekFlat = (abs(weekDelta) * 60f).roundToInt() == 0,
        partnerWeek = partnerWeek,
        todayCounted = today in countedDays,
        monthHours = monthHours,
        monthDelta = monthDelta,
        longestSession = if (longest == 0) "None yet" else formatMinutes(maxOf(longest / 60, 1)),
        monthSummits = thisMonth.count { it.seconds >= SummitSeconds },
        history = history,
        recent = recent,
        activity = activity,
        totalHours = allMine / 3600f,
        sessionsTogether = totals.mineCount + totals.partnerCount + unsent.size,
        hoursTogether = (allMine + totals.partnerSeconds) / 3600f,
        daysTogether = together,
        longestSeconds = longest,
        quickSessions = mine.count { it.seconds < QuickSeconds && day(it) == today },
        longestStreak = maxOf(longestRun(mineByDay.keys), streak),
        allSummits = mine.count { it.seconds >= SummitSeconds },
        longestEverSeconds = mine.maxOfOrNull { it.seconds } ?: 0,
        bestDaySeconds = bestDay?.value ?: 0,
        bestDayEpoch = bestDay?.key?.toEpochDay() ?: -1L,
        bestWeekSeconds = weekTotals.values.maxOrNull() ?: 0,
        dayTotals = mineByDay.mapKeys { it.key.toEpochDay() },
        dayCounts = mine.groupBy { day(it).toEpochDay() }.mapValues { it.value.size },
        hourBuckets = hourBuckets,
        freezeUsedOn = freezeRecent?.toEpochDay() ?: -1L,
        restToday = today.dayOfWeek in restDays,
        weekSeconds = (0..6).sumOf { i -> monday.plusDays(i.toLong()).let { d -> if (d > today) 0 else mineByDay[d] ?: 0 } },
        lastWeekSeconds = lastWeekAll,
        exportRows = mine.reversed(),
    )
}
