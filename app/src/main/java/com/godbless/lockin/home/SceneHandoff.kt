package com.godbless.lockin.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.godbless.lockin.intro.ClimberSpec
import com.godbless.lockin.intro.easeInOutSine

/**
 * The two climbers on Home. They are the same two as on the welcome page: same start, same stride, so a
 * climber standing at a given distance has exactly the same pose on both pages. That is what lets the
 * welcome page hand its mountain to Home without anyone jumping.
 */
internal val HomeYouSpec = ClimberSpec(135f, 1f, 12, 520f, 0f, 1f, stride = 31f)
internal val HomePartnerSpec = ClimberSpec(1115f, -1f, 13, 708f, 0f, 1f, stride = 28.8f, female = true)

/** Home spells progress as 0 to 1 along the whole route; this finds it for a distance already walked. */
internal fun progressAtDistance(spec: ClimberSpec, distance: Float): Float {
    val want = (distance / spec.dTotal).coerceIn(0f, 1f)
    var lo = 0f
    var hi = 1f
    repeat(28) {
        val mid = (lo + hi) / 2f
        if (0.35f * mid + 0.65f * easeInOutSine(mid) < want) lo = mid else hi = mid
    }
    return (lo + hi) / 2f
}

/**
 * What the welcome page and Home share while one turns into the other. Welcome shrinks into the box where
 * Home's mountain lives (the mountain and climbers never stop moving), and Home waits underneath with its
 * mountain hidden until the move has finished, then takes over at exactly the same picture.
 */
internal object SceneHandoff {
    /** Where Home's mountain box is, in window pixels. Null until Home has been laid out. */
    var box by mutableStateOf<Rect?>(null)

    /** Corner radius of Home's card and the width of its outline, in pixels. */
    var corner = 0f
    var border = 0f

    /** How far up the mountain each climber is on Home (0 to 1). Home keeps these current. */
    var reachYou = 0.04f
    var reachPartner = 0.04f

    /** The welcome page's clock, so Home's flag carries on waving where the welcome page's left off. */
    var clock = 0f

    /** True while Home's mountain stays hidden because the welcome page is drawing it. */
    var sceneHidden by mutableStateOf(false)

    /** Whether the big number, the status chip and the name tags are showing on Home's card. */
    var chromeShown by mutableStateOf(true)

    /** True from the first frame of the move until Home takes over. */
    var flying by mutableStateOf(false)

    /**
     * Home has who your partner is and today's numbers, so its first picture is the right one. The welcome page
     * waits for this (a few seconds at most) before it moves, so Home never shows "Invite your partner" for a
     * moment and then rearranges itself.
     */
    var dataReady by mutableStateOf(false)

    fun beginToHome() {
        dataReady = false
        box = null
        sceneHidden = true
        chromeShown = false
        flying = true
    }

    fun land() {
        flying = false
        sceneHidden = false
        chromeShown = true
    }

    fun beginToWelcome() {
        sceneHidden = true
        flying = true
    }

    fun reset() {
        flying = false
        sceneHidden = false
        chromeShown = true
    }
}
