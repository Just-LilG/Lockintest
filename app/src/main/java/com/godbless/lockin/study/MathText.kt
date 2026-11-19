package com.godbless.lockin.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * MathText: a drop-in for Text() wherever the AI writes. It understands what the server asks the model for (see FORMAT and
 * MATH in supabase/functions/ai): LaTeX between dollar signs ($...$ inline, $$...$$ on its own line), **bold**, *italic*,
 * `code`, {lime|coloured} and ==highlighted== text, "- " / "1. " lists, ## headings, > Tip: callouts, | tables |, --- rules,
 * and fenced blocks: ```plot (a graph), ```flow / ```cycle / ```timeline (diagrams), any other fence (code).
 * Maths that arrives as plain text ("x^2", "sqrt(b^2-4ac)") is rescued too (RichSplit.upgrade).
 *
 * Text with only bold/italic/code is drawn by a plain Text (so it keeps selection and hyphenation). Anything else is laid
 * out here: words and formula pieces become boxes on one baseline, wrapped greedily (MathTex.kt does the maths boxes);
 * RichBlocks.kt draws the table, callout, code, graph and diagram blocks.
 *
 * Tapping a formula (when onExplain is given) hands the tapped piece (a fraction, a root, one variable...) back as LaTeX.
 * A numbers-only equation that is wrong gets a red mark, a right display equation a small tick (NumCheck).
 */

// Extra "fonts" for prose boxes (F_RM / F_IT / F_BF are the maths fonts in MathTex.kt).
internal const val F_PROSE = 10
internal const val F_PROSE_B = 11
internal const val F_PROSE_I = 12
internal const val F_PROSE_BI = 13
internal const val F_CODE = 14

private fun proseFont(bold: Boolean, italic: Boolean, code: Boolean) = when {
    code -> F_CODE
    bold && italic -> F_PROSE_BI
    bold -> F_PROSE_B
    italic -> F_PROSE_I
    else -> F_PROSE
}

/** The colour a tint index means in the current theme (see tintIndex in MathTex.kt). */
internal fun tintColor(idx: Int, ink: Color): Color = when (idx) {
    1 -> LockInColors.Lime
    2 -> LockInColors.Flare
    3 -> if (LockInColors.isDark) Color(0xFF7DBBFF) else Color(0xFF1B64C7)
    4 -> if (LockInColors.isDark) Color(0xFFFFC857) else Color(0xFFA86B00)
    5 -> LockInColors.Mist
    9 -> LockInColors.Lime.copy(alpha = 0.28f)
    else -> ink
}

/** Measures and caches text runs for both the layout (widths) and the drawing (the same layouts). */
internal class MathPainter(private val measurer: TextMeasurer, private val density: Density, private val prose: TextStyle) {
    private val cache = HashMap<Triple<String, Int, Int>, TextLayoutResult>()
    private val wrapCache = HashMap<Triple<String, Int, Int>, TextLayoutResult>()

    fun layout(text: String, font: Int, size: Float): TextLayoutResult =
        cache.getOrPut(Triple(text, font, (size * 4).roundToInt())) {
            measurer.measure(text, styleFor(font, size), softWrap = false, maxLines = 1)
        }

    /** [text] wrapped to [width] px and centred (diagram boxes). */
    fun wrapped(text: String, size: Float, width: Int): TextLayoutResult =
        wrapCache.getOrPut(Triple(text, (size * 4).roundToInt(), width)) {
            measurer.measure(
                text,
                styleFor(F_PROSE, size).copy(textAlign = TextAlign.Center),
                softWrap = true,
                constraints = Constraints(maxWidth = max(width, 1)),
            )
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

/** Everything a block needs to draw itself. */
internal class RichEnv(val painter: MathPainter, val style: TextStyle, val ink: Color, val onExplain: ((String) -> Unit)?) {
    fun withInk(ink2: Color): RichEnv = RichEnv(painter, style, ink2, onExplain)
}

// ---- TextFlow layout ----------------------------------------------------------------------------------------------------------
private class Atom(val box: MBox, val glue: Float, val breakable: Boolean, val src: String?)
internal class PlacedBox(val box: MBox, val x: Float, val baseline: Float, val src: String?)
internal class TextFlow(val height: Float, val width: Float, val placed: List<PlacedBox>)

private fun proseBox(word: String, font: Int, size: Float, painter: MathPainter, color: Int = 0, mark: Boolean = false): MBox {
    val lr = painter.layout(word, font, size)
    val asc = lr.firstBaseline
    val w = lr.size.width.toFloat()
    val h = lr.size.height.toFloat()
    val text = MOp.Text(0f, 0f, word, size, font)
    if (color != 0) text.tint = color
    val ops = ArrayList<MOp>(2)
    if (mark) { val r = MOp.Rule(-1.5f, -asc, w + 3f, h); r.tint = 9; ops.add(r) }
    ops.add(text)
    return MBox(w, asc, h - asc, ops)
}

private fun tintedBox(b: MBox, idx: Int) = MBox(b.w, b.asc, b.desc, b.ops.map { if (it.tint == 0) it.tinted(idx) else it })

private val WORDS = Regex("""\S+|\s+""")

internal fun buildFlow(spans: List<Span>, painter: MathPainter, size: Float, lineHeight: Float, maxW: Float, center: Boolean, spots: Boolean = false): TextFlow {
    val strut = painter.layout("Hg", F_PROSE, size)
    val strutAsc = strut.firstBaseline
    val strutDesc = strut.size.height - strutAsc
    val space = painter.layout(" ", F_PROSE, size).size.width.toFloat().coerceAtLeast(size * 0.25f)
    val math = MathLayout(painter.metrics, size * 1.05f, spots)

    val atoms = ArrayList<Atom>()
    var glue = 0f
    for (sp in spans) {
        when (sp) {
            is Span.Txt -> {
                val font = proseFont(sp.bold, sp.italic, sp.code)
                for (tok in WORDS.findAll(sp.text)) {
                    if (tok.value[0].isWhitespace()) { glue = space; continue }
                    atoms.add(Atom(proseBox(tok.value, font, size, painter, sp.color, sp.mark), if (atoms.isEmpty()) 0f else glue, glue > 0f, null))
                    glue = 0f
                }
            }
            is Span.Math -> {
                val ast = MathTex.parse(sp.tex)
                if (ast == null) { // unreadable: show what the model wrote
                    atoms.add(Atom(proseBox(sp.tex, F_CODE, size, painter), if (atoms.isEmpty()) 0f else glue, glue > 0f, null))
                } else {
                    val verdict = NumCheck.check(sp.tex)
                    val colour = if (verdict == false) 2 else sp.color
                    val pieces = math.pieces(ast)
                    for ((k, p) in pieces.withIndex()) {
                        val first = k == 0
                        val box = if (colour != 0) tintedBox(p, colour) else p
                        atoms.add(Atom(box, if (first && atoms.isNotEmpty()) glue else 0f, if (first) glue > 0f else true, sp.tex))
                    }
                    if (verdict == false) atoms.add(Atom(proseBox("⚠", F_PROSE, size * 0.8f, painter, 2), size * 0.15f, false, null))
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

    val placed = ArrayList<PlacedBox>()
    var y = 0f
    var widest = 0f
    for (line in lines) {
        var asc = strutAsc
        var desc = strutDesc
        for ((a, _) in line) { asc = max(asc, a.box.asc); desc = max(desc, a.box.desc) }
        val h = max(lineHeight, asc + desc + size * 0.15f)
        val baseline = y + (h - (asc + desc)) / 2 + asc
        val lineW = if (line.isEmpty()) 0f else line.last().second + line.last().first.box.w
        widest = max(widest, lineW)
        val dx = if (center) max(0f, (maxW - lineW) / 2) else 0f
        for ((a, ax) in line) placed.add(PlacedBox(a.box, ax + dx, baseline, a.src))
        y += h
    }
    return TextFlow(y, widest, placed)
}

// ---- Drawing ----------------------------------------------------------------------------------------------------------------
internal fun DrawScope.drawOps(painter: MathPainter, ops: List<MOp>, ox: Float, oy: Float, ink: Color) {
    for (o in ops) {
        val color = if (o.tint == 0) ink else tintColor(o.tint, ink)
        when (o) {
            is MOp.Text -> {
                val lr = (o.cached as? TextLayoutResult) ?: painter.layout(o.text, o.font, o.size).also { o.cached = it }
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
            is MOp.Spot -> Unit
        }
    }
}

/** The piece a tap lit up, kept for a moment so the student sees what was asked about. */
private class Lit(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float)

// ---- Paragraphs ---------------------------------------------------------------------------------------------------------------
@Composable
internal fun FlowPara(p: Block.Para, env: RichEnv, widthPx: Float, styleOverride: TextStyle? = null) {
    val style = styleOverride ?: env.style
    val painter = env.painter
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toPx() }
    val lineH = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else size * 1.45f }
    val marker = p.marker
    val markerW = if (marker != null) painter.layout(marker, F_PROSE, size).size.width.toFloat() else 0f
    val indent = if (marker != null) max(markerW + size * 0.45f, size * 1.2f) else 0f
    val center = style.textAlign == TextAlign.Center
    val explain = env.onExplain
    val tappable = explain != null
    val flow = remember(p, widthPx, size, lineH, painter, center, tappable) {
        buildFlow(p.spans, painter, size, lineH, widthPx - indent, center, tappable)
    }
    var lit by remember(p) { mutableStateOf<Lit?>(null) }
    LaunchedEffect(lit) { if (lit != null) { delay(1400); lit = null } }
    val tap = if (explain == null) Modifier else Modifier.pointerInput(flow) {
        detectTapGestures(onTap = { off ->
            val slop = 6.dp.toPx()
            for ((idx, pl) in flow.placed.withIndex()) {
                val src = pl.src ?: continue
                val lx = off.x - indent - pl.x
                val ly = off.y - pl.baseline
                if (lx < -slop || lx > pl.box.w + slop || ly < -pl.box.asc - slop || ly > pl.box.desc + slop) continue
                val spot = MathHit.find(pl.box.ops, lx, ly, slop)
                if (spot != null) {
                    lit = Lit(idx, spot.x, spot.y, spot.w, spot.h)
                    explain(MathSrc.tex(spot.node).trim())
                } else {
                    lit = Lit(idx, 0f, -pl.box.asc, pl.box.w, pl.box.height)
                    explain(src)
                }
                break
            }
        })
    }
    Canvas(Modifier.fillMaxWidth().height(with(density) { flow.height.toDp() }).then(tap)) {
        val l = lit
        if (l != null && l.index < flow.placed.size) {
            val pl = flow.placed[l.index]
            drawRoundRect(
                tintColor(9, env.ink),
                Offset(indent + pl.x + l.x - 2f, pl.baseline + l.y - 2f),
                Size(l.w + 4f, l.h + 4f),
                CornerRadius(5.dp.toPx()),
            )
        }
        if (marker != null && flow.placed.isNotEmpty()) {
            drawOps(painter, listOf(MOp.Text(0f, 0f, marker, size, F_PROSE)), 0f, flow.placed[0].baseline, env.ink)
        }
        for (pl in flow.placed) drawOps(painter, pl.box.ops, indent + pl.x, pl.baseline, env.ink)
    }
}

// ---- Display equations --------------------------------------------------------------------------------------------------------
@Composable
internal fun DisplayMath(tex: String, env: RichEnv, widthPx: Float) {
    val painter = env.painter
    val density = LocalDensity.current
    val size = with(density) { env.style.fontSize.toPx() } * 1.2f
    val verdict = remember(tex) { NumCheck.check(tex) }
    val tappable = env.onExplain != null
    val box = remember(tex, size, painter, verdict, tappable) {
        MathTex.parse(tex)?.let { ast ->
            MathLayout(painter.metrics, size, tappable).box(if (verdict == false) MNode.Tint(2, ast) else ast, true)
        }
    }
    if (box == null) {
        Text(tex, style = env.style.copy(color = env.ink, fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
        return
    }
    val padV = with(density) { 6.dp.toPx() }
    val contentW = max(box.w + padV * 2, widthPx)
    val ox = (contentW - box.w) / 2
    val oy = padV + box.asc
    var lit by remember(tex) { mutableStateOf<Lit?>(null) }
    LaunchedEffect(lit) { if (lit != null) { delay(1400); lit = null } }
    val explain = env.onExplain
    val tap = if (explain == null) Modifier else Modifier.pointerInput(box) {
        detectTapGestures(onTap = { off ->
            val slop = 6.dp.toPx()
            val spot = MathHit.find(box.ops, off.x - ox, off.y - oy, slop)
            if (spot != null) {
                lit = Lit(0, spot.x, spot.y, spot.w, spot.h)
                explain(MathSrc.tex(spot.node).trim())
            } else if (off.x >= ox - slop && off.x <= ox + box.w + slop) {
                lit = Lit(0, 0f, -box.asc, box.w, box.height)
                explain(tex)
            }
        })
    }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(with(density) { contentW.toDp() }).height(with(density) { (box.height + padV * 2).toDp() }).then(tap)) {
            val l = lit
            if (l != null) {
                drawRoundRect(tintColor(9, env.ink), Offset(ox + l.x - 3f, oy + l.y - 3f), Size(l.w + 6f, l.h + 6f), CornerRadius(6.dp.toPx()))
            }
            drawOps(painter, box.ops, ox, oy, env.ink)
            if (verdict != null) {
                val mark = painter.layout(if (verdict) "✓" else "✗ check this", F_PROSE, size * 0.52f)
                val c = tintColor(if (verdict) 1 else 2, env.ink)
                drawText(mark, color = c, topLeft = Offset(contentW - mark.size.width - padV * 0.6f, padV * 0.3f))
            }
        }
    }
}

// ---- The entry point --------------------------------------------------------------------------------------------------------
/**
 * Draws [text] the way the AI wrote it: prose, lists, bold, tables, callouts, graphs, diagrams and typeset maths.
 * [equation] says the whole string is a formula even though it has no dollar signs (the "you selected" text, a card's
 * answer): it is rescued as one formula. [onExplain] makes formulas tappable: it receives the LaTeX of the tapped piece.
 */
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LockInText.Sub,
    color: Color = Color.Unspecified,
    equation: Boolean = false,
    onExplain: ((String) -> Unit)? = null,
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
        val env = remember(painter, style, ink, onExplain) { RichEnv(painter, style, ink, onExplain) }
        val spokenText = remember(shown) { spokenMath(shown) }
        Box(Modifier.fillMaxWidth().semantics { contentDescription = spokenText }) {
            BlocksColumn(blocks, env, widthPx)
        }
    }
}

/** The blocks of an answer, one under another with the spacing a page would have. */
@Composable
internal fun BlocksColumn(blocks: List<Block>, env: RichEnv, widthPx: Float) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { i, b ->
            val above = when {
                i == 0 -> 0.dp
                b is Block.Para -> if (b.gapBefore) 8.dp else 3.dp
                b is Block.Heading -> 16.dp
                else -> 8.dp
            }
            if (above > 0.dp) Spacer(Modifier.height(above))
            when (b) {
                is Block.Para -> FlowPara(b, env, widthPx)
                is Block.Display -> DisplayMath(b.tex, env, widthPx)
                is Block.Heading -> HeadingBlock(b, env, widthPx)
                is Block.Table -> TableBlock(b, env, widthPx)
                is Block.Callout -> CalloutBlock(b, env, widthPx)
                is Block.Quote -> QuoteBlock(b, env, widthPx)
                is Block.Code -> CodeBlock(b, env)
                is Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Edge))
                is Block.Plot -> PlotBlock(b.spec, env, widthPx)
                is Block.Diagram -> DiagramBlock(b.spec, env, widthPx)
            }
        }
    }
}

private val SPOKEN_COMMAND = Regex("""\\([A-Za-z]+)""")
private val SPOKEN_FENCE = Regex("""```[a-z]*""")
private val SPOKEN_PUNCT = Regex("[${'$'}{}|>#\\\\]")
private val SPOKEN_SPACE = Regex("\\s+")

/** What a screen reader says for a formula: words, not LaTeX. */
internal fun spokenMath(text: String): String {
    val words = mapOf(
        "frac" to "fraction", "sqrt" to "square root of", "sum" to "sum", "int" to "integral", "pm" to "plus or minus",
        "times" to "times", "cdot" to "times", "le" to "less than or equal to", "leq" to "less than or equal to",
        "ge" to "greater than or equal to", "geq" to "greater than or equal to", "ne" to "not equal to",
        "neq" to "not equal to", "approx" to "approximately", "infty" to "infinity", "to" to "to", "left" to "", "right" to "",
        "textcolor" to "",
    )
    return text
        .replace(SPOKEN_COMMAND) { " " + (words[it.groupValues[1]] ?: it.groupValues[1]) + " " }
        .replace(SPOKEN_FENCE, " ")
        .replace("^", " to the power ")
        .replace("_", " sub ")
        .replace(SPOKEN_PUNCT, " ")
        .replace(SPOKEN_SPACE, " ")
        .trim()
}
