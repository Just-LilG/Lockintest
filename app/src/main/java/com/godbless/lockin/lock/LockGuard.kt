package com.godbless.lockin.lock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import com.godbless.lockin.MainActivity
import com.godbless.lockin.usage.LimitStore

/** How hard LockIn is to get around on this phone. */
enum class ProtectionLevel(val label: String) {
    /** Nothing granted: a session pins the screen, but anyone can leave it or uninstall the app. */
    Basic("Basic"),

    /** Device admin: the app cannot be uninstalled until the admin is switched off in Settings. */
    Strong("Strong"),

    /** Device owner: a session is a full kiosk, and the admin cannot be switched off from Settings. */
    Total("Total"),
}

/**
 * Everything LockIn does to the phone itself: what it has been allowed to do, the Settings screens to
 * allow more, and holding or releasing the screen during a session.
 */
class LockGuard(context: Context) {
    private val app = context.applicationContext
    private val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(app, LockAdminReceiver::class.java)

    // ----- What we have been allowed -----

    fun isAdmin(): Boolean = dpm.isAdminActive(admin)

    fun isOwner(): Boolean = dpm.isDeviceOwnerApp(app.packageName)

    fun canOverlay(): Boolean = Settings.canDrawOverlays(app)

    fun batteryUnrestricted(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)

    fun level(): ProtectionLevel = when {
        isOwner() -> ProtectionLevel.Total
        isAdmin() -> ProtectionLevel.Strong
        else -> ProtectionLevel.Basic
    }

    // ----- Screens that grant more -----

    fun adminIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "LockIn needs this to lock your phone during a session and to stop itself being uninstalled while you are locked in.",
        )

    fun overlayIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))

    @SuppressLint("BatteryLife")
    fun batteryIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))

    /** The command that makes LockIn the device owner. It only works while no accounts are signed in on the phone. */
    val ownerCommand: String = "adb shell dpm set-device-owner ${app.packageName}/.lock.LockAdminReceiver"

    /**
     * Device owner only: stops an app from opening at all (true), or lets it open again (false). Returns
     * whether it worked. Used by app limits; LockIn itself can never be suspended.
     */
    fun suspend(pkg: String, on: Boolean): Boolean {
        if (!isOwner() || pkg == app.packageName) return false
        // An app the lock is holding shut stays shut until the lock ends, whatever a limit says.
        if (!on && pkg in heldByLock()) return false
        return runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), on).isEmpty() }.getOrDefault(false)
    }

    /** Gives up device owner. Refused while a session is running. Apps suspended by a limit are switched back on first. */
    @Suppress("DEPRECATION")
    fun clearOwner(): Boolean {
        if (engaged || hardened || LocalLock(app).active || !isOwner()) return false
        val store = LimitStore(app)
        store.suspended().forEach { suspend(it, false) }
        store.setSuspended(emptySet())
        return runCatching { dpm.clearDeviceOwnerApp(app.packageName) }.isSuccess
    }

    // ----- During a partner's lock (device owner only) -----

    /**
     * Shuts the doors a device owner can shut while a partner's lock runs: no safe mode (which would start
     * the phone without LockIn), no factory reset, no changing the clock, no adb, no new users, no uninstalling
     * LockIn. The notification shade stays usable (owner's call: it isn't a way out, the cover stays up). All of it is undone when the lock ends ([on] false).
     * Does nothing without device owner.
     */
    fun hardenForLock(on: Boolean) {
        if (!isOwner()) return
        val restrictions = listOf(
            android.os.UserManager.DISALLOW_SAFE_BOOT,
            android.os.UserManager.DISALLOW_FACTORY_RESET,
            android.os.UserManager.DISALLOW_CONFIG_DATE_TIME,
            android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
            android.os.UserManager.DISALLOW_ADD_USER,
            // Settings > Apps > LockIn > Force stop / Clear storage: the two ways to kill the lock service and wipe
            // what it remembers, with no warning to the locker until minutes later.
            android.os.UserManager.DISALLOW_APPS_CONTROL,
            // Switching to another user or a guest leaves the lock behind on this profile.
            "no_user_switch", // UserManager.DISALLOW_USER_SWITCH (Android 9+; an older phone just ignores it)
        )
        for (r in restrictions) {
            runCatching { if (on) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r) }
        }
        runCatching { dpm.setUninstallBlocked(admin, app.packageName, on) }
        // Android 11+: the same for LockIn alone, even where a phone maker's Settings ignores the restriction above.
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { dpm.setUserControlDisabledPackages(admin, if (on) listOf(app.packageName) else emptyList()) }
        }
        // Always false: also clears it on phones an older LockIn left with the status bar off.
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        if (!on) releaseOthers()
        hardened = on
    }

    // ----- Holding every other app shut (device owner only) -----

    private fun heldByLock(): Set<String> = lockDevicePrefs(app).getStringSet(K_HELD, emptySet()).orEmpty()

    /**
     * For the length of a partner's lock: suspends every app that has a launcher icon except [keep] (the apps your partner
     * allowed, the phone app, the home screen, system screens an allowed app needs) and LockIn. A suspended app can't be
     * opened at all, so there is no floating window, split screen or recents entry to cover. Safe to call again when [keep]
     * changes: what is no longer wanted shut is switched back on. Only apps this call suspended are remembered (in
     * device-protected storage, so a restart still lets them go), never ones a limit already holds.
     */
    @Suppress("DEPRECATION")
    fun holdOthers(keep: Set<String>) {
        if (!isOwner()) return
        val prefs = lockDevicePrefs(app)
        val mine = prefs.getStringSet(K_HELD, emptySet()).orEmpty().toMutableSet()
        val pm = app.packageManager
        val never = HashSet<String>(keep)
        never.add(app.packageName)
        never.add("com.android.emergency")
        never.add("com.android.systemui")
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { never.add(it.activityInfo.packageName) } }
        runCatching { (app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { never.add(it) } }
        runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(app)?.let { never.add(it) } }
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val want = launchable - never
        // Let go of what is now kept open (your partner allowed another app mid-lock).
        for (pkg in mine.filter { it !in want }) {
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
            mine.remove(pkg)
        }
        val add = want.filter { it !in mine && !runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(true) }
        if (add.isNotEmpty()) {
            // setPackagesSuspended answers with the ones it could not suspend (the phone refuses some system apps).
            val failed = runCatching { dpm.setPackagesSuspended(admin, add.toTypedArray(), true).toSet() }.getOrDefault(add.toSet())
            mine.addAll(add.filter { it !in failed })
        }
        prefs.edit().putStringSet(K_HELD, mine).apply()
    }

    /** Switches back on every app [holdOthers] suspended. Called when the lock ends, and by [LockService.sync]. */
    fun releaseOthers() {
        if (!isOwner()) return
        val prefs = lockDevicePrefs(app)
        val mine = prefs.getStringSet(K_HELD, emptySet()).orEmpty()
        if (mine.isEmpty()) return
        val failed = runCatching { dpm.setPackagesSuspended(admin, mine.toTypedArray(), false).toSet() }.getOrDefault(mine)
        prefs.edit().putStringSet(K_HELD, failed.toSet()).apply()
    }

    /** Locks the screen now (the last answer to something that stays on screen under the cover). True if it did. */
    fun lockScreenNow(): Boolean {
        if (!isAdmin() && !isOwner()) return false
        return runCatching { dpm.lockNow(); true }.getOrDefault(false)
    }

    // ----- During a session -----

    /**
     * Holds the screen. As device owner this is a full kiosk (no home, no recents; notifications still show),
     * with the phone app still allowed so a call can be answered. Otherwise Android pins the screen and
     * the person has to confirm once; leaving it takes a deliberate gesture.
     */
    fun engage(activity: Activity) {
        if (isOwner()) {
            runCatching {
                val dialer = (app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
                dpm.setLockTaskPackages(admin, listOfNotNull(app.packageName, dialer).toTypedArray())
                // Notifications and the status bar stay on in the kiosk; home and recents stay blocked.
                // (Android keeps Quick Settings tiles off in kiosk mode; no setting changes that.)
                if (Build.VERSION.SDK_INT >= 28) {
                    val open = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                    runCatching { dpm.setLockTaskFeatures(admin, open) }
                        .onFailure { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO) }
                }
            }
        }
        runCatching { activity.startLockTask() }
        engaged = true
    }

    fun release(activity: Activity) {
        if (!engaged) return
        engaged = false
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) runCatching { activity.stopLockTask() }
    }

    /** True while the screen is on, so a screen that turns off is not mistaken for someone leaving the app. */
    fun screenOn(): Boolean = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Pulls LockIn back to the front after someone leaves it. Needs the "display over other apps" permission. */
    fun bringBack() {
        if (!canOverlay()) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }, 250)
    }

    companion object {
        private const val K_HELD = "held_suspended"

        /** Survives the screen being rebuilt, so we only release what we held. */
        @Volatile
        private var engaged = false

        /** True while a partner's lock has the device-owner restrictions on. */
        @Volatile
        private var hardened = false
    }
}
