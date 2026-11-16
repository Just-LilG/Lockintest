package com.godbless.lockin.intro

/** The mountain's smoothed skyline, so climbers can stand on it. Scene units. */
internal object Ground {
    fun y(x: Float): Float {
        val sky = Geo.skyline
        val f = (x - Geo.SKY_X0) / Geo.SKY_STEP
        var i = f.toInt()
        if (f < 0f) i = 0
        if (i < 0) i = 0
        if (i > sky.size - 2) i = sky.size - 2
        val t = clamp01(f - i)
        return sky[i] * (1f - t) + sky[i + 1] * t
    }
}
