package com.godbless.lockin.push

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import com.godbless.lockin.data.Plan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A nudge ten minutes before a planned session. The alarms are exact when the phone allows it (see [Alarms]),
 * otherwise a reminder can arrive a little late while the phone is dozing. They are set again every time
 * the plans are fetched, and cleared when a plan is cancelled or reminders are switched off.
 */
object PlanReminders {
    private const val Prefs = "plan_reminders"
    private const val KeyOn = "on"
    private const val KeyScheduled = "scheduled"
    const val Channel = "plans"
    const val LeadMillis = 10 * 60_000L
    internal const val ExtraTitle = "title"
    internal const val ExtraAt = "at"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean(KeyOn, true)

    fun setEnabled(context: Context, on: Boolean, plans: List<Plan>) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putBoolean(KeyOn, on).apply()
        sync(context, plans)
    }

    private fun pending(context: Context, plan: Plan, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java)
            .putExtra(ExtraTitle, plan.title)
            .putExtra(ExtraAt, plan.startsAt)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, plan.id.hashCode(), intent, flags)
    }

    /** Makes the alarms match [plans]: one per future plan when reminders are on, none otherwise. */
    fun sync(context: Context, plans: List<Plan>) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val want = if (enabled(context)) plans.filter { it.startsAt - LeadMillis > now } else emptyList()

        // Take back the alarms for plans that are gone. The plan's id and title are enough to find them.
        val kept = want.map { it.id }.toSet()
        for (entry in prefs.getStringSet(KeyScheduled, emptySet()).orEmpty()) {
            val (id, title, at) = entry.split('\u0001').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "0" }.toLongOrNull() ?: 0L) }
            if (id in kept) continue
            pending(context, Plan(id, title, at, 0, false, ""), create = false)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }

        val scheduled = HashSet<String>()
        for (plan in want) {
            val pi = pending(context, plan, create = true) ?: continue
            Alarms.at(context, plan.startsAt - LeadMillis, pi)
            scheduled += listOf(plan.id, plan.title, plan.startsAt.toString()).joinToString("\u0001")
        }
        prefs.edit().putStringSet(KeyScheduled, scheduled).apply()
    }

    internal fun show(context: Context, title: String, startsAt: Long) {
        if (!Push.canShow(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(Channel) == null) {
            manager.createNotificationChannel(
                NotificationChannel(Channel, "Planned sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A reminder before a session you planned"
                },
            )
        }
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(startsAt).atZone(ZoneId.systemDefault()))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setContentTitle("Starts in 10 minutes")
            .setContentText("$title at $time. Get ready to lock in.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(1100 + (startsAt / 60_000L % 1000).toInt(), notification)
    }
}

/**
 * Sets a wake-up alarm: exact when the phone lets LockIn ("Exact times" on the permissions page, Android 12
 * and up asks), otherwise inexact, which a dozing phone may deliver a few minutes late.
 */
object Alarms {
    fun exactAllowed(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun at(context: Context, atMillis: Long, pi: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val ok = exactAllowed(context) && runCatching {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }.isSuccess
        if (!ok) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}

/**
 * A lock your partner scheduled for later. An alarm at its start time wakes the app so it asks the server and
 * shows the lock, even when LockIn was closed. It rides on [PlanReminderReceiver] so no new receiver is needed.
 *
 * Alarms do not survive a restart, so the start time (and the end of a lock that is running) is kept here too:
 * [rearm] runs from the boot receiver, sets the alarm again, and brings LockIn back if a lock is still on.
 */
object LockStartAlarm {
    internal const val Extra = "lock_start"
    private const val Code = 7101
    private const val Prefs = "lock_alarm"
    private const val KeyStart = "start"
    private const val KeyUntil = "until"

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java).putExtra(Extra, true)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, Code, intent, flags)
    }

    fun set(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KeyStart, atMillis).apply()
        val pi = pending(context, create = true) ?: return
        Alarms.at(context, maxOf(atMillis, System.currentTimeMillis() + 1000L), pi)
    }

    fun cancel(context: Context) {
        prefs(context).edit().remove(KeyStart).apply()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, create = false)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    /** A lock is running on this phone until [untilMillis] (0 when none is). */
    fun running(context: Context, untilMillis: Long) {
        prefs(context).edit().putLong(KeyUntil, untilMillis).apply()
    }

    /** After a restart: set the start alarm again, and pull LockIn up if a lock should be on now. */
    fun rearm(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val start = p.getLong(KeyStart, 0L)
        val until = p.getLong(KeyUntil, 0L)
        when {
            start > now -> set(context, start)
            // The start passed while the phone was off: the lock is on now (the server has the final word).
            start > 0L -> { p.edit().remove(KeyStart).apply(); fire(context) }
            until > now -> fire(context)
            else -> { if (until != 0L) p.edit().remove(KeyUntil).apply() }
        }
    }

    internal fun fire(context: Context) {
        (context.applicationContext as com.godbless.lockin.LockInApplication).lockPing.update { it + 1 }
        com.godbless.lockin.lock.LockGuard(context).bringBack()
    }
}

class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(LockStartAlarm.Extra, false)) {
            LockStartAlarm.fire(context)
            return
        }
        // The daily "flashcards are due" reminder.
        if (intent.getBooleanExtra(QuizReminder.Extra, false)) {
            QuizReminder.fire(context)
            return
        }
        // A crew session you're in starts now.
        if (intent.getBooleanExtra(CrewAlarm.Extra, false)) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { CrewAlarm.fire(context) } finally { pending.finish() }
            }
            return
        }
        // A repeating lock starts now: check with the server off the main thread, then let the receiver go.
        if (intent.getBooleanExtra(RepeatAlarm.Extra, false)) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { RepeatAlarm.fire(context) } finally { pending.finish() }
            }
            return
        }
        val at = intent.getLongExtra(PlanReminders.ExtraAt, 0L)
        // An alarm that was held back for a long time (a dozing phone) is not worth showing once the session has begun.
        if (at < System.currentTimeMillis()) return
        PlanReminders.show(context, intent.getStringExtra(PlanReminders.ExtraTitle).orEmpty().ifBlank { "Your session" }, at)
    }
}
