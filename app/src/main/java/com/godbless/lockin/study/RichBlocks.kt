package com.godbless.lockin.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * The blocks of an answer that are not a paragraph or an equation: headings, tables, callouts, quotes, code, graphs and
 * diagrams. MathText.kt chooses them (BlocksColumn); the parsing is in RichSplit.kt and the numbers in PlotMath.kt.
 * Everything is drawn from the theme (LockInColors), so it follows the accent and the light/dark setting.
 */

private fun scaled(t: TextUnit, f: Float): TextUnit = if (t.isSpecified) t * f else t

private fun List<Span>.styled(bold: Boolean, upper: Boolean): List<Span> = map { s ->
    if (s is Span.Txt) Span.Txt(if (upper) s.text.uppercase() else s.text, bold || s.bold, s.italic, s.code, s.color, s.mark) else s
}

// ---- Headings -----------------------------------------------------------------------------------------------------------------
@Composable
internal fun HeadingBlock(h: Block.Heading, env: RichEnv, widthPx: Float) {
    val base = env.style
    when (h.level) {
        1 -> FlowPara(
            Block.Para(h.spans.styled(bold = true, upper = false), null, false), env, widthPx,
            base.copy(fontSize = scaled(base.fontSize, 1.25f), lineHeight = scaled(base.lineHeight, 1.22f)),
        )
        2 -> FlowPara(
            Block.Para(h.spans.styled(bold = true, upper = true), null, false), env.withInk(LockInColors.Lime), widthPx,
            LockInText.Label.copy(fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold),
        )
        else -> FlowPara(
            Block.Para(h.spans.styled(bold = true, upper = false), null, false), env, widthPx,
            base.copy(fontSize = scaled(base.fontSize, 1.05f)),
        )
    }
}

// ---- Tables -------------------------------------------------------------------------------------------------------------------
internal class TableLayout(
    val colX: FloatArray,
    val colW: FloatArray,
    val rowY: FloatArray,
    val cells: List<List<TextFlow>>,
    val width: Float,
    val height: Float,
    val lineH: Float,
)

internal fun buildTable(t: Block.Table, painter: MathPainter, size: Float, lineH: Float, avail: Float, padX: Float, padY: Float): TableLayout {
    val n = t.header.size
    val rows: List<List<List<Span>>> = listOf(t.header.map { it.styled(bold = true, upper = false) }) + t.rows
    val natural = FloatArray(n) { 3.2f * size }
    for (row in rows) {
        for (c in 0 until n) {
            natural[c] = max(natural[c], buildFlow(row[c], painter, size, lineH, 1e6f, false).width + 2 * padX)
        }
    }
    val sum = natural.sum()
    val colW = FloatArray(n)
    if (sum <= avail) {
        val extra = avail - sum
        for (c in 0 until n) colW[c] = natural[c] + extra * natural[c] / sum
    } else {
        val cap = max(avail * 0.62f, 8f * size)
        for (c in 0 until n) colW[c] = min(natural[c], cap)
    }
    val cells = rows.map { row -> List(n) { c -> buildFlow(row[c], painter, size, lineH, colW[c] - 2 * padX, false) } }
    val rowH = cells.map { r -> max(lineH, r.maxOf { it.height }) + 2 * padY }
    val colX = FloatArray(n)
    var x = 0f
    for (c in 0 until n) { colX[c] = x; x += colW[c] }
    val rowY = FloatArray(rows.size + 1)
    for (i in rows.indices) rowY[i + 1] = rowY[i] + rowH[i]
    return TableLayout(colX, colW, rowY, cells, x, rowY[rows.size], lineH)
}

@Composable
internal fun TableBlock(t: Block.Table, env: RichEnv, widthPx: Float) {
    val density = LocalDensity.current
    val painter = env.painter
    val size = with(density) { env.style.fontSize.toPx() } * 0.94f
    val padX = with(density) { 10.dp.toPx() }
    val padY = with(density) { 7.dp.toPx() }
    val lay = remember(t, widthPx, size, painter) { buildTable(t, painter, size, size * 1.35f, widthPx, padX, padY) }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(with(density) { lay.width.toDp() }).height(with(density) { lay.height.toDp() })) {
            val r = 10.dp.toPx()
            val edge = LockInColors.Edge
            val round = Path().apply { addRoundRect(RoundRect(0f, 0f, lay.width, lay.height, CornerRadius(r))) }
            clipPath(round) {
                drawRect(LockInColors.Graphite.copy(alpha = 0.55f), Offset.Zero, Size(lay.width, lay.rowY[1]))
                for (i in 1 until lay.rowY.size - 1) drawLine(edge, Offset(0f, lay.rowY[i]), Offset(lay.width, lay.rowY[i]), 1.dp.toPx())
                for (c in 1 until lay.colX.size) drawLine(edge.copy(alpha = 0.55f), Offset(lay.colX[c], 0f), Offset(lay.colX[c], lay.height), 1.dp.toPx())
            }
            drawRoundRect(edge, Offset.Zero, Size(lay.width, lay.height), CornerRadius(r), style = Stroke(1.dp.toPx()))
            for ((ri, row) in lay.cells.withIndex()) {
                for ((c, flow) in row.withIndex()) {
                    val inner = lay.colW[c] - 2 * padX
                    val single = flow.height <= lay.lineH * 1.5f
                    val dx = if (!single) 0f else when (t.aligns[c]) { 'c' -> (inner - flow.width) / 2; 'r' -> inner - flow.width; else -> 0f }
                    for (pl in flow.placed) drawOps(painter, pl.box.ops, lay.colX[c] + padX + dx + pl.x, lay.rowY[ri] + padY + pl.baseline, env.ink)
                }
            }
        }
    }
}

// ---- Callouts and quotes ------------------------------------------------------------------------------------------------------
@Composable
internal fun CalloutBlock(c: Block.Callout, env: RichEnv, widthPx: Float) {
    val density = LocalDensity.current
    val (label, idx) = when (c.kind) {
        "tip" -> "Tip" to 1
        "warning" -> "Watch out" to 2
        "careful" -> "Careful" to 2
        "remember" -> "Remember" to 3
        "key idea" -> "Key idea" to 3
        "example" -> "Example" to 4
        else -> "Note" to 5
    }
    val accent = tintColor(idx, env.ink)
    val inner = widthPx - with(density) { 26.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.11f))
            .drawBehind { drawRect(accent, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            label.uppercase(),
            style = LockInText.Label.copy(color = accent, fontSize = 11.5.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        BlocksColumn(c.body, env, inner)
    }
}

@Composable
internal fun QuoteBlock(q: Block.Quote, env: RichEnv, widthPx: Float) {
    val density = LocalDensity.current
    val bar = env.ink.copy(alpha = 0.35f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(bar, Offset.Zero, Size(2.5f.dp.toPx(), size.height)) }
            .padding(start = 14.dp),
    ) {
        BlocksColumn(q.body, env.withInk(env.ink.copy(alpha = 0.82f)), widthPx - with(density) { 14.dp.toPx() })
    }
}

// ---- Code -----------------------------------------------------------------------------------------------------------------------
@Composable
internal fun CodeBlock(b: Block.Code, env: RichEnv) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LockInColors.Graphite.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (b.lang.isEmpty()) "code" else b.lang).uppercase(),
                style = LockInText.Label.copy(color = LockInColors.Mist, fontSize = 10.5.sp, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (copied) "Copied" else "Copy",
                style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 12.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { clipboard.setText(AnnotatedString(b.text)); copied = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Text(
            b.text,
            style = env.style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = env.ink),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

// ---- Graphs ---------------------------------------------------------------------------------------------------------------------
private fun fmt(v: Double): String {
    val s = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0" || s.isEmpty()) "0" else s
}

/** Round tick positions and their labels, made once per range rather than on every frame. */
private fun ticks(lo: Double, hi: Double): List<Pair<Double, String>> {
    val step = PlotCalc.niceStep(hi - lo)
    val out = ArrayList<Pair<Double, String>>()
    var v = ceil(lo / step) * step
    while (v <= hi + 1e-9 && out.size < 40) { out.add(Pair(v, fmt(v))); v += step }
    return out
}

private fun DrawScope.drawPlot(
    painter: MathPainter,
    spec: PlotSpec,
    s: PlotCalc.Sampled,
    yMin: Double,
    yMax: Double,
    xTicks: List<Pair<Double, String>>,
    yTicks: List<Pair<Double, String>>,
    ink: Color,
    label: Float,
) {
    val left = label * 3.4f
    val right = label * 0.9f
    val top = label * 0.7f
    val bottom = label * 2.1f
    val pw = size.width - left - right
    val ph = size.height - top - bottom
    fun px(x: Double) = (left + (x - spec.xMin) / (spec.xMax - spec.xMin) * pw).toFloat()
    fun py(y: Double) = (top + (1 - (y - yMin) / (yMax - yMin)) * ph).toFloat()
    val grid = LockInColors.Graphite
    val axis = LockInColors.Mist
    val hair = 1.dp.toPx()

    for ((v, text) in xTicks) {
        drawLine(grid, Offset(px(v), top), Offset(px(v), top + ph), hair)
        val lr = painter.layout(text, F_PROSE, label)
        drawText(lr, color = axis, topLeft = Offset(px(v) - lr.size.width / 2f, top + ph + label * 0.5f))
    }
    for ((v, text) in yTicks) {
        drawLine(grid, Offset(left, py(v)), Offset(left + pw, py(v)), hair)
        val lr = painter.layout(text, F_PROSE, label)
        drawText(lr, color = axis, topLeft = Offset(left - lr.size.width - label * 0.5f, py(v) - lr.size.height / 2f))
    }
    if (yMin < 0 && 0 < yMax) drawLine(axis, Offset(left, py(0.0)), Offset(left + pw, py(0.0)), 1.6.dp.toPx())
    if (spec.xMin < 0 && 0 < spec.xMax) drawLine(axis, Offset(px(0.0), top), Offset(px(0.0), top + ph), 1.6.dp.toPx())

    val colors = listOf(tintColor(1, ink), tintColor(3, ink), tintColor(2, ink))
    clipRect(left, top, left + pw, top + ph) {
        for ((ci, ys) in s.ys.withIndex()) {
            var path = Path()
            var open = false
            var prev = 0f
            fun finish() {
                if (open) drawPath(path, colors[ci % colors.size], style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                path = Path()
                open = false
            }
            for (i in ys.indices) {
                val y = ys[i]
                if (y.isNaN() || y.isInfinite()) { finish(); continue }
                val yy = py(y)
                val xx = px(s.xs[i])
                if (abs(yy) > ph * 6 || (open && abs(yy - prev) > ph * 1.5f)) { finish(); prev = yy; continue }
                if (!open) { path.moveTo(xx, yy); open = true } else path.lineTo(xx, yy)
                prev = yy
            }
            finish()
        }
    }
    for (p in spec.points) {
        if (p.x < spec.xMin || p.x > spec.xMax || p.y < yMin || p.y > yMax) continue
        val c = Offset(px(p.x), py(p.y))
        drawCircle(ink, 4.dp.toPx(), c)
        drawCircle(LockInColors.Lime, 2.dp.toPx(), c)
        if (p.label.isNotEmpty()) {
            val lr = painter.layout(p.label, F_PROSE, label * 1.05f)
            drawText(lr, color = ink, topLeft = Offset(c.x + 7.dp.toPx(), c.y - 7.dp.toPx() - lr.size.height))
        }
    }
}

@Composable
internal fun PlotBlock(spec: PlotSpec, env: RichEnv, widthPx: Float) {
    val density = LocalDensity.current
    val values = remember(spec) { mutableStateMapOf<Char, Double>().also { m -> spec.params.forEach { m[it.name] = it.value } } }
    val current = spec.params.associate { it.name to (values[it.name] ?: it.value) }
    val sampled = remember(spec, current) { PlotCalc.sample(spec, current) }
    val yr = remember(spec, sampled) { PlotCalc.yRange(spec, sampled) }
    val xTicks = remember(spec) { ticks(spec.xMin, spec.xMax) }
    val yTicks = remember(yr) { ticks(yr.first, yr.second) }
    val h = (widthPx * 0.62f).coerceIn(with(density) { 190.dp.toPx() }, with(density) { 300.dp.toPx() })
    val label = with(density) { env.style.fontSize.toPx() } * 0.74f
    val colors = listOf(tintColor(1, env.ink), tintColor(3, env.ink), tintColor(2, env.ink))
    Column(Modifier.fillMaxWidth()) {
        if (spec.title.isNotBlank()) {
            MathText(spec.title, style = env.style.copy(fontWeight = FontWeight.SemiBold), color = env.ink)
            Spacer(Modifier.height(6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { h.toDp() })) {
            drawPlot(env.painter, spec, sampled, yr.first, yr.second, xTicks, yTicks, env.ink, label)
        }
        for ((i, c) in spec.curves.withIndex()) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                Spacer(Modifier.width(8.dp))
                MathText("y = " + c.src, equation = true, style = env.style.copy(fontSize = scaled(env.style.fontSize, 0.95f)), color = env.ink)
            }
        }
        for (p in spec.params) {
            val v = values[p.name] ?: p.value
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} = ${fmt(v)}", style = LockInText.Label.copy(color = LockInColors.Silver), modifier = Modifier.width(78.dp))
                Slider(
                    value = v.toFloat(),
                    onValueChange = { values[p.name] = it.toDouble() },
                    valueRange = p.lo.toFloat()..p.hi.toFloat(),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = LockInColors.Lime,
                        activeTrackColor = LockInColors.Lime,
                        inactiveTrackColor = LockInColors.Graphite,
                    ),
                )
            }
        }
    }
}

// ---- Diagrams -------------------------------------------------------------------------------------------------------------------
private fun edgePoint(r: NodeRect, toX: Float, toY: Float): Offset {
    val dx = toX - r.cx
    val dy = toY - r.cy
    if (dx == 0f && dy == 0f) return Offset(r.cx, r.cy)
    val tx = if (dx != 0f) (r.w / 2) / abs(dx) else Float.MAX_VALUE
    val ty = if (dy != 0f) (r.h / 2) / abs(dy) else Float.MAX_VALUE
    val t = min(tx, ty)
    return Offset(r.cx + dx * t, r.cy + dy * t)
}

@Composable
internal fun DiagramBlock(spec: DiagramSpec, env: RichEnv, widthPx: Float) {
    if (spec.kind == "timeline") { TimelineBlock(spec, env, widthPx); return }
    val density = LocalDensity.current
    val painter = env.painter
    val size = with(density) { env.style.fontSize.toPx() } * 0.92f
    val padX = with(density) { 10.dp.toPx() }
    val padY = with(density) { 7.dp.toPx() }
    val gapX = with(density) { 12.dp.toPx() }
    val gapY = with(density) { 30.dp.toPx() }
    val margin = with(density) { 6.dp.toPx() }
    // Each box: the label on one line if it fits, else wrapped to the widest the row allows.
    val sizeFor = { text: String, maxW: Float ->
        val natural = painter.layout(text, F_PROSE, size).size.width.toFloat()
        val inner = min(natural + 2f, max(maxW - 2 * padX, 24f)).toInt()
        val lr = painter.wrapped(text, size, inner)
        Pair(inner + 2 * padX, lr.size.height + 2 * padY)
    }
    val lay = remember(spec, widthPx, size, painter) {
        if (spec.kind == "cycle") DiagramPlacer.cycle(spec, widthPx - 2 * margin, sizeFor)
        else DiagramPlacer.flow(spec, widthPx - 2 * margin, gapX, gapY, sizeFor)
    }
    Canvas(Modifier.fillMaxWidth().height(with(density) { (lay.height + 2 * margin).toDp() })) {
        val arrow = LockInColors.Mist
        val sw = 1.6.dp.toPx()
        for ((a, b, text) in spec.edges) {
            val ra = lay.rects[a]
            val rb = lay.rects[b]
            val p0 = edgePoint(ra, rb.cx, rb.cy)
            val p1 = edgePoint(rb, ra.cx, ra.cy)
            val o = Offset(margin, margin)
            drawLine(arrow, p0 + o, p1 + o, sw, StrokeCap.Round)
            val ang = atan2(p1.y - p0.y, p1.x - p0.x)
            val head = 8.dp.toPx()
            for (side in floatArrayOf(-0.45f, 0.45f)) {
                drawLine(arrow, p1 + o, Offset(p1.x - head * cos(ang + side), p1.y - head * sin(ang + side)) + o, sw, StrokeCap.Round)
            }
            if (text != null) {
                val lr = painter.layout(text, F_PROSE, size * 0.82f)
                drawText(lr, color = LockInColors.Lime, topLeft = Offset((p0.x + p1.x) / 2 + margin + 6.dp.toPx(), (p0.y + p1.y) / 2 + margin - lr.size.height / 2f))
            }
        }
        for ((i, r) in lay.rects.withIndex()) {
            val x = r.x + margin
            val y = r.y + margin
            drawRoundRect(LockInColors.Graphite.copy(alpha = 0.55f), Offset(x, y), Size(r.w, r.h), CornerRadius(10.dp.toPx()))
            drawRoundRect(LockInColors.Lime.copy(alpha = 0.8f), Offset(x, y), Size(r.w, r.h), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
            val lr = painter.wrapped(spec.nodes[i], size, (r.w - 2 * padX).toInt())
            drawText(lr, color = env.ink, topLeft = Offset(x + padX, y + padY))
        }
    }
}

@Composable
private fun TimelineBlock(spec: DiagramSpec, env: RichEnv, widthPx: Float) {
    val last = spec.items.size - 1
    Column(Modifier.fillMaxWidth()) {
        for ((i, item) in spec.items.withIndex()) {
            val (whenText, what) = item
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 9.dp.toPx()
                        val dotY = 11.dp.toPx()
                        val line = LockInColors.Graphite
                        drawLine(line, Offset(x, if (i == 0) dotY else 0f), Offset(x, if (i == last) dotY else size.height), 2.dp.toPx())
                        drawCircle(LockInColors.Lime, 5.dp.toPx(), Offset(x, dotY))
                    }
                    .padding(start = 28.dp, bottom = if (i == last) 0.dp else 14.dp),
            ) {
                if (whenText.isNotEmpty()) {
                    Text(whenText, style = LockInText.Label.copy(color = LockInColors.Lime, fontWeight = FontWeight.Bold))
                }
                MathText(what, style = env.style, color = env.ink)
            }
        }
    }
}
