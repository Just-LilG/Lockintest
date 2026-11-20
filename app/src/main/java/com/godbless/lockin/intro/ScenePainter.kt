package com.godbless.lockin.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The scene is drawn in the logo's own coordinate space. This is the crop we show: x 60..1200, y 250..930.
internal const val SCENE_X = 60f
internal const val SCENE_Y = 250f
internal const val SCENE_W = 1140f
internal const val SCENE_H = 680f

private const val MOUNTAIN_X = 87.333f
private const val MOUNTAIN_Y = 474f
private const val MOUNTAIN_PX_PER_UNIT = 1.5f
internal const val BASELINE = 905f
internal const val SUMMIT_X = 614f
internal const val SUMMIT_Y = 479f

/** Where both ropes tie on: the middle of the flag pole's foot (the shaft runs x 612.5 to 629.5). */
internal const val ROPE_TIE_X = 621f
internal const val ROPE_TIE_Y = 478f

private val RopeColor = Color(0xFF9A9AA8)
/** Darkens folds of the lime flag that turn away from the light. */
private val Shade = Color(0xFF2B3A06)
private val Silver = Color(0xFFA4A4B2)

/** Set to false to remove the ropes that tie both climbers to the summit. */
internal const val SHOW_ROPES = true

internal fun DrawScope.drawMountain(image: ImageBitmap) {
    withTransform({
        translate(MOUNTAIN_X, MOUNTAIN_Y)
        scale(1f / MOUNTAIN_PX_PER_UNIT, 1f / MOUNTAIN_PX_PER_UNIT, Offset.Zero)
    }) {
        val size = IntSize(image.width, image.height)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = size,
            dstOffset = IntOffset.Zero,
            dstSize = size,
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun themed(overrides: Map<Int, Long>) = Array(ClimberPalette.size) { i ->
    Color(overrides[i]?.toInt() ?: ClimberPalette[i])
}

/** Black kit for the dark theme. Only the clothing and gear change; skin, hair, goggles and the accent stay. */
private val DarkPalette = themed(mapOf(
    Tone.INK to 0xFF2A2E36, Tone.JACKET to 0xFF0E0F12, Tone.JACKET_FAR to 0xFF08090B, Tone.PANTS to 0xFF15171B,
    Tone.PANTS_FAR to 0xFF0C0D10, Tone.BOOT to 0xFF0A0B0D, Tone.GLOVE to 0xFF121317, Tone.PACK to 0xFF1A1C21,
    Tone.ROLL to 0xFF24272D, Tone.JACKET_SHADE to 0xFF23262C, Tone.PANTS_SHADE to 0xFF121418, Tone.GAITER to 0xFF0F1013,
    Tone.HOOD to 0xFF1A1C21, Tone.SLEEVE_SHADE to 0xFF1D2026, Tone.GLOVE_HI to 0xFF3A3F48, Tone.PACK_SHADE to 0xFF101216,
    Tone.PACK_LID to 0xFF24272D, Tone.PANTS_HI to 0xFF4A4F59, Tone.JACKET_DEEP to 0xFF060709, Tone.PANTS_DEEP to 0xFF08090B,
    Tone.PACK_DEEP to 0xFF2B2F36,
))

/** White kit for the light theme, shaded in cool greys so the folds still read. The dark outline keeps it crisp. */
private val LightPalette = themed(mapOf(
    Tone.JACKET to 0xFFF4F6FA, Tone.JACKET_FAR to 0xFFD5DAE3, Tone.PANTS to 0xFFE9ECF2, Tone.PANTS_FAR to 0xFFCDD3DD,
    Tone.BOOT to 0xFFE3E6EC, Tone.GLOVE to 0xFFEEF0F5, Tone.PACK to 0xFFDFE3EA, Tone.ROLL to 0xFFC4CAD4,
    Tone.JACKET_SHADE to 0xFFC9CFDA, Tone.PANTS_SHADE to 0xFFC2C8D3, Tone.GAITER to 0xFFD0D5DE, Tone.HOOD to 0xFFE6E9EF,
    Tone.SLEEVE_SHADE to 0xFFCBD1DB, Tone.GLOVE_HI to 0xFFFFFFFF, Tone.PACK_SHADE to 0xFFC8CDD7, Tone.PACK_LID to 0xFFEEF0F4,
    Tone.PANTS_HI to 0xFFFFFFFF, Tone.JACKET_DEEP to 0xFFB8BFCB, Tone.PANTS_DEEP to 0xFFB3BAC6, Tone.PACK_DEEP to 0xFFA9B0BD,
))

private val scratchPath = Path()

/** Draws one climber from the shapes built in [ClimberShapes]. */
internal fun DrawScope.drawClimber(c: ClimberSpec, p: Pose) {
    val prims = ClimberShapes.build(c, p)
    val Palette = if (com.godbless.lockin.ui.LockInColors.isDark) DarkPalette else LightPalette
    for (pr in prims) {
        // The helmet band and zip follow the theme's accent.
        val color = if (pr.tone == Tone.ACCENT) com.godbless.lockin.ui.LockInColors.Lime else Palette[pr.tone]
        val v = pr.pts
        when (pr.kind) {
            0 -> drawCircle(color, v[2], Offset(v[0], v[1]))
            // A soft disc: full colour towards the middle, fading to nothing at the edge.
            3 -> drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = 0.85f), 0.45f to color.copy(alpha = 0.55f), 1f to color.copy(alpha = 0f),
                    center = Offset(v[0], v[1]), radius = v[2],
                ),
                radius = v[2], center = Offset(v[0], v[1]),
            )
            1 -> {
                scratchPath.rewind()
                scratchPath.moveTo(v[0], v[1])
                var i = 2
                while (i < v.size) {
                    scratchPath.lineTo(v[i], v[i + 1])
                    i += 2
                }
                scratchPath.close()
                drawPath(scratchPath, color)
            }
            else -> {
                scratchPath.rewind()
                scratchPath.moveTo(v[0], v[1])
                var i = 2
                while (i < v.size) {
                    scratchPath.lineTo(v[i], v[i + 1])
                    i += 2
                }
                drawPath(scratchPath, color, style = Stroke(width = pr.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A rope from the climber's chest to the summit. [grow] 0..1 pays it out from the climber. */
internal fun DrawScope.drawRope(from: Offset, sag: Float, grow: Float) {
    if (grow <= 0f) return
    val to = Offset(ROPE_TIE_X, ROPE_TIE_Y)
    val ctrl = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f + sag)
    val path = Path()
    path.moveTo(from.x, from.y)
    val n = 28
    for (i in 1..n) {
        val u = grow * i / n
        val a = (1f - u) * (1f - u)
        val b = 2f * (1f - u) * u
        val c = u * u
        path.lineTo(a * from.x + b * ctrl.x + c * to.x, a * from.y + b * ctrl.y + c * to.y)
    }
    drawPath(path, RopeColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun Path.polyline(pts: FloatArray, close: Boolean) {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) {
        lineTo(pts[i], pts[i + 1])
        i += 2
    }
    if (close) close()
}

/** The summit flag: a rigid pole and a cloth that ripples in the wind, shaded by how each fold faces the light. */
internal class FlagMesh {
    private val polePath = Path().apply { polyline(Geo.pole, close = true) }
    private val clothPath = Path()
    private val silver = Brush.linearGradient(
        0f to Color.White,
        0.45f to Color(0xFFF8F8FB),
        0.7f to Color(0xFFDCDCE4),
        1f to Color.White,
        start = Offset(250f, 300f),
        end = Offset(1000f, 950f),
    )
    /** The flag is the theme's accent: a little lighter where it catches the light, deeper at the far edge. */
    private var limeFor = Color.Unspecified
    private var lime: Brush = Brush.linearGradient(listOf(Color.White, Color.White))
    private var shade = Shade

    private fun accentBrush() {
        val a = com.godbless.lockin.ui.LockInColors.Lime
        if (a == limeFor) return
        limeFor = a
        lime = Brush.linearGradient(
            0f to lerp(a, Color.White, 0.28f),
            0.45f to a,
            0.75f to lerp(a, Color.Black, 0.09f),
            1f to a,
            start = Offset(250f, 300f),
            end = Offset(1000f, 950f),
        )
        // Folds turning away from the light go towards a deep shade of the same colour.
        shade = lerp(a, Color.Black, 0.78f)
    }

    fun draw(scope: DrawScope, t: Float) {
        accentBrush()
        with(scope) {
            val rise = (1f - easeOutBack(seg(t, Timeline.FLAG_START, Timeline.FLAG_RISE_DUR), 1.1f)) * 210f
            if (rise >= 209f) return
            val wind = easeInOutSine(seg(t, Timeline.FLAG_WIND_START, Timeline.FLAG_WIND_DUR))
            val amp = FlagMath.BASE_AMPLITUDE * (0.12f + 0.88f * wind)

            // One continuous cloth (no loose tails that could look torn): along the top edge, in to a single
            // swallowtail notch, and back along the bottom edge. Every point rides the same wave.
            clothPath.rewind()
            val n = FlagMath.SAMPLES
            for (k in 0..n) {
                val x = FlagMath.CLOTH_LEFT + (FlagMath.CLOTH_RIGHT - FlagMath.CLOTH_LEFT) * k / n
                val dy = FlagMath.displacement(x, t, amp)
                val px = FlagMath.gather(x, dy)
                val y = FlagMath.top(x) + dy
                if (k == 0) clothPath.moveTo(px, y) else clothPath.lineTo(px, y)
            }
            val notchX = FlagMath.CLOTH_RIGHT - FlagMath.NOTCH
            val notchDy = FlagMath.displacement(notchX, t, amp)
            clothPath.lineTo(FlagMath.gather(notchX, notchDy), (FlagMath.top(notchX) + FlagMath.bottom(notchX)) / 2f + notchDy)
            for (k in n downTo 0) {
                val x = FlagMath.CLOTH_LEFT + (FlagMath.CLOTH_RIGHT - FlagMath.CLOTH_LEFT) * k / n
                val dy = FlagMath.displacement(x, t, amp)
                clothPath.lineTo(FlagMath.gather(x, dy), FlagMath.bottom(x) + dy)
            }
            clothPath.close()

            clipRect(left = 560f, top = -700f, right = 1000f, bottom = SUMMIT_Y) {
                translate(top = rise) {
                    drawPath(polePath, silver)
                    drawPath(clothPath, lime)
                    clipPath(clothPath) {
                        // One smooth gradient across the cloth: darker where a fold turns away from the light.
                        val strips = 48
                        val x0 = FlagMath.CLOTH_LEFT - 6f
                        val x1 = FlagMath.CLOTH_RIGHT + 14f
                        val step = (x1 - x0) / strips
                        val stops = Array(strips + 1) { k ->
                            val xc = x0 + k * step
                            val slope = (FlagMath.displacement(xc + 2f, t, amp) - FlagMath.displacement(xc - 2f, t, amp)) / 4f
                            val dark = clamp01(0.5f + slope * 2.4f).pow(1.5f) * 0.42f
                            (k.toFloat() / strips) to shade.copy(alpha = dark)
                        }
                        drawRect(
                            Brush.horizontalGradient(*stops, startX = x0, endX = x1),
                            topLeft = Offset(x0, 260f),
                            size = Size(x1 - x0, 200f),
                        )
                    }
                }
            }
        }
    }
}

/** Topographic lines that draw themselves on, lowest ground first, then stay. */
internal class ContourField {
    private val lines = Geo.contours
    private val levels = Geo.contourLevels
    private val full: List<Path> = lines.map { pts -> Path().apply { polyline(pts, close = false) } }
    private val cumulative: List<FloatArray> = lines.map { pts ->
        val n = pts.size / 2
        val out = FloatArray(n)
        for (k in 1 until n) {
            val dx = pts[2 * k] - pts[2 * k - 2]
            val dy = pts[2 * k + 1] - pts[2 * k - 1]
            out[k] = out[k - 1] + sqrt(dx * dx + dy * dy)
        }
        out
    }
    private val scratch = Path()

    fun draw(scope: DrawScope, t: Float, w: Float, h: Float) {
        with(scope) {
            val s = max(w / Geo.FIELD_W, h / Geo.FIELD_H)
            val ox = (w - Geo.FIELD_W * s) / 2f
            val oy = (h - Geo.FIELD_H * s) / 2f
            withTransform({
                translate(ox, oy)
                scale(s, s, Offset.Zero)
            }) {
                for (i in lines.indices) {
                    val lvl = levels[i]
                    val p = easeOutCubic(seg(t, Timeline.CONTOURS_START + 0.035f * lvl, 1.15f))
                    if (p <= 0f) continue
                    val color = Silver.copy(alpha = (0.10f + 0.30f * lvl / 16f) * 0.9f)
                    val width = if (lvl % 4 == 0) 1.1f else 0.7f
                    val style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(if (p >= 1f) full[i] else partial(i, p), color, style = style)
                }
            }
        }
    }

    private fun partial(i: Int, frac: Float): Path {
        val pts = lines[i]
        val c = cumulative[i]
        val target = c[c.size - 1] * frac
        scratch.rewind()
        scratch.moveTo(pts[0], pts[1])
        var k = 1
        while (k < c.size && c[k] <= target) {
            scratch.lineTo(pts[2 * k], pts[2 * k + 1])
            k++
        }
        if (k < c.size) {
            val len = c[k] - c[k - 1]
            val f = if (len > 0f) (target - c[k - 1]) / len else 0f
            val ax = pts[2 * (k - 1)]
            val ay = pts[2 * (k - 1) + 1]
            scratch.lineTo(ax + (pts[2 * k] - ax) * f, ay + (pts[2 * k + 1] - ay) * f)
        }
        return scratch
    }
}

// ---------------------------------------------------------------------------------------------
// The living scene (see SceneFx): its shapes, the sky clip and the physics rope.
// ---------------------------------------------------------------------------------------------

private val fxPath = Path()

/** Draws a layer of [FxPrim]s from [SceneFx], in scene units. */
internal fun DrawScope.drawFx(prims: List<FxPrim>) {
    for (p in prims) {
        val color = Color(p.color)
        val v = p.pts
        when (p.kind) {
            0 -> drawCircle(color, v[2], Offset(v[0], v[1]))
            3 -> if (v[2] > 1f) drawCircle(
                Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(v[0], v[1]), radius = v[2]),
                radius = v[2],
                center = Offset(v[0], v[1]),
            )
            else -> {
                fxPath.rewind()
                fxPath.moveTo(v[0], v[1])
                var i = 2
                while (i < v.size) {
                    fxPath.lineTo(v[i], v[i + 1])
                    i += 2
                }
                if (p.kind == 1) {
                    fxPath.close()
                    drawPath(fxPath, color)
                } else {
                    drawPath(fxPath, color, style = Stroke(width = p.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** The open sky above the skyline: the back layer is clipped to it so nothing shows through the mountain. */
internal val SkyClip: Path by lazy { Path().apply { polyline(SceneFx.skyPolygon, close = true) } }

private val ropePath = Path()

/** A rope through the points of a [RopeSim]. */
internal fun DrawScope.drawRopePoints(pts: FloatArray) {
    ropePath.rewind()
    ropePath.moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) {
        // Through the midpoints, so the chain reads as one smooth curve.
        val mx = if (i + 2 < pts.size) (pts[i] + pts[i + 2]) / 2f else pts[i]
        val my = if (i + 2 < pts.size) (pts[i + 1] + pts[i + 3]) / 2f else pts[i + 1]
        ropePath.quadraticBezierTo(pts[i], pts[i + 1], mx, my)
        i += 2
    }
    drawPath(ropePath, RopeColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
