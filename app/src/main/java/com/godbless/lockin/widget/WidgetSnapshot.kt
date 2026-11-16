package com.godbless.lockin.widget

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * What the home-screen widgets show, saved on the phone so they can draw while LockIn is closed. Home writes it
 * ([publish]) whenever one of these numbers changes; the widgets only ever read it.
 *
 * Times are wall-clock milliseconds. A snapshot from an earlier day is aged by [today]: today's minutes start
 * again from 0, the week from an earlier week empties, and a streak that has missed a whole day is gone.
 */
internal class WidgetSnapshot(
    val signedIn: Boolean,
    /** The day these numbers were for (epoch day). */
    val day: Long,
    val todaySeconds: Int,
    val partnerTodaySeconds: Int,
    val goalMinutes: Int,
    val partnerGoalMinutes: Int,
    val streak: Int,
    /** Hours, Monday to Sunday, for the week [day] is in. */
    val week: FloatArray,
    val weekSeconds: Int,
    val lastWeekSeconds: Int,
    /** "+1h 10m" against the same days last week (empty when there's nothing to compare), and which way. */
    val weekDelta: String,
    val weekUp: Boolean,
    val myName: String,
    /** Null when there is no partner. */
    val partnerName: String?,
    /** When your own session started, or 0 when none is running. */
    val sessionStart: Long,
    /** A lock your partner set on this phone: when it ends (0: none), how long it is, who set it. */
    val lockOnMeEnd: Long,
    val lockOnMeTotal: Int,
    val lockOnMeBy: String,
    /** A lock you set on your partner that is running: when it ends (0: none) and how long it is. */
    val lockOnThemEnd: Long,
    val lockOnThemTotal: Int,
    val nextTitle: String,
    /** When the next planned session starts, or 0. */
    val nextAt: Long,
) {
    /** The same snapshot as it stands on [now]'s day. */
    fun today(now: Long = System.currentTimeMillis()): WidgetSnapshot {
        val today = LocalDate.now().toEpochDay()
        if (today == day) return this
        val then = LocalDate.ofEpochDay(day)
        val sameWeek = LocalDate.now().with(DayOfWeek.MONDAY) == then.with(DayOfWeek.MONDAY)
        val nextWeek = LocalDate.now().with(DayOfWeek.MONDAY) == then.with(DayOfWeek.MONDAY).plusWeeks(1)
        return WidgetSnapshot(
            signedIn, today, 0, 0, goalMinutes, partnerGoalMinutes,
            // Yesterday's streak still stands today (today isn't over); a whole missed day ends it.
            if (today - day == 1L) streak else 0,
            if (sameWeek) week else FloatArray(7),
            if (sameWeek) weekSeconds else 0,
            when { sameWeek -> lastWeekSeconds; nextWeek -> weekSeconds; else -> 0 },
            if (sameWeek) weekDelta else "", weekUp,
            myName, partnerName,
            sessionStart, lockOnMeEnd, lockOnMeTotal, lockOnMeBy, lockOnThemEnd, lockOnThemTotal,
            nextTitle, if (nextAt > now) nextAt else 0L,
        )
    }

    companion object {
        private const val Prefs = "widget_snapshot"

        val SignedOut = WidgetSnapshot(
            false, LocalDate.now().toEpochDay(), 0, 0, 120, 120, 0, FloatArray(7), 0, 0, "", true, "", null,
            0L, 0L, 0, "", 0L, 0, "", 0L,
        )

        fun load(context: Context): WidgetSnapshot {
            val p = runCatching { context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE) }.getOrNull()
                ?: return SignedOut
            if (!p.getBoolean("in", false)) return SignedOut
            val week = (p.getString("week", "") ?: "").split(',').mapNotNull { it.toFloatOrNull() }
            return WidgetSnapshot(
                signedIn = true,
                day = p.getLong("day", LocalDate.now().toEpochDay()),
                todaySeconds = p.getInt("today", 0),
                partnerTodaySeconds = p.getInt("ptoday", 0),
                goalMinutes = p.getInt("goal", 120).coerceAtLeast(1),
                partnerGoalMinutes = p.getInt("pgoal", 120).coerceAtLeast(1),
                streak = p.getInt("streak", 0),
                week = FloatArray(7) { week.getOrElse(it) { 0f } },
                weekSeconds = p.getInt("wsec", 0),
                lastWeekSeconds = p.getInt("lwsec", 0),
                weekDelta = p.getString("wdelta", "") ?: "",
                weekUp = p.getBoolean("wup", true),
                myName = p.getString("me", "") ?: "",
                partnerName = p.getString("partner", null),
                sessionStart = p.getLong("session", 0L),
                lockOnMeEnd = p.getLong("meEnd", 0L),
                lockOnMeTotal = p.getInt("meTotal", 0),
                lockOnMeBy = p.getString("meBy", "") ?: "",
                lockOnThemEnd = p.getLong("themEnd", 0L),
                lockOnThemTotal = p.getInt("themTotal", 0),
                nextTitle = p.getString("nextTitle", "") ?: "",
                nextAt = p.getLong("nextAt", 0L),
            ).today()
        }

        /** Saves what Home knows right now and redraws every widget. Cheap when nothing changed. */
        fun publish(
            context: Context,
            todaySeconds: Int,
            partnerTodaySeconds: Int,
            goalMinutes: Int,
            partnerGoalMinutes: Int,
            streak: Int,
            week: FloatArray,
            weekSeconds: Int,
            lastWeekSeconds: Int,
            weekDelta: String,
            weekUp: Boolean,
            myName: String,
            partnerName: String?,
            sessionStart: Long,
            lockOnMeEnd: Long,
            lockOnMeTotal: Int,
            lockOnMeBy: String,
            lockOnThemEnd: Long,
            lockOnThemTotal: Int,
            nextTitle: String,
            nextAt: Long,
        ) {
            val app = context.applicationContext
            val p = app.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            val ed = p.edit()
                .putBoolean("in", true)
                .putLong("day", LocalDate.now().toEpochDay())
                .putInt("today", todaySeconds)
                .putInt("ptoday", partnerTodaySeconds)
                .putInt("goal", goalMinutes)
                .putInt("pgoal", partnerGoalMinutes)
                .putInt("streak", streak)
                .putString("week", week.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) })
                .putInt("wsec", weekSeconds)
                .putInt("lwsec", lastWeekSeconds)
                .putString("wdelta", weekDelta)
                .putBoolean("wup", weekUp)
                .putString("me", myName)
                .putString("partner", partnerName)
                .putLong("session", sessionStart)
                .putLong("meEnd", lockOnMeEnd)
                .putInt("meTotal", lockOnMeTotal)
                .putString("meBy", lockOnMeBy)
                .putLong("themEnd", lockOnThemEnd)
                .putInt("themTotal", lockOnThemTotal)
                .putString("nextTitle", nextTitle)
                .putLong("nextAt", nextAt)
            // Only redraw when something the widgets show has changed (Home calls this often).
            val before = p.all.toString()
            ed.commit()
            if (p.all.toString() != before) LockInWidgets.refreshAll(app)
        }

        /** Signed out: the widgets ask you to open LockIn. */
        fun clear(context: Context) {
            val app = context.applicationContext
            app.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().clear().commit()
            LockInWidgets.refreshAll(app)
        }
    }
}
