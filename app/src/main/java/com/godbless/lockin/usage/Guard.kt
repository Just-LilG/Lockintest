package com.godbless.lockin.usage

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.godbless.lockin.lock.LockGuard

/** Starts and stops the background service that reports app times and holds the limits. */
object Guard {
    const val ActionRefresh = "com.godbless.lockin.REFRESH_LIMITS"

    /** Needs the usage permission, and something to do: report app times, or hold at least one limit. */
    fun wanted(context: Context): Boolean {
        if (!UsageAccess.granted(context)) return false
        val store = LimitStore(context)
        return store.reporting || store.load().isNotEmpty()
    }

    /**
     * Runs the service if there is something for it to do, and stops it if there isn't. An app that was
     * suspended is switched back on if its limit is gone or LockIn can no longer read app times, because
     * then nothing would ever switch it back on at midnight.
     */
    fun sync(context: Context) {
        val store = LimitStore(context)
        if (store.suspended().isNotEmpty() && (store.load().isEmpty() || !UsageAccess.granted(context))) releaseSuspended(context)
        if (wanted(context) || watching(context)) start(context) else stop(context)
    }

    /**
     * A paired phone keeps the service running even with no limit and no app-time sharing: it is the one thing that keeps
     * watching (and checking in) if the accessibility guard is switched off.
     */
    fun watching(context: Context): Boolean = com.godbless.lockin.lock.Protection.on(context)

    /** Switches back on every app this phone suspended for a limit. */
    fun releaseSuspended(context: Context) {
        val store = LimitStore(context)
        val held = store.suspended()
        if (held.isEmpty()) return
        val guard = LockGuard(context)
        held.forEach { guard.suspend(it, false) }
        store.setSuspended(emptySet())
    }

    /** [refresh] makes it fetch the limits right away, as when your partner has just changed one. */
    fun start(context: Context, refresh: Boolean = false) {
        runCatching {
            val intent = Intent(context, GuardService::class.java)
            if (refresh) intent.action = ActionRefresh
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, GuardService::class.java)) }
    }

    /** On sign-out: switch back on anything suspended, forget the limits, and stop. */
    fun reset(context: Context) {
        releaseSuspended(context)
        LimitStore(context).clearSettings()
        stop(context)
    }
}
