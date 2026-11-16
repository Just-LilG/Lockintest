package com.godbless.lockin.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.godbless.lockin.R

/**
 * Closes the gap after a restart. Android says "the phone has started" (BOOT_COMPLETED) only after the
 * first unlock, and some phones hold that message back for a minute or more, which left the phone usable
 * until LockIn caught up. Android sends an earlier signal, LOCKED_BOOT_COMPLETED, as soon as the phone is up
 * and still locked. [EarlyBootReceiver] catches it, and if a lock is on, starts this small watcher. The moment
 * the phone is unlocked, it starts the real lock service and steps aside, so the cover is there straight away.
 *
 * Runs before the first unlock, so it only touches device-protected storage (the lock in [LocalLock]).
 */
class BootGate : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setContentTitle("Lock still on")
            .setContentText("Your lock picks up again as soon as you unlock.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val started = runCatching {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, 2101, notification, type)
        }.isSuccess
        if (!started) Log.w("LockInLock", "boot gate couldn't start in the foreground")

        if (unlocked()) {
            handOver()
            return START_NOT_STICKY
        }
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (unlocked()) handOver()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        }
        return START_STICKY
    }

    private fun unlocked(): Boolean = runCatching { getSystemService(UserManager::class.java)?.isUserUnlocked != false }.getOrDefault(true)

    /** The phone is unlocked: the real lock service takes over (it covers other apps at once). */
    private fun handOver() {
        runCatching { LockService.sync(applicationContext) }.onFailure { Log.w("LockInLock", "hand-over failed: ${it.message}") }
        stopSelf()
    }

    private fun ensureChannel(): String {
        val id = "lock_boot"
        val m = getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(id) == null) {
            m.createNotificationChannel(NotificationChannel(id, "Lock after a restart", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown briefly after a restart while a lock is on"
                setShowBadge(false)
            })
        }
        return id
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val i = Intent(context, BootGate::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }.onFailure { Log.w("LockInLock", "boot gate start failed: ${it.message}") }
        }
    }
}

/**
 * The earliest signal after a restart (before the first unlock). If a lock is on, the boot watcher starts so
 * the lock is back the moment the phone is unlocked. Also catches the "quick boot" signal some phones send.
 */
class EarlyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                val dp = context.createDeviceProtectedStorageContext()
                val local = LocalLock(dp)
                // Before anything else: was there a start-up in between that LockIn never saw (safe mode)?
                local.noteBoot()
                if (local.active) BootGate.start(dp)
            }
        }
    }
}
