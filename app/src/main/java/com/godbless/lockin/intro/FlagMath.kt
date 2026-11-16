package com.godbless.lockin.intro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Wind model for the summit flag. The pole stays rigid; the cloth ripples more towards its free edge. */
internal object FlagMath {
    /** The cloth starts on the pole's right edge (629.5), a hair inside it so they never part. */
    const val CLOTH_LEFT = 628.5f
    const val CLOTH_RIGHT = 884f
    const val BASE_AMPLITUDE = 13f
    /** How deep the swallowtail cut at the free end is. */
    const val NOTCH = 34f
    const val SAMPLES = 48

    /** Top and bottom edges of the cloth before the wind moves it. It tapers a little towards the free end. */
    fun top(x: Float): Float {
        val u = ((x - CLOTH_LEFT) / (CLOTH_RIGHT - CLOTH_LEFT)).coerceIn(0f, 1f)
        return 314f + 6f * u
    }

    fun bottom(x: Float): Float {
        val u = ((x - CLOTH_LEFT) / (CLOTH_RIGHT - CLOTH_LEFT)).coerceIn(0f, 1f)
        return 396f - 12f * u
    }

    /** Vertical displacement of the cloth at scene x [x] and time [t]. [amp] is 0 when still. */
    fun displacement(x: Float, t: Float, amp: Float): Float {
        val u = max(0f, (x - CLOTH_LEFT) / (CLOTH_RIGHT - CLOTH_LEFT))
        val w = u.pow(1.15f)
        val twoPi = (2.0 * PI).toFloat()
        val gust = 1f + 0.22f * sin(t * 0.9f)
        val wave = sin(twoPi * (1.55f * u - 0.95f * t)) +
            0.35f * sin(twoPi * (3.1f * u - 1.8f * t) + 1.3f)
        return amp * gust * w * wave
    }

    /** The cloth gathers a little in x as it bends. */
    fun gather(x: Float, dy: Float): Float {
        val u = max(0f, (x - CLOTH_LEFT) / (CLOTH_RIGHT - CLOTH_LEFT))
        return x - 0.10f * abs(dy) * u
    }
}
