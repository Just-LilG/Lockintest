package com.godbless.lockin.ui

import android.app.Activity

/**
 * Asks Android for the screen's fastest refresh rate while LockIn is open. Without a request many phones run an app
 * at 60 Hz (or lower to save power) even when the panel can do 90 or 120, which reads as stutter in motion. The
 * system may still lower it (battery saver, thermal limits); this is a request, not an order.
 */
internal object PeakRefresh {
    fun request(activity: Activity) {
        try {
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay ?: return
            val now = display.mode
            // The fastest mode at the same resolution (a different resolution would reflow the screen).
            val best = display.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != best.modeId || lp.preferredRefreshRate != best.refreshRate) {
                lp.preferredDisplayModeId = best.modeId
                lp.preferredRefreshRate = best.refreshRate
                activity.window.attributes = lp
            }
        } catch (_: Throwable) {
            // Some phones refuse; the app simply runs at whatever rate the system picks.
        }
    }
}
