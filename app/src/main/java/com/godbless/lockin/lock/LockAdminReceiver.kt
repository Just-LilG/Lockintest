package com.godbless.lockin.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * The hook Android uses to make LockIn a device admin (and, when set up with adb, the device owner).
 * It does nothing itself; [LockGuard] does the work. The message below is what the person reads if they
 * go to switch the admin off.
 */
class LockAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        if (LocalLock(context).active) {
            "You're in a lock your partner set. If you switch this off now, they will be told."
        } else {
            "LockIn can't lock your phone or protect your sessions without this."
        }

    /** Switched off during a partner's lock: queue a warning for them and make sure the lock service sends it. */
    override fun onDisabled(context: Context, intent: Intent) {
        val local = LocalLock(context)
        if (!local.active || local.alreadySent("admin_off")) return
        local.markSent("admin_off")
        local.enqueue("lock_tamper|admin_off")
        LockService.sync(context)
    }
}
