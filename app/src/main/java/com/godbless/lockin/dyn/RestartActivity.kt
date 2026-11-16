package com.godbless.lockin.dyn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts the app so a downloaded update takes over. It runs in its own small process: it starts the app's
 * launcher screen, then ends the old process, so the app comes back fresh.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val next = intent.getParcelableExtra<Intent>(ExtraNext)
        val pid = intent.getIntExtra(ExtraPid, -1)
        if (next != null) startActivity(next)
        if (pid > 0) Process.killProcess(pid)
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val ExtraNext = "next"
        private const val ExtraPid = "pid"

        /** Closes the app and opens it again. */
        fun now(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ExtraNext, launch)
                    .putExtra(ExtraPid, Process.myPid()),
            )
        }
    }
}
