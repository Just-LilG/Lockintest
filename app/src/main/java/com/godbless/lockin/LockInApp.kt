package com.godbless.lockin

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.godbless.lockin.data.AuthRepository
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.SessionRepository
import com.godbless.lockin.home.HomeScreen
import com.godbless.lockin.home.SceneHandoff
import com.godbless.lockin.intro.IntroWelcomeScreen
import com.godbless.lockin.push.PushRegistrar
import com.godbless.lockin.usage.Guard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the welcome page takes to close in on Home's mountain card, and back. */
private const val CLIMB_MS = 760
private val Glide = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Two pages, one scene. Home sits underneath while the welcome page, drawn on top, shrinks into the card
 * where Home keeps its mountain: the headline and buttons drop away, the page closes in around the
 * mountain, the climbers walk to today's place, and Home's own rows rise into the space it uncovers.
 * When the move ends Home's mountain is already showing exactly the same picture, so there is no cut.
 * Signing out runs it backwards. Nothing fades.
 */
@Composable
fun LockInApp(repo: AuthRepository) {
    // The confirmation link in the email opens the app and signs in; this notices and moves on to Home.
    val linkTick by repo.linkSignIns.collectAsState()
    val linkError by repo.linkErrors.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(linkError) {
        linkError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            repo.clearLinkError()
        }
    }
    var introPlayed by rememberSaveable { mutableStateOf(false) }

    // Which pages exist right now. Both do only while one is turning into the other.
    var welcomeUp by rememberSaveable { mutableStateOf(true) }
    var homeUp by rememberSaveable { mutableStateOf(false) }
    // 0 is the welcome page, 1 is Home. The welcome page reads it while drawing.
    val climb = remember { Animatable(if (homeUp) 1f else 0f) }
    var moving by remember { mutableStateOf(false) }
    // Coming back to a Home that was already up (the app was recreated): nothing is in flight.
    // (Runs once, during the first draw, so no frame shows stale handoff flags. It ends in `true` only because
    // Compose's lint refuses a remember that returns nothing.)
    remember { if (homeUp) SceneHandoff.reset(); true }

    fun goHome() {
        if (homeUp || moving) return
        moving = true
        SceneHandoff.beginToHome()
        // Home is built under the welcome page; its intros wait until the move starts so everything lands together.
        com.godbless.lockin.ui.EntranceGate.held = true
        homeUp = true
        scope.launch {
            try {
                // Wait for Home's first layout, so the page knows where its mountain card is.
                withTimeoutOrNull(700) { snapshotFlow { SceneHandoff.box }.first { it != null } }
                // And for who your partner is and today's numbers, so Home's first picture is the right one.
                // The page says "getting your partner's news" while this runs. A slow connection only waits so long.
                withTimeoutOrNull(2500) { snapshotFlow { SceneHandoff.dataReady }.first { it } }
            } finally {
                com.godbless.lockin.ui.EntranceGate.held = false
            }
            climb.snapTo(0f)
            climb.animateTo(1f, tween(CLIMB_MS, easing = Glide))
            SceneHandoff.land()
            welcomeUp = false
            moving = false
        }
    }

    fun goWelcome() {
        if (!homeUp || moving) return
        moving = true
        scope.launch {
            // The number, chip and name tags tuck away first, then the welcome page grows back out of the card.
            SceneHandoff.chromeShown = false
            kotlinx.coroutines.delay(240)
            SceneHandoff.beginToWelcome()
            climb.snapTo(1f)
            welcomeUp = true
            climb.animateTo(0f, tween(CLIMB_MS, easing = Glide))
            homeUp = false
            SceneHandoff.reset()
            moving = false
        }
    }

    LaunchedEffect(linkTick) {
        if (linkTick > 0 && repo.hasSession() && !homeUp) goHome()
    }

    Box(Modifier.fillMaxSize()) {
        if (homeUp) {
            val partners = remember(repo) { PartnerRepository(repo) }
            val sessions = remember(repo) { SessionRepository(context.applicationContext, partners, repo) }
            val pairCode = (context.applicationContext as LockInApplication).incomingPairCode
            val incoming by pairCode.collectAsState()
            var signingOut by remember { mutableStateOf(false) }
            var askSignOut by remember { mutableStateOf(false) }
            val trySignOut: () -> Unit = {
                if (com.godbless.lockin.lock.LocalLock(context).active) {
                    // Signing out would stop this phone checking in, so it can't be used to slip out of a lock.
                    android.widget.Toast.makeText(context, "You can't sign out while you're locked. It opens when the lock ends.", android.widget.Toast.LENGTH_LONG).show()
                } else if (com.godbless.lockin.lock.Protection.required(context, com.godbless.lockin.lock.Protection.SignOut)) {
                    // While you're paired, your partner has to say yes first.
                    askSignOut = true
                } else if (!signingOut) {
                    signingOut = true
                    scope.launch {
                        // Tell the server this phone is no longer yours while the token still works, but don't wait long.
                        withTimeoutOrNull(2500) { PushRegistrar.forget(context, partners) }
                        // And take this phone off your devices list.
                        withTimeoutOrNull(2500) { com.godbless.lockin.data.DeviceRepository(partners).forget(com.godbless.lockin.data.ThisDevice.id(context)) }
                        // Stop watching apps and switch back on anything this phone had switched off for a limit.
                        Guard.reset(context)
                        // The widgets stop showing this person's day.
                        runCatching { com.godbless.lockin.widget.WidgetSnapshot.clear(context) }
                        // The next person to sign in on this phone gets the permissions page too.
                        (context.applicationContext as LockInApplication).permissionsOffered = false
                        // Nobody is paired on this phone any more, so nothing is held back.
                        com.godbless.lockin.lock.Protection.clear(context)
                        com.godbless.lockin.home.Identity.load(context)
                        com.godbless.lockin.home.Identity.reset()
                        repo.signOut()
                        goWelcome()
                    }
            }
            }
            if (askSignOut) {
                com.godbless.lockin.lock.ProtectAskSheet(
                    kind = com.godbless.lockin.lock.Protection.SignOut,
                    onAllowed = { askSignOut = false; trySignOut() },
                    onClose = { askSignOut = false },
                )
            }
            HomeScreen(
                name = repo.userName(),
                auth = repo,
                partners = partners,
                sessions = sessions,
                incomingCode = incoming,
                onIncomingHandled = { pairCode.value = null },
                onSignOut = trySignOut,
            )
        }
        if (welcomeUp && (!homeUp || moving)) {
            IntroWelcomeScreen(
                skipIntro = introPlayed,
                onIntroDone = { introPlayed = true },
                repo = repo,
                onSignedIn = { goHome() },
                exit = { climb.value },
            )
        }
    }
}
