package com.godbless.lockin.intro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything around the climbers that makes the mountain feel alive: the sky for the time of day, weather,
 * the trail and its camps, footprints, cairns, tents, breath in the cold and the summit burst.
 *
 * Plain Kotlin, in scene units, so the app and the desktop preview draw exactly the same thing. Each layer
 * comes back as a list of [FxPrim] the painter draws in order.
 */
internal class FxPrim(
    /** 0 circle [x, y, r]; 1 polygon; 2 polyline stroke; 3 soft glow [x, y, r] (colour fading to clear). */
    val kind: Int,
    /** ARGB. */
    val color: Int,
    val pts: FloatArray,
    val width: Float = 0f,
)

/** How the day is going, as weather. */
internal enum class Weather { Clear, Cloudy, Snow }

internal object SceneFx {
    // ---------------------------------------------------------------------------------------------
    // Colours (ARGB). Black, white and lime like the rest of LockIn; the sky only tints the dark a little.
    // ---------------------------------------------------------------------------------------------
    /** The theme's accent (ARGB) and whether the theme is light; set by the scene before each frame. */
    var accent: Int = 0xFFC6F432.toInt()
    var light: Boolean = false

    private val LIME: Int get() = accent
    /** White on the dark theme; on the light one, white things in the sky (snow, clouds, breath) turn soft grey. */
    private val SNOW: Int get() = if (light) 0xFF8C93A6.toInt() else 0xFFFFFFFF.toInt()

    /** Clouds: white wisps on the dark theme; on the light one a soft sky blue, which shows against the pale page. */
    private val CLOUD: Int get() = if (light) 0xFF9DB6DE.toInt() else 0xFFFFFFFF.toInt()
    private const val INK = 0xFF0B0B0C.toInt()
    private const val TRAIL = 0xFFB9B9C8.toInt()
    private const val PRINT = 0xFFC4C4D2.toInt()
    private const val TENT = 0xFFE4E4EC.toInt()
    private const val TENT_SHADE = 0xFFB4B4C4.toInt()
    private const val STONE_A = 0xFF8F8FA0.toInt()
    private const val STONE_B = 0xFF6E6E7E.toInt()
    private const val STONE_C = 0xFFA8A8B8.toInt()

    private fun argb(a: Float, rgb: Int): Int = ((clamp01(a) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    /** Weather from how today is going: on track is clear, behind by midday is cloudy, far behind by evening snows. */
    fun weatherFor(todaySeconds: Int, goalSeconds: Int, hour: Float): Weather {
        val done = todaySeconds / goalSeconds.coerceAtLeast(1).toFloat()
        return when {
            done >= 1f -> Weather.Clear
            hour >= 18f && done < 0.5f -> Weather.Snow
            hour >= 12f && done < 0.25f -> Weather.Cloudy
            else -> Weather.Clear
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The skyline as a clip: everything in the back layer stays above the mountain.
    // ---------------------------------------------------------------------------------------------

    /** Polygon of the open sky above the skyline, in scene units. */
    val skyPolygon: FloatArray by lazy {
        val sky = Geo.skyline
        val out = FloatArray(sky.size * 2 + 8)
        var k = 0
        out[k++] = -400f; out[k++] = -400f
        out[k++] = -400f; out[k++] = sky[0]
        for (i in sky.indices) {
            out[k++] = Geo.SKY_X0 + i * Geo.SKY_STEP.toFloat()
            out[k++] = sky[i]
        }
        out[k++] = 1800f; out[k++] = sky[sky.size - 1]
        out[k++] = 1800f; out[k++] = -400f
        out
    }

    // ---------------------------------------------------------------------------------------------
    // Time of day
    // ---------------------------------------------------------------------------------------------

    /** 0 to 1 for each part of the day, overlapping smoothly. */
    class DayParts(val dawn: Float, val day: Float, val dusk: Float, val night: Float)

    fun dayParts(hour: Float): DayParts {
        fun bump(center: Float, half: Float): Float {
            var d = abs(hour - center)
            if (d > 12f) d = 24f - d
            return clamp01(1f - d / half).let { it * it * (3f - 2f * it) }
        }
        val dawn = bump(6.5f, 2.2f)
        val dusk = bump(18.6f, 2.2f)
        val day = clamp01(bump(12.5f, 5.6f) * 1.4f) * (1f - max(dawn, dusk) * 0.6f)
        val night = clamp01(1f - dawn - dusk - day)
        return DayParts(dawn, day, dusk, night)
    }

    /**
     * Behind the mountain: the sky's glow, stars, the sun or moon on its arc, the northern lights after a long
     * streak, and far clouds. [bloom] 0 to 1 grows it all in when the scene appears (circles grow, nothing fades).
     */
    fun back(t: Float, hour: Float, weather: Weather, aurora: Boolean, bloom: Float): List<FxPrim> {
        val out = ArrayList<FxPrim>(160)
        if (bloom <= 0f) return out
        val p = dayParts(hour)
        val cloud = when (weather) { Weather.Clear -> 0f; Weather.Cloudy -> 1f; Weather.Snow -> 1.3f }

        // The glow: warm at dawn and dusk, cool and faint by day, deep blue at night. It sits low behind the range.
        fun glow(x: Float, y: Float, r: Float, a: Float, rgb: Int) {
            // On the pale page a glow needs more weight to show at all.
            val k = if (light) 1.3f else 1f
            if (a > 0.004f) out.add(FxPrim(3, argb(minOf(a * k, 0.6f), rgb), floatArrayOf(x, y, r * bloom)))
        }
        glow(250f, 700f, 760f, 0.26f * p.dawn, 0xFFB074)
        glow(980f, 700f, 760f, 0.26f * p.dusk, 0xFF7A5C)
        glow(1080f, 520f, 520f, 0.12f * p.dusk, 0xC85CFF)
        glow(630f, 360f, 900f, if (light) 0.22f * p.day else 0.09f * p.day, if (light) 0x8CC3F2 else 0x9FD3FF)
        glow(630f, 520f, 820f, 0.13f * p.night, 0x4C5BFF)

        // Stars: only where it's dark enough, twinkling by growing and shrinking.
        val starVis = if (light) 0f else clamp01(p.night + 0.5f * (p.dawn + p.dusk) - 0.25f) * clamp01(1f - 0.75f * cloud)
        if (starVis > 0f) {
            for (s in stars) {
                val tw = 0.62f + 0.38f * sin(t * s.speed + s.phase)
                val r = s.r * tw * starVis * bloom
                if (r > 0.15f) out.add(FxPrim(0, argb(0.55f + 0.45f * s.bright, SNOW), floatArrayOf(s.x, s.y, r)))
            }
            // A shooting star now and then, at night only.
            val period = 17f
            val f = ((t + 5f) % period) / 1.1f
            if (p.night > 0.6f && f in 0f..1f) {
                val k = ((t + 5f) / period).toInt()
                val sx = 300f + (k * 397 % 600)
                val sy = 300f + (k * 131 % 80)
                val hx = sx + 210f * f
                val hy = sy + 70f * f
                val tail = 70f * sin(PI.toFloat() * f)
                out.add(FxPrim(2, argb(0.9f, SNOW), floatArrayOf(hx - tail * 0.95f, hy - tail * 0.32f, hx, hy), 2.2f * bloom))
            }
        }

        // The northern lights: curtains of light hanging from a slow ribbon, lime fading into teal.
        if (aurora && p.night > 0.05f) {
            val a = p.night * clamp01(1f - cloud)
            for (band in 0..1) {
                var x = 110f + band * 7f
                while (x < 1180f) {
                    val top = 300f + band * 34f + 38f * sin(x * 0.005f + t * (0.2f + band * 0.06f) + band * 1.9f) +
                        14f * sin(x * 0.016f - t * 0.33f)
                    // Each ray breathes in length on its own.
                    val len = (30f + 34f * (0.5f + 0.5f * sin(x * 0.041f + t * 0.9f + band))) * bloom
                    val rgb = if (band == 0) accent and 0xFFFFFF else 0x4FE3C1
                    out.add(FxPrim(2, argb(0.035f * a, rgb), floatArrayOf(x, top, x, top + len), 12f))
                    out.add(FxPrim(2, argb(0.06f * a, rgb), floatArrayOf(x, top + 2f, x, top + len * 0.4f), 4f))
                    x += 10f
                }
            }
        }

        // The sun by day (6 to 18) and the moon by night (18 to 6), on an arc that sets behind the range.
        val sunF = (hour - 6f) / 12f
        if (sunF in 0f..1f) {
            val (x, y) = arc(sunF)
            val warm = 1f - p.day
            glow(x, y, 110f, 0.22f * (1f - 0.6f * cloud), if (warm > 0.4f) 0xFFB074 else if (light) 0xFFCB5C else 0xFFE7A8)
            // The sun's disc: pale cream on the dark theme, a real gold on the light one (cream would vanish).
            out.add(FxPrim(0, argb(1f, if (light) (if (warm > 0.4f) 0xFF9A55 else 0xFFC22E) else if (warm > 0.4f) 0xFFC58A else 0xFFF1C9), floatArrayOf(x, y, 19f * bloom)))
        } else {
            val mf = ((hour - 18f + 24f) % 24f) / 12f
            if (mf in 0f..1f) {
                val (x, y) = arc(mf)
                glow(x, y, 110f, 0.14f, 0xDDE6FF)
                out.add(FxPrim(1, argb(1f, if (light) 0x8F9CC4 else 0xEEF1FA), crescent(x, y, 19f * bloom)))
            }
        }

        // Far clouds drift behind the peaks; more of them when the day is going badly.
        val far = 2 + (cloud * 3f).toInt()
        for (i in 0 until far) cloudPuffs(out, t, i, 420f + (i % 3) * 38f, 0.10f + 0.06f * cloud, 0.55f + 0.1f * (i % 2), bloom, 6f + i * 2.2f)
        return out
    }

    private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val bright: Float)

    private val stars: List<Star> by lazy {
        val list = ArrayList<Star>()
        var seed = 7
        fun rnd(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 10000) / 10000f }
        while (list.size < 70) {
            val x = 70f + rnd() * 1120f
            val y = 262f + rnd() * 420f
            if (y > Ground.y(x) - 22f) continue
            list.add(Star(x, y, 1.1f + 1.6f * rnd() * rnd(), 1.2f + 2.4f * rnd(), rnd() * 6.28f, rnd()))
        }
        list
    }

    private fun arc(f: Float): Pair<Float, Float> {
        val x = 120f + 1040f * f
        val y = 760f - 470f * sin(PI.toFloat() * f)
        return x to y
    }

    private fun crescent(x: Float, y: Float, r: Float): FloatArray {
        val n = 18
        val pts = FloatArray(4 * (n + 1))
        // Outer edge: the lit half of the disc; inner edge: a smaller arc pushed into it.
        for (i in 0..n) {
            val a = -PI.toFloat() / 2f + PI.toFloat() * i / n
            pts[2 * i] = x + r * cos(a)
            pts[2 * i + 1] = y + r * sin(a)
        }
        for (i in 0..n) {
            val a = PI.toFloat() / 2f - PI.toFloat() * i / n
            pts[2 * (n + 1) + 2 * i] = x + 0.38f * r * cos(a)
            pts[2 * (n + 1) + 2 * i + 1] = y + r * sin(a)
        }
        return pts
    }

    /** One cloud: a row of overlapping puffs drifting sideways and wrapping round. */
    private fun cloudPuffs(out: MutableList<FxPrim>, t: Float, i: Int, y: Float, a: Float, size: Float, bloom: Float, speed: Float) {
        val span = 1700f
        val x0 = ((i * 433f + t * speed) % span + span) % span - 250f
        val r = 46f * size * bloom
        val puffs = floatArrayOf(-1.4f, 0.55f, -0.6f, 0.85f, 0.2f, 1f, 1.0f, 0.75f, 1.7f, 0.5f)
        var k = 0
        while (k < puffs.size) {
            // Soft puffs (a glow each), so the cloud has no hard edge.
            out.add(FxPrim(3, argb(if (light) minOf(a * 2.4f, 0.5f) else a, CLOUD), floatArrayOf(x0 + puffs[k] * r * 1.1f, y - puffs[k + 1] * r * 0.55f, 1.9f * r * puffs[k + 1])))
            k += 2
        }
    }

    // ---------------------------------------------------------------------------------------------
    // On the mountain: trail, camps, footprints, cairns, shadows, tents
    // ---------------------------------------------------------------------------------------------

    /** Progress points of the three camps on the way up (fractions of the day's goal). */
    val CampAt = floatArrayOf(0.25f, 0.5f, 0.75f)

    /** The scene progress a fraction of the goal maps to (matches the home screen's `reach`). */
    fun progressOf(fraction: Float): Float = 0.04f + 0.9f * clamp01(fraction)

    /**
     * The trail still to climb (dashes) and the footprints already made, for one climber at [progress],
     * plus the camps along the way: a camp lights up (a lime pennant) once the climber has passed it.
     * [grow] 0 to 1 draws the trail on from the climber upwards.
     */
    fun ground(c: ClimberSpec, progress: Float, grow: Float, t: Float, out: MutableList<FxPrim>) {
        val d = Rig.distance(c, progress)
        val end = Rig.distance(c, progressOf(1f))
        // Footprints: alternating little dents in the snow behind the climber, newest nearest.
        var u = d - 34f
        var side = 1f
        while (u > 12f) {
            val x = c.worldX(u)
            val y = Ground.y(x) - 1.5f
            val g = Rig.groundGrade(c, u) * c.dir
            // Alternate feet sit a touch apart along the slope, so the prints read as left, right.
            out.add(FxPrim(1, argb(1f, PRINT), ellipse(x + side * 1.6f, y, 4.6f, 1.9f, g, 8)))
            side = -side
            u -= c.stepLen * 0.62f
        }
        // The way ahead: short dashes on the snow from just above the climber to the top.
        val from = d + 40f
        val to = from + (end + 70f - from) * clamp01(grow)
        var v = from
        while (v < to) {
            val x1 = c.worldX(v)
            val x2 = c.worldX(min(v + 7f, to))
            out.add(FxPrim(2, argb(1f, TRAIL), floatArrayOf(x1, Ground.y(x1) - 2.5f, x2, Ground.y(x2) - 2.5f), 2.4f))
            v += 17f
        }
        // Camps.
        for ((k, f) in CampAt.withIndex()) {
            val cu = Rig.distance(c, progressOf(f)) + 6f
            val passed = progress >= progressOf(f) - 0.004f
            tent(c.worldX(cu), 17f, passed, t + k * 1.3f, out)
        }
    }

    /** A small pitched tent standing on the slope at [x]; [lit] adds a lime pennant and a warm door. */
    fun tent(x: Float, size: Float, lit: Boolean, t: Float, out: MutableList<FxPrim>, lamp: Float = 0f) {
        val w = size * 1.5f
        val yl = Ground.y(x - w / 2f)
        val yr = Ground.y(x + w / 2f)
        val base = min(yl, yr)
        val apexY = base - size * 1.05f
        // Outline first so it reads on the white mountain, then the two faces of the fly.
        out.add(FxPrim(1, INK, floatArrayOf(x - w / 2f - 2.4f, yl + 1.6f, x, apexY - 2.6f, x + w / 2f + 2.4f, yr + 1.6f)))
        out.add(FxPrim(1, TENT, floatArrayOf(x - w / 2f, yl, x, apexY, x + w / 2f, yr)))
        out.add(FxPrim(1, TENT_SHADE, floatArrayOf(x, apexY, x + w / 2f, yr, x + w * 0.12f, (yl + yr) / 2f)))
        // The door: dark, or glowing when someone's home.
        val door = floatArrayOf(x - w * 0.16f, (yl + yr) / 2f, x, apexY + size * 0.45f, x + w * 0.16f, (yl + yr) / 2f)
        out.add(FxPrim(1, if (lamp > 0f) argb(1f, 0xFFD9A0) else argb(1f, 0x3C3C48), door))
        if (lamp > 0f) out.add(FxPrim(3, argb(0.24f * lamp, 0xFFC27A), floatArrayOf(x, (yl + yr) / 2f - size * 0.15f, size * 1.2f * lamp)))
        // Pole and pennant: lime once passed, rippling.
        out.add(FxPrim(2, INK, floatArrayOf(x, apexY, x, apexY - size * 0.75f), 2.6f))
        if (lit) {
            val px = x
            val py = apexY - size * 0.75f
            val ripple = 2.2f * sin(t * 6f)
            out.add(FxPrim(1, LIME, floatArrayOf(px, py, px + size * 0.7f, py + 3.2f + ripple, px, py + size * 0.42f)))
        }
    }

    /** Cairns at the foot of the mountain: one stone for each day of the streak (up to 12), in two piles. */
    fun cairns(streak: Int, grow: Float, out: MutableList<FxPrim>) {
        val n = min(streak, 12)
        if (n <= 0) return
        val piles = floatArrayOf(168f, 214f)
        var left = n
        for ((pi, x) in piles.withIndex()) {
            val count = if (pi == 0) min(left, 7) else left
            left -= count
            var y = Ground.y(x) + 1f
            for (k in 0 until count) {
                // Each stone pops on in turn (it grows; nothing fades).
                val s = clamp01(grow * (n + 2) - (pi * 7 + k)).let { easeOutBack(it) }
                if (s <= 0f) break
                val w = (14f - k * 1.3f) * s
                val h = (6.4f - k * 0.35f) * s
                val wobble = if (k % 2 == 0) 1.6f else -1.4f
                val tone = when (k % 3) { 0 -> STONE_A; 1 -> STONE_C; else -> STONE_B }
                out.add(FxPrim(1, INK, ellipse(x + wobble, y - h, w + 2.2f, h + 2.2f, 0f, 14)))
                out.add(FxPrim(1, tone, ellipse(x + wobble, y - h, w, h, 0f, 14)))
                y -= h * 1.75f
            }
        }
    }

    /** A soft contact shadow on the snow under a climber standing at local [u]. */
    fun shadow(c: ClimberSpec, u: Float, scale: Float, out: MutableList<FxPrim>) {
        val x = c.worldX(u)
        val g = Rig.groundGrade(c, u) * c.dir
        out.add(FxPrim(1, argb(0.16f, 0x2A2A38), ellipse(x, Ground.y(x) - 1f, 38f * scale, 5.5f * scale, g, 16)))
    }

    // ---------------------------------------------------------------------------------------------
    // In front: breath, sleep, snow, mist, the summit burst
    // ---------------------------------------------------------------------------------------------

    /** Puffs of breath in the cold, from the face ([fx], [fy]) in the facing direction ([dx], [dy]). */
    fun breath(fx: Float, fy: Float, dx: Float, dy: Float, t: Float, phase: Float, out: MutableList<FxPrim>) {
        val period = 3.1f
        val f = ((t + phase) % period) / 1.5f
        if (f !in 0f..1f) return
        for (k in 0..2) {
            val g = clamp01(f * 1.25f - k * 0.12f)
            if (g <= 0f || g >= 1f) continue
            val r = 7.5f * sin(PI.toFloat() * g) * (1f - 0.2f * k)
            val dist = 10f + 26f * g + k * 6f
            out.add(FxPrim(0, argb(0.28f, SNOW), floatArrayOf(fx + dx * dist, fy + dy * dist - 9f * g, r * 0.8f)))
        }
    }

    /** Little Zs drifting up from a sleeping tent, each growing then shrinking away. */
    fun zzz(x: Float, y: Float, t: Float, out: MutableList<FxPrim>) {
        for (k in 0..2) {
            val f = ((t * 0.45f + k / 3f) % 1f)
            val s = 9f * sin(PI.toFloat() * f)
            if (s <= 0.4f) continue
            val cx = x + 14f * f + 6f * sin(t * 1.7f + k)
            val cy = y - 46f * f
            out.add(FxPrim(2, argb(0.9f, if (light) 0x3B3B42 else 0xFFFFFF), floatArrayOf(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy - s / 2f, cx - s / 2f, cy + s / 2f, cx + s / 2f, cy + s / 2f), 2.4f))
        }
    }

    private class Flake(val x: Float, val y: Float, val r: Float, val fall: Float, val sway: Float, val phase: Float)

    private val flakes: List<Flake> by lazy {
        var seed = 11
        fun rnd(): Float { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return (seed % 10000) / 10000f }
        List(90) { Flake(rnd() * 1300f, rnd() * 800f, 1.4f + 2.2f * rnd(), 26f + 40f * rnd(), 8f + 14f * rnd(), rnd() * 6.28f) }
    }

    /** Falling snow ([amount] 0 to 1) and mist drifting across the foot of the mountain. */
    fun front(t: Float, weather: Weather, bloom: Float, out: MutableList<FxPrim>) {
        val snow = if (weather == Weather.Snow) 1f else 0f
        if (snow > 0f && bloom > 0f) {
            for ((i, f) in flakes.withIndex()) {
                if (i > 90 * snow) break
                val y = 230f + ((f.y + f.fall * t) % 720f)
                val x = 40f + (((f.x + 9f * t + f.sway * sin(t * 0.9f + f.phase)) % 1220f) + 1220f) % 1220f
                out.add(FxPrim(0, argb(0.85f, SNOW), floatArrayOf(x, y, f.r * bloom)))
            }
        }
        val mist = when (weather) { Weather.Clear -> 1; Weather.Cloudy -> 3; Weather.Snow -> 3 }
        for (i in 0 until mist) cloudPuffs(out, t, 10 + i, 850f - (i % 2) * 40f, 0.07f + 0.03f * i, 0.75f, bloom, 9f + 3f * i)
    }

    /**
     * Reaching the goal: a burst of snow and lime sparks from the summit, [age] seconds after it began.
     * Each particle flies out, falls back and shrinks to nothing within about 1.6 s.
     */
    fun burst(age: Float, out: MutableList<FxPrim>) {
        if (age < 0f || age > 1.8f) return
        for (k in 0 until 26) {
            val a = -PI.toFloat() * (0.08f + 0.84f * ((k * 37) % 26) / 25f)
            val sp = 210f + 170f * (((k * 53) % 11) / 10f)
            val x = ROPE_TIE_X + cos(a) * sp * age
            val y = ROPE_TIE_Y - 40f + sin(a) * sp * age + 0.5f * 520f * age * age
            val r = (if (k % 3 == 0) 5.2f else 3.6f) * clamp01(1f - age / 1.6f)
            if (r > 0.2f) out.add(FxPrim(0, if (k % 3 == 0) LIME else SNOW, floatArrayOf(x, y, r)))
        }
        // A ring that spreads from the flag and thins away.
        val ring = clamp01(age / 0.9f)
        if (ring < 1f) {
            val pts = FloatArray(2 * 33)
            for (i in 0..32) {
                val an = 2f * PI.toFloat() * i / 32f
                pts[2 * i] = ROPE_TIE_X + (16f + 110f * ring) * cos(an)
                pts[2 * i + 1] = ROPE_TIE_Y - 70f + (16f + 110f * ring) * sin(an)
            }
            out.add(FxPrim(2, LIME, pts, 4f * (1f - ring)))
        }
    }

    /** A spark between two climbers' raised hands when both made it: a high five over the summit. */
    fun highFive(x: Float, y: Float, t: Float, out: MutableList<FxPrim>) {
        val pulse = 0.5f + 0.5f * sin(t * 7f)
        for (k in 0..3) {
            val a = PI.toFloat() / 4f * k + t * 0.8f
            val r = 10f + 6f * pulse
            out.add(FxPrim(2, LIME, floatArrayOf(x - cos(a) * r, y - sin(a) * r, x + cos(a) * r, y + sin(a) * r), 2.6f))
        }
        out.add(FxPrim(0, SNOW, floatArrayOf(x, y, 3.2f + 1.5f * pulse)))
    }

    // ---------------------------------------------------------------------------------------------

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rot: Float, n: Int): FloatArray {
        val pts = FloatArray(2 * n)
        val cr = cos(rot)
        val sr = sin(rot)
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n
            val x = rx * cos(a)
            val y = ry * sin(a)
            pts[2 * i] = cx + x * cr - y * sr
            pts[2 * i + 1] = cy + x * sr + y * cr
        }
        return pts
    }

    private fun easeOutBack(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val m = x - 1f
        return 1f + c3 * m * m * m + c1 * m * m
    }
}

/**
 * A rope that hangs and swings like a real one: a chain of points (Verlet integration) pinned at the
 * climber's harness and the summit, with gravity, a set length, and the snow underneath it.
 */
internal class RopeSim(private val n: Int = 18) {
    val xs = FloatArray(n + 1)
    val ys = FloatArray(n + 1)
    private val px = FloatArray(n + 1)
    private val py = FloatArray(n + 1)
    private var ready = false
    private var acc = 0f

    /** Lays the rope along the same curve the old drawing used, so a hand-over never jumps. */
    fun reset(fx: Float, fy: Float, tx: Float, ty: Float, sag: Float) {
        val cx = (fx + tx) / 2f
        val cy = (fy + ty) / 2f + sag
        for (i in 0..n) {
            val u = i / n.toFloat()
            val a = (1 - u) * (1 - u)
            val b = 2 * (1 - u) * u
            val c = u * u
            xs[i] = a * fx + b * cx + c * tx
            ys[i] = a * fy + b * cy + c * ty
            px[i] = xs[i]
            py[i] = ys[i]
        }
        ready = true
    }

    /**
     * Moves the rope on by [dt] seconds with its ends at ([fx], [fy]) and ([tx], [ty]). [slack] 0 is a
     * straight, tight rope; 0.12 hangs loosely. [kick] gives the middle a sideways shove (a twang).
     */
    fun step(dt: Float, fx: Float, fy: Float, tx: Float, ty: Float, slack: Float, kick: Float = 0f) {
        if (!ready) reset(fx, fy, tx, ty, 40f)
        acc = min(acc + dt, 0.1f)
        val h = 1f / 90f
        val len = hypot(tx - fx, ty - fy) * (1f + slack) / n
        if (kick != 0f) for (i in 1 until n) {
            val k = sin(PI.toFloat() * i / n)
            py[i] -= kick * k * 3.2f
        }
        while (acc >= h) {
            acc -= h
            for (i in 1 until n) {
                val vx = (xs[i] - px[i]) * 0.985f
                val vy = (ys[i] - py[i]) * 0.985f
                px[i] = xs[i]; py[i] = ys[i]
                xs[i] += vx
                ys[i] += vy + 900f * h * h
            }
            xs[0] = fx; ys[0] = fy; xs[n] = tx; ys[n] = ty
            repeat(10) {
                for (i in 0 until n) {
                    val dx = xs[i + 1] - xs[i]
                    val dy = ys[i + 1] - ys[i]
                    val d = max(sqrt(dx * dx + dy * dy), 1e-4f)
                    val diff = (d - len) / d * 0.5f
                    val ox = dx * diff
                    val oy = dy * diff
                    if (i != 0) { xs[i] += ox; ys[i] += oy }
                    if (i + 1 != n) { xs[i + 1] -= ox; ys[i + 1] -= oy }
                }
                // The rope lies on the snow rather than passing through the mountain.
                for (i in 1 until n) {
                    val gy = Ground.y(xs[i]) - 2.5f
                    if (ys[i] > gy) { ys[i] = gy; py[i] = py[i] * 0.6f + gy * 0.4f }
                }
            }
        }
    }

    fun points(): FloatArray {
        val out = FloatArray(2 * (n + 1))
        for (i in 0..n) { out[2 * i] = xs[i]; out[2 * i + 1] = ys[i] }
        return out
    }
}
