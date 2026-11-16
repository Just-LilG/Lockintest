package com.godbless.lockin.lock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Whether this phone is under a partner's protection, and the short windows in which the partner said Allow.
 *
 * Protection is on while this phone is signed in as someone who is paired. Then the things that would take it
 * off (switching off device admin or the guard, uninstalling or stopping LockIn, turning off "display over other
 * apps" or app-time access, signing out) are held back until the partner allows them: the phone asks, the
 * partner answers Allow or Not yet, and an Allow opens a window of about ten minutes for that one thing.
 *
 * A window is counted on the since-boot clock (immune to Settings clock changes) and dies with a restart.
 */
internal object Protection {
    private const val PREFS = "protection"

    /** What can be held back. The words after each are what the screens and the partner's note call it. */
    const val Admin = "admin"
    const val Uninstall = "uninstall"
    const val Overlay = "overlay"
    const val Usage = "usage"
    const val Guard = "guard"
    const val SignOut = "signout"
    const val Other = "other"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** This phone is signed in as someone who is paired. */
    fun on(context: Context): Boolean = prefs(context).getBoolean("paired", false)

    fun partner(context: Context): String = prefs(context).getString("partner", null)?.ifBlank { null } ?: "your partner"

    /** The signed-in person's id, kept so the guard knows whose protection it is. */
    fun owner(context: Context): String? = prefs(context).getString("owner", null)

    /** Home calls this whenever it knows the pairing. */
    fun update(context: Context, paired: Boolean, partner: String, owner: String?) {
        val p = prefs(context)
        val wasPaired = p.getBoolean("paired", false)
        // Only the protected person's own pairing counts: another account signed in on this phone (which has no
        // partner) must not switch the protection off. Signing out with the partner's OK uses [clear].
        val before = p.getString("owner", null)
        if (!paired && wasPaired && before != null && owner != null && before != owner) return
        val ed = p.edit().putBoolean("paired", paired)
        if (paired) ed.putString("partner", partner).putString("owner", owner) else ed.remove("partner").remove("owner")
        if (!paired && wasPaired) clearWindows(ed)
        ed.apply()
        // The always-on service (which watches the protection and checks in) runs while paired: start or stop it now.
        if (paired != wasPaired) runCatching { com.godbless.lockin.usage.Guard.sync(context.applicationContext) }
    }

    /** Off: nobody is paired on this phone (signed out with the partner's OK, or unpaired). */
    fun clear(context: Context) = update(context, paired = false, partner = "", owner = null)

    // ----- Allow windows -----

    private fun clearWindows(ed: android.content.SharedPreferences.Editor) {
        for (k in listOf(Admin, Uninstall, Overlay, Usage, Guard, SignOut, Other)) ed.remove("w_$k")
    }

    /** The partner said Allow for [kind], for [seconds]. Allowing an uninstall also allows switching admin off. */
    fun open(context: Context, kind: String, seconds: Int) {
        val end = SystemClock.elapsedRealtime() + seconds.coerceIn(1, 3600) * 1000L
        val ed = prefs(context).edit().putInt("w_boot", bootCount(context)).putLong("w_$kind", end)
        if (kind == Uninstall) ed.putLong("w_$Admin", end)
        ed.apply()
    }

    fun isOpen(context: Context, kind: String): Boolean {
        val p = prefs(context)
        val end = p.getLong("w_$kind", 0L)
        if (end == 0L) return false
        val boot = bootCount(context)
        if (boot != -1 && p.getInt("w_boot", -2) != boot) return false
        return end > SystemClock.elapsedRealtime()
    }

    /** Seconds left in the window for [kind] (0 when closed). */
    fun secondsLeft(context: Context, kind: String): Int =
        if (isOpen(context, kind)) ((prefs(context).getLong("w_$kind", 0L) - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(1) else 0

    /** Held back right now: protection is on and the partner has not allowed [kind]. */
    fun required(context: Context, kind: String): Boolean = on(context) && !isOpen(context, kind)

    // ----- The guard service -----

    /** The guard has been switched on at least once on this phone: if it is off later, that is a warning. */
    fun guardSeen(context: Context): Boolean = prefs(context).getBoolean("guard_seen", false)

    fun markGuardSeen(context: Context) = prefs(context).edit().putBoolean("guard_seen", true).apply()

    /** The guard is switched on in Accessibility settings (it may not have started yet). */
    fun guardEnabled(context: Context): Boolean {
        if (SettingsGuardService.running()) return true
        val flat = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull().orEmpty()
        val me = android.content.ComponentName(context, SettingsGuardService::class.java)
        return flat.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
    }

    /** What the screens say a kind is about, as "<verb phrase>" after "to ". */
    fun phrase(kind: String): String = when (kind) {
        Admin -> "switch off LockIn's device admin"
        Uninstall -> "uninstall or stop LockIn"
        Overlay -> "turn off \"display over other apps\""
        Usage -> "turn off app-time access"
        Guard -> "switch off LockIn's guard"
        SignOut -> "sign out of LockIn"
        else -> "change this on your phone"
    }
}
