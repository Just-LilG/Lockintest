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
        )
        for (r in restrictions) {
            runCatching { if (on) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r) }
        }
        runCatching { dpm.setUninstallBlocked(admin, app.packageName, on) }
        // Always false: also clears it on phones an older LockIn left with the status bar off.
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        hardened = on
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
        /** Survives the screen being rebuilt, so we only release what we held. */
        @Volatile
        private var engaged = false

        /** True while a partner's lock has the device-owner restrictions on. */
        @Volatile
        private var hardened = false
    }
}
