package com.godbless.lockin.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import com.godbless.lockin.R
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pictures inside the widgets, drawn as bitmaps: the dial ring, the whole Roped scene (sky, ranges, the mountain
 * you and your partner climb, and the rope between you) and the week's bars. Everything is drawn at a capped
 * density, because a widget's pictures travel to the home screen in one parcel and a large one can be refused.
 *
 * The looping effects on top (twinkling stars, drifting cloud, the comet round the ring, the shimmer on buttons)
 * are not drawn here: they are animated vectors on the home screen itself (res/drawable/w_fx_*, made by
 * tools/gen_widget_fx.py). What is drawn here is what holds still and carries the numbers.
 */
internal object WidgetArt {
    /** Pixels per dp for the bitmaps: the screen's, but at most 2.5. */
    fun scale(context: Context): Float = min(context.resources.displayMetrics.density, 2.5f)

    private fun font(context: Context, id: Int): Typeface? = runCatching { context.resources.getFont(id) }.getOrNull()

    /** Figtree at [weight] (a variable font, so the weight is set on the paint). */
    private fun Paint.figtree(context: Context, weight: Int) {
        typeface = font(context, R.font.figtree)
        runCatching { fontVariationSettings = "'wght' $weight" }
    }

    private fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    private fun Int.a(alpha: Int): Int = WidgetLook.withAlpha(this, alpha)
    private fun mixc(from: Int, to: Int, t: Float): Int = WidgetLook.mix(from, to, t)

    /** Rounds the picture's corners (anti-aliased) so it fits the card it is laid over. */
    private fun roundCorners(bmp: Bitmap, radiusPx: Float) {
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), radiusPx, radiusPx, p)
    }

    /** A circle's worth of soft light in an oval: [colors] from the middle out. */
    private fun Canvas.softOval(cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray?) {
        save()
        translate(cx, cy)
        scale(1f, ry / rx)
        drawCircle(0f, 0f, rx, paint().apply { shader = RadialGradient(0f, 0f, rx, colors, stops, Shader.TileMode.CLAMP) })
        restore()
    }

    private fun Canvas.polygon(pts: List<Pair<Float, Float>>, p: Paint) {
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        drawPath(path, p)
    }

    /** A little four-point star. */
    private fun Canvas.sparkle(x: Float, y: Float, r: Float, tint: Int) {
        val k = r * .14f
        val path = Path().apply {
            moveTo(x, y - r)
            quadTo(x + k, y - k, x + r, y)
            quadTo(x + k, y + k, x, y + r)
            quadTo(x - k, y + k, x - r, y)
            quadTo(x - k, y - k, x, y - r)
            close()
        }
        drawPath(path, paint().apply { color = tint })
    }

    // ---- The ring --------------------------------------------------------------------------------------------

    /**
     * The dial: a soft disc, a track with sixty tick marks inside it (they light up as the arc passes), and an arc
     * in a sweep of the accent, glowing, with a bright head. Three small dots mark a quarter, a half and three
     * quarters. All sizes are a share of [sizeDp] (the home screen's comet is drawn to the same proportions).
     */
    fun ring(context: Context, look: WidgetLook, sizeDp: Float, fraction: Float): Bitmap {
        val s = scale(context)
        val px = (sizeDp * s).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val th = px * 0.09f
        val r = (px - th) / 2f
        val cx = px / 2f
        val acc = look.accent
        val f = fraction.coerceIn(0f, 1f)

        // The soft disc under the figures.
        val rIn = r - th / 2f - 1.5f * s
        c.drawCircle(cx, cx, rIn, paint().apply {
            shader = RadialGradient(
                cx, cx, rIn,
                intArrayOf(look.raised.a(if (look.dark) 255 else 200), look.raised.a(120), look.raised.a(0)),
                floatArrayOf(0f, .75f, 1f), Shader.TileMode.CLAMP,
            )
        })
        // The track.
        val p = paint().apply { style = Paint.Style.STROKE; strokeWidth = th; strokeCap = Paint.Cap.ROUND }
        p.color = if (look.dark) look.track else mixc(look.track, look.mist, .18f)
        c.drawCircle(cx, cx, r, p)

        // Tick marks inside the track, on a big enough dial.
        if (sizeDp >= 96f) {
            val n = 60
            val r1 = r - th / 2f - 3.2f * s
            val r0 = r1 - 3.4f * s
            val tick = paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            for (i in 0 until n) {
                val a = -PI / 2.0 + 2.0 * PI * i / n
                val lit = i / n.toFloat() < f
                val big = i % 5 == 0
                tick.strokeWidth = (if (big) 1.1f else .8f) * s
                tick.color = if (lit) acc.a(235) else look.mist.a(if (big) 90 else 52)
                val inner = r0 - if (big) 1.2f * s else 0f
                c.drawLine(
                    cx + inner * cos(a).toFloat(), cx + inner * sin(a).toFloat(),
                    cx + r1 * cos(a).toFloat(), cx + r1 * sin(a).toFloat(), tick,
                )
            }
        }

        if (f > 0f) {
            val box = RectF(cx - r, cx - r, cx + r, cx + r)
            val sweep = if (f >= .999f) 360f else max(f * 360f, 1f)
            // A glow under the arc.
            c.save()
            c.rotate(-90f, cx, cx)
            val glow = paint().apply {
                style = Paint.Style.STROKE; strokeWidth = th; strokeCap = Paint.Cap.ROUND
                color = acc.a(150)
                maskFilter = BlurMaskFilter(th * 1.4f, BlurMaskFilter.Blur.NORMAL)
            }
            if (f >= .999f) c.drawCircle(cx, cx, r, glow) else c.drawArc(box, 0f, sweep, false, glow)
            val arc = paint().apply { style = Paint.Style.STROKE; strokeWidth = th; strokeCap = Paint.Cap.ROUND }
            if (f >= .999f) {
                arc.color = acc
                c.drawCircle(cx, cx, r, arc)
            } else {
                // From a dim tail to a bright head. The stops also keep the round cap at twelve o'clock (which
                // reaches back past the start of the sweep) the tail's colour, and the head's cap the head's.
                val head = mixc(acc, Color.WHITE, .30f)
                val tail = mixc(acc, look.surface, if (look.dark) .55f else .35f)
                val delta = (th / 2f) / (2f * PI.toFloat() * r)
                val back = max(f + delta * 1.2f, 1f - delta * 1.5f).coerceAtMost(.9995f)
                arc.shader = SweepGradient(
                    cx, cx,
                    intArrayOf(tail, acc, head, head, tail, tail),
                    floatArrayOf(0f, f * .98f, f, back, (back + .0004f).coerceAtMost(.9999f), 1f),
                )
                c.drawArc(box, 0f, sweep, false, arc)
            }
            c.restore()
            if (f in 0.03f..0.999f) {
                val a = Math.toRadians((f * 360f - 90f).toDouble())
                val hx = cx + r * cos(a).toFloat()
                val hy = cx + r * sin(a).toFloat()
                c.drawCircle(hx, hy, th * .95f, paint().apply {
                    color = mixc(acc, Color.WHITE, .30f).a(120)
                    maskFilter = BlurMaskFilter(th * .8f, BlurMaskFilter.Blur.NORMAL)
                })
                c.drawCircle(hx, hy, th * .36f, paint().apply { color = Color.WHITE.a(235) })
            }
        }
        // A quarter, a half and three quarters, on the part of the track still to climb.
        val dot = paint().apply { color = look.mist.a(150) }
        for (q in floatArrayOf(.25f, .5f, .75f)) {
            if (q > f + .02f) {
                val a = Math.toRadians((q * 360f - 90f).toDouble())
                c.drawCircle(cx + r * cos(a).toFloat(), cx + r * sin(a).toFloat(), th * .13f, dot)
            }
        }
        return bmp
    }

    // ---- The Roped scene -----------------------------------------------------------------------------------------

    // The mountain from LockIn's logo, in 0..1 (x right, y down). The same numbers as the design mock.
    private val Ridge = floatArrayOf(0f, 1f, .17f, .74f, .25f, .6f, .31f, .68f, .5f, .12f, .69f, .64f, .76f, .54f, .84f, .72f, 1f, 1f)
    private val LeftRoute = floatArrayOf(.05f, .95f, .17f, .74f, .25f, .6f, .31f, .68f, .5f, .12f)
    private val RightRoute = floatArrayOf(.95f, .95f, .84f, .72f, .76f, .54f, .69f, .64f, .5f, .12f)
    private val SnowCap = floatArrayOf(.42f, .34f, .5f, .12f, .585f, .36f, .545f, .31f, .5f, .4f, .465f, .3f)

    /**
     * The whole Roped card behind its text: a sky (a night with an aurora and stars, or a pale day with a sun), two
     * hazy ranges, the mountain from the logo with its lit and shadowed faces, a snow cap, pines and low mist, the
     * flag on the summit, and you and your partner each as far up as today's goal. A rope hangs between the two of
     * you. The picture fills the card and its corners are rounded to [radiusDp]. The mountain's foot is at the bottom
     * of the box the layout keeps free ([artTopDp] down and [artHeightDp] tall); on a short card its flag rises up
     * beside the header instead of waiting below it, so the mountain can stand tall.
     */
    fun scene(
        context: Context,
        look: WidgetLook,
        widthDp: Float,
        heightDp: Float,
        artTopDp: Float,
        artHeightDp: Float,
        you: Float,
        them: Float?,
        youInitial: String,
        themInitial: String,
        radiusDp: Float,
    ): Bitmap {
        val s = scale(context)
        val w = (widthDp * s).toInt().coerceAtLeast(40)
        val h = (heightDp * s).toInt().coerceAtLeast(40)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val W = w.toFloat()
        val H = h.toFloat()
        val dark = look.dark
        val acc = look.accent
        val white = Color.WHITE

        // Where the mountain stands (the box the layout keeps free): flag room above it, the climbers' badges at the sides.
        val artTop = artTopDp * s
        val artH = artHeightDp * s
        val padTop = 24f * s
        val mx0 = 16f * s
        val mw = W - (16f + 14f) * s
        val baseY = artTop + artH
        val mTop = if (heightDp < 200f) 6f * s else artTop
        val mh = baseY - mTop - padTop
        fun x(v: Float) = mx0 + v * mw
        fun y(v: Float) = mTop + padTop + v * mh
        val summitX = x(.5f)
        val summitY = y(.12f)

        // ---- Sky
        if (dark) {
            val top = mixc(look.surface, Color.BLACK, .30f)
            val mid = mixc(look.surface, acc, .13f)
            c.drawRect(0f, 0f, W, H, paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(top, mid, look.surface), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            })
            c.softOval(W / 2f, summitY + mh * .5f, W * .62f, mh * 1.25f, intArrayOf(acc.a(105), acc.a(34), acc.a(0)), floatArrayOf(0f, .55f, 1f))
        } else {
            val top = mixc(Color.WHITE, acc, .13f)
            c.drawRect(0f, 0f, W, H, paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(top, mixc(Color.WHITE, acc, .05f), Color.WHITE), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            })
            c.softOval(W / 2f, summitY + mh * .4f, W * .6f, mh * 1.2f, intArrayOf(acc.a(60), acc.a(0)), null)
        }
        if (dark) {
            // Two ribbons of aurora, soft and wide.
            val ribbon = paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            val ribbons = arrayOf(floatArrayOf(.30f, .16f, 20f, 52f), floatArrayOf(.42f, .24f, 14f, 36f))
            for ((k, rb) in ribbons.withIndex()) {
                val path = Path()
                for (i in 0..40) {
                    val t = i / 40f
                    val px = W * (-.05f + 1.1f * t)
                    val py = artTop - 2f * s + H * (rb[0] + (rb[1] - rb[0]) * t) * .5f + sin(t * 5f + k * 1.7f) * 7f * s
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                ribbon.strokeWidth = rb[2] * s
                ribbon.color = mixc(acc, white, .15f * k).a(rb[3].toInt())
                ribbon.maskFilter = BlurMaskFilter(16f * s, BlurMaskFilter.Blur.NORMAL)
                c.drawPath(path, ribbon)
            }
            // Stars (the ones that twinkle are on the home screen, over this).
            val rnd = Random(7)
            val star = paint()
            repeat(52) {
                val sx = rnd.nextFloat() * W
                val sy = rnd.nextFloat() * (artTop + artH * .85f)
                star.color = white.a((70 + rnd.nextFloat() * 150f).toInt())
                c.drawCircle(sx, sy, (.35f + rnd.nextFloat() * .65f) * s, star)
            }
        } else {
            // A soft sun.
            val sunX = W * .82f
            val sunY = 46f * s
            c.drawCircle(sunX, sunY, 20f * s, paint().apply {
                shader = RadialGradient(sunX, sunY, 20f * s, intArrayOf(white.a(235), white.a(0)), null, Shader.TileMode.CLAMP)
            })
        }

        // ---- Two hazy ranges, full width, fading into the card
        fun range(seed: Long, base: Float, amp: Float, step: Float, colTop: Int, colBot: Int) {
            val r = Random(seed)
            val pts = ArrayList<Pair<Float, Float>>()
            pts.add(-6f * s to H)
            var px = -6f * s
            while (px < W + 12f * s) {
                val drop = amp * (.35f + .65f * r.nextFloat()) * (if (r.nextFloat() > .3f) 1f else .4f)
                pts.add(px to base - drop)
                px += step * s * (.7f + r.nextFloat() * .6f)
            }
            pts.add(W + 6f * s to base - amp * .3f)
            pts.add(W + 6f * s to H)
            c.polygon(pts, paint().apply {
                shader = LinearGradient(0f, base - amp, 0f, base + 30f * s, colTop, colBot, Shader.TileMode.CLAMP)
            })
        }
        val farTint = if (dark) look.surface else Color.WHITE
        range(3, baseY - 4f * s, artH * .78f, 22f, mixc(farTint, acc, .30f).a(235), look.surface.a(0))
        range(11, baseY + 2f * s, artH * .52f, 17f, mixc(farTint, acc, if (dark) .17f else .20f).a(255), look.surface.a(0))

        // ---- The mountain
        val ridge = ArrayList<Pair<Float, Float>>()
        for (i in Ridge.indices step 2) ridge.add(x(Ridge[i]) to y(Ridge[i + 1]))
        val litTop = if (dark) mixc(look.hill, white, .30f) else mixc(look.hill, white, .35f)
        val shadeTop = mixc(look.hill, Color.BLACK, if (dark) .35f else .12f)
        val foot = look.surface.a(0)
        val bottom = baseY + 6f * s
        // The line between the lit and the shadowed face, not quite straight.
        val split = listOf(
            summitX - .02f * mw to y(.4f),
            summitX + .01f * mw to y(.68f),
            summitX - .025f * mw to bottom,
        )
        val left = Path().apply {
            moveTo(ridge[0].first, ridge[0].second)
            for (i in 1..4) lineTo(ridge[i].first, ridge[i].second)
            for (q in split) lineTo(q.first, q.second)
            lineTo(ridge[0].first, bottom)
            close()
        }
        val right = Path().apply {
            moveTo(ridge[4].first, ridge[4].second)
            for (i in 5..8) lineTo(ridge[i].first, ridge[i].second)
            lineTo(ridge[8].first, bottom)
            for (q in split.reversed()) lineTo(q.first, q.second)
            close()
        }
        c.drawPath(left, paint().apply {
            shader = LinearGradient(0f, summitY, 0f, baseY + 10f * s, intArrayOf(litTop, look.hill.a(190), foot), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawPath(right, paint().apply {
            shader = LinearGradient(0f, summitY, 0f, baseY + 10f * s, intArrayOf(shadeTop, shadeTop.a(170), foot), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        })
        // Light along the lit ridge, and a few creases.
        val litRidge = Path().apply {
            moveTo(ridge[0].first, ridge[0].second)
            for (i in 1..4) lineTo(ridge[i].first, ridge[i].second)
        }
        c.drawPath(litRidge, paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.1f * s; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = (if (dark) white else mixc(acc, white, .5f)).a(if (dark) 110 else 150)
        })
        val crease = paint().apply {
            style = Paint.Style.STROKE; strokeWidth = .8f * s; strokeCap = Paint.Cap.ROUND
            color = (if (dark) Color.BLACK else Color.rgb(60, 70, 100)).a(55)
        }
        for (cr in arrayOf(floatArrayOf(.5f, .12f, .55f), floatArrayOf(.47f, .2f, .5f), floatArrayOf(.53f, .2f, .6f))) {
            val fx = cr[0]
            val toX = x(fx + (fx - .5f) * 2f - .02f * (if (fx >= .5f) 1f else -1f))
            c.drawLine(x(fx), y(cr[1]), toX, y(cr[1]) + cr[2] * mh * .8f, crease)
        }
        // The snow cap, brighter on the lit side.
        val cap = ArrayList<Pair<Float, Float>>()
        for (i in SnowCap.indices step 2) cap.add(x(SnowCap[i]) to y(SnowCap[i + 1]))
        val snowShade = mixc(look.snowcap, if (dark) Color.rgb(120, 130, 160) else Color.rgb(150, 160, 190), .38f)
        c.polygon(cap, paint().apply { color = snowShade.a(245) })
        c.polygon(listOf(cap[0], cap[1], cap[4], cap[5]), paint().apply { color = look.snowcap.a(250) })

        // Low mist at the foot (and cloud drifting over it is on the home screen).
        for (m in arrayOf(floatArrayOf(.2f, .5f, 38f), floatArrayOf(.8f, .5f, 34f), floatArrayOf(.5f, .7f, 26f))) {
            val a = (m[2] + if (dark) 0f else 40f).toInt()
            c.softOval(x(m[0]), baseY - 2f * s, mw * m[1] * .5f, 8f * s, intArrayOf(white.a(a), white.a(0)), null)
        }
        // Pines at either foot.
        val rp = Random(5)
        val pine = paint().apply { color = if (dark) mixc(look.surface, Color.BLACK, .55f) else mixc(Color.WHITE, acc, .45f).a(200) }
        for (side in 0..1) {
            for (i in 0 until 9) {
                val px = if (side == 0) (14f + i * 8f + rp.nextFloat() * 5f) * s else W - (14f + i * 8f + rp.nextFloat() * 5f) * s
                val ph = (9f + rp.nextFloat() * 8f - i * .5f) * s
                c.polygon(listOf(px to baseY + s, px - ph * .26f to baseY + s, px to baseY - ph, px + ph * .26f to baseY + s), pine)
            }
        }
        // A calm surface under the figures and buttons.
        val scrim = baseY - 6f * s
        c.drawRect(0f, scrim, W, H, paint().apply {
            shader = LinearGradient(0f, scrim, 0f, min(H, scrim + 34f * s), look.surface.a(0), look.surface.a(235), Shader.TileMode.CLAMP)
        })

        // ---- Routes and climbers
        class Climber(val x: Float, val y: Float, val colour: Int, val ink: Int, val letter: String, val done: Float)

        fun climb(route: FloatArray, done: Float, colour: Int, ink: Int, letter: String): Climber {
            val full = Path()
            for (i in route.indices step 2) if (i == 0) full.moveTo(x(route[0]), y(route[1])) else full.lineTo(x(route[i]), y(route[i + 1]))
            val pm = PathMeasure(full, false)
            val len = pm.length
            // Not quite on the summit, so two climbers who both made it stand either side of the flag.
            val at = done.coerceIn(0f, 1f) * 0.94f * len
            c.drawPath(full, paint().apply {
                color = look.mist.a(190)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * s
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(0.1f, 4.6f * s), 0f)
            })
            val pos = FloatArray(2)
            val wp = paint().apply { color = look.mist.a(160) }
            for (q in floatArrayOf(.25f, .5f, .75f)) {
                pm.getPosTan(q * .94f * len, pos, null)
                c.drawCircle(pos[0], pos[1], 1.6f * s, wp)
            }
            val climbed = Path()
            pm.getSegment(0f, max(at, 0.01f), climbed, true)
            c.drawPath(climbed, paint().apply {
                color = colour.a(70)
                style = Paint.Style.STROKE
                strokeWidth = 5.5f * s
                strokeCap = Paint.Cap.ROUND
                maskFilter = BlurMaskFilter(4.5f * s, BlurMaskFilter.Blur.NORMAL)
            })
            c.drawPath(climbed, paint().apply {
                color = colour
                style = Paint.Style.STROKE
                strokeWidth = 2.6f * s
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
            pm.getPosTan(at, pos, null)
            return Climber(pos[0], pos[1], colour, ink, letter, done)
        }

        val climbers = ArrayList<Climber>()
        if (them != null) climbers.add(climb(RightRoute, them, look.frost, look.surface, themInitial))
        val me = climb(LeftRoute, you, acc, look.onAccent, youInitial)
        climbers.add(me)

        // The rope hangs between the two of you.
        if (them != null) {
            val other = climbers.first()
            val dist = hypot(me.x - other.x, me.y - other.y)
            val sag = min(20f * s, dist * .13f)
            val mxp = (me.x + other.x) / 2f
            val myp = (me.y + other.y) / 2f + sag
            val rope = Path().apply { moveTo(me.x, me.y); quadTo(mxp, myp, other.x, other.y) }
            // Its shadow sits a little lower.
            c.save()
            c.translate(0f, 1.2f * s)
            c.drawPath(rope, paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 1.4f * s; strokeCap = Paint.Cap.ROUND
                color = Color.BLACK.a(if (dark) 70 else 40)
                maskFilter = BlurMaskFilter(1.4f * s, BlurMaskFilter.Blur.NORMAL)
            })
            c.restore()
            c.drawPath(rope, paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 1.3f * s; strokeCap = Paint.Cap.ROUND
                color = look.frost.a(215)
            })
        }

        // ---- The flag on the summit, with a halo and, once someone has made it, a few sparkles
        c.drawCircle(summitX, summitY - 12f * s, 15f * s, paint().apply {
            shader = RadialGradient(summitX, summitY - 12f * s, 15f * s, intArrayOf(acc.a(110), acc.a(0)), null, Shader.TileMode.CLAMP)
        })
        c.drawLine(summitX, summitY, summitX, summitY - 20f * s, paint().apply {
            color = look.frost; style = Paint.Style.STROKE; strokeWidth = 1.6f * s; strokeCap = Paint.Cap.ROUND
        })
        val flag = Path().apply {
            moveTo(summitX + 0.8f * s, summitY - 20f * s)
            quadTo(summitX + 7.8f * s, summitY - 22f * s, summitX + 13.8f * s, summitY - 19f * s)
            quadTo(summitX + 9.8f * s, summitY - 16f * s, summitX + 12.8f * s, summitY - 12f * s)
            quadTo(summitX + 6.8f * s, summitY - 14f * s, summitX + 0.8f * s, summitY - 12f * s)
            close()
        }
        c.drawPath(flag, paint().apply { color = acc })
        if (you >= 1f || (them ?: 0f) >= 1f) {
            val tint = if (dark) white else acc
            c.sparkle(summitX - 15f * s, summitY - 24f * s, 4.2f * s, tint.a(230))
            c.sparkle(summitX + 24f * s, summitY - 30f * s, 3.2f * s, tint.a(200))
            c.sparkle(summitX + 6f * s, summitY - 36f * s, 2.6f * s, tint.a(170))
            c.sparkle(summitX - 28f * s, summitY - 8f * s, 2.4f * s, tint.a(150))
        }

        // ---- The climbers, on top of everything, each with their share of the goal
        val letter = paint().apply {
            figtree(context, 700)
            textSize = 10.5f * s
            textAlign = Paint.Align.CENTER
        }
        val pctText = paint().apply {
            figtree(context, 700)
            textSize = 9f * s
            textAlign = Paint.Align.CENTER
        }
        for (cl in climbers) {
            c.drawCircle(cl.x, cl.y + s, 12.5f * s, paint().apply {
                color = Color.BLACK.a(60)
                maskFilter = BlurMaskFilter(3.5f * s, BlurMaskFilter.Blur.NORMAL)
            })
            c.drawCircle(cl.x, cl.y, 11.5f * s, paint().apply { color = look.surface })
            c.drawCircle(cl.x, cl.y, 9.5f * s, paint().apply { color = cl.colour })
            letter.color = cl.ink
            c.drawText(cl.letter, cl.x, cl.y - (letter.descent() + letter.ascent()) / 2f, letter)
            val pct = "${(min(cl.done, 9.99f) * 100f).toInt()}%"
            val tw = pctText.measureText(pct) + 9f * s
            val py = if (cl.y > mTop + 30f * s) cl.y - 24f * s else cl.y + 16f * s
            c.drawRoundRect(RectF(cl.x - tw / 2f, py - 7.5f * s, cl.x + tw / 2f, py + 7.5f * s), 7.5f * s, 7.5f * s, paint().apply { color = look.surface.a(225) })
            pctText.color = if (dark) cl.colour else look.snow
            c.drawText(pct, cl.x, py - (pctText.descent() + pctText.ascent()) / 2f, pctText)
        }

        roundCorners(bmp, radiusDp * s)
        return bmp
    }

    // ---- The week ----------------------------------------------------------------------------------------------

    /**
     * Monday to Sunday as glossy rounded bars: today's in the accent with a glow, past days grey, days to come as faint
     * stubs. The daily goal is a dashed line with a small tag, a dot sits over each day that reached it, today's
     * time rides above its bar, and the day letters are underneath (today's in a pill).
     */
    fun week(
        context: Context,
        look: WidgetLook,
        widthDp: Float,
        heightDp: Float,
        hours: FloatArray,
        todayIndex: Int,
        goalHours: Float,
        goalTag: String,
        todayLabel: String,
    ): Bitmap {
        val s = scale(context)
        val w = (widthDp * s).toInt().coerceAtLeast(40)
        val h = (heightDp * s).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val n = 7
        val acc = look.accent
        val gap = min(10f * s, w * 0.03f)
        val bw = (w - gap * (n - 1)) / n
        val top = 16f * s
        val label = 20f * s
        val ch = h - label - top
        val most = max(goalHours * 1.15f, hours.maxOrNull() ?: 0f).coerceAtLeast(0.1f)

        // Two faint guides.
        val guide = paint().apply { color = look.mist.a(36); strokeWidth = .6f * s }
        for (g in floatArrayOf(.33f, .66f)) {
            val gy = top + ch - ch * g
            c.drawLine(0f, gy, w.toFloat(), gy, guide)
        }
        val text = paint().apply { textSize = 10.5f * s; textAlign = Paint.Align.CENTER }
        val letters = arrayOf("M", "T", "W", "T", "F", "S", "S")
        val bar = paint()
        val white = Color.WHITE
        for (i in 0 until n) {
            val v = hours.getOrElse(i) { 0f }
            val bx = i * (bw + gap)
            val bh = max(if (v > 0f) 6f * s else 4f * s, ch * v / most)
            val yTop = top + ch - bh
            val rr = min(bw / 2f, 7f * s)
            val rect = RectF(bx, yTop, bx + bw, top + ch)
            when {
                i == todayIndex -> {
                    // A glow under it, then the bar from a bright top to a deeper foot, with a bright strip down its left.
                    c.drawRoundRect(RectF(bx - s, yTop + 4f * s, bx + bw + s, top + ch + 4f * s), rr, rr, paint().apply {
                        color = acc.a(150)
                        maskFilter = BlurMaskFilter(9f * s, BlurMaskFilter.Blur.NORMAL)
                    })
                    bar.shader = LinearGradient(
                        0f, yTop, 0f, top + ch,
                        intArrayOf(mixc(acc, white, .28f), acc, mixc(acc, look.surface, .35f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP,
                    )
                    c.drawRoundRect(rect, rr, rr, bar)
                    bar.shader = null
                    val strip = RectF(bx + 2f * s, yTop + 2f * s, bx + 2f * s + bw * .32f, max(yTop + 5f * s, top + ch - 6f * s))
                    c.drawRoundRect(strip, min(bw * .16f, 3f * s), min(bw * .16f, 3f * s), paint().apply { color = white.a(if (look.dark) 48 else 70) })
                }
                i > todayIndex -> {
                    bar.color = look.track
                    c.drawRoundRect(RectF(bx, top + ch - 4f * s, bx + bw, top + ch), 2f * s, 2f * s, bar)
                }
                else -> {
                    bar.shader = LinearGradient(
                        0f, yTop, 0f, top + ch,
                        intArrayOf(if (look.dark) mixc(look.bar, white, .12f) else look.bar, mixc(look.bar, look.surface, .35f)), null, Shader.TileMode.CLAMP,
                    )
                    c.drawRoundRect(rect, rr, rr, bar)
                    bar.shader = null
                    c.drawRoundRect(RectF(bx + 2f * s, yTop + 2f * s, bx + 2f * s + bw * .28f, max(yTop + 5f * s, top + ch - 6f * s)), min(bw * .14f, 3f * s), min(bw * .14f, 3f * s),
                        paint().apply { color = white.a(if (look.dark) 22 else 40) })
                }
            }
            val cx = bx + bw / 2f
            if (v >= goalHours && i <= todayIndex && goalHours > 0f) {
                c.drawCircle(cx, yTop - 6.5f * s, 3.2f * s, paint().apply {
                    color = acc.a(90)
                    maskFilter = BlurMaskFilter(2.6f * s, BlurMaskFilter.Blur.NORMAL)
                })
                c.drawCircle(cx, yTop - 6.5f * s, 2.1f * s, paint().apply { color = acc })
            }
            // The day letter (today's in a pill).
            if (i == todayIndex) {
                c.drawRoundRect(RectF(cx - 9f * s, h - 15.5f * s, cx + 9f * s, h - 1.5f * s), 7f * s, 7f * s, paint().apply { color = acc.a(48) })
                text.figtree(context, 700)
                text.color = look.snow
            } else {
                text.figtree(context, 500)
                text.color = look.mist
            }
            c.drawText(letters[i], cx, h - 5f * s, text)
            // Today's time, in a small pill above its bar.
            if (i == todayIndex && v > 0f && todayLabel.isNotEmpty()) {
                val tp = paint().apply { figtree(context, 700); textSize = 9.5f * s; textAlign = Paint.Align.CENTER; color = look.snow }
                val tw = tp.measureText(todayLabel) + 10f * s
                val above = yTop - 15f * s
                val ly = if (above > 2f * s) above else yTop + 4f * s
                c.drawRoundRect(RectF(cx - tw / 2f, ly - 8f * s, cx + tw / 2f, ly + 8f * s), 8f * s, 8f * s, paint().apply { color = look.surface.a(235) })
                c.drawText(todayLabel, cx, ly - (tp.descent() + tp.ascent()) / 2f, tp)
            }
        }
        // The goal: a dashed line with its tag at the right end.
        if (goalHours > 0f) {
            val gy = top + ch - ch * goalHours / most
            c.drawLine(0f, gy, w.toFloat(), gy, paint().apply {
                color = look.mist.a(190); strokeWidth = 1.1f * s
                pathEffect = DashPathEffect(floatArrayOf(3.2f * s, 3.6f * s), 0f)
            })
            val gp = paint().apply { figtree(context, 600); textSize = 9f * s; textAlign = Paint.Align.CENTER; color = look.body }
            val tw = gp.measureText(goalTag) + 10f * s
            c.drawRoundRect(RectF(w - tw, gy - 7.5f * s, w.toFloat(), gy + 7.5f * s), 7.5f * s, 7.5f * s, paint().apply { color = look.raised })
            c.drawText(goalTag, w - tw / 2f, gy - (gp.descent() + gp.ascent()) / 2f, gp)
        }
        return bmp
    }
}
