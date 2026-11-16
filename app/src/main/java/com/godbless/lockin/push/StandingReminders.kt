package com.godbless.lockin.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.godbless.lockin.data.CrewStanding
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A reminder ten minutes before each of the crew's standing sessions. The slots live on the server (minutes from
 * Monday 00:00 UTC); this sets an alarm for each slot's next few turns, so a reminder still comes if the app isn't
 * opened for a couple of weeks. It rides on [PlanReminderReceiver], and follows the same "session reminders" switch.
 */
object StandingReminders {
    private const val Prefs = "standing_reminders"
    private const val KeyScheduled = "scheduled"
    private const val WeekMillis = 7 * 24 * 60 * 60_000L
    private const val Turns = 4

    /** The start times (millis) of the next [Turns] turns of a slot that still have a reminder ahead of them. */
    internal fun nextStarts(wkMinute: Int, now: Long): List<Long> {
        val monday = ZonedDateTime.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
        var t = monday + wkMinute * 60_000L
        while (t - PlanReminders.LeadMillis <= now) t += WeekMillis
        return List(Turns) { t + it * WeekMillis }
    }

    private fun pending(context: Context, id: Long, title: String, at: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java)
            .putExtra(PlanReminders.ExtraTitle, title)
            .putExtra(PlanReminders.ExtraAt, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, "standing$id$at".hashCode(), intent, flags)
    }

    private fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        for (entry in prefs.getStringSet(KeyScheduled, emptySet()).orEmpty()) {
            val p = entry.split('\u0001')
            val id = p.getOrNull(0)?.toLongOrNull() ?: continue
            val at = p.getOrNull(2)?.toLongOrNull() ?: continue
            pending(context, id, p.getOrElse(1) { "" }, at, create = false)?.let { alarms.cancel(it); it.cancel() }
        }
        prefs.edit().remove(KeyScheduled).apply()
    }

    /** Makes the alarms match [slots] (none when session reminders are off or the crew is gone). */
    fun sync(context: Context, crewName: String, slots: List<CrewStanding>) {
        cancelAll(context)
        if (!PlanReminders.enabled(context) || slots.isEmpty()) return
        val now = System.currentTimeMillis()
        val title = "$crewName crew session"
        val scheduled = HashSet<String>()
        for (slot in slots) for (at in nextStarts(slot.wkMinute, now)) {
            val pi = pending(context, slot.id, title, at, create = true) ?: continue
            Alarms.at(context, at - PlanReminders.LeadMillis, pi)
            scheduled += listOf(slot.id.toString(), title, at.toString()).joinToString("\u0001")
        }
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putStringSet(KeyScheduled, scheduled).apply()
    }

    fun clear(context: Context) = cancelAll(context)
}
