package com.godbless.lockin.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * MathText: a drop-in for Text() wherever the AI writes. It understands what the server asks the model for: LaTeX between
 * dollar signs ($...$ inline, $$...$$ on its own line), **bold**, *italic*, `code`, and "- " / "1. " lists. Maths that
 * arrives as plain text ("x^2", "sqrt(b^2-4ac)") is rescued too (RichSplit.upgrade).
 *
 * Text with no maths and no list is drawn by a plain Text (so it keeps selection, hyphenation and so on). Anything with
 * maths is laid out here: words and formula pieces become boxes on a shared baseline, wrapped greedily (MathTex.kt does
 * the maths boxes). A display equation wider than the screen scrolls sideways instead of shrinking.
 */

// Extra "fonts" for prose boxes (F_RM / F_IT / F_BF are the maths fonts in MathTex.kt).
private const val F_PROSE = 10
private const val F_PROSE_B = 11
private const val F_PROSE_I = 12
private const val F_PROSE_BI = 13
private const val F_CODE = 14

private fun proseFont(bold: Boolean, italic: Boolean, code: Boolean) = when {
    code -> F_CODE
    bold && italic -> F_PROSE_BI
    bold -> F_PROSE_B
    italic -> F_PROSE_I
    else -> F_PROSE
}

/** Measures and caches text runs for both the layout (widths) and the drawing (the same layouts). */
internal class MathPainter(private val measurer: TextMeasurer, private val density: Density, private val prose: TextStyle) {
    private val cache = HashMap<Triple<String, Int, Int>, TextLayoutResult>()

    fun layout(text: String, font: Int, size: Float): TextLayoutResult =
        cache.getOrPut(Triple(text, font, (size * 4).roundToInt())) {
            measurer.measure(text, styleFor(font, size), softWrap = false, maxLines = 1)
        }

    private fun styleFor(font: Int, size: Float): TextStyle {
        val sp = with(density) { size.toSp() }
        return when (font) {
            F_RM -> TextStyle(fontFamily = FontFamily.Serif, fontSize = sp)
            F_IT -> TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = sp)
            F_BF -> TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = sp)
            else -> prose.copy(
                fontSize = sp,
                lineHeight = TextUnit.Unspecified,
                color = Color.Unspecified,
                fontWeight = if (font == F_PROSE_B || font == F_PROSE_BI) FontWeight.Bold else prose.fontWeight,
                fontStyle = if (font == F_PROSE_I || font == F_PROSE_BI) FontStyle.Italic else prose.fontStyle,
                fontFamily = if (font == F_CODE) FontFamily.Monospace else prose.fontFamily,
            )
        }
    }

    val metrics = MathMetrics { text, font, size -> layout(text, font, size).size.width.toFloat() }
}

// ---- Flow layout ----------------------------------------------------------------------------------------------------------
private class Atom(val box: MBox, val glue: Float, val breakable: Boolean)
private class Placed(val box: MBox, val x: Float, val baseline: Float)
private class Flow(val height: Float, val placed: List<Placed>)

private fun proseBox(word: String, font: Int, size: Float, painter: MathPainter): MBox {
    val lr = painter.layout(word, font, size)
    val asc = lr.firstBaseline
    return MBox(lr.size.width.toFloat(), asc, lr.size.height - asc, listOf(MOp.Text(0f, 0f, word, size, font)))
}

private val WORDS = Regex("""\S+|\s+""")

private fun buildFlow(spans: List<Span>, painter: MathPainter, size: Float, lineHeight: Float, maxW: Float, center: Boolean): Flow {
    val strut = painter.layout("Hg", F_PROSE, size)
    val strutAsc = strut.firstBaseline
    val strutDesc = strut.size.height - strutAsc
    val space = painter.layout(" ", F_PROSE, size).size.width.toFloat().coerceAtLeast(size * 0.25f)
    val math = MathLayout(painter.metrics, size * 1.05f)

    val atoms = ArrayList<Atom>()
    var glue = 0f
    for (sp in spans) {
        when (sp) {
            is Span.Txt -> {
                val font = proseFont(sp.bold, sp.italic, sp.code)
                for (tok in WORDS.findAll(sp.text)) {
                    if (tok.value[0].isWhitespace()) { glue = space; continue }
                    atoms.add(Atom(proseBox(tok.value, font, size, painter), if (atoms.isEmpty()) 0f else glue, glue > 0f))
                    glue = 0f
                }
            }
            is Span.Math -> {
                val ast = MathTex.parse(sp.tex)
                if (ast == null) { // unreadable: show what the model wrote
                    atoms.add(Atom(proseBox(sp.tex, F_CODE, size, painter), if (atoms.isEmpty()) 0f else glue, glue > 0f))
                } else {
                    val pieces = math.pieces(ast)
                    for ((k, p) in pieces.withIndex()) {
                        val first = k == 0
                        atoms.add(Atom(p, if (first && atoms.isNotEmpty()) glue else 0f, if (first) glue > 0f else true))
                    }
                }
                glue = 0f
            }
        }
    }

    val lines = ArrayList<ArrayList<Pair<Atom, Float>>>()
    var cur = ArrayList<Pair<Atom, Float>>()
    var x = 0f
    for (a in atoms) {
        var g = if (cur.isEmpty()) 0f else a.glue
        if (cur.isNotEmpty() && a.breakable && x + g + a.box.w > maxW) {
            lines.add(cur); cur = ArrayList(); x = 0f; g = 0f
        }
        x += g
        cur.add(Pair(a, x))
        x += a.box.w
    }
    if (cur.isNotEmpty() || lines.isEmpty()) lines.add(cur)

    val placed = ArrayList<Placed>()
    var y = 0f
    for (line in lines) {
        var asc = strutAsc
        var desc = strutDesc
        for ((a, _) in line) { asc = max(asc, a.box.asc); desc = max(desc, a.box.desc) }
        val h = max(lineHeight, asc + desc + size * 0.15f)
        val baseline = y + (h - (asc + desc)) / 2 + asc
        val lineW = if (line.isEmpty()) 0f else line.last().second + line.last().first.box.w
        val dx = if (center) max(0f, (maxW - lineW) / 2) else 0f
        for ((a, ax) in line) placed.add(Placed(a.box, ax + dx, baseline))
        y += h
    }
    return Flow(y, placed)
}

// ---- Drawing ----------------------------------------------------------------------------------------------------------------
private fun DrawScope.drawOps(painter: MathPainter, ops: List<MOp>, ox: Float, oy: Float, color: Color) {
    for (o in ops) {
        when (o) {
            is MOp.Text -> {
                val lr = painter.layout(o.text, o.font, o.size)
                drawText(lr, color = color, topLeft = Offset(ox + o.x, oy + o.y - lr.firstBaseline))
            }
            is MOp.Rule -> drawRect(color, Offset(ox + o.x, oy + o.y), Size(o.w, max(o.h, 1f)))
            is MOp.Line -> drawLine(color, Offset(ox + o.x1, oy + o.y1), Offset(ox + o.x2, oy + o.y2), strokeWidth = o.w, cap = StrokeCap.Round)
            is MOp.Quad -> {
                val p = Path()
                p.moveTo(ox + o.x1, oy + o.y1)
                p.quadraticBezierTo(ox + o.cx, oy + o.cy, ox + o.x2, oy + o.y2)
                drawPath(p, color, style = Stroke(width = o.w, cap = StrokeCap.Round))
            }
            is MOp.Dot -> drawCircle(color, o.r, Offset(ox + o.x, oy + o.y))
        }
    }
}

// ---- Composables ------------------------------------------------------------------------------------------------------------
@Composable
private fun FlowPara(p: Block.Para, painter: MathPainter, style: TextStyle, ink: Color, widthPx: Float) {
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toPx() }
    val lineH = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else size * 1.45f }
    val marker = p.marker
    val markerW = if (marker != null) painter.layout(marker, F_PROSE, size).size.width.toFloat() else 0f
    val indent = if (marker != null) max(markerW + size * 0.45f, size * 1.2f) else 0f
    val center = style.textAlign == TextAlign.Center
    val flow = remember(p, widthPx, size, lineH, painter, center) { buildFlow(p.spans, painter, size, lineH, widthPx - indent, center) }
    Canvas(Modifier.fillMaxWidth().height(with(density) { flow.height.toDp() })) {
        if (marker != null && flow.placed.isNotEmpty()) {
            drawOps(painter, listOf(MOp.Text(0f, 0f, marker, size, F_PROSE)), 0f, flow.placed[0].baseline, color = ink)
        }
        for (pl in flow.placed) drawOps(painter, pl.box.ops, indent + pl.x, pl.baseline, ink)
    }
}

@Composable
private fun DisplayMath(tex: String, painter: MathPainter, style: TextStyle, ink: Color, widthPx: Float) {
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toPx() } * 1.2f
    val box = remember(tex, size, painter) { MathTex.parse(tex)?.let { MathLayout(painter.metrics, size).box(it, true) } }
    if (box == null) {
        Text(tex, style = style.copy(color = ink, fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
        return
    }
    val padV = with(density) { 4.dp.toPx() }
    val contentW = max(box.w + padV * 2, widthPx)
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(with(density) { contentW.toDp() }).height(with(density) { (box.height + padV * 2).toDp() })) {
            drawOps(painter, box.ops, (contentW - box.w) / 2, padV + box.asc, ink)
        }
    }
}

/**
 * Draws [text] the way the AI wrote it: prose, lists, bold, and typeset maths. [equation] says the whole string is a
 * formula even though it has no dollar signs (the "you selected" text, a card's answer): it is rescued as one formula.
 */
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LockInText.Sub,
    color: Color = Color.Unspecified,
    equation: Boolean = false,
) {
    val shown = remember(text, equation) {
        if (equation && text.indexOf('$') < 0 && RichSplit.looksMath(text)) "$" + RichSplit.forceTex(text) + "$" else text
    }
    val blocks = remember(shown) { RichSplit.blocks(shown) }
    val ink = color.takeOrElse { style.color.takeOrElse { LockInColors.Snow } }

    if (!RichSplit.isRich(blocks)) {
        val annotated = remember(blocks) {
            buildAnnotatedString {
                blocks.forEachIndexed { i, b ->
                    if (b !is Block.Para) return@forEachIndexed
                    if (i > 0) { append("\n"); if (b.gapBefore) append("\n") }
                    for (s in b.spans) when (s) {
                        is Span.Txt -> withStyle(
                            SpanStyle(
                                fontWeight = if (s.bold) FontWeight.Bold else null,
                                fontStyle = if (s.italic) FontStyle.Italic else null,
                                fontFamily = if (s.code) FontFamily.Monospace else null,
                            ),
                        ) { append(s.text) }
                        is Span.Math -> append(s.tex)
                    }
                }
            }
        }
        Text(annotated, style = style.copy(color = ink), modifier = modifier)
        return
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width: Dp = if (maxWidth == Dp.Infinity) 320.dp else maxWidth
        val widthPx = with(density) { width.toPx() }
        val measurer = rememberTextMeasurer()
        val painter = remember(measurer, density, style) { MathPainter(measurer, density, style) }
        Column(Modifier.fillMaxWidth().semantics { contentDescription = spokenMath(shown) }) {
            blocks.forEachIndexed { i, b ->
                when (b) {
                    is Block.Para -> {
                        if (i > 0) Spacer(Modifier.height(if (b.gapBefore) 8.dp else 3.dp))
                        FlowPara(b, painter, style, ink, widthPx)
                    }
                    is Block.Display -> {
                        Spacer(Modifier.height(4.dp))
                        DisplayMath(b.tex, painter, style, ink, widthPx)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** What a screen reader says for a formula: words, not LaTeX. */
internal fun spokenMath(text: String): String {
    val words = mapOf(
        "frac" to "fraction", "sqrt" to "square root of", "sum" to "sum", "int" to "integral", "pm" to "plus or minus",
        "times" to "times", "cdot" to "times", "le" to "less than or equal to", "leq" to "less than or equal to",
        "ge" to "greater than or equal to", "geq" to "greater than or equal to", "ne" to "not equal to",
        "neq" to "not equal to", "approx" to "approximately", "infty" to "infinity", "to" to "to", "left" to "", "right" to "",
    )
    return text
        .replace(Regex("""\\([A-Za-z]+)""")) { " " + (words[it.groupValues[1]] ?: it.groupValues[1]) + " " }
        .replace("^", " to the power ")
        .replace("_", " sub ")
        .replace(Regex("[${'$'}{}\\\\]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
