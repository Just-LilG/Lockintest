package com.godbless.lockin.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.sin

private val Dim: Color get() = com.godbless.lockin.ui.LockInColors.Mist
private val Lit: Color get() = com.godbless.lockin.ui.LockInColors.Snow
/** "In" lights up in LockIn's lime when the word locks in. */
private val LitAccent: Color get() = com.godbless.lockin.ui.LockInColors.Lime

/**
 * "LockIn", laid out letter by letter. Each letter rises out of a mask line with a small spring and
 * twist, one after another. When the last one lands the word "locks in": a light wipe sweeps across,
 * turning it from dim to bright, with a quick press of the whole word.
 */
internal class Wordmark(measurer: TextMeasurer, style: TextStyle, text: String = "LockIn") {
    val letters: List<TextLayoutResult> = text.map { measurer.measure(AnnotatedString(it.toString()), style) }
    val lefts: FloatArray
    val widths: FloatArray
    val width: Float
    val height: Float

    init {
        val whole = measurer.measure(AnnotatedString(text), style)
        lefts = FloatArray(text.length) { whole.getBoundingBox(it).left }
        widths = FloatArray(text.length) { whole.getBoundingBox(it).width }
        width = whole.size.width.toFloat()
        height = whole.size.height.toFloat()
    }
}

/**
 * Draws the wordmark with its top-left at [origin], scaled by [size] (1 = as measured).
 *
 * [rise] is seconds since the first letter started rising (negative = not yet).
 * [exit] is seconds since the first letter started dropping back out of its mask (0 or less = still shown).
 * [click] is seconds since the word "locks in" (dim to bright); null means it is already bright.
 */
internal fun DrawScope.drawWordmark(wm: Wordmark, origin: Offset, size: Float, rise: Float, exit: Float, click: Float?) {
    if (rise < 0f) return
    val exitTotal = Timeline.EXIT_STAGGER * wm.letters.size + Timeline.EXIT_DUR
    if (exit > exitTotal) return
    withTransform({
        translate(origin.x, origin.y)
        scale(size, size, Offset.Zero)
    }) {
        val pulse = if (click == null) 1f else 1f + 0.05f * sin(PI.toFloat() * clamp01(click / 0.3f))
        scale(pulse, pulse, Offset(wm.width / 2f, wm.height / 2f)) {
            clipRect(-12f, 0f, wm.width + 12f, wm.height) {
                if (click == null) {
                    drawLetters(wm, rise, exit, Lit, LitAccent)
                } else {
                    drawLetters(wm, rise, exit, Dim)
                    val wipe = easeInOutCubic(click / Timeline.CLICK_DUR)
                    if (wipe > 0f) {
                        val front = mix(-40f, wm.width + 60f, wipe)
                        val skew = wm.height * 0.35f
                        val region = Path().apply {
                            moveTo(-200f, -10f)
                            lineTo(front + skew, -10f)
                            lineTo(front - skew, wm.height + 10f)
                            lineTo(-200f, wm.height + 10f)
                            close()
                        }
                        clipPath(region) { drawLetters(wm, rise, exit, Lit, LitAccent) }
                    }
                }
            }
        }
    }
}

/** Draws the letters in [color]; with [accent], the last two letters ("In") use it instead. */
private fun DrawScope.drawLetters(wm: Wordmark, rise: Float, exit: Float, color: Color, accent: Color? = null) {
    for (i in wm.letters.indices) {
        val p = easeOutBack(clamp01((rise - i * Timeline.WORD_STAGGER) / Timeline.WORD_RISE_DUR), 1.3f)
        val out = easeInCubic(clamp01((exit - i * Timeline.EXIT_STAGGER) / Timeline.EXIT_DUR))
        val dy = (1f - p) * wm.height * 1.05f + out * wm.height * 1.1f
        val twist = (1f - clamp01(p)) * (if (i % 2 == 0) -10f else 10f)
        val cx = wm.lefts[i] + wm.widths[i] / 2f
        translate(0f, dy) {
            rotate(twist, Offset(cx, wm.height * 0.8f)) {
                val c = if (accent != null && i >= wm.letters.size - 2) accent else color
                drawText(wm.letters[i], color = c, topLeft = Offset(wm.lefts[i], 0f))
            }
        }
    }
}
