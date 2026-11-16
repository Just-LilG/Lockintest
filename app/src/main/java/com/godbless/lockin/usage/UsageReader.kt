package com.godbless.lockin.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One app's time on screen for a day. [limitable] is false for the phone app, Settings and LockIn itself. */
class AppUse(val pkg: String, val label: String, val seconds: Int, val limitable: Boolean)

/**
 * Reads how long each app was on screen, from Android's usage log (needs the usage access permission).
 * Only apps with a launcher icon are counted, so system noise stays out, and LockIn and the home screen
 * are left out of the list.
 */
class UsageReader(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = app.packageManager

    private class Info(val label: String, val limitable: Boolean)

    private val infos = HashMap<String, Info?>()

    @Suppress("DEPRECATION")
    private val homes: Set<String> by lazy {
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Apps that can never be limited: calls, texts, Settings, emergency, and LockIn. A limit must never lock someone out of help. */
    @Suppress("DEPRECATION")
    private val protectedPackages: Set<String> by lazy {
        val set = HashSet<String>()
        set.add(app.packageName)
        set.add("com.android.settings")
        set.add("com.android.emergency")
        set.add("com.android.systemui")
        runCatching { (app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { set.add(it) } }
        runCatching { Telephony.Sms.getDefaultSmsPackage(app)?.let { set.add(it) } }
        runCatching {
            pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0).forEach { set.add(it.activityInfo.packageName) }
        }
        set
    }

    private fun info(pkg: String): Info? {
        if (infos.containsKey(pkg)) return infos[pkg]
        val found = when {
            pkg.isEmpty() || pkg == app.packageName || pkg in homes -> null
            pm.getLaunchIntentForPackage(pkg) == null -> null
            else -> {
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Info(label.ifBlank { pkg }, pkg !in protectedPackages)
            }
        }
        infos[pkg] = found
        return found
    }

    /** Is [pkg] one of the apps we track (so it can have a limit)? */
    fun isTracked(pkg: String): Boolean = info(pkg) != null

    fun events(from: Long, to: Long): List<UsageEvent> {
        val out = ArrayList<UsageEvent>()
        val query = try {
            manager.queryEvents(from, to)
        } catch (e: Exception) {
            null
        } ?: return out
        val e = UsageEvents.Event()
        while (query.hasNextEvent()) {
            query.getNextEvent(e)
            if (UsageEventType.wanted(e.eventType)) {
                out.add(UsageEvent(e.packageName.orEmpty(), e.className.orEmpty(), e.eventType, e.timeStamp))
            }
        }
        return out
    }

    private fun startOf(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Seconds [pkg] has been on screen since midnight. */
    fun usedToday(pkg: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = startOf(today, zone)
        return foregroundSeconds(events(from, now), from, now)[pkg] ?: 0
    }

    /** The last [count] days, today first. Apps used under half a minute are left out. */
    fun days(count: Int = 7, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<AppUse>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val all = events(startOf(today.minusDays(count - 1L), zone), now)
        val out = LinkedHashMap<LocalDate, List<AppUse>>()
        for (i in 0 until count) {
            val day = today.minusDays(i.toLong())
            val from = startOf(day, zone)
            val to = minOf(startOf(day.plusDays(1), zone), now)
            out[day] = foregroundSeconds(all, from, to)
                .mapNotNull { (pkg, secs) ->
                    if (secs < 30) null else info(pkg)?.let { AppUse(pkg, it.label, secs, it.limitable) }
                }
                .sortedByDescending { it.seconds }
        }
        return out
    }
}

/** Knows which app is in front right now, by reading only the newest events each time it is asked. */
class ForegroundWatcher(private val reader: UsageReader) {
    // The first look goes back a little, so an app that is already open when we start is noticed.
    private var since = System.currentTimeMillis() - 10 * 60_000

    var current: String? = null
        private set

    // Every app screen that is showing, by package. With split screen or a floating ("small window") app,
    // more than one app is on screen at once, and both stay "resumed": the newest one isn't the only one.
    private val screens = HashMap<String, MutableSet<String>>()
    private val resumedAt = HashMap<String, Long>()

    /** Every app with a screen showing right now. */
    val onScreen: Set<String> get() = screens.keys.toSet()

    /** When [pkg] last came on screen, or 0. */
    fun resumedAt(pkg: String): Long = resumedAt[pkg] ?: 0L

    fun poll(): String? {
        val now = System.currentTimeMillis()
        for (e in reader.events(since, now)) {
            when (e.type) {
                UsageEventType.Resumed -> {
                    current = e.pkg
                    screens.getOrPut(e.pkg) { HashSet() }.add(e.cls)
                    resumedAt[e.pkg] = e.time
                }
                // Paused only means "not focused": a floating window that isn't focused is paused but still on
                // screen. So from Android 10 (which reports Stopped) a screen leaves only once it's stopped.
                UsageEventType.Paused -> {
                    if (current == e.pkg) current = null
                    if (android.os.Build.VERSION.SDK_INT < 29) {
                        screens[e.pkg]?.let { open -> open.remove(e.cls); if (open.isEmpty()) screens.remove(e.pkg) }
                    }
                }
                UsageEventType.Stopped -> screens[e.pkg]?.let { open -> open.remove(e.cls); if (open.isEmpty()) screens.remove(e.pkg) }
                UsageEventType.ScreenOff, UsageEventType.KeyguardShown, UsageEventType.Shutdown -> {
                    current = null
                    screens.clear()
                }
            }
        }
        // Overlap a little so nothing is missed; replaying an event gives the same answer.
        since = now - 1_000
        return current
    }
}
