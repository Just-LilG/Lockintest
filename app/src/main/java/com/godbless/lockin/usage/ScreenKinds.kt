package com.godbless.lockin.usage

import android.content.Context
import android.content.Intent

/**
 * Which packages are "the phone itself" rather than an app someone opened.
 *
 * Android's usage log keeps the home screen and the system UI listed as on screen long after an app was opened
 * over them, so counting them as "another app" made a full-screen limited app look like it sat in a small window.
 */
internal object ScreenKinds {
    /** The home screen app(s), the system UI and our own app. Never "another app on screen". */
    fun chrome(context: Context): Set<String> {
        val out = HashSet<String>()
        out.add("com.android.systemui")
        out.add(context.packageName)
        runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { out.add(it.activityInfo.packageName) }
        }
        return out
    }

    /**
     * Settings and the permission screens. Android hides every app's "display over other apps" window while one
     * of these is showing (so nothing can draw over a permission switch), which also hides LockIn's cover: a
     * floating app next to it can then be used freely. The only answer is to send the person Home.
     */
    fun isSettings(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "settings" in p || "permissioncontroller" in p || "packageinstaller" in p
    }

    /** Sends the person to the home screen, by every way we have. */
    fun goHome(context: Context) {
        // The accessibility guard can press Home even while Android is hiding our windows.
        if (com.godbless.lockin.lock.SettingsGuardService.goHome()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Ends [pkg]'s process if it is in the background (Android won't end one that is on screen, so this follows
     * Home or a closed window). A video or a game left playing behind the home screen stops with it.
     */
    fun killBackground(context: Context, pkg: String) {
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
        }
    }
}
