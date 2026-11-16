package com.godbless.lockin.usage

/** One entry from Android's usage event log, cut down to what the maths needs. [time] is epoch millis. */
class UsageEvent(val pkg: String, val cls: String, val type: Int, val time: Long)

/** The event types we use. These are the numbers Android's `UsageEvents.Event` uses for them. */
object UsageEventType {
    const val Resumed = 1 // an app screen came to the front
    const val Paused = 2 // an app screen left the front
    const val ScreenOff = 16
    const val KeyguardShown = 17
    const val Shutdown = 26
    const val Stopped = 23 // an app screen is no longer visible at all (Android 10+)

    fun wanted(type: Int) = type == Resumed || type == Paused || type == Stopped || type == ScreenOff || type == KeyguardShown || type == Shutdown
}

/**
 * How many seconds each app was on screen between [from] and [to] (epoch millis).
 *
 * Only one app is in front at a time, so when a different app comes to the front the previous one is
 * closed at that moment. That keeps one lost "paused" event from adding hours to an app. The screen
 * turning off or locking closes everything. Time is clipped to the window, so a day can be asked for
 * from midnight to midnight. Apps under one second are left out.
 */
fun foregroundSeconds(events: List<UsageEvent>, from: Long, to: Long): Map<String, Int> {
    val screens = HashMap<String, MutableSet<String>>() // package -> its screens that are in front right now
    val since = HashMap<String, Long>() // package -> when it first came to the front (this stretch)
    val millis = HashMap<String, Long>() // package -> time on screen

    // The package leaves the front at [end]: add its stretch, clipped to the window.
    fun leave(pkg: String, end: Long) {
        screens.remove(pkg)
        val began = since.remove(pkg) ?: return
        val a = maxOf(began, from)
        val b = minOf(end, to)
        if (b > a) millis[pkg] = (millis[pkg] ?: 0L) + (b - a)
    }

    for (e in events.sortedBy { it.time }) {
        if (e.time > to) break
        when (e.type) {
            UsageEventType.Resumed -> {
                for (other in screens.keys.toList()) if (other != e.pkg) leave(other, e.time)
                val open = screens.getOrPut(e.pkg) { HashSet() }
                if (open.isEmpty()) since[e.pkg] = e.time
                open.add(e.cls)
            }
            UsageEventType.Paused -> {
                val open = screens[e.pkg] ?: continue
                open.remove(e.cls)
                if (open.isEmpty()) leave(e.pkg, e.time)
            }
            UsageEventType.ScreenOff, UsageEventType.KeyguardShown, UsageEventType.Shutdown ->
                for (pkg in screens.keys.toList()) leave(pkg, e.time)
        }
    }
    for (pkg in screens.keys.toList()) leave(pkg, to)
    return millis.mapValues { (it.value / 1000).toInt() }.filterValues { it > 0 }
}

/** "1 h 05 m", "12 m", or "under a minute". */
fun durationText(seconds: Int): String {
    val m = seconds / 60
    return when {
        seconds < 60 -> "under a minute"
        m < 60 -> "$m m"
        else -> "${m / 60} h ${(m % 60).toString().padStart(2, '0')} m"
    }
}
