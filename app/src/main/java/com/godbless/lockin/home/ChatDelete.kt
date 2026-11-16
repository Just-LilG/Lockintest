package com.godbless.lockin.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A message that breaks apart. [progress] runs 0 to 1:
 *  - the first moments, the bubble swells a little, like it is gathering itself;
 *  - then it breaks into small tiles from the left edge to the right, each one tumbling away up and to the right and
 *    shrinking to nothing, the last of them going just as the animation ends.
 * No fading anywhere: the pieces move and shrink. The bubble is drawn as it really looks, then cut into tiles, so a
 * photo, a voice note or a reply all break the same way.
 */
internal fun Modifier.disintegrate(progress: () -> Float): Modifier = composed {
    val layer = rememberGraphicsLayer()
    drawWithContent {
        val p = progress()
        if (p <= 0f) {
            drawContent()
            return@drawWithContent
        }
        if (p >= 1f) return@drawWithContent

        // Draw the bubble into a layer once per frame, so it can be drawn whole or in pieces.
        layer.record { this@drawWithContent.drawContent() }

        val w = size.width
        val h = size.height
        val charge = 0.18f
        if (p < charge) {
            // Gathering itself: a quick swell, centred.
            val k = sin((p / charge) * (PI / 2.0)).toFloat()
            val s = 1f + 0.07f * k
            withTransform({ scale(s, s, pivot = Offset(w / 2f, h / 2f)) }) { drawLayer(layer) }
            return@drawWithContent
        }

        val q = ((p - charge) / (1f - charge)).coerceIn(0f, 1f)
        val tile = 14.dp.toPx()
        val cols = (w / tile).roundToInt().coerceIn(4, 22)
        val rows = (h / tile).roundToInt().coerceIn(2, 14)
        val tw = w / cols
        val th = h / rows
        val rnd = java.util.Random(7L)
        val sweepSpan = 0.55f // how much of the time is spent sweeping across; the rest is each tile's own fall
        val fall = 1f - sweepSpan

        // The columns that have not started yet are still one piece: drawn in one go, so no seams show.
        var firstWaiting = cols
        for (c in 0 until cols) {
            val start = sweepSpan * (c / (cols - 1f))
            if (q <= start) { firstWaiting = c; break }
        }
        if (firstWaiting < cols) {
            clipRect(left = firstWaiting * tw, top = 0f, right = w, bottom = h) { drawLayer(layer) }
        }

        val d = density
        for (c in 0 until firstWaiting) {
            val start = sweepSpan * (c / (cols - 1f))
            for (r in 0 until rows) {
                // Four numbers per tile, always drawn from the same sequence, so a tile keeps its own character.
                val n1 = rnd.nextFloat()
                val n2 = rnd.nextFloat()
                val n3 = rnd.nextFloat()
                val n4 = rnd.nextFloat()
                val local = ((q - start) / fall).coerceIn(0f, 1f)
                val e = local * local
                if (e >= 1f) continue
                val left = c * tw
                val top = r * th
                val center = Offset(left + tw / 2f, top + th / 2f)
                val dx = (26f + 96f * n1) * e * d
                val dy = -(8f + 64f * n2) * e * d + 40f * e * e * d * n4
                val rot = (n3 - 0.5f) * 180f * e
                val sc = 1f - e
                withTransform({
                    translate(left = dx, top = dy)
                    rotate(degrees = rot, pivot = center)
                    scale(scaleX = sc, scaleY = sc, pivot = center)
                    clipRect(left = left, top = top, right = left + tw, bottom = top + th)
                }) { drawLayer(layer) }
            }
        }
    }
}
