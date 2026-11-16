package com.godbless.lockin.lock

import android.content.Context
import android.provider.Settings
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.usage.UsageAccess
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices when something that holds LockIn's protection is switched off on a paired phone, and tells the partner.
 *
 * The guard (the accessibility service) tries to stop it by covering the Settings screen. This is the other half: whatever
 * gets past it is still seen here. It runs from LockIn's always-on service (see GuardService) and from the guard itself, so
 * switching the guard off does not switch the watcher off with it.
 *
 * A switch counts only if it was seen ON first, and not if the partner has said Allow for it (that is what the Allow window
 * is for). It is reported once per time it goes off. During a lock the lock service does the reporting (and voids the lock),
 * so this stays quiet then.
 *
 * Also notes a restart LockIn never saw (safe mode): Android counts every start-up, and in safe mode LockIn can't run, so a
 * count that jumped by two or more since LockIn last looked means one start-up happened without it.
 *
 * Reports wait in a small queue until there is a connection, so cutting the internet first does not hide anything.
 */
internal object ProtectionWatch {
    private const val PREFS = "protect_watch"
    private const val QUEUE = "queue"
    private const val BOOT = "boot"

    private val KINDS = listOf(Protection.Overlay, Protection.Usage, Protection.Admin, Protection.Guard)
    private val flushing = AtomicBoolean(false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The server's word for "this was switched off". */
    private fun wire(kind: String): String = when (kind) {
        Protection.Overlay -> "overlay_off"
        Protection.Usage -> "usage_off"
        Protection.Admin -> "admin_off"
        else -> "guard_off"
    }

    private fun isOn(context: Context, kind: String): Boolean = when (kind) {
        Protection.Overlay -> Settings.canDrawOverlays(context)
        Protection.Usage -> UsageAccess.granted(context)
        Protection.Admin -> LockGuard(context).let { it.isAdmin() || it.isOwner() }
        else -> Protection.guardEnabled(context)
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** Looks once. Cheap enough to call every few seconds. Anything switched off without the partner's OK is queued. */
    fun check(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!Protection.on(app)) {
            // Nobody is paired here (any more): forget what was seen, so a later pairing starts clean.
            if (p.all.keys.any { it.startsWith("seen_") }) {
                val ed = p.edit()
                for (k in KINDS) ed.remove("seen_$k")
                ed.remove(BOOT).apply()
            }
            return
        }
        val inLock = LocalLock(app).active
        val ed = p.edit()
        var changed = false
        for (kind in KINDS) {
            val on = isOn(app, kind)
            val seen = p.getBoolean("seen_$kind", false)
            if (on && !seen) {
                ed.putBoolean("seen_$kind", true)
                changed = true
            } else if (!on && seen) {
                ed.putBoolean("seen_$kind", false)
                changed = true
                // Switched off with the partner's OK, or during a lock (which reports it itself): nothing to add.
                if (!Protection.isOpen(app, kind) && !inLock) enqueue(app, wire(kind))
            }
        }
        val boot = bootCount(app)
        if (boot >= 0) {
            val last = p.getInt(BOOT, -1)
            if (last >= 0 && boot - last >= 2 && !inLock) enqueue(app, "safe_mode")
            if (boot != last) {
                ed.putInt(BOOT, boot)
                changed = true
            }
        }
        if (changed) ed.apply()
    }

    // ----- The queue -----

    fun queued(context: Context): List<String> =
        prefs(context).getString(QUEUE, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun enqueue(context: Context, kind: String) {
        val q = queued(context)
        if (kind in q) return
        prefs(context).edit().putString(QUEUE, (q + kind).takeLast(10).joinToString("\n")).apply()
    }

    private fun dequeue(context: Context, kind: String) {
        prefs(context).edit().putString(QUEUE, queued(context).filter { it != kind }.joinToString("\n")).apply()
    }

    /** Sends what is waiting. Call with a signed-in [partners]; whatever doesn't get through stays queued for next time. */
    suspend fun flush(context: Context, partners: PartnerRepository) {
        val app = context.applicationContext
        if (queued(app).isEmpty() || !flushing.compareAndSet(false, true)) return
        try {
            for (kind in queued(app)) {
                partners.protectTamper(kind).onSuccess { dequeue(app, kind) }
            }
        } finally {
            flushing.set(false)
        }
    }
}
