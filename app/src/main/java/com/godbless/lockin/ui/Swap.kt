package com.godbless.lockin.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/*
 * One set of rules for swapping what is on screen (a line of text, a card, a tab), so every swap in the app
 * looks finished.
 *
 * Why these exist: a swap that travels only part of the item's height (it / 2, it / 3) leaves the old and new
 * faces drawn on top of each other for the whole slide, and when the two faces are different heights (a
 * two-line sentence becoming a one-line one) even a full-height slide still crosses them. Both showed up as
 * text printed over text. So:
 *
 *  - Every face always travels its whole length. Nothing travels a half or a third.
 *  - Faces that are the same size move together ([pushSwap]).
 *  - Faces that can be different sizes go one after the other ([handoffSwap]): the old one clears, the new one
 *    arrives, and the box glides between the two sizes. They are never on screen at the same spot.
 *  - The box is always clipped while it animates, so nothing slides out over its neighbours.
 *
 * With reduced motion on, [UiGlide] already jumps to the end, and the handoff drops its wait so nothing blanks.
 */

/** Length of one slide, in milliseconds. */
internal const val SwapMs = 300

private fun sizeGlide(ms: Int) = SizeTransform(clip = true) { _, _ -> tween(ms, easing = UiGlide) }

/**
 * Both faces move together, one whole length each. Only for faces that are the same size on the axis they move
 * along (a digit, a number, a one-line label, a page that fills the same box).
 *
 * [dir] 1: the new face comes from below (or the right) and the old one leaves upward (or left). -1 is the reverse.
 */
internal fun pushSwap(vertical: Boolean, dir: Int = 1, ms: Int = SwapMs): ContentTransform =
    if (vertical) {
        (slideInVertically(tween(ms, easing = UiGlide)) { it * dir } togetherWith
            slideOutVertically(tween(ms, easing = UiGlide)) { -it * dir }).using(sizeGlide(ms))
    } else {
        (slideInHorizontally(tween(ms, easing = UiGlide)) { it * dir } togetherWith
            slideOutHorizontally(tween(ms, easing = UiGlide)) { -it * dir }).using(sizeGlide(ms))
    }

/**
 * For faces that can be different heights (a sentence that wraps to two lines and then one, a button that
 * becomes a form). The old face leaves first, the new one arrives right behind it, and the box glides between
 * the two heights, so the two are never drawn over each other.
 *
 * [dirIn] 1: the new face rises from below, -1: it drops from above. [dirOut] -1: the old face leaves upward,
 * 1: it leaves downward.
 */
internal fun handoffSwap(dirIn: Int = 1, dirOut: Int = -1, ms: Int = SwapMs): ContentTransform {
    val out = (ms * 2 / 3).coerceAtLeast(120)
    // The slide eases out hard, so the old face has cleared well before it ends. The new one starts then.
    val wait = if (MotionState.reduced) 0 else (out * 0.55f).toInt()
    return (slideInVertically(tween(ms - wait, delayMillis = wait, easing = UiGlide)) { it * dirIn } togetherWith
        slideOutVertically(tween(out, easing = UiGlide)) { it * dirOut }).using(sizeGlide(ms))
}
