package com.godbless.lockin.lock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Keeps the guard (the accessibility service) running. Android only starts it after a restart if the phone maker lets
 * it, and many (HyperOS, ColorOS, One UI with battery limits) leave it listed as "on" but never start it, or stop it
 * later; and switching it off is one tap in Accessibility settings.
 *
 * With the one-time permission WRITE_SECURE_SETTINGS (granted once from a computer:
 * `adb shell pm grant com.godbless.lockin android.permission.WRITE_SECURE_SETTINGS`) LockIn can switch its own guard
 * back on, and does, during every lock. Without it this does nothing and the guard has to be switched on by hand.
 */
object GuardKeeper {
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Switches the guard on (off and on again, since a listed-but-dead service only restarts on a change). True if it tried. */
    fun ensure(context: Context): Boolean {
        if (SettingsGuardService.running() || !canWrite(context)) return false
        val me = ComponentName(context, SettingsGuardService::class.java)
        return runCatching {
            val cr = context.contentResolver
            val others = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
