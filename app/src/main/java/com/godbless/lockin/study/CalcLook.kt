package com.godbless.lockin.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.Fonts
import kotlin.random.Random

// How the calculator looks, kept apart from how it works. Everything here is drawn with gradients and strokes (no images)
// so it stays crisp on any screen: a moulded body with a fine grain, a recessed LCD under glass, keycaps with a bevel, a
// highlight and a drop of shadow, a metal-ringed direction pad and the little solar strip across the top.

/** Mix [c] towards white by [f]. */
internal fun lighter(c: Color, f: Float): Color = lerp(c, Color.White, f)

/** Mix [c] towards black by [f]. */
internal fun darker(c: Color, f: Float): Color = lerp(c, Color.Black, f)

/** The body of the device: a soft vertical gradient, a faint light from the top, and a fine speckle like moulded plastic. */
internal fun Modifier.calcChassis(): Modifier = this.drawWithCache {
    val rnd = Random(7)
    val specks = List(360) { Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) to rnd.nextFloat() }
    val body = Brush.verticalGradient(listOf(Color(0xFF1D2027), Color(0xFF14161B), Color(0xFF0D0E12)))
    val glow = Brush.radialGradient(
        listOf(Color(0x1FFFFFFF), Color.Transparent),
        center = Offset(size.width * 0.5f, 0f),
        radius = size.width * 0.95f,
    )
    onDrawBehind {
        drawRect(body)
        drawRect(glow)
        val r = 0.6.dp.toPx()
        specks.forEach { (p, a) -> drawCircle(if (a > 0.5f) Color(0x0AFFFFFF) else Color(0x14000000), r, p) }
    }
}

/**
 * Draws a screen under glass. Put it after the screen's own background and clip: it adds the soft shadow the bezel throws
 * on the top and sides, the faint pixel rows of an LCD, and a diagonal reflection across the upper left.
 */
internal fun Modifier.lcdGlass(rowPx: Float = 3f): Modifier = this.drawWithCache {
    val w = size.width
    val h = size.height
    val top = Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent), startY = 0f, endY = 16.dp.toPx())
    val left = Brush.horizontalGradient(listOf(Color(0x33000000), Color.Transparent), startX = 0f, endX = 10.dp.toPx())
    val right = Brush.horizontalGradient(listOf(Color.Transparent, Color(0x26000000)), startX = w - 10.dp.toPx(), endX = w)
    val bottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0x1F000000)), startY = h - 8.dp.toPx(), endY = h)
    val glare = Path().apply {
        moveTo(0f, 0f); lineTo(w * 0.62f, 0f); lineTo(w * 0.34f, h * 0.55f); lineTo(0f, h * 0.55f); close()
    }
    val glareBrush = Brush.linearGradient(
        listOf(Color(0x24FFFFFF), Color(0x05FFFFFF)),
        start = Offset(0f, 0f), end = Offset(w * 0.5f, h * 0.55f),
    )
    val step = rowPx.coerceAtLeast(2f) * density
    onDrawWithContent {
        drawContent()
        // The rows of the display, so the glass looks like it has pixels behind it.
        var y = step
        while (y < h) { drawRect(Color(0x0D000000), Offset(0f, y), Size(w, 1f)); y += step }
        drawRect(top); drawRect(left); drawRect(right); drawRect(bottom)
        drawPath(glare, glareBrush)
        // A thin bright line along the very top of the glass.
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x40FFFFFF), Color.Transparent)), Offset(0f, 0f), Size(w, 1.dp.toPx()))
    }
}

/** The ink's shadow on an LCD: segments sit a hair above the back plate, so each glyph throws a faint offset shadow. */
internal fun lcdInkShadow(ink: Color) = Shadow(ink.copy(alpha = 0.22f), Offset(1.5f, 2.5f), 3f)

/** The dark frame around the screen. */
internal fun Modifier.lcdBezel(): Modifier = this
    .background(Brush.verticalGradient(listOf(Color(0xFF30333C), Color(0xFF07080A))), RoundedCornerShape(27.dp))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x0DFFFFFF))), RoundedCornerShape(27.dp))

/** The face of a keycap: lit from above, a little darker at the foot. */
internal fun keyFace(face: Color): Brush = Brush.verticalGradient(
    0f to lighter(face, 0.17f),
    0.45f to lighter(face, 0.03f),
    1f to darker(face, 0.14f),
)

/** The solar strip: four dark cells behind glass with silver bus lines, a hint of blue, and a sheen. */
@Composable
internal fun SolarPanel(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 92.dp, height = 26.dp)) {
        val w = size.width
        val h = size.height
        val r = 6.dp.toPx()
        drawRoundRect(Color(0xFF050608), cornerRadius = CornerRadius(r + 1.dp.toPx()))
        val pad = 2.dp.toPx()
        val cw = (w - pad * 2) / 4f
        for (i in 0 until 4) {
            val x = pad + cw * i
            val cell = Brush.verticalGradient(listOf(Color(0xFF2A2640), Color(0xFF151227), Color(0xFF0B0A16)), startY = pad, endY = h - pad)
            drawRoundRect(cell, Offset(x + 1.dp.toPx(), pad), Size(cw - 2.dp.toPx(), h - pad * 2), CornerRadius(3.dp.toPx()))
            // The thin metal lines across a cell.
            drawRect(Color(0x33C9CCE0), Offset(x + 3.dp.toPx(), pad + 3.dp.toPx()), Size(cw - 6.dp.toPx(), 0.7.dp.toPx()))
            drawRect(Color(0x33C9CCE0), Offset(x + 3.dp.toPx(), h - pad - 3.7.dp.toPx()), Size(cw - 6.dp.toPx(), 0.7.dp.toPx()))
        }
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0x40FFFFFF), Color.Transparent, Color(0x14FFFFFF)), start = Offset(0f, 0f), end = Offset(w * 0.7f, h)),
            cornerRadius = CornerRadius(r + 1.dp.toPx()),
        )
        drawRoundRect(Color(0x33FFFFFF), cornerRadius = CornerRadius(r + 1.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** The wordmark and model line printed on the body, left of the solar strip. */
@Composable
internal fun CalcBrand(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "LOCKIN",
            style = com.godbless.lockin.ui.LockInText.Small.copy(
                color = CC.Ink, fontFamily = Fonts.Wordmark, fontSize = 17.sp, lineHeight = 20.sp, letterSpacing = 2.sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 1f),
            ),
            maxLines = 1,
        )
        Text(
            "SCIENTIFIC CALCULATOR",
            style = com.godbless.lockin.ui.LockInText.Small.copy(
                color = CC.Dim, fontSize = 8.5.sp, lineHeight = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

/** The line across the top of the device: brand on the left, solar strip on the right. */
@Composable
internal fun CalcBrandRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CalcBrand()
        SolarPanel()
    }
}
