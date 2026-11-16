package com.godbless.lockin.push

import android.content.Context

/**
 * Which kinds of notification you want. These only decide what LockIn shows; the pushes still arrive, so a lock
 * still takes over the screen and Home still refreshes when a kind is switched off.
 */
enum class NotifyKind(val title: String, val detail: String) {
    Messages("Messages", "When your partner writes to you."),
    Locks("Locks", "When you're locked, a lock changes, or your partner asks to be let out."),
    Plans("Plans", "When your partner plans or cancels a session."),
    Nudges("Nudges", "When your partner nudges you to lock in."),
    Limits("App limits", "When your partner sets or removes a limit on an app."),
    Games("Games", "When your partner sends you a game or makes a move."),
}

object NotifyPrefs {
    private const val Prefs = "notify_prefs"

    fun on(context: Context, kind: NotifyKind): Boolean =
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean(kind.name, true)

    fun set(context: Context, kind: NotifyKind, on: Boolean) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putBoolean(kind.name, on).apply()
    }

    /** What kind of notification a push is, from its event code (empty for a plain message). */
    fun kindOf(event: String): NotifyKind = when {
        event.isEmpty() -> NotifyKind.Messages
        event == "nudge" -> NotifyKind.Nudges
        event.startsWith("game_") -> NotifyKind.Games
        event.startsWith("plan_") -> NotifyKind.Plans
        event.startsWith("limit_") -> NotifyKind.Limits
        event.startsWith("lock_") || event.startsWith("protect_") || event in setOf("asked_unlock", "unlock_denied", "emergency_release") -> NotifyKind.Locks
        else -> NotifyKind.Messages
    }

    // ---- Quiet hours: nothing is shown between two times, except locks, which still come through. ----

    fun quietOn(context: Context): Boolean = prefs(context).getBoolean("quiet_on", false)
    /** Minutes after midnight. */
    fun quietFrom(context: Context): Int = prefs(context).getInt("quiet_from", 22 * 60)
    fun quietTo(context: Context): Int = prefs(context).getInt("quiet_to", 7 * 60)

    fun setQuiet(context: Context, on: Boolean) { prefs(context).edit().putBoolean("quiet_on", on).apply() }
    fun setQuietFrom(context: Context, minutes: Int) { prefs(context).edit().putInt("quiet_from", minutes).apply() }
    fun setQuietTo(context: Context, minutes: Int) { prefs(context).edit().putInt("quiet_to", minutes).apply() }

    /** Whether the current time falls inside quiet hours (they may run past midnight). */
    fun quietNow(context: Context, nowMinutes: Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }): Boolean {
        if (!quietOn(context)) return false
        val from = quietFrom(context)
        val to = quietTo(context)
        return when {
            from == to -> false
            from < to -> nowMinutes in from until to
            else -> nowMinutes >= from || nowMinutes < to
        }
    }

    fun allows(context: Context, event: String): Boolean {
        val kind = kindOf(event)
        return on(context, kind) && (kind == NotifyKind.Locks || !quietNow(context))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    /** The kinds switched off, as the server names them (messages, locks, ...). */
    fun offKeys(context: Context): List<String> = NotifyKind.entries.filter { !on(context, it) }.map { it.name.lowercase() }
}
