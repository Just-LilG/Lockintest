package com.godbless.lockin.home

import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The small line icons used on the home page. All are drawn, so the app needs no icon library. */
enum class Glyph { Peak, Flame, Timer, History, People, Person, Lock, ArrowUp, Chevron, Bell, Target, Plus, Minus, Calendar, Exit, Calculator, Book, Cards, Close, Crew, Gear, Palette, Shield, Download, Star, Share, Select }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, boxSize: Dp = 24.dp, strokeWidth: Float = 1.8f) {
    Canvas(modifier.size(boxSize)) { drawGlyph(glyph, color, this.size.width / 24f, strokeWidth) }
}

/** Draws [g] on a 24-unit grid scaled by [k]. */
internal fun DrawScope.drawGlyph(g: Glyph, color: Color, k: Float, sw: Float = 1.8f) {
    val style = Stroke(width = sw * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        Glyph.Peak -> {
            val p = Path().apply {
                moveTo(2.5f * k, 19.5f * k)
                lineTo(9.5f * k, 6.5f * k)
                lineTo(13f * k, 13f * k)
                lineTo(15.3f * k, 9.5f * k)
                lineTo(21.5f * k, 19.5f * k)
                close()
            }
            drawPath(p, color, style = style)
        }
        Glyph.Flame -> {
            val p = Path().apply {
                moveTo(12f * k, 3f * k)
                cubicTo(12f * k, 3f * k, 6f * k, 8f * k, 6f * k, 13.5f * k)
                cubicTo(6f * k, 17f * k, 8.7f * k, 20f * k, 12f * k, 20f * k)
                cubicTo(15.3f * k, 20f * k, 18f * k, 17f * k, 18f * k, 13.5f * k)
                cubicTo(18f * k, 11f * k, 16.8f * k, 9f * k, 15.5f * k, 7.5f * k)
                cubicTo(15.2f * k, 9.5f * k, 14.2f * k, 10.5f * k, 13f * k, 11f * k)
                cubicTo(13.5f * k, 8.5f * k, 13f * k, 5.5f * k, 12f * k, 3f * k)
                close()
            }
            drawPath(p, color, style = style)
        }
        Glyph.Timer -> {
            drawCircle(color, radius = 8f * k, center = Offset(12f * k, 13.5f * k), style = style)
            drawLine(color, Offset(12f * k, 13.5f * k), Offset(12f * k, 9.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(9.5f * k, 3f * k), Offset(14.5f * k, 3f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.History -> {
            drawCircle(color, radius = 9f * k, center = Offset(12f * k, 12f * k), style = style)
            val p = Path().apply {
                moveTo(12f * k, 7f * k)
                lineTo(12f * k, 12f * k)
                lineTo(15.5f * k, 14f * k)
            }
            drawPath(p, color, style = style)
        }
        Glyph.People -> {
            drawCircle(color, radius = 3.2f * k, center = Offset(9f * k, 8.5f * k), style = style)
            val a = Path().apply {
                moveTo(3f * k, 19.5f * k)
                quadraticBezierTo(9f * k, 9.5f * k, 15f * k, 19.5f * k)
            }
            drawPath(a, color, style = style)
            drawCircle(color, radius = 2.6f * k, center = Offset(16.5f * k, 9.5f * k), style = style)
            val b = Path().apply {
                moveTo(16f * k, 14f * k)
                quadraticBezierTo(19.5f * k, 14.5f * k, 21f * k, 19f * k)
            }
            drawPath(b, color, style = style)
        }
        Glyph.Person -> {
            drawCircle(color, radius = 3.8f * k, center = Offset(12f * k, 8f * k), style = style)
            val p = Path().apply {
                moveTo(5f * k, 20f * k)
                quadraticBezierTo(12f * k, 9.5f * k, 19f * k, 20f * k)
            }
            drawPath(p, color, style = style)
        }
        Glyph.Select -> {
            // A text cursor over a highlighted line: "pick some words".
            drawRoundRect(color.copy(alpha = 0.28f), topLeft = Offset(3f * k, 9f * k), size = Size(12f * k, 6f * k), cornerRadius = CornerRadius(1.5f * k, 1.5f * k))
            drawLine(color, Offset(17f * k, 5f * k), Offset(17f * k, 19f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(14.5f * k, 4.5f * k), Offset(19.5f * k, 4.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(14.5f * k, 19.5f * k), Offset(19.5f * k, 19.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(3f * k, 19.5f * k), Offset(11f * k, 19.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.Lock -> {
            drawRoundRect(
                color, topLeft = Offset(5.5f * k, 10.5f * k), size = Size(13f * k, 9.5f * k),
                cornerRadius = CornerRadius(2.5f * k, 2.5f * k), style = style,
            )
            val p = Path().apply {
                moveTo(8.5f * k, 10.5f * k)
                lineTo(8.5f * k, 8f * k)
                cubicTo(8.5f * k, 3.5f * k, 15.5f * k, 3.5f * k, 15.5f * k, 8f * k)
                lineTo(15.5f * k, 10.5f * k)
            }
            drawPath(p, color, style = style)
        }
        Glyph.ArrowUp -> {
            drawLine(color, Offset(12f * k, 19f * k), Offset(12f * k, 6f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            val p = Path().apply {
                moveTo(6.5f * k, 11.5f * k)
                lineTo(12f * k, 6f * k)
                lineTo(17.5f * k, 11.5f * k)
            }
            drawPath(p, color, style = style)
        }
        Glyph.Chevron -> {
            val p = Path().apply {
                moveTo(9f * k, 5.5f * k)
                lineTo(15.5f * k, 12f * k)
                lineTo(9f * k, 18.5f * k)
            }
            drawPath(p, color, style = style)
        }
        Glyph.Bell -> {
            val p = Path().apply {
                moveTo(6f * k, 17f * k)
                cubicTo(6f * k, 17f * k, 6.5f * k, 15.5f * k, 6.5f * k, 11f * k)
                cubicTo(6.5f * k, 7f * k, 9f * k, 5f * k, 12f * k, 5f * k)
                cubicTo(15f * k, 5f * k, 17.5f * k, 7f * k, 17.5f * k, 11f * k)
                cubicTo(17.5f * k, 15.5f * k, 18f * k, 17f * k, 18f * k, 17f * k)
                close()
            }
            drawPath(p, color, style = style)
            drawLine(color, Offset(12f * k, 3.2f * k), Offset(12f * k, 5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            val c = Path().apply {
                moveTo(10f * k, 19.8f * k)
                quadraticBezierTo(12f * k, 22f * k, 14f * k, 19.8f * k)
            }
            drawPath(c, color, style = style)
        }
        Glyph.Gear -> {
            // A cog: eight rounded teeth around a ring, with the hole in the middle.
            val p = Path()
            val teeth = 8
            for (i in 0 until teeth * 4) {
                val a = (i / (teeth * 4f)) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                val r = if ((i % 4) == 1 || (i % 4) == 2) 9.6f else 7.4f
                val x = 12f * k + r * k * cos(a)
                val y = 12f * k + r * k * sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, color, style = style)
            drawCircle(color, radius = 3f * k, center = Offset(12f * k, 12f * k), style = style)
        }
        Glyph.Palette -> {
            // A painter's palette: a rounded blob with a thumb notch and three dots of paint.
            val p = Path().apply {
                moveTo(12f * k, 3.5f * k)
                cubicTo(6.8f * k, 3.5f * k, 3.5f * k, 7.4f * k, 3.5f * k, 12f * k)
                cubicTo(3.5f * k, 16.8f * k, 7.4f * k, 20.5f * k, 11.8f * k, 20.5f * k)
                cubicTo(13.4f * k, 20.5f * k, 14f * k, 19.4f * k, 13.3f * k, 18.1f * k)
                cubicTo(12.6f * k, 16.8f * k, 13.3f * k, 15.4f * k, 15f * k, 15.4f * k)
                lineTo(17f * k, 15.4f * k)
                cubicTo(19.2f * k, 15.4f * k, 20.5f * k, 14f * k, 20.5f * k, 11.6f * k)
                cubicTo(20.5f * k, 7f * k, 16.7f * k, 3.5f * k, 12f * k, 3.5f * k)
                close()
            }
            drawPath(p, color, style = style)
            drawCircle(color, radius = 1.3f * k, center = Offset(8f * k, 11.5f * k))
            drawCircle(color, radius = 1.3f * k, center = Offset(11f * k, 7.6f * k))
            drawCircle(color, radius = 1.3f * k, center = Offset(15.6f * k, 9f * k))
        }
        Glyph.Target -> {
            drawCircle(color, radius = 8.5f * k, center = Offset(12f * k, 12f * k), style = style)
            drawCircle(color, radius = 4.5f * k, center = Offset(12f * k, 12f * k), style = style)
            drawCircle(color, radius = 1.1f * k, center = Offset(12f * k, 12f * k))
        }
        Glyph.Plus -> {
            drawLine(color, Offset(12f * k, 5.5f * k), Offset(12f * k, 18.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(5.5f * k, 12f * k), Offset(18.5f * k, 12f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.Minus -> {
            drawLine(color, Offset(5.5f * k, 12f * k), Offset(18.5f * k, 12f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.Calendar -> {
            drawRoundRect(
                color, topLeft = Offset(4f * k, 5.5f * k), size = Size(16f * k, 14.5f * k),
                cornerRadius = CornerRadius(3f * k, 3f * k), style = style,
            )
            drawLine(color, Offset(4f * k, 10f * k), Offset(20f * k, 10f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(8.5f * k, 3.5f * k), Offset(8.5f * k, 7f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(15.5f * k, 3.5f * k), Offset(15.5f * k, 7f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.Shield -> {
            val p = Path().apply {
                moveTo(12f * k, 3.5f * k)
                lineTo(19f * k, 6.3f * k)
                lineTo(19f * k, 11.5f * k)
                cubicTo(19f * k, 16f * k, 15.8f * k, 19.2f * k, 12f * k, 20.8f * k)
                cubicTo(8.2f * k, 19.2f * k, 5f * k, 16f * k, 5f * k, 11.5f * k)
                lineTo(5f * k, 6.3f * k)
                close()
            }
            drawPath(p, color, style = style)
            val tick = Path().apply {
                moveTo(8.8f * k, 12f * k); lineTo(11f * k, 14.2f * k); lineTo(15.2f * k, 9.6f * k)
            }
            drawPath(tick, color, style = style)
        }
        Glyph.Download -> {
            drawLine(color, Offset(12f * k, 4f * k), Offset(12f * k, 14.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            val head = Path().apply { moveTo(7.5f * k, 10.5f * k); lineTo(12f * k, 15f * k); lineTo(16.5f * k, 10.5f * k) }
            drawPath(head, color, style = style)
            val tray = Path().apply { moveTo(5f * k, 15.5f * k); lineTo(5f * k, 19.5f * k); lineTo(19f * k, 19.5f * k); lineTo(19f * k, 15.5f * k) }
            drawPath(tray, color, style = style)
        }
        Glyph.Star -> {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 8.6f else 3.7f
                val a = (-90.0 + i * 36.0) * Math.PI / 180.0
                val x = (12f + r * cos(a).toFloat()) * k
                val y = (12.6f + r * sin(a).toFloat()) * k
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            drawPath(star, color, style = style)
        }
        Glyph.Share -> {
            drawCircle(color, radius = 2.4f * k, center = Offset(6.5f * k, 12f * k), style = style)
            drawCircle(color, radius = 2.4f * k, center = Offset(17.5f * k, 6.2f * k), style = style)
            drawCircle(color, radius = 2.4f * k, center = Offset(17.5f * k, 17.8f * k), style = style)
            drawLine(color, Offset(8.6f * k, 10.9f * k), Offset(15.4f * k, 7.3f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(8.6f * k, 13.1f * k), Offset(15.4f * k, 16.7f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
        Glyph.Exit -> {
            val door = Path().apply {
                moveTo(10f * k, 4.5f * k)
                lineTo(5.5f * k, 4.5f * k)
                lineTo(5.5f * k, 19.5f * k)
                lineTo(10f * k, 19.5f * k)
            }
            drawPath(door, color, style = style)
            drawLine(color, Offset(10f * k, 12f * k), Offset(19f * k, 12f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(15.5f * k, 8.5f * k)
                lineTo(19f * k, 12f * k)
                lineTo(15.5f * k, 15.5f * k)
            }
            drawPath(head, color, style = style)
        }
        Glyph.Calculator -> {
            drawRoundRect(
                color, topLeft = Offset(5f * k, 3f * k), size = Size(14f * k, 18f * k),
                cornerRadius = CornerRadius(3f * k, 3f * k), style = style,
            )
            drawLine(color, Offset(8.5f * k, 7.5f * k), Offset(15.5f * k, 7.5f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            for (row in 0..1) for (col in 0..2) {
                drawCircle(color, radius = 1f * k, center = Offset((9f + col * 3f) * k, (12.5f + row * 4f) * k))
            }
        }
        Glyph.Book -> {
            val left = Path().apply {
                moveTo(12f * k, 6.5f * k)
                cubicTo(10f * k, 5f * k, 6.5f * k, 4.8f * k, 3.5f * k, 5.5f * k)
                lineTo(3.5f * k, 18.2f * k)
                cubicTo(6.5f * k, 17.5f * k, 10f * k, 17.7f * k, 12f * k, 19.5f * k)
                close()
            }
            val right = Path().apply {
                moveTo(12f * k, 6.5f * k)
                cubicTo(14f * k, 5f * k, 17.5f * k, 4.8f * k, 20.5f * k, 5.5f * k)
                lineTo(20.5f * k, 18.2f * k)
                cubicTo(17.5f * k, 17.5f * k, 14f * k, 17.7f * k, 12f * k, 19.5f * k)
                close()
            }
            drawPath(left, color, style = style)
            drawPath(right, color, style = style)
        }
        Glyph.Cards -> {
            drawRoundRect(
                color, topLeft = Offset(3.5f * k, 8f * k), size = Size(13f * k, 11.5f * k),
                cornerRadius = CornerRadius(2.5f * k, 2.5f * k), style = style,
            )
            val back = Path().apply {
                moveTo(7f * k, 5f * k)
                lineTo(18.5f * k, 5f * k)
                quadraticBezierTo(20.5f * k, 5f * k, 20.5f * k, 7f * k)
                lineTo(20.5f * k, 15.5f * k)
            }
            drawPath(back, color, style = style)
        }
        // Three climbers on one rope: heads on a rising line, tied by a sagging rope.
        Glyph.Crew -> {
            drawCircle(color, radius = 2.4f * k, center = Offset(5f * k, 13.5f * k), style = style)
            drawCircle(color, radius = 2.4f * k, center = Offset(12f * k, 9.5f * k), style = style)
            drawCircle(color, radius = 2.4f * k, center = Offset(19f * k, 5.5f * k), style = style)
            val rope = Path().apply {
                moveTo(2.5f * k, 20f * k)
                quadraticBezierTo(8.5f * k, 19f * k, 12f * k, 15f * k)
                quadraticBezierTo(15.5f * k, 11.5f * k, 21.5f * k, 11f * k)
            }
            drawPath(rope, color, style = style)
        }
        Glyph.Close -> {
            drawLine(color, Offset(6f * k, 6f * k), Offset(18f * k, 18f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
            drawLine(color, Offset(18f * k, 6f * k), Offset(6f * k, 18f * k), strokeWidth = sw * k, cap = StrokeCap.Round)
        }
    }
}
