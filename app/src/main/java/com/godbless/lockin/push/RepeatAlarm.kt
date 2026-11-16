package com.godbless.lockin.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.data.LockRepeat
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.RemoteLockRepository
import com.godbless.lockin.lock.LockService
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Wakes this phone at the start of the next repeating lock set on it (exact when allowed, see [Alarms]).
 * At that moment it asks the server, which creates the lock right then, and the lock service starts holding it,
 * even if LockIn is closed. The server also starts repeats by itself every minute, so a phone that was off
 * still finds the lock running when it comes back.
 *
 * The repeats are kept here too, so the alarm can be set again after a restart without a connection.
 */
object RepeatAlarm {
    internal const val Extra = "lock_repeat"
    private const val Code = 7102
    private const val Prefs = "lock_repeats"
    private const val Key = "list"

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java).putExtra(Extra, true)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, Code, intent, flags)
    }

    /** Remembers the repeats set on this phone and sets the alarm for the next start (or clears it). */
    fun sync(context: Context, onMe: List<LockRepeat>) {
        val arr = JSONArray()
        onMe.forEach {
            arr.put(JSONObject().put("days", it.days).put("start", it.startMinute).put("seconds", it.seconds).put("tz", it.tz))
        }
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putString(Key, arr.toString()).apply()
        rearm(context)
    }

    /** Sets the alarm from what is remembered. Called after a restart too. */
    fun rearm(context: Context) {
        val next = nextStart(load(context), System.currentTimeMillis())
        if (next == null) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            pending(context, create = false)?.let { alarms.cancel(it); it.cancel() }
            return
        }
        val pi = pending(context, create = true) ?: return
        Alarms.at(context, next, pi)
    }

    private class Saved(val days: Int, val start: Int, val seconds: Int, val tz: String)

    private fun load(context: Context): List<Saved> {
        val raw = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getString(Key, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Saved(o.getInt("days"), o.getInt("start"), o.getInt("seconds"), o.getString("tz"))
            }
        }.getOrDefault(emptyList())
    }

    /** The soonest start after [now] across all repeats, in milliseconds, or null if there are none. */
    private fun nextStart(list: List<Saved>, now: Long): Long? {
        var best: Long? = null
        for (r in list) {
            val zone = runCatching { ZoneId.of(r.tz) }.getOrDefault(ZoneId.systemDefault())
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            for (d in 0..7) {
                val day = today.plusDays(d.toLong())
                if (r.days and (1 shl (day.dayOfWeek.value - 1)) == 0) continue
                val at = ZonedDateTime.of(day.atStartOfDay(), zone).plusMinutes(r.start.toLong()).toInstant().toEpochMilli()
                if (at > now + 1_000L) {
                    if (best == null || at < best) best = at
                    break
                }
            }
        }
        return best
    }

    /** The alarm went off: ask the server (which starts the lock now), hold it, and set the next alarm. */
    internal suspend fun fire(context: Context) {
        val app = context.applicationContext as LockInApplication
        app.lockPing.update { it + 1 }
        runCatching {
            // After a restart nobody is signed in yet: sign back in with the remembered session.
            if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
            val mine = RemoteLockRepository(PartnerRepository(app.auth)).mine().getOrNull()
            if (mine != null && mine.active) {
                LockService.hold(context, mine.id, mine.remaining, mine.by)
                com.godbless.lockin.lock.LockGuard(context).bringBack()
            }
        }.onFailure { Log.w("LockInLock", "repeat start check failed: ${it.message}") }
        rearm(context)
    }
}
