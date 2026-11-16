package com.godbless.lockin

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.godbless.lockin.data.PairLink
import com.godbless.lockin.push.Push
import com.godbless.lockin.ui.LockInTheme

/** A FragmentActivity because the fingerprint prompt needs one. */
class MainActivity : FragmentActivity() {
    private val auth get() = (application as LockInApplication).auth

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash is only ink-coloured, so there is no flash before the intro starts.
        // Removing it immediately (no exit animation) keeps the hand-off to our own first frame seamless.
        installSplashScreen().setOnExitAnimationListener { it.remove() }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleLink(intent)
        setContent {
            LockInTheme {
                androidx.compose.foundation.layout.Box {
                    LockInApp(auth)
                    // After a crash: the reason, ready to copy.
                    CrashNotice()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Battery saver or the animation setting may have changed while LockIn was in the background.
        com.godbless.lockin.ui.MotionState.refresh(this)
        // Smoothest the screen can do (90 or 120 Hz where there is such a panel).
        com.godbless.lockin.ui.PeakRefresh.request(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        // A tapped notification carries no link, only a note of what to open.
        when (val open = intent?.getStringExtra(Push.ExtraOpen)) {
            Push.OpenChat -> (application as LockInApplication).openChat.value = true
            Push.OpenLock, Push.OpenPartner, Push.OpenCrew, Push.OpenCrewChat, Push.OpenParty, Push.OpenStart, Push.OpenClimbs -> (application as LockInApplication).openTarget.value = open
        }
        val uri = intent?.data ?: return
        if (auth.handleLink(uri)) return
        // Not a sign-in link, so it may be a pairing link.
        PairLink.parse(uri.toString())?.let { (application as LockInApplication).incomingPairCode.value = it }
    }
}
