package com.godbless.lockin.lock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * The partner lock on this phone, kept on the phone itself so it holds with no signal, after LockIn is swiped
 * away, and after a restart. The server stays the judge: whenever it can be reached it corrects the time left
 * or ends the lock.
 *
 * Time left is counted on the clock that can't be changed in Settings (time since boot). After a restart that
 * clock starts again, so the wall clock is used until the server is reached; a clock that jumps is reported.
 */
/** The lock's storage: device-protected, readable before the first unlock after a restart. */
internal fun lockDevicePrefs(app: Context): android.content.SharedPreferences {
    val dp = app.createDeviceProtectedStorageContext()
    val unlocked = runCatching { app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != false }.getOrDefault(true)
    if (unlocked) {
        // Once: bring over a lock saved by an older LockIn in normal storage.
        val meta = dp.getSharedPreferences("partner_lock_meta", Context.MODE_PRIVATE)
        if (!meta.getBoolean("moved", false)) {
            runCatching { dp.moveSharedPreferencesFrom(app, "partner_lock") }
            meta.edit().putBoolean("moved", true).apply()
        }
    }
    return dp.getSharedPreferences("partner_lock", Context.MODE_PRIVATE)
}

internal class LocalLock(context: Context) {
    private val app = context.applicationContext
    // Kept in device-protected storage, which Android can read right after a restart, before the phone is
    // first unlocked. That lets the boot watcher know a lock is on and cover the phone the moment it's unlocked
    // (see BootGate). It holds nothing secret: an id, a name, end times and counts.
    private val prefs = lockDevicePrefs(app)


    private fun bootCount(): Int = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    val active: Boolean get() = id != 0L && remainingMillis() > 0L
    val id: Long get() = prefs.getLong(K_ID, 0L)
    val by: String get() = prefs.getString(K_BY, null) ?: "Your partner"
    val escapes: Int get() = prefs.getInt(K_ESCAPES, 0)

    /**
     * Something that holds the lock was switched off for real during it (device admin, "display over other apps",
     * app-time access, the guard, safe mode, the clock). Such a lock is voided: it is not written as a session, here or on
     * the server (see lock_tamper). A phone that only went quiet does not void it.
     */
    val voided: Boolean get() = prefs.getBoolean(K_VOIDED, false)

    fun markVoided() {
        if (id != 0L && !voided) prefs.edit().putBoolean(K_VOIDED, true).apply()
    }

    /**
     * Whose lock this is (the signed-in person's id), or null when it was set before anyone was known (a push
     * that arrived while signed out). The lock belongs to that account: someone else signing in on this phone
     * can't end it, because only the owner's check-in is believed (see LockService).
     */
    val owner: String? get() = prefs.getString(K_OWNER, null)?.ifBlank { null }

    /** Stamps the owner if none is known yet. */
    fun adoptOwner(userId: String?) {
        if (userId.isNullOrBlank() || owner != null || id == 0L) return
        prefs.edit().putString(K_OWNER, userId).apply()
    }

    /** Someone set (or the server confirmed) a lock with [remainingSeconds] left. */
    fun set(lockId: Long, remainingSeconds: Int, lockerName: String, ownerId: String? = null) {
        val now = System.currentTimeMillis()
        val e = SystemClock.elapsedRealtime()
        val ed = prefs.edit()
        if (lockId != id) {
            // A new lock starts its own counts and warnings.
            ed.putInt(K_ESCAPES, 0).putStringSet(K_SENT, emptySet()).remove(K_BEAT_WALL).remove(K_BEAT_ELAPSED)
                .remove(K_OWNER).remove(K_VOIDED).remove("n_stopped").remove("n_emergency_dial")
        }
        if (!ownerId.isNullOrBlank() && (lockId != id || owner == null)) ed.putString(K_OWNER, ownerId)
        ed.putLong(K_ID, lockId)
            .putString(K_BY, lockerName)
            .putLong(K_END_WALL, now + remainingSeconds * 1000L)
            .putLong(K_END_ELAPSED, e + remainingSeconds * 1000L)
            .putInt(K_BOOT, bootCount())
            // A fresh time from the server or the locker starts from this clock as it reads now.
            .putLong(K_SKEW, 0L)
            .putLong(K_OFFSET, now - e)
            .apply()
    }

    /** How far the Settings clock was moved while the phone stayed on (summed jumps). Taken off the wall clock. */
    private fun skew(): Long = prefs.getLong(K_SKEW, 0L)

    fun remainingMillis(): Long {
        if (prefs.getLong(K_ID, 0L) == 0L) return 0L
        val sameBoot = prefs.getInt(K_BOOT, -2) == bootCount() && bootCount() != -1
        return if (sameBoot) {
            prefs.getLong(K_END_ELAPSED, 0L) - SystemClock.elapsedRealtime()
        } else {
            // After a restart the since-boot clock starts over, so the wall clock is all there is, minus any move
            // of the Settings clock that was seen before the restart.
            prefs.getLong(K_END_WALL, 0L) - (System.currentTimeMillis() - skew())
        }.coerceAtLeast(0L)
    }

    /** The lock is over. Warnings still waiting for a connection are kept, so they still reach the locker. */
    fun clear() {
        val queue = prefs.getString(K_QUEUE, "")
        prefs.edit().clear().putString(K_QUEUE, queue).apply()
    }

    fun addEscape(): Int {
        val n = escapes + 1
        prefs.edit().putInt(K_ESCAPES, n).apply()
        return n
    }

    // ----- Heartbeat: how we notice LockIn was stopped during a lock -----

    /**
     * Written every few seconds while the lock service runs. Also keeps the wall-clock end of the lock in step
     * with the since-boot clock, and notes any jump of the Settings clock, so setting the clock forward and then
     * restarting the phone offline doesn't shorten the lock.
     */
    fun beat() {
        val boot = bootCount()
        val wall = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        val ed = prefs.edit()
        if (boot != -1 && id != 0L) {
            val left = remainingMillis()
            val offset = wall - el
            if (prefs.getInt(K_BOOT, -2) == boot) {
                // Same start-up as the last check: a change in (wall - elapsed) is the clock being moved.
                val prev = prefs.getLong(K_OFFSET, offset)
                val jump = offset - prev
                val skew = prefs.getLong(K_SKEW, 0L) + if (kotlin.math.abs(jump) > 120_000L) jump else 0L
                ed.putLong(K_SKEW, skew).putLong(K_END_WALL, wall - skew + left)
            } else {
                // First check after a restart: carry the lock onto this start-up's clock.
                ed.putInt(K_BOOT, boot).putLong(K_END_ELAPSED, el + left).putLong(K_END_WALL, wall + left).putLong(K_SKEW, 0L)
            }
            ed.putLong(K_OFFSET, offset)
        }
        ed.apply()
        prefs.edit()
            .putLong(K_BEAT_WALL, System.currentTimeMillis())
            .putLong(K_BEAT_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(K_BEAT_BOOT, bootCount())
            .apply()
    }

    /** How long the service was not running since its last heartbeat (0 if it never ran for this lock). */
    fun gapMillis(): Long {
        val wall = prefs.getLong(K_BEAT_WALL, 0L)
        if (wall == 0L) return 0L
        val sameBoot = prefs.getInt(K_BEAT_BOOT, -2) == bootCount() && bootCount() != -1
        return if (sameBoot) SystemClock.elapsedRealtime() - prefs.getLong(K_BEAT_ELAPSED, 0L)
        else System.currentTimeMillis() - wall
    }

    // ----- Safe mode: a restart LockIn never saw -----
    // Android counts every start-up (Settings.Global.BOOT_COUNT), safe mode included, but in safe mode LockIn
    // isn't allowed to run at all. So on each start-up during a lock LockIn notes the count; if it jumped by
    // more than one since LockIn last ran, the phone was started at least once without LockIn: safe mode.

    /** Call on every start-up (before the first unlock is fine). Returns true if a start-up was missed. */
    fun noteBoot(): Boolean {
        val now = bootCount()
        if (now < 0 || !active) return false
        val last = maxOf(prefs.getInt(K_SEEN_BOOT, -1), prefs.getInt(K_BEAT_BOOT, -1), prefs.getInt(K_BOOT, -1))
        val missed = last >= 0 && now - last >= 2
        val ed = prefs.edit().putInt(K_SEEN_BOOT, now)
        if (missed) ed.putBoolean(K_SAFE_MODE, true)
        ed.apply()
        return missed
    }

    /** True once after a missed start-up (see [noteBoot]); reading it clears it. */
    fun takeSafeMode(): Boolean {
        if (!prefs.getBoolean(K_SAFE_MODE, false)) return false
        prefs.edit().remove(K_SAFE_MODE).apply()
        return true
    }

    // ----- Warnings to the locker, sent at most once per lock and kind, queued while offline -----

    fun alreadySent(kind: String): Boolean = kind in prefs.getStringSet(K_SENT, emptySet()).orEmpty()

    fun markSent(kind: String) {
        prefs.edit().putStringSet(K_SENT, prefs.getStringSet(K_SENT, emptySet()).orEmpty() + kind).apply()
    }

    /** Event codes waiting for a connection, such as "lock_tamper|stopped|12". */
    fun queued(): List<String> = prefs.getString(K_QUEUE, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun enqueue(code: String) {
        val q = (queued() + code).takeLast(10)
        prefs.edit().putString(K_QUEUE, q.joinToString("\n")).apply()
    }

    /** Takes one copy of [code] off the queue (repeatable warnings can sit in it more than once). */
    fun dequeue(code: String) {
        val q = queued().toMutableList()
        q.remove(code)
        prefs.edit().putString(K_QUEUE, q.joinToString("\n")).apply()
    }

    /** Counts one more time [kind] was told during this lock, and returns the count (for warnings that may repeat). */
    fun bump(kind: String): Int {
        val n = prefs.getInt("n_$kind", 0) + 1
        prefs.edit().putInt("n_$kind", n).apply()
        return n
    }

    private companion object {
        const val K_ID = "id"
        const val K_BY = "by"
        const val K_END_WALL = "end_wall"
        const val K_END_ELAPSED = "end_elapsed"
        const val K_BOOT = "boot"
        const val K_ESCAPES = "escapes"
        const val K_BEAT_WALL = "beat_wall"
        const val K_BEAT_ELAPSED = "beat_elapsed"
        const val K_BEAT_BOOT = "beat_boot"
        const val K_SEEN_BOOT = "seen_boot"
        const val K_SAFE_MODE = "safe_mode"
        const val K_SENT = "sent"
        const val K_QUEUE = "queue"
        const val K_OWNER = "owner"
        const val K_SKEW = "skew"
        const val K_OFFSET = "offset"
        const val K_VOIDED = "voided"
    }
}
