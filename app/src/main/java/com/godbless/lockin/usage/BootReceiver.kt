package com.godbless.lockin.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts or LockIn is updated, start the guard again so limits keep holding,
 * and re-arm a partner's lock (see LockStartAlarm.rearm). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Guard.sync(context)
            // Alarms are wiped by a restart: set a scheduled lock's start again, and bring back a lock that is still on.
            com.godbless.lockin.push.LockStartAlarm.rearm(context)
            // The lock service covers other apps from the first unlock after a restart, even with no signal.
            com.godbless.lockin.lock.LockService.sync(context)
            // And the alarm for the next repeating lock.
            com.godbless.lockin.push.RepeatAlarm.rearm(context)
            runCatching { com.godbless.lockin.push.CrewAlarm.rearm(context) }
            runCatching { com.godbless.lockin.push.QuizReminder.rearm(context) }
            // The home-screen widgets: redrawn, and their next redraw set again.
            runCatching { com.godbless.lockin.widget.LockInWidgets.refreshAll(context) }
        }
    }
}
