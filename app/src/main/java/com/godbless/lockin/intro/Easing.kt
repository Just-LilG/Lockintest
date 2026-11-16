package com.godbless.lockin.intro

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

internal fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

/** Linear blend between [a] and [b]. */
internal fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Progress (0..1) of an animation that starts at [start] and lasts [dur] seconds. */
internal fun seg(t: Float, start: Float, dur: Float): Float = clamp01((t - start) / dur)

internal fun easeInOutSine(x: Float): Float = 0.5f - 0.5f * cos(PI.toFloat() * clamp01(x))

internal fun easeInOutCubic(x: Float): Float {
    val v = clamp01(x)
    return if (v < 0.5f) 4f * v * v * v else 1f - (-2f * v + 2f).pow(3) / 2f
}

internal fun easeOutCubic(x: Float): Float {
    val v = 1f - clamp01(x)
    return 1f - v * v * v
}

internal fun easeInCubic(x: Float): Float {
    val v = clamp01(x)
    return v * v * v
}

internal fun easeOutExpo(x: Float): Float {
    val v = clamp01(x)
    return if (v >= 1f) 1f else 1f - 2f.pow(-10f * v)
}

/** Overshoots 1.0 and settles back. [s] is the overshoot strength. */
internal fun easeOutBack(x: Float, s: Float = 1.7f): Float {
    val v = clamp01(x) - 1f
    return 1f + (s + 1f) * v * v * v + s * v * v
}
