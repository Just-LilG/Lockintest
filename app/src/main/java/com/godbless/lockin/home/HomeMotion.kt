package com.godbless.lockin.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import com.godbless.lockin.ui.LockInText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Motion helpers for Home. Everything here moves by sliding, springing or growing: nothing fades.
 */

/** One springy curve shared by Home's motion, so it all feels like one piece. */
internal fun <T> homeSpring(bouncy: Boolean = false) =
    if (com.godbless.lockin.ui.MotionState.reduced) spring<T>(dampingRatio = 1f, stiffness = 3000f)
    else if (bouncy) spring<T>(dampingRatio = 0.55f, stiffness = 260f) else spring<T>(dampingRatio = 0.8f, stiffness = 140f)

/**
 * What Home has already shown since the app opened. Intros (the greeting, the count-up, the mountain walking up)
 * play once; coming back to Home from another tab shows everything settled, so it never looks like a reset.
 */
internal object HomeIntro {
    var greeted = false
    var weekGrown = false
    var usageGrown = false
    var ropePulled = false
    var sceneSeen = false
    /** The flag's wave clock when Home was last on screen, so it carries on instead of climbing out of the summit again. */
    var sceneClock = 0.2f
    /** Today's minutes as last shown, null before the first count-up. */
    var countedMinutes: Float? = null
    /** Slide-ins and pops that have played, by name. */
    val played = mutableSetOf<String>()
}

/** Text whose letters rise into place one after another from below a clipped baseline. [settled] skips the intro. */
@Composable
internal fun StaggerText(text: String, style: TextStyle, modifier: Modifier = Modifier, startDelay: Long = 120L, settled: Boolean = false) {
    Row(modifier.clipToBounds()) {
        com.godbless.lockin.ui.graphemes(text).forEachIndexed { i, ch ->
            val lift = remember(text) { Animatable(if (settled) 0f else 1f) }
            LaunchedEffect(text) {
                if (settled) return@LaunchedEffect
                delay(if (com.godbless.lockin.ui.MotionState.reduced) 0L else startDelay + i * 45L)
                lift.animateTo(0f, homeSpring(bouncy = true))
            }
            Text(
                ch,
                style = style,
                modifier = Modifier.graphicsLayer { translationY = lift.value * size.height },
            )
        }
    }
}

/** Grows from nothing to full size with a small bounce, after [delayMillis]. */
@Composable
internal fun rememberPop(delayMillis: Long, key: String? = null): State<Float> {
    val seen = key != null && key in HomeIntro.played
    val a = remember { Animatable(if (seen) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (key != null) HomeIntro.played.add(key)
        if (a.value >= 1f) return@LaunchedEffect
        delay(if (com.godbless.lockin.ui.MotionState.reduced) 0L else delayMillis)
        a.animateTo(1f, homeSpring(bouncy = true))
    }
    return a.asState()
}

/**
 * Tells whether something has scrolled into view (its top is above the bottom tenth of the screen). It stays
 * true once seen, so a card below the fold plays its entrance the first time you scroll to it.
 */
internal class InView {
    var seen by mutableStateOf(false)
}

@Composable
internal fun rememberInView(): Pair<InView, Modifier> {
    val state = remember { InView() }
    val heightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp * density }
    val modifier = Modifier.onGloballyPositioned { c ->
        if (!state.seen && c.boundsInWindow().top < heightPx * 0.9f) state.seen = true
    }
    return state to modifier
}

/** Slides in from the side (and settles with a spring) once [go] turns true; staggered by [index]. */
@Composable
internal fun rememberSlideIn(go: Boolean, index: Int, fromRight: Boolean = true, key: String? = null): State<Float> {
    val seen = key != null && key in HomeIntro.played
    val a = remember { Animatable(if (seen) 0f else 1f) }
    LaunchedEffect(go) {
        if (!go) return@LaunchedEffect
        if (key != null) HomeIntro.played.add(key)
        if (a.value <= 0f) return@LaunchedEffect
        launch {
            delay(index * 90L)
            a.animateTo(0f, homeSpring())
        }
    }
    return a.asState()
}

/**
 * A waving hand in front of the greeting. It waves a few times when Home opens, then rests; with reduced motion it
 * simply stays put. Pivots at the wrist (bottom centre) so it swings like a real wave.
 */
@Composable
internal fun WavingHand(modifier: Modifier = Modifier) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (com.godbless.lockin.ui.MotionState.reduced) return@LaunchedEffect
        if (HomeIntro.played.contains("wave")) return@LaunchedEffect
        HomeIntro.played.add("wave")
        delay(500)
        for (target in floatArrayOf(22f, -12f, 20f, -8f, 14f, 0f)) {
            angle.animateTo(target, androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Text(
        "\uD83D\uDC4B",
        style = LockInText.Sub,
        modifier = modifier
            .padding(end = 6.dp)
            .graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.7f, 0.9f)
                rotationZ = angle.value
            },
    )
}
