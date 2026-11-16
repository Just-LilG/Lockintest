package com.godbless.lockin.study

import androidx.activity.compose.BackHandler
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// The calculator's other modes, each on its own page: STAT, MATRIX, VECTOR, DISTR and BASE-N. Every number
// box accepts a little sum too (1/2, 3+4), the same way the main screen does.

private val Shown = NumFormat(NumFormat.Kind.Norm2, 11)

private fun show(v: Double) = Calc.format(v, Shown)

/** A number box's text as a number: blank is null, a bad sum is an error. */
private fun cell(text: String): Double? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    return Calc.eval(t, AngleMode.Deg, 0.0)
}

private fun cellOr(text: String, default: Double): Double = cell(text) ?: default

private fun unpack(s: String, n: Int): List<String> = s.split('|').let { l -> List(n) { l.getOrElse(it) { "" } } }

private fun pack(l: List<String>) = l.joinToString("|")

private fun clean(t: String) = t.replace("|", "").replace(";", "")

@Composable
internal fun CalcPageFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(CC.Body)
            .padding(top = topPad)
            .navigationBarsPadding()
            .imePadding()
            .readable(Widths.Sheet)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(title, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp, color = CC.Ink), modifier = Modifier.weight(1f).padding(start = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> CalcPill(l, { onPick(i) }, on = i == selected) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = LockInText.Sub.copy(color = CC.Dim, fontSize = 14.sp))
        CalcPill("−", { if (value > min) onChange(value - 1) })
        Text(value.toString(), style = LockInText.Title.copy(color = CC.Ink, fontSize = 18.sp, lineHeight = 22.sp), modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        CalcPill("+", { if (value < max) onChange(value + 1) })
    }
}

private class Line(val label: String, val value: String, val hi: Boolean = false)

@Composable
private fun Lines(lines: List<Line>, note: String, isError: Boolean) {
    if (lines.isEmpty()) {
        if (isError) ErrorText(note) else Text(note, style = LockInText.Sub.copy(color = CC.Dim))
    } else {
        lines.forEach { CalcResultRow(it.label, it.value, it.hi) }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, style = LockInText.Error.copy(color = CC.Bad, fontSize = 15.sp))
}

/** Numbers pasted from a sheet or a message become rows: one per line (or pairs, for two variables). */
private fun parseList(text: String, two: Boolean): String? {
    val rows = ArrayList<List<String>>()
    fun nums(line: String) = line.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() && it.toDoubleOrNull() != null }
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (two) {
        val loose = ArrayList<String>()
        for (l in lines) {
            val n = nums(l)
            if (n.size >= 2) rows += listOf(n[0], n[1], "") else loose += n
        }
        var k = 0
        while (k + 1 < loose.size) { rows += listOf(loose[k], loose[k + 1], ""); k += 2 }
    } else {
        for (l in lines) for (n in nums(l)) rows += listOf(n, "", "")
    }
    if (rows.isEmpty()) return null
    return rows.take(60).joinToString(";") { pack(it) }
}

// ---- STAT -----------------------------------------------------------------------------------

@Composable
internal fun StatPage(onBack: () -> Unit) {
    var kind by rememberSaveable { mutableIntStateOf(0) } // 0 is one variable, then the regression types
    var rows by rememberSaveable { mutableStateOf("||;||;||") } // each row is x|y|frequency
    var xq by rememberSaveable { mutableStateOf("") }
    var yq by rememberSaveable { mutableStateOf("") }
    val two = kind != 0
    val table = rows.split(';').map { unpack(it, 3) }

    fun setCell(r: Int, c: Int, v: String) {
        rows = table.mapIndexed { i, row -> if (i == r) row.toMutableList().also { it[c] = clean(v) } else row }.joinToString(";") { pack(it) }
    }

    val clipboard = LocalClipboardManager.current
    CalcPageFrame("Statistics", onBack) {
        Chips(listOf("1-Var") + Reg.entries.map { it.label }, kind) { kind = it }
        CalcSectionTitle(if (two) "Data: x and y" else "Data: values and how often")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("", Modifier.width(24.dp))
            Text("x", style = LockInText.Label.copy(color = CC.Amber), modifier = Modifier.weight(1f))
            Text(if (two) "y" else "freq", style = LockInText.Label.copy(color = CC.Amber), modifier = Modifier.weight(1f))
            Text("", Modifier.width(36.dp))
        }
        table.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((r + 1).toString(), style = LockInText.Small.copy(color = CC.Dim), modifier = Modifier.width(24.dp))
                CalcField(row[0], { setCell(r, 0, it) }, Modifier.weight(1f))
                CalcField(if (two) row[1] else row[2], { setCell(r, if (two) 1 else 2, it) }, Modifier.weight(1f), hint = if (two) "" else "1")
                Text(
                    "✕",
                    style = LockInText.Label.copy(color = CC.Dim, fontSize = 16.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.width(36.dp).clickable(role = Role.Button) {
                        if (table.size > 1) rows = table.filterIndexed { i, _ -> i != r }.joinToString(";") { pack(it) }
                    }.padding(6.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcPill("+ Row", { if (table.size < 60) rows = rows + ";||" })
            CalcPill("Paste list", { parseList(clipboard.getText()?.text.orEmpty(), two)?.let { rows = it } })
            CalcPill("Clear data", { rows = "||;||;||"; xq = ""; yq = "" }, accent = CC.Bad)
        }

        // Work out what the data says.
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val fs = ArrayList<Double>()
        var problem = ""
        try {
            for (row in table) {
                val x = cell(row[0]) ?: continue
                if (two) {
                    val y = cell(row[1]) ?: continue
                    xs += x; ys += y
                } else {
                    xs += x; fs += cellOr(row[2], 1.0)
                }
            }
        } catch (e: CalcException) {
            problem = e.message ?: "Check the numbers"
        }

        val out = ArrayList<Line>()
        var note = problem
        var isError = problem.isNotEmpty()
        if (note.isEmpty()) {
            if (xs.size < (if (two) 2 else 1)) {
                note = if (two) "Enter at least two pairs" else "Enter some values"
            } else try {
                if (!two) {
                    val st = Stat.oneVar(xs, fs)
                    out += Line("n", show(st.n)); out += Line("Σx", show(st.sumX)); out += Line("Σx²", show(st.sumX2))
                    out += Line("mean x̄", show(st.mean), true); out += Line("σx (population)", show(st.popSd)); out += Line("sx (sample)", show(st.sampleSd))
                    out += Line("min", show(st.min)); out += Line("Q1", show(st.q1)); out += Line("median", show(st.median))
                    out += Line("Q3", show(st.q3)); out += Line("max", show(st.max))
                } else {
                    val fit = Stat.regress(Reg.entries[kind - 1], xs, ys)
                    out += Line("a", show(fit.a), true); out += Line("b", show(fit.b), true)
                    if (fit.type == Reg.Quadratic) out += Line("c", show(fit.c), true)
                    fit.r?.let { out += Line("r", show(it)) }
                }
            } catch (e: CalcException) {
                out.clear(); note = e.message ?: "Math error"; isError = true
            }
        }
        CalcSectionTitle("Results")
        CalcCard { Lines(out, note, isError) }

        if (two && problem.isEmpty() && xs.size >= 2) {
            CalcSectionTitle("Estimate")
            val fit = try { Stat.regress(Reg.entries[kind - 1], xs, ys) } catch (e: CalcException) { null }
            if (fit != null) {
                CalcCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("x =", style = LockInText.Label.copy(color = CC.Dim))
                        CalcField(xq, { xq = it }, Modifier.weight(1f))
                    }
                    val xv = try { cell(xq) } catch (e: CalcException) { null }
                    if (xv != null) {
                        val est = try { show(fit.predictY(xv)) } catch (e: CalcException) { "Math error" }
                        CalcResultRow("ŷ", if (est.contains("NaN")) "Math error" else est, true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("y =", style = LockInText.Label.copy(color = CC.Dim))
                        CalcField(yq, { yq = it }, Modifier.weight(1f))
                    }
                    val yv = try { cell(yq) } catch (e: CalcException) { null }
                    if (yv != null) {
                        val est = fit.predictX(yv).filter { !it.isNaN() }
                        if (est.isEmpty()) CalcResultRow("x̂", "Math error") else est.forEachIndexed { i, v -> CalcResultRow(if (est.size > 1) "x̂${i + 1}" else "x̂", show(v), true) }
                    }
                }
            }
        }
    }
}

// ---- MATRIX ---------------------------------------------------------------------------------

private val MatrixOps = listOf("A+B", "A−B", "A×B", "B×A", "det A", "det B", "A⁻¹", "B⁻¹", "Aᵀ", "A²", "k×A")

private fun matOf(text: String, rows: Int, cols: Int): Mat {
    val cells = unpack(text, 16)
    return Mat(rows, cols, DoubleArray(rows * cols) { i -> cellOr(cells[(i / cols) * 4 + i % cols], 0.0) })
}

@Composable
private fun MatrixEditor(name: String, rows: Int, cols: Int, text: String, onDims: (Int, Int) -> Unit, onText: (String) -> Unit) {
    CalcCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(name, style = LockInText.Title.copy(color = CC.Amber, fontSize = 22.sp, lineHeight = 26.sp))
            Stepper("rows", rows, 1, 4) { onDims(it, cols) }
            Stepper("cols", cols, 1, 4) { onDims(rows, it) }
        }
        val cells = unpack(text, 16)
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until cols) {
                    CalcField(cells[r * 4 + c], { v -> onText(pack(cells.mapIndexed { i, old -> if (i == r * 4 + c) clean(v) else old })) }, Modifier.weight(1f), signed = cols <= 3)
                }
            }
        }
    }
}

@Composable
internal fun MatrixPage(onBack: () -> Unit) {
    var ar by rememberSaveable { mutableIntStateOf(2) }
    var ac by rememberSaveable { mutableIntStateOf(2) }
    var br by rememberSaveable { mutableIntStateOf(2) }
    var bc by rememberSaveable { mutableIntStateOf(2) }
    var ta by rememberSaveable { mutableStateOf("1|2|||3|4|||||||||") }
    var tb by rememberSaveable { mutableStateOf("1|0|||0|1|||||||||") }
    var op by rememberSaveable { mutableIntStateOf(0) }
    var k by rememberSaveable { mutableStateOf("2") }

    CalcPageFrame("Matrix", onBack) {
        MatrixEditor("A", ar, ac, ta, { r, c -> ar = r; ac = c }, { ta = it })
        MatrixEditor("B", br, bc, tb, { r, c -> br = r; bc = c }, { tb = it })
        CalcSectionTitle("Do")
        Chips(MatrixOps, op) { op = it }
        if (op == 10) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("k =", style = LockInText.Label.copy(color = CC.Dim))
                CalcField(k, { k = it }, Modifier.weight(1f))
            }
        }
        var matOut: Mat? = null
        var scalarOut: Double? = null
        var err = ""
        try {
            val a = matOf(ta, ar, ac)
            val b = matOf(tb, br, bc)
            val out: Any = when (op) {
                0 -> a + b
                1 -> a - b
                2 -> a * b
                3 -> b * a
                4 -> a.det()
                5 -> b.det()
                6 -> a.inverse()
                7 -> b.inverse()
                8 -> a.transpose()
                9 -> a.pow(2)
                else -> a * cellOr(k, 1.0)
            }
            if (out is Mat) matOut = out else if (out is Double) scalarOut = out
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        val shownMat = matOut
        val shownScalar = scalarOut
        CalcSectionTitle("Answer")
        CalcCard {
            if (err.isNotEmpty()) {
                ErrorText(err)
            } else if (shownMat != null) {
                for (r in 0 until shownMat.rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 0 until shownMat.cols) {
                            Text(
                                show(shownMat[r, c]),
                                style = LockInText.Label.copy(color = CC.Good, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else if (shownScalar != null) {
                CalcResultRow(MatrixOps[op], show(shownScalar), true)
            }
        }
    }
}

// ---- VECTOR ---------------------------------------------------------------------------------

private val VectorOps = listOf("A+B", "A−B", "A·B", "A×B", "|A|", "|B|", "angle", "unit A")

@Composable
internal fun VectorPage(onBack: () -> Unit) {
    var dim by rememberSaveable { mutableIntStateOf(3) }
    var ta by rememberSaveable { mutableStateOf("1|2|3") }
    var tb by rememberSaveable { mutableStateOf("4|5|6") }
    var op by rememberSaveable { mutableIntStateOf(0) }

    @Composable
    fun editor(name: String, text: String, onText: (String) -> Unit) {
        CalcCard {
            Text(name, style = LockInText.Title.copy(color = CC.Amber, fontSize = 22.sp, lineHeight = 26.sp))
            val cells = unpack(text, 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until dim) {
                    CalcField(cells[i], { v -> onText(pack(cells.mapIndexed { j, old -> if (j == i) clean(v) else old })) }, Modifier.weight(1f), hint = "xyz"[i].toString(), signed = dim <= 3)
                }
            }
        }
    }

    CalcPageFrame("Vector", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dimension", style = LockInText.Sub.copy(color = CC.Dim, fontSize = 14.sp))
            CalcPill("2D", { dim = 2 }, on = dim == 2)
            CalcPill("3D", { dim = 3 }, on = dim == 3)
        }
        editor("A", ta) { ta = it }
        editor("B", tb) { tb = it }
        CalcSectionTitle("Do")
        Chips(VectorOps, op) { op = it }
        val out = ArrayList<Line>()
        var err = ""
        try {
            val ca = unpack(ta, 3); val cb = unpack(tb, 3)
            val a = Vec(DoubleArray(dim) { cellOr(ca[it], 0.0) })
            val b = Vec(DoubleArray(dim) { cellOr(cb[it], 0.0) })
            when (op) {
                0, 1, 3, 7 -> {
                    val v = when (op) { 0 -> a + b; 1 -> a - b; 3 -> a.cross(b); else -> a.unit() }
                    out += Line("(" + "xyz".take(v.size).map { "$it" }.joinToString(", ") + ")", v.v.joinToString(", ") { show(it) }, true)
                }
                2 -> out += Line("A · B", show(a.dot(b)), true)
                4 -> out += Line("|A|", show(a.norm()), true)
                5 -> out += Line("|B|", show(b.norm()), true)
                else -> {
                    val rad = a.angle(b)
                    out += Line("degrees", show(Calc.snap(Math.toDegrees(rad))), true)
                    out += Line("radians", show(rad))
                }
            }
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        CalcSectionTitle("Answer")
        CalcCard { Lines(out, err, true) }
    }
}

// ---- DISTR ----------------------------------------------------------------------------------

private class Spec(val label: String, val default: Double?)

private class DistrKind(val name: String, val specs: List<Spec>)

private val DistrKinds = listOf(
    DistrKind("Normal PD", listOf(Spec("x", null), Spec("σ", 1.0), Spec("μ", 0.0))),
    DistrKind("Normal CD", listOf(Spec("lower", null), Spec("upper", null), Spec("σ", 1.0), Spec("μ", 0.0))),
    DistrKind("Inverse Normal", listOf(Spec("area", null), Spec("σ", 1.0), Spec("μ", 0.0))),
    DistrKind("Binomial PD", listOf(Spec("x", null), Spec("n", null), Spec("p", null))),
    DistrKind("Binomial CD", listOf(Spec("x", null), Spec("n", null), Spec("p", null))),
    DistrKind("Poisson PD", listOf(Spec("x", null), Spec("λ", null))),
    DistrKind("Poisson CD", listOf(Spec("x", null), Spec("λ", null))),
)

@Composable
internal fun DistrPage(onBack: () -> Unit) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var vals by rememberSaveable { mutableStateOf("|||") }
    val d = DistrKinds[kind]
    val cells = unpack(vals, 4)

    CalcPageFrame("Distribution", onBack) {
        Chips(DistrKinds.map { it.name }, kind) { kind = it }
        CalcCard {
            d.specs.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.label, style = LockInText.Label.copy(color = CC.Amber, fontSize = 16.sp), modifier = Modifier.width(60.dp))
                    CalcField(cells[i], { v -> vals = pack(cells.mapIndexed { j, old -> if (j == i) clean(v) else old }) }, Modifier.weight(1f), hint = s.default?.let { show(it) } ?: "")
                }
            }
        }
        val out = ArrayList<Line>()
        var err = ""
        try {
            val v = d.specs.mapIndexed { i, sp -> cell(cells[i]) ?: sp.default ?: throw CalcException("Fill in ${sp.label}") }
            out += when (kind) {
                0 -> Line("P(x)", show(Distr.normalPdf(v[0], v[1], v[2])), true)
                1 -> Line("p", show(Distr.normalCdf(v[0], v[1], v[2], v[3])), true)
                2 -> Line("x", show(Distr.invNormal(v[0], v[1], v[2])), true)
                3 -> Line("P(X = x)", show(Distr.binomialPdf(v[0], v[1], v[2])), true)
                4 -> Line("P(X ≤ x)", show(Distr.binomialCdf(v[0], v[1], v[2])), true)
                5 -> Line("P(X = x)", show(Distr.poissonPdf(v[0], v[1])), true)
                else -> Line("P(X ≤ x)", show(Distr.poissonCdf(v[0], v[1])), true)
            }
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        CalcSectionTitle("Answer")
        CalcCard { Lines(out, err, true) }
    }
}

// ---- BASE-N ---------------------------------------------------------------------------------

private val BaseRows = listOf(
    listOf("and", "or", "xor", "xnor", "not(", "neg("),
    listOf("A", "B", "C", "D", "E", "F"),
    listOf("7", "8", "9", "(", ")", "DEL"),
    listOf("4", "5", "6", "×", "÷", "AC"),
    listOf("1", "2", "3", "+", "−", "="),
)

@Composable
internal fun BasePage(onBack: () -> Unit) {
    var base by rememberSaveable { mutableStateOf(Base.Dec) }
    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") } // the answer as text, or empty
    var problem by rememberSaveable { mutableStateOf("") }
    val value: Long? = result.toLongOrNull()

    fun keyOk(k: String): Boolean = when {
        k.length == 1 && (k[0].isDigit() || k[0] in 'A'..'F') -> Character.digit(k[0], base.radix) >= 0
        else -> true
    }

    fun press(k: String) {
        problem = ""
        when (k) {
            "AC" -> { text = ""; result = "" }
            "DEL" -> {
                if (result.isNotEmpty()) { text = ""; result = "" } else {
                    val tokens = listOf("xnor", "and", "xor", "not(", "neg(", "or")
                    val cut = tokens.firstOrNull { text.endsWith(it) }?.length ?: 1
                    text = text.dropLast(cut)
                }
            }
            "=" -> {
                if (text.isBlank()) return
                try {
                    val v = BaseN.eval(text, base)
                    result = v.toString()
                } catch (e: CalcException) {
                    problem = e.message ?: "Math error"
                }
            }
            else -> {
                if (!keyOk(k)) return
                val word = k == "and" || k == "or" || k == "xor" || k == "xnor"
                if (result.isNotEmpty()) {
                    val carry = word || k in listOf("+", "−", "×", "÷")
                    text = if (carry) BaseN.format(value ?: 0L, base) else ""
                    result = ""
                }
                text += if (word) " $k " else k
            }
        }
    }

    CalcPageFrame("Base-N", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Base.entries.forEach { b ->
                CalcPill(b.label, {
                    base = b
                    problem = ""
                    // Show the answer in the new base instead of losing it.
                    text = if (value != null) BaseN.format(value, b) else ""
                    if (value != null) result = value.toString() else result = ""
                }, Modifier.weight(1f), on = base == b)
            }
        }
        val theme = LcdThemes[0]
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(androidx.compose.ui.graphics.Color(theme.screen)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = androidx.compose.ui.graphics.Color(theme.ink)
            val dim = androidx.compose.ui.graphics.Color(theme.dim)
            Text(
                if (text.isEmpty()) " " else text,
                style = LockInText.Title.copy(color = ink, fontSize = 24.sp, lineHeight = 30.sp),
                maxLines = 2,
            )
            if (problem.isNotEmpty()) {
                Text(problem, style = LockInText.Error.copy(color = androidx.compose.ui.graphics.Color(0xFFB3261E), fontSize = 18.sp))
            } else {
                Base.entries.forEach { b ->
                    val line = if (value != null) BaseN.format(value, b) else ""
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, style = LockInText.Small.copy(color = if (b == base) ink else dim, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(48.dp))
                        Text(
                            line.ifEmpty { "–" },
                            style = LockInText.Headline.copy(color = if (b == base) ink else dim, fontSize = if (b == base) 30.sp else 20.sp, lineHeight = 36.sp, textAlign = TextAlign.End),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        BaseRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val ok = keyOk(k)
                    val shape2 = RoundedCornerShape(14.dp)
                    val word = k.length > 1 && k != "DEL" && k != "AC"
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(shape2)
                            .background(if (k == "=") CC.Amber else if (k == "AC" || k == "DEL") CC.Orange else if (word) CC.KeyFn else CC.KeyNum)
                            .clickable(enabled = ok, role = Role.Button) { press(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            k.removeSuffix("("),
                            style = LockInText.Label.copy(
                                color = if (!ok) CC.Dim.copy(alpha = 0.4f) else if (k == "=" || k == "AC" || k == "DEL") CC.Body else CC.Ink,
                                fontSize = if (word) 14.sp else 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}


// ---- EQN ------------------------------------------------------------------------------------

private class EqnKind(val name: String, val rows: List<List<String>>, val defaults: String)

private val EqnKinds = listOf(
    EqnKind("Quadratic", listOf(listOf("x² +", "x +", "= 0")), "1|-5|6"),
    EqnKind("Cubic", listOf(listOf("x³ +", "x² +", "x +", "= 0")), "1|-6|11|-6"),
    EqnKind("2 unknowns", listOf(listOf("x +", "y =", ""), listOf("x +", "y =", "")), "1|1|3|1|-1|1"),
    EqnKind("3 unknowns", List(3) { listOf("x +", "y +", "z =", "") }, "1|1|1|6|0|2|5|-4|2|5|-1|27"),
)

@Composable
internal fun EqnPage(onBack: () -> Unit) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var vals by rememberSaveable { mutableStateOf(EqnKinds[0].defaults) }
    val spec = EqnKinds[kind]
    val cells = unpack(vals, 12)
    val starts = spec.rows.runningFold(0) { acc, r -> acc + r.size }

    CalcPageFrame("Equations", onBack) {
        Chips(EqnKinds.map { it.name }, kind) { kind = it; vals = EqnKinds[it].defaults }
        CalcCard {
            spec.rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { j, suffix ->
                        val idx = starts[r] + j
                        CalcField(cells[idx], { v -> vals = pack(cells.mapIndexed { n, old -> if (n == idx) clean(v) else old }) }, Modifier.weight(1f), signed = false)
                        if (suffix.isNotEmpty()) Text(suffix, style = LockInText.Label.copy(color = CC.Amber, fontSize = 15.sp), maxLines = 1)
                    }
                }
            }
            Text("Blank boxes count as 0. Tap a box and use the minus on your keyboard, or type a sum like 1/2.", style = LockInText.Small.copy(color = CC.Dim, fontSize = 12.sp))
        }
        val out = ArrayList<Line>()
        var err = ""
        try {
            val count = starts.last()
            val v = List(count) { cellOr(cells[it], 0.0) }
            fun cx(c: Cx) = CalcComplex.format(c, false, AngleMode.Deg, Shown)
            when (kind) {
                0 -> {
                    if (v[0] == 0.0) throw CalcException("a can't be 0")
                    Equations.quadratic(v[0], v[1], v[2]).forEachIndexed { i, c -> out += Line("x${i + 1}", cx(c), true) }
                    out += Line("Δ", show(v[1] * v[1] - 4 * v[0] * v[2]))
                }
                1 -> {
                    if (v[0] == 0.0) throw CalcException("a can't be 0")
                    Equations.cubic(v[0], v[1], v[2], v[3]).forEachIndexed { i, c -> out += Line("x${i + 1}", cx(c), true) }
                }
                2 -> Equations.system(listOf(v.subList(0, 3), v.subList(3, 6))).forEachIndexed { i, r -> out += Line("xy"[i].toString(), show(r), true) }
                else -> Equations.system(listOf(v.subList(0, 4), v.subList(4, 8), v.subList(8, 12))).forEachIndexed { i, r -> out += Line("xyz"[i].toString(), show(r), true) }
            }
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        CalcSectionTitle("Answer")
        CalcCard { Lines(out, err, true) }
    }
}

// ---- TABLE ----------------------------------------------------------------------------------

@Composable
internal fun TablePage(onBack: () -> Unit) {
    var f by rememberSaveable { mutableStateOf("x²") }
    var from by rememberSaveable { mutableStateOf("-3") }
    var to by rememberSaveable { mutableStateOf("3") }
    var step by rememberSaveable { mutableStateOf("1") }
    var rad by rememberSaveable { mutableStateOf(true) }

    CalcPageFrame("Table", onBack) {
        CalcSectionTitle("f(x) =")
        CalcField(f, { f = it }, Modifier.fillMaxWidth(), hint = "x²−3x+1", number = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Angles", style = LockInText.Sub.copy(color = CC.Dim, fontSize = 14.sp))
            CalcPill("Rad", { rad = true }, on = rad)
            CalcPill("Deg", { rad = false }, on = !rad)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { CalcSectionTitle("From"); CalcField(from, { from = it }, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { CalcSectionTitle("To"); CalcField(to, { to = it }, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { CalcSectionTitle("Step"); CalcField(step, { step = it }, Modifier.fillMaxWidth(), signed = false) }
        }
        val rows = ArrayList<Pair<String, String>>()
        var err = ""
        try {
            val a = cellOr(from, 0.0); val b = cellOr(to, 0.0); val st = cellOr(step, 1.0)
            if (st <= 0.0) throw CalcException("Step must be above 0")
            if (b < a) throw CalcException("To must not be below From")
            val n = floor((b - a) / st + 1e-9).toInt()
            if (n > 98) throw CalcException("That is over 99 rows: raise the step")
            if (f.isBlank()) throw CalcException("Type f(x) above")
            val angle = if (rad) AngleMode.Rad else AngleMode.Deg
            for (k in 0..n) {
                val x = Calc.snap(a + k * st)
                val fx = try { show(Calc.eval(f, angle, 0.0, 0.0, mapOf("x" to x), 5_000)) } catch (e: CalcException) { "—" }
                rows += show(x) to fx
            }
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        CalcSectionTitle("Values")
        CalcCard {
            if (err.isNotEmpty()) {
                ErrorText(err)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text("x", style = LockInText.Label.copy(color = CC.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("f(x)", style = LockInText.Label.copy(color = CC.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), modifier = Modifier.weight(1f))
                }
                rows.forEach { (x, y) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(x, style = LockInText.Label.copy(color = CC.Ink, fontSize = 16.sp), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(y, style = LockInText.Label.copy(color = CC.Good, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End), modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---- GRAPH ----------------------------------------------------------------------------------

private fun niceStep(raw: Float): Float {
    val p = 10.0.pow(floor(log10(raw.toDouble())))
    val m = raw / p
    val n = if (m < 1.5) 1.0 else if (m < 3.5) 2.0 else if (m < 7.5) 5.0 else 10.0
    return (n * p).toFloat()
}

/** The slope of the curve at x, by looking a tiny step either side. */
private fun slopeAt(f: String, angle: AngleMode, x: Double): Double? {
    val h = 1e-5 * maxOf(1.0, abs(x))
    val a = sampleAt(f, angle, x - h) ?: return null
    val b = sampleAt(f, angle, x + h) ?: return null
    return Calc.snap((b - a) / (2 * h))
}

private fun sampleAt(f: String, angle: AngleMode, x: Double): Double? =
    if (f.isBlank()) null else try { Calc.eval(f, angle, 0.0, 0.0, mapOf("x" to x), 1_500) } catch (e: CalcException) { null }

private fun DrawScope.drawGraph(cx: Float, cy: Float, scale: Float, f1: String, f2: String, angle: AngleMode, traceX: Float, paint: Paint) {
    val w = size.width
    val h = size.height
    fun px(x: Float) = w / 2f + (x - cx) * scale
    fun py(y: Float) = h / 2f - (y - cy) * scale
    val step = niceStep(70f / scale)
    val x0 = cx - w / 2f / scale
    val x1 = cx + w / 2f / scale
    val y0 = cy - h / 2f / scale
    val y1 = cy + h / 2f / scale
    val grid = Color(0xFF23252D)
    val axis = Color(0xFF5A5D6B)
    var k = ceil(x0 / step).toInt()
    while (k * step <= x1) { drawLine(grid, Offset(px(k * step), 0f), Offset(px(k * step), h), 1f); k++ }
    k = ceil(y0 / step).toInt()
    while (k * step <= y1) { drawLine(grid, Offset(0f, py(k * step)), Offset(w, py(k * step)), 1f); k++ }
    drawLine(axis, Offset(px(0f), 0f), Offset(px(0f), h), 2f)
    drawLine(axis, Offset(0f, py(0f)), Offset(w, py(0f)), 2f)

    // The numbers along the axes.
    paint.color = CC.Dim.toArgb()
    paint.textSize = 11.sp.toPx()
    val ay = py(0f).coerceIn(16f, h - 6f)
    val ax = px(0f).coerceIn(6f, w - 46f)
    drawIntoCanvas { c ->
        var i = ceil(x0 / step).toInt()
        while (i * step <= x1) { if (i != 0) c.nativeCanvas.drawText(Calc.format((i * step).toDouble()), px(i * step) + 3f, ay + 14f, paint); i++ }
        i = ceil(y0 / step).toInt()
        while (i * step <= y1) { if (i != 0) c.nativeCanvas.drawText(Calc.format((i * step).toDouble()), ax + 4f, py(i * step) - 3f, paint); i++ }
    }

    fun plot(f: String, color: Color) {
        if (f.isBlank()) return
        val path = Path()
        var pen = false
        var prev = 0f
        var sx = 0f
        while (sx <= w) {
            val x = cx + (sx - w / 2f) / scale
            val y = sampleAt(f, angle, x.toDouble())
            if (y == null) {
                pen = false
            } else {
                val sy = py(y.toFloat())
                if (sy.isNaN() || abs(sy) > 20000f) pen = false
                else if (!pen || abs(sy - prev) > h * 1.5f) { path.moveTo(sx, sy); pen = true }
                else path.lineTo(sx, sy)
                prev = sy
            }
            sx += 2f
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
    }
    plot(f1, CC.Amber)
    plot(f2, CC.Violet)

    if (!traceX.isNaN()) {
        drawLine(Color(0x66FFFFFF), Offset(px(traceX), 0f), Offset(px(traceX), h), 1.5.dp.toPx())
        sampleAt(f1, angle, traceX.toDouble())?.let { drawCircle(CC.Amber, 5.dp.toPx(), Offset(px(traceX), py(it.toFloat()))) }
        sampleAt(f2, angle, traceX.toDouble())?.let { drawCircle(CC.Violet, 5.dp.toPx(), Offset(px(traceX), py(it.toFloat()))) }
    }
}

@Composable
internal fun GraphPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var f1 by rememberSaveable { mutableStateOf("sin(x)") }
    var f2 by rememberSaveable { mutableStateOf("") }
    var rad by rememberSaveable { mutableStateOf(true) }
    var cx by rememberSaveable { mutableStateOf(0f) }
    var cy by rememberSaveable { mutableStateOf(0f) }
    var scale by rememberSaveable { mutableStateOf(40f) }
    var traceX by rememberSaveable { mutableStateOf(Float.NaN) }
    val paint = remember { Paint().apply { isAntiAlias = true } }
    val angle = if (rad) AngleMode.Rad else AngleMode.Deg
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)

    Column(
        Modifier.fillMaxSize().background(CC.Body).padding(top = topPad).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text("Graph", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp, color = CC.Ink), modifier = Modifier.weight(1f).padding(start = 14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("y₁ =", style = LockInText.Label.copy(color = CC.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            CalcField(f1, { f1 = it }, Modifier.weight(1f), hint = "sin(x)", number = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("y₂ =", style = LockInText.Label.copy(color = CC.Violet, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            CalcField(f2, { f2 = it }, Modifier.weight(1f), hint = "x²÷4", number = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CalcPill("Rad", { rad = true }, on = rad)
            CalcPill("Deg", { rad = false }, on = !rad)
            CalcPill("Reset view", { cx = 0f; cy = 0f; scale = 40f; traceX = Float.NaN })
            CalcPill("Zoom in", { scale = (scale * 1.5f).coerceAtMost(4000f) })
            CalcPill("Zoom out", { scale = (scale / 1.5f).coerceAtLeast(0.5f) })
        }
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF0E0F13))
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val worldX = cx + (centroid.x - w / 2f) / scale
                        val worldY = cy - (centroid.y - h / 2f) / scale
                        val ns = (scale * zoom).coerceIn(0.5f, 4000f)
                        cx = worldX - (centroid.x + pan.x - w / 2f) / ns
                        cy = worldY + (centroid.y + pan.y - h / 2f) / ns
                        scale = ns
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { cx = 0f; cy = 0f; scale = 40f; traceX = Float.NaN },
                        onTap = { p -> traceX = cx + (p.x - size.width / 2f) / scale },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawGraph(cx, cy, scale, f1, f2, angle, traceX, paint) }
        }
        val readout = if (traceX.isNaN()) {
            "Drag to move, pinch to zoom, tap to trace, double-tap to reset"
        } else {
            val parts = ArrayList<String>()
            parts += "x = " + show(Calc.snap(traceX.toDouble()))
            sampleAt(f1, angle, traceX.toDouble())?.let { parts += "y₁ = " + show(it) }
            slopeAt(f1, angle, traceX.toDouble())?.let { parts += "slope = " + show(it) }
            sampleAt(f2, angle, traceX.toDouble())?.let { parts += "y₂ = " + show(it) }
            parts.joinToString("    ")
        }
        Text(readout, style = LockInText.Sub.copy(color = CC.Dim, fontSize = 13.sp), maxLines = 2)
        Spacer(Modifier.height(4.dp))
    }
}


// ---- RATIO ----------------------------------------------------------------------------------

@Composable
internal fun RatioPage(onBack: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0: A:B = C:X, 1: A:B = X:D, 2: simplify A:B
    var ta by rememberSaveable { mutableStateOf("2") }
    var tb by rememberSaveable { mutableStateOf("3") }
    var tc by rememberSaveable { mutableStateOf("4") }
    var td by rememberSaveable { mutableStateOf("6") }

    @Composable
    fun pair(a: @Composable () -> Unit, b: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { a() }
            Text(":", style = LockInText.Title.copy(color = CC.Amber, fontSize = 24.sp, lineHeight = 28.sp))
            Box(Modifier.weight(1f)) { b() }
        }
    }

    @Composable
    fun unknown() {
        Text("X = ?", style = LockInText.Label.copy(color = CC.Violet, fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 10.dp))
    }

    CalcPageFrame("Ratio", onBack) {
        Chips(listOf("A:B = C:X", "A:B = X:D", "Simplify A:B"), mode) { mode = it }
        CalcCard {
            pair({ CalcField(ta, { ta = it }, Modifier.fillMaxWidth(), hint = "A") }, { CalcField(tb, { tb = it }, Modifier.fillMaxWidth(), hint = "B") })
            if (mode == 0) pair({ CalcField(tc, { tc = it }, Modifier.fillMaxWidth(), hint = "C") }, { unknown() })
            if (mode == 1) pair({ unknown() }, { CalcField(td, { td = it }, Modifier.fillMaxWidth(), hint = "D") })
        }
        val out = ArrayList<Line>()
        var err = ""
        try {
            val a = cellOr(ta, 0.0); val b = cellOr(tb, 0.0)
            when (mode) {
                0 -> out += Line("X", show(Ratio.findLast(a, b, cellOr(tc, 0.0))), true)
                1 -> out += Line("X", show(Ratio.findThird(a, b, cellOr(td, 0.0))), true)
                else -> Ratio.simplify(a, b).let { (p, q) -> out += Line("A : B", "$p : $q", true) }
            }
        } catch (e: CalcException) {
            err = e.message ?: "Math error"
        }
        CalcSectionTitle("Answer")
        CalcCard { Lines(out, err, true) }
    }
}
