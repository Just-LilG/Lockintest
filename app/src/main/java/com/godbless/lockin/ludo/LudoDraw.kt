package com.godbless.lockin.ludo

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The four colours, by seat: red, green, yellow, blue. */
internal object LudoPalette {
    val base = listOf(Color(0xFFFF4D5E), Color(0xFF2FD47A), Color(0xFFFFC93C), Color(0xFF3D8BFF))
    val deep = listOf(Color(0xFFB8162E), Color(0xFF0F7D45), Color(0xFFC98306), Color(0xFF1A4FBF))
    val light = listOf(Color(0xFFFFA3AC), Color(0xFF9AF2C2), Color(0xFFFFE7A3), Color(0xFFA8CBFF))
    val names = listOf("Red", "Green", "Yellow", "Blue")
    fun c(seat: Int) = base[seat.coerceIn(0, 3)]
    fun d(seat: Int) = deep[seat.coerceIn(0, 3)]
    fun l(seat: Int) = light[seat.coerceIn(0, 3)]
}

/** Board point (cell units, canonical) to pixels for a viewer turned [turns] quarter turns, with cells [cell] px wide. */
internal fun px(p: P, turns: Int, cell: Float): Offset = LudoGeo.view(p, turns).let { Offset(it.x * cell, it.y * cell) }

private fun rectOf(a: P, b: P, turns: Int, cell: Float): Rect {
    val p = px(a, turns, cell)
    val q = px(b, turns, cell)
    return Rect(min(p.x, q.x), min(p.y, q.y), max(p.x, q.x), max(p.y, q.y))
}

private fun starPath(c: Offset, r: Float, inner: Float = 0.45f): Path = Path().apply {
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * inner
        val x = c.x + (cos(a) * rr).toFloat()
        val y = c.y + (sin(a) * rr).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * The board itself (everything that doesn't move). [present] says which seats are in the game; empty corners are drawn
 * dimmed. [dark] follows the app's theme.
 */
internal fun DrawScope.drawLudoBoard(cell: Float, turns: Int, dark: Boolean, present: BooleanArray) {
    val w = cell * LudoGeo.N
    val boardShape = CornerRadius(cell * 0.9f)
    val surface = if (dark) Color(0xFF15181D) else Color(0xFFF4F1EA)
    val square = if (dark) Color(0xFF242930) else Color(0xFFFFFFFF)
    val squareEdge = if (dark) Color(0xFF303741) else Color(0xFFE3DED3)
    val ink = if (dark) Color(0xFF0D0F12) else Color(0xFF2A2C31)

    // The board: a soft slab with a lit top edge.
    drawRoundRect(
        Brush.verticalGradient(listOf(lerp(surface, Color.White, if (dark) 0.06f else 0.4f), surface)),
        size = Size(w, w), cornerRadius = boardShape,
    )
    drawRoundRect(Color.White.copy(alpha = if (dark) 0.07f else 0.6f), size = Size(w, w), cornerRadius = boardShape, style = Stroke(cell * 0.06f))

    // Yards.
    for (s in 0..3) {
        val r = rectOf(LudoGeo.rot(P(0.25f, 0.25f), s), LudoGeo.rot(P(5.75f, 5.75f), s), turns, cell)
        val on = present[s]
        val top = if (on) LudoPalette.c(s) else lerp(LudoPalette.c(s), surface, 0.72f)
        val bottom = if (on) LudoPalette.d(s) else lerp(LudoPalette.d(s), surface, 0.72f)
        drawRoundRect(Brush.linearGradient(listOf(lerp(top, Color.White, 0.12f), bottom), r.topLeft, r.bottomRight), r.topLeft, r.size, CornerRadius(cell * 0.7f))
        // Gloss across the top.
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (on) 0.28f else 0.08f), Color.Transparent), r.top, r.top + r.height * 0.55f),
            r.topLeft, Size(r.width, r.height * 0.55f), CornerRadius(cell * 0.7f),
        )
        val inner = rectOf(LudoGeo.rot(P(1.05f, 1.05f), s), LudoGeo.rot(P(4.95f, 4.95f), s), turns, cell)
        drawRoundRect(ink.copy(alpha = if (dark) 0.78f else 0.9f), inner.topLeft, inner.size, CornerRadius(cell * 0.55f))
        drawRoundRect(Color.White.copy(alpha = 0.10f), inner.topLeft, inner.size, CornerRadius(cell * 0.55f), style = Stroke(cell * 0.05f))
        for (k in 0..3) {
            val c = px(LudoGeo.point(s, -1, k)!!, turns, cell)
            drawCircle(Color.Black.copy(alpha = 0.35f), cell * 0.62f, c + Offset(0f, cell * 0.05f))
            drawCircle(Brush.radialGradient(listOf(lerp(bottom, Color.Black, 0.35f), bottom), c, cell * 0.6f), cell * 0.58f, c)
            drawCircle(top.copy(alpha = if (on) 0.9f else 0.4f), cell * 0.58f, c, style = Stroke(cell * 0.07f))
        }
    }

    // Track squares.
    for (a in 0 until 52) {
        val c = px(LudoGeo.square(a), turns, cell)
        val seat = a / 13
        val tl = c - Offset(cell * 0.46f, cell * 0.46f)
        val sz = Size(cell * 0.92f, cell * 0.92f)
        val start = LudoGeo.isStart(a)
        val fill = if (start) LudoPalette.c(seat).let { if (present[seat]) it else lerp(it, square, 0.6f) } else square
        drawRoundRect(fill, tl, sz, CornerRadius(cell * 0.18f))
        drawRoundRect(if (start) Color.White.copy(alpha = 0.35f) else squareEdge, tl, sz, CornerRadius(cell * 0.18f), style = Stroke(cell * 0.035f))
        if (LudoGeo.isStar(a)) {
            drawPath(starPath(c, cell * 0.3f), (if (dark) Color.White else Color.Black).copy(alpha = 0.14f))
            drawPath(starPath(c, cell * 0.3f), (if (dark) Color.White else Color.Black).copy(alpha = 0.3f), style = Stroke(cell * 0.04f, join = StrokeJoin.Round))
        }
        if (start) {
            // An arrow showing which way the colour sets off.
            val n = px(LudoGeo.square((a + 1) % 52), turns, cell)
            val d = (n - c).let { it / it.getDistance() }
            val side = Offset(-d.y, d.x)
            val tip = c + d * (cell * 0.24f)
            val back = c - d * (cell * 0.16f)
            drawPath(
                Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo((back + side * (cell * 0.2f)).x, (back + side * (cell * 0.2f)).y)
                    lineTo((back - side * (cell * 0.2f)).x, (back - side * (cell * 0.2f)).y)
                    close()
                },
                Color.White.copy(alpha = 0.92f),
            )
        }
    }

    // Lanes, brightening towards home.
    for (s in 0..3) {
        for (p in 51..55) {
            val c = px(LudoGeo.laneSquare(s, p), turns, cell)
            val tl = c - Offset(cell * 0.46f, cell * 0.46f)
            val f = (p - 51) / 4f
            val col = lerp(LudoPalette.c(s), LudoPalette.l(s), f * 0.6f).let { if (present[s]) it else lerp(it, square, 0.65f) }
            drawRoundRect(col, tl, Size(cell * 0.92f, cell * 0.92f), CornerRadius(cell * 0.18f))
            drawRoundRect(Color.White.copy(alpha = 0.25f), tl, Size(cell * 0.92f, cell * 0.92f * 0.45f), CornerRadius(cell * 0.18f))
        }
        // The square before the lane has a little chevron pointing in.
        val entry = px(LudoGeo.point(s, 50, 0)!!, turns, cell)
        val into = px(LudoGeo.laneSquare(s, 51), turns, cell)
        val d = (into - entry).let { it / it.getDistance() }
        val side = Offset(-d.y, d.x)
        val m = entry + d * (cell * 0.08f)
        drawPath(
            Path().apply {
                moveTo((m - d * (cell * 0.14f) + side * (cell * 0.18f)).x, (m - d * (cell * 0.14f) + side * (cell * 0.18f)).y)
                lineTo((m + d * (cell * 0.12f)).x, (m + d * (cell * 0.12f)).y)
                lineTo((m - d * (cell * 0.14f) - side * (cell * 0.18f)).x, (m - d * (cell * 0.14f) - side * (cell * 0.18f)).y)
            },
            LudoPalette.c(s).copy(alpha = if (present[s]) 0.95f else 0.35f),
            style = Stroke(cell * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // Home: four triangles meeting in the middle, each lit from the centre.
    val mid = px(P(7.5f, 7.5f), turns, cell)
    for (s in 0..3) {
        val a = px(LudoGeo.rot(P(6f, 6f), s), turns, cell)
        val b = px(LudoGeo.rot(P(6f, 9f), s), turns, cell)
        val col = if (present[s]) LudoPalette.c(s) else lerp(LudoPalette.c(s), surface, 0.6f)
        val tri = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(mid.x, mid.y); close() }
        drawPath(tri, Brush.linearGradient(listOf(lerp(col, Color.Black, 0.18f), lerp(col, Color.White, 0.25f)), (a + b) / 2f, mid))
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0f)), mid, cell * 0.9f), cell * 0.9f, mid)
    drawPath(starPath(mid, cell * 0.36f), Color.White)
}

/**
 * A glossy pawn standing at [ground], raised by [lift] px and squashed by [squash] (0 = none, + = flattened). [scale]
 * shrinks it when it shares a square. [dim] greys it (a player who left).
 */
internal fun DrawScope.drawPawn(seat: Int, ground: Offset, cell: Float, lift: Float = 0f, squash: Float = 0f, scale: Float = 1f, spin: Float = 0f, dim: Boolean = false) {
    val r = cell * 0.36f * scale
    val base = if (dim) Color(0xFF8A8F98) else LudoPalette.c(seat)
    val deep = if (dim) Color(0xFF555A62) else LudoPalette.d(seat)
    val hi = if (dim) Color(0xFFC9CDD3) else LudoPalette.l(seat)
    // The shadow stays on the board and shrinks as the pawn rises.
    val up = (lift / (cell * 1.2f)).coerceIn(0f, 1f)
    drawOval(
        Color.Black.copy(alpha = 0.38f * (1f - up * 0.6f)),
        topLeft = ground + Offset(-r * (0.95f - up * 0.35f), r * 0.12f),
        size = Size(r * 2f * (0.95f - up * 0.35f), r * 0.62f * (1f - up * 0.3f)),
    )
    val g = ground - Offset(0f, lift)
    scale(1f + squash * 0.5f, 1f - squash, pivot = g + Offset(0f, r * 0.35f)) {
        rotate(spin, pivot = g - Offset(0f, r * 0.3f)) {
            // Base disc.
            drawOval(deep, topLeft = g + Offset(-r * 0.92f, r * 0.02f), size = Size(r * 1.84f, r * 0.62f))
            drawOval(Color.White.copy(alpha = 0.85f), topLeft = g + Offset(-r * 0.92f, r * 0.02f), size = Size(r * 1.84f, r * 0.62f), style = Stroke(r * 0.1f))
            // Body: a rounded cone.
            val body = Path().apply {
                moveTo(g.x - r * 0.78f, g.y + r * 0.3f)
                cubicTo(g.x - r * 0.7f, g.y - r * 0.1f, g.x - r * 0.3f, g.y - r * 0.35f, g.x - r * 0.28f, g.y - r * 0.62f)
                lineTo(g.x + r * 0.28f, g.y - r * 0.62f)
                cubicTo(g.x + r * 0.3f, g.y - r * 0.35f, g.x + r * 0.7f, g.y - r * 0.1f, g.x + r * 0.78f, g.y + r * 0.3f)
                close()
            }
            drawPath(body, Brush.horizontalGradient(listOf(hi, base, deep), g.x - r * 0.8f, g.x + r * 0.8f))
            // Collar.
            drawOval(deep, topLeft = g + Offset(-r * 0.42f, -r * 0.72f), size = Size(r * 0.84f, r * 0.22f))
            // Head.
            val hc = g - Offset(0f, r * 1.02f)
            drawCircle(Brush.radialGradient(listOf(Color.White, hi, base, deep), hc - Offset(r * 0.18f, r * 0.2f), r * 0.62f), r * 0.46f, hc)
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.11f, hc - Offset(r * 0.17f, r * 0.18f))
        }
    }
}

/** A ring that says "this one can move", breathing with [pulse] (0..1). */
internal fun DrawScope.drawMovableRing(seat: Int, ground: Offset, cell: Float, pulse: Float) {
    val r = cell * (0.48f + 0.08f * pulse)
    drawCircle(LudoPalette.c(seat).copy(alpha = 0.25f + 0.2f * pulse), r * 1.15f, ground + Offset(0f, cell * 0.12f))
    drawCircle(Color.White.copy(alpha = 0.9f), r, ground + Offset(0f, cell * 0.12f), style = Stroke(cell * 0.06f))
}

/** Where a token would land: a soft ghost with a dotted outline. */
internal fun DrawScope.drawGhost(seat: Int, at: Offset, cell: Float, pulse: Float) {
    drawCircle(LudoPalette.c(seat).copy(alpha = 0.22f + 0.12f * pulse), cell * 0.38f, at)
    val steps = 12
    for (i in 0 until steps) {
        val a = i * 2 * PI / steps + pulse * 0.6
        drawCircle(LudoPalette.c(seat), cell * 0.045f, at + Offset((cos(a) * cell * 0.38f).toFloat(), (sin(a) * cell * 0.38f).toFloat()))
    }
}

// ---- The die --------------------------------------------------------------------------------------------------------

private class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun times(f: Float) = V3(x * f, y * f, z * f)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
}

private fun V3.rx(deg: Float): V3 { val a = deg * PI.toFloat() / 180f; val c = cos(a); val s = sin(a); return V3(x, y * c - z * s, y * s + z * c) }
private fun V3.ry(deg: Float): V3 { val a = deg * PI.toFloat() / 180f; val c = cos(a); val s = sin(a); return V3(x * c + z * s, y, -x * s + z * c) }
private fun V3.rz(deg: Float): V3 { val a = deg * PI.toFloat() / 180f; val c = cos(a); val s = sin(a); return V3(x * c - y * s, x * s + y * c, z) }

private class Face(val value: Int, val n: V3, val u: V3, val v: V3)

private const val H = 0.5f
private val FACES = listOf(
    Face(1, V3(0f, 0f, 1f), V3(1f, 0f, 0f), V3(0f, 1f, 0f)),
    Face(6, V3(0f, 0f, -1f), V3(-1f, 0f, 0f), V3(0f, 1f, 0f)),
    Face(2, V3(1f, 0f, 0f), V3(0f, 0f, -1f), V3(0f, 1f, 0f)),
    Face(5, V3(-1f, 0f, 0f), V3(0f, 0f, 1f), V3(0f, 1f, 0f)),
    Face(3, V3(0f, 1f, 0f), V3(1f, 0f, 0f), V3(0f, 0f, -1f)),
    Face(4, V3(0f, -1f, 0f), V3(1f, 0f, 0f), V3(0f, 0f, 1f)),
)

private val PIPS = mapOf(
    1 to listOf(0 to 0),
    2 to listOf(-1 to -1, 1 to 1),
    3 to listOf(-1 to -1, 0 to 0, 1 to 1),
    4 to listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1),
    5 to listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1),
    6 to listOf(-1 to -1, -1 to 0, -1 to 1, 1 to -1, 1 to 0, 1 to 1),
)

/** The die's turn (x, y in degrees) that shows [value] to the viewer. */
internal fun dieFace(value: Int): Pair<Float, Float> = when (value) {
    6 -> 0f to 180f
    2 -> 0f to -90f
    5 -> 0f to 90f
    3 -> 90f to 0f
    4 -> -90f to 0f
    else -> 0f to 0f
}

private val LIGHT = V3(-0.42f, -0.62f, 0.66f).let { val l = sqrt(it.dot(it)); it * (1f / l) }

/**
 * A real 3D die: a rounded ivory cube turned by [rx], [ry], [rz] (degrees), seen slightly from above and to the side,
 * lit from the top left, with its 1 in [accent]. [size] is the cube's edge in px; [lift] raises it off its shadow.
 */
internal fun DrawScope.drawDie(center: Offset, size: Float, rx: Float, ry: Float, rz: Float, accent: Color, lift: Float = 0f, glow: Float = 0f) {
    val up = (lift / (size * 1.5f)).coerceIn(0f, 1f)
    drawOval(
        Color.Black.copy(alpha = 0.32f * (1f - up * 0.5f)),
        topLeft = center + Offset(-size * 0.6f * (1f - up * 0.3f), size * 0.42f),
        size = Size(size * 1.2f * (1f - up * 0.3f), size * 0.32f),
    )
    if (glow > 0f) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * glow), Color.Transparent), center, size * 1.25f), size * 1.25f, center)
    val c = center - Offset(0f, lift)
    fun turn(p: V3) = p.ry(ry).rx(rx).rz(rz).ry(24f).rx(-20f)
    fun proj(p: V3): Offset { val s = 3.4f / (3.4f - p.z); return Offset(c.x + p.x * s * size, c.y + p.y * s * size) }
    val drawn = FACES.map { f -> f to turn(f.n) }.filter { it.second.z > 0.02f }.sortedBy { it.second.z }
    val ivory = Color(0xFFFFFBF2)
    val shade = Color(0xFFCFC6B6)
    for ((f, n) in drawn) {
        val centre3 = f.n * H
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (a, b) -> proj(turn(centre3 + f.u * (a * H) + f.v * (b * H))) }
        val light = (0.5f + 0.5f * n.dot(LIGHT).coerceAtLeast(0f)).coerceIn(0f, 1f)
        // Rounded corners: each corner is cut and curved through.
        val path = Path()
        for (i in 0..3) {
            val p = corners[i]
            val prev = corners[(i + 3) % 4]
            val next = corners[(i + 1) % 4]
            val a = p + (prev - p) * 0.2f
            val b = p + (next - p) * 0.2f
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            path.quadraticBezierTo(p.x, p.y, b.x, b.y)
        }
        path.close()
        drawPath(path, lerp(shade, ivory, light))
        drawPath(path, Color.Black.copy(alpha = 0.10f), style = Stroke(size * 0.025f))
        // Pips.
        for ((pu, pv) in PIPS.getValue(f.value)) {
            val pc = centre3 + f.u * (pu * 0.27f) + f.v * (pv * 0.27f)
            val pip = Path()
            for (i in 0 until 12) {
                val a = i * 2 * PI.toFloat() / 12
                val pt = proj(turn(pc + f.u * (cos(a) * 0.085f) + f.v * (sin(a) * 0.085f)))
                if (i == 0) pip.moveTo(pt.x, pt.y) else pip.lineTo(pt.x, pt.y)
            }
            pip.close()
            drawPath(pip, if (f.value == 1) accent else Color(0xFF1C1E24).copy(alpha = 0.55f + 0.45f * light))
        }
    }
}

// ---- Bits of light --------------------------------------------------------------------------------------------------

internal class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val color: Color, val size: Float, val life: Float, val kind: Int, var spin: Float = 0f, val vs: Float = 0f,
) { var age = 0f }

internal class Shock(val x: Float, val y: Float, val color: Color, val max: Float) { var age = 0f }

/** Confetti, sparks and shock rings, in board pixels. Stepped by the screen's frame clock. */
internal class LudoFx {
    val parts = ArrayList<Particle>()
    val rings = ArrayList<Shock>()
    val busy get() = parts.isNotEmpty() || rings.isNotEmpty()

    fun burst(at: Offset, colors: List<Color>, n: Int, speed: Float, size: Float, confetti: Boolean = false) {
        repeat(n) {
            val a = Math.random() * 2 * PI
            val v = speed * (0.35f + Math.random().toFloat() * 0.75f)
            parts.add(
                Particle(
                    at.x, at.y, (cos(a) * v).toFloat(), (sin(a) * v).toFloat() - speed * 0.35f,
                    colors[it % colors.size], size * (0.6f + Math.random().toFloat() * 0.8f),
                    0.7f + Math.random().toFloat() * 0.7f, if (confetti) 1 else if (it % 4 == 0) 2 else 0,
                    (Math.random() * 360).toFloat(), ((Math.random() - 0.5) * 900).toFloat(),
                ),
            )
        }
    }

    fun rain(width: Float, colors: List<Color>, n: Int, size: Float) {
        repeat(n) {
            parts.add(
                Particle(
                    (Math.random() * width).toFloat(), -size * (2 + Math.random().toFloat() * 12),
                    ((Math.random() - 0.5) * 80).toFloat(), (120 + Math.random() * 160).toFloat(),
                    colors[it % colors.size], size * (0.7f + Math.random().toFloat() * 0.6f), 3.2f, 1,
                    (Math.random() * 360).toFloat(), ((Math.random() - 0.5) * 600).toFloat(),
                ),
            )
        }
    }

    fun ring(at: Offset, color: Color, max: Float) { rings.add(Shock(at.x, at.y, color, max)) }

    fun step(dt: Float, gravity: Float) {
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) { it.remove(); continue }
            if (p.kind == 1) { p.vx *= 0.985f; p.vy = p.vy * 0.985f + gravity * 0.35f * dt } else p.vy += gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.spin += p.vs * dt
        }
        val r = rings.iterator()
        while (r.hasNext()) { val s = r.next(); s.age += dt; if (s.age > 0.6f) r.remove() }
    }
}

internal fun DrawScope.drawFx(fx: LudoFx) {
    for (s in fx.rings) {
        val t = s.age / 0.6f
        drawCircle(s.color.copy(alpha = (1f - t) * 0.8f), s.max * (0.2f + 0.8f * t), Offset(s.x, s.y), style = Stroke(s.max * 0.12f * (1f - t) + 1f))
    }
    for (p in fx.parts) {
        val t = (p.age / p.life).coerceIn(0f, 1f)
        val a = if (t < 0.7f) 1f else (1f - t) / 0.3f
        val c = Offset(p.x, p.y)
        when (p.kind) {
            1 -> rotate(p.spin, pivot = c) {
                // A paper square seen turning: its width swings with the spin.
                val w = p.size * (0.35f + 0.65f * kotlin.math.abs(cos(p.spin * PI.toFloat() / 90f)))
                drawRect(p.color.copy(alpha = a), c - Offset(w / 2, p.size * 0.35f), Size(w, p.size * 0.7f))
            }
            2 -> drawPath(starPath(c, p.size * 0.9f), p.color.copy(alpha = a))
            else -> {
                drawCircle(p.color.copy(alpha = a * 0.35f), p.size * 1.8f, c)
                drawCircle(Color.White.copy(alpha = a), p.size * 0.6f, c)
            }
        }
    }
}
