package com.godbless.lockin.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.data.CrewRepository
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.lock.LockService
import kotlinx.coroutines.flow.update

/**
 * A crew session you said "I'm in" to, set for later. An alarm at its start wakes LockIn, asks the server
 * whether you're still in, and holds the lock, even when LockIn was closed. Rides on [PlanReminderReceiver].
 */
object CrewAlarm {
    internal const val Extra = "crew_start"
    private const val Code = 7103
    private const val Prefs = "crew_alarm"
    private const val KeyAt = "at"

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java).putExtra(Extra, true)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, Code, intent, flags)
    }

    /** Wake at [atMillis]. Setting the same time again does nothing. */
    fun set(context: Context, atMillis: Long) {
        val p = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        if (kotlin.math.abs(p.getLong(KeyAt, 0L) - atMillis) < 5_000L) return
        p.edit().putLong(KeyAt, atMillis).apply()
        val pi = pending(context, create = true) ?: return
        Alarms.at(context, maxOf(atMillis, System.currentTimeMillis() + 1000L), pi)
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().remove(KeyAt).apply()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, create = false)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    /** After a restart: set the alarm again, or check now if the start went by while the phone was off. */
    fun rearm(context: Context) {
        val at = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getLong(KeyAt, 0L)
        if (at == 0L) return
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().remove(KeyAt).apply()
        set(context, at)
    }

    internal suspend fun fire(context: Context) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().remove(KeyAt).apply()
        val app = context.applicationContext as LockInApplication
        app.crewPing.update { it + 1 }
        runCatching {
            if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
            val b = CrewRepository(PartnerRepository(app.auth)).beat().getOrNull() ?: return@runCatching
            when (b.state) {
                "active" -> {
                    LockService.holdCrew(context, b.id, b.remaining, b.crew)
                    com.godbless.lockin.lock.LockGuard(context).bringBack()
                }
                // Not quite yet (a dozing phone woke early): try again at the real start.
                "upcoming" -> set(context, System.currentTimeMillis() + b.startsIn * 1000L)
            }
        }.onFailure { Log.w("LockInLock", "crew start check failed: ${it.message}") }
    }
}
