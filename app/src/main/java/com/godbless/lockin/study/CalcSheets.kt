package com.godbless.lockin.study

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable

// The calculator is its own little device, so it keeps its own colours (the keys, the LCD, the amber SHIFT and
// violet ALPHA) whatever theme the rest of the app wears. This file holds those colours, the sheets that rise
// from the bottom (MODE, CONST, CONV, FUNC, STO/RCL, history, settings) and the small parts the pages share.

internal object CC {
    val Body = Color(0xFF111216)
    val Panel = Color(0xFF1B1D23)
    val Raised = Color(0xFF262932)
    val Line = Color(0xFF343743)
    val KeyFn = Color(0xFF4A4C57)
    val KeyNum = Color(0xFF282B36)
    val Amber = Color(0xFFFFC62E)
    val Violet = Color(0xFFA48BFF)
    val AlphaKey = Color(0xFF7B6CC3)
    val Orange = Color(0xFFE88F52)
    val Ink = Color(0xFFF2F3F7)
    val Dim = Color(0xFF8D91A0)
    val Good = Color(0xFF6BF2A4)
    val Bad = Color(0xFFFF7A7A)
}

internal enum class CalcSheet { None, Mode, Const, Conv, Func, Sto, Rcl, History, Settings, KeyInfo, Tip, Compare }

internal enum class CalcPage { Comp, Stat, Matrix, Vector, Distr, Base, Eqn, Table, Graph, Ratio }

// ---- Small shared parts ---------------------------------------------------------------------

/** A little rounded button: toolbar items, chips, segmented choices. */
@Composable
internal fun CalcPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, accent: Color = CC.Violet, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (on) accent else CC.Raised)
            .border(1.dp, if (on) accent else CC.Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = LockInText.Label.copy(color = if (!enabled) CC.Dim.copy(alpha = 0.55f) else if (on) CC.Body else CC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

/** A text box that takes a number or a little sum (1/2, 3+4). */
@Composable
internal fun CalcField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    number: Boolean = true,
    signed: Boolean = number,
) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = LockInText.Input.copy(color = CC.Ink, fontSize = 16.sp),
        cursorBrush = SolidColor(CC.Violet),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        modifier = modifier
            .clip(shape)
            .background(CC.Raised)
            .border(1.dp, CC.Line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = LockInText.Input.copy(color = CC.Dim, fontSize = 16.sp), maxLines = 1)
                    inner()
                }
                if (signed) {
                    // Not every phone keyboard has a minus key, so every number box carries its own ± button.
                    Text(
                        "±",
                        style = LockInText.Label.copy(color = CC.Violet, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clickable(role = Role.Button) { onChange(flipSign(value)) }.padding(start = 8.dp),
                    )
                }
            }
        },
    )
}

private fun flipSign(v: String): String = when {
    v.isBlank() -> "-"
    v.startsWith("-") || v.startsWith("−") -> v.drop(1)
    else -> "-$v"
}

@Composable
internal fun CalcSectionTitle(text: String) {
    Text(text.uppercase(), style = LockInText.Small.copy(color = CC.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
}

/** A card the pages draw their answers on. */
@Composable
internal fun CalcCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(CC.Panel).border(1.dp, CC.Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun CalcResultRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LockInText.Sub.copy(color = CC.Dim, fontSize = 15.sp), modifier = Modifier.width(96.dp), maxLines = 1)
        Text(
            value,
            style = LockInText.Label.copy(color = if (highlight) CC.Good else CC.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
    }
}

// ---- The sheet that rises from the bottom -----------------------------------------------------

@Composable
internal fun CalcOverlay(visible: Boolean, title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        if (visible) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose))
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(300, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(260, easing = UiGlide)) { it },
        ) {
            val top = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            Column(
                Modifier
                    .readable(Widths.Sheet)
                    .heightIn(max = 560.dp)
                    .clip(top)
                    .background(CC.Panel)
                    .border(1.dp, CC.Line, top)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(CC.Line))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp, color = CC.Ink), modifier = Modifier.weight(1f))
                    CalcPill("Close", onClose)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, sub: String = "", lead: String = "", trail: String = "", onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(CC.Raised).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (lead.isNotEmpty()) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(CC.Panel), contentAlignment = Alignment.Center) {
                Text(lead, style = LockInText.Label.copy(color = CC.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LockInText.Label.copy(color = CC.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 2)
            if (sub.isNotEmpty()) Text(sub, style = LockInText.Small.copy(color = CC.Dim, fontSize = 12.sp), maxLines = 2)
        }
        if (trail.isNotEmpty()) Text(trail, style = LockInText.Label.copy(color = CC.Violet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

// ---- MODE -----------------------------------------------------------------------------------

@Composable
internal fun ModeSheet(visible: Boolean, complex: Boolean, onPick: (Int) -> Unit, onClose: () -> Unit) {
    CalcOverlay(visible, "Mode", onClose) {
        SheetRow("Calculate", "Everyday and scientific sums", "1", if (!complex) "On" else "") { onPick(0) }
        SheetRow("Complex", "Sums with i, polar and rectangular answers", "2", if (complex) "On" else "") { onPick(1) }
        SheetRow("Base-N", "Binary, octal, decimal and hex with and, or, xor", "3") { onPick(2) }
        SheetRow("Statistics", "Mean, deviation, quartiles and regression", "4") { onPick(3) }
        SheetRow("Matrix", "Add, multiply, determinant and inverse", "5") { onPick(4) }
        SheetRow("Vector", "Dot and cross product, angle and length", "6") { onPick(5) }
        SheetRow("Distribution", "Normal, binomial and Poisson", "7") { onPick(6) }
        SheetRow("Equations", "Quadratic, cubic, and 2 or 3 unknowns", "8") { onPick(7) }
        SheetRow("Table", "Values of f(x) over a range", "9") { onPick(8) }
        SheetRow("Graph", "Plot f(x): drag to move, pinch to zoom", "0") { onPick(9) }
        SheetRow("Ratio", "A:B = C:X, and simplest form", "+") { onPick(10) }
    }
}

// ---- CONST ----------------------------------------------------------------------------------

@Composable
internal fun ConstSheet(visible: Boolean, onPick: (PhysConst) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    CalcOverlay(visible, "Constants", onClose) {
        CalcField(query, { query = it }, Modifier.fillMaxWidth(), hint = "Search: planck, G, boltzmann", number = false)
        val q = query.trim().lowercase()
        val list = Constants.all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.symbol.lowercase().contains(q) }
        if (list.isEmpty()) Text("Nothing matches", style = LockInText.Sub.copy(color = CC.Dim), modifier = Modifier.padding(8.dp))
        list.forEach { c ->
            SheetRow(c.name, Calc.format(c.value) + (if (c.unit.isEmpty()) "" else " " + c.unit), c.no.toString(), c.symbol) { onPick(c) }
        }
    }
}

// ---- CONV -----------------------------------------------------------------------------------

@Composable
internal fun ConvSheet(visible: Boolean, current: Double?, onPick: (Conversion) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    CalcOverlay(visible, "Convert", onClose) {
        Text(
            if (current == null) "Type a number first, then pick a conversion" else "Converting " + Calc.format(current),
            style = LockInText.Sub.copy(color = CC.Dim, fontSize = 14.sp),
        )
        CalcField(query, { query = it }, Modifier.fillMaxWidth(), hint = "Search: in, kg, °F", number = false)
        val q = query.trim().lowercase()
        Conversions.all.filter { q.isEmpty() || it.label.lowercase().contains(q) }.forEach { c ->
            SheetRow(c.label, "", c.no.toString(), if (current != null) Calc.format(c.apply(current)) else "") { onPick(c) }
        }
    }
}

// ---- STO and RCL ------------------------------------------------------------------------------

@Composable
internal fun VarSheet(visible: Boolean, store: Boolean, values: Map<String, String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    CalcOverlay(visible, if (store) "Store in" else "Recall", onClose) {
        CalcKeys.letters.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { name ->
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.weight(1f).clip(shape).background(CC.Raised).clickable(role = Role.Button) { onPick(name) }.padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(name, style = LockInText.Title.copy(color = CC.Amber, fontSize = 22.sp, lineHeight = 26.sp))
                        Text(values[name] ?: "0", style = LockInText.Small.copy(color = CC.Dim, fontSize = 11.sp), maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- History ----------------------------------------------------------------------------------

@Composable
internal fun HistorySheet(
    visible: Boolean,
    items: List<Pair<String, String>>,
    onUseSum: (Int) -> Unit,
    onUseAnswer: (Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    CalcOverlay(visible, "History", onClose) {
        if (items.isEmpty()) {
            Text("Your sums show up here. Tap a sum to edit it, or its answer to use it.", style = LockInText.Sub.copy(color = CC.Dim), modifier = Modifier.padding(4.dp))
        } else {
            items.forEachIndexed { i, (sum, answer) ->
                val shape = RoundedCornerShape(14.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(CC.Raised), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sum,
                        style = LockInText.Label.copy(color = CC.Ink, fontSize = 16.sp),
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { onUseSum(i) }.padding(12.dp),
                        maxLines = 1,
                    )
                    Text(
                        "= $answer",
                        style = LockInText.Label.copy(color = CC.Good, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                        modifier = Modifier.clickable(role = Role.Button) { onUseAnswer(i) }.padding(12.dp),
                        maxLines = 1,
                    )
                }
            }
            CalcPill("Clear history", onClear, Modifier.align(Alignment.End), accent = CC.Bad)
        }
    }
}

// ---- Settings ---------------------------------------------------------------------------------

@Composable
internal fun SettingsSheet(visible: Boolean, model: CalcModel, version: Int, onChange: () -> Unit, onClose: () -> Unit) {
    CalcOverlay(visible, "Settings", onClose) {
        // `version` makes this redraw each time something changes.
        if (version < 0) return@CalcOverlay
        CalcSectionTitle("Angle unit")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AngleMode.entries.forEach { a ->
                CalcPill(a.label, { model.changeAngle(a); onChange() }, Modifier.weight(1f), on = model.angle == a)
            }
        }
        CalcSectionTitle("Number format")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                NumFormat.Kind.Norm1 to "Norm 1", NumFormat.Kind.Norm2 to "Norm 2",
                NumFormat.Kind.Fix to "Fix", NumFormat.Kind.Sci to "Sci",
            ).forEach { (kind, label) ->
                val digits = when (kind) {
                    NumFormat.Kind.Fix -> if (model.fmt.kind == kind) model.fmt.digits else 2
                    NumFormat.Kind.Sci -> if (model.fmt.kind == kind) model.fmt.digits else 3
                    else -> if (model.fmt.kind == kind || model.fmt.digits >= 8) model.fmt.digits.coerceAtLeast(8) else 13
                }
                CalcPill(label, { model.setFormat(kind, digits); onChange() }, Modifier.weight(1f), on = model.fmt.kind == kind)
            }
        }
        val fixed = model.fmt.kind == NumFormat.Kind.Fix || model.fmt.kind == NumFormat.Kind.Sci
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (fixed) "Decimal places" else "Digits shown", style = LockInText.Sub.copy(color = CC.Dim, fontSize = 15.sp), modifier = Modifier.weight(1f))
            CalcPill("−", { model.setFormat(model.fmt.kind, model.fmt.digits - 1); onChange() })
            Text(model.fmt.digits.toString(), style = LockInText.Title.copy(color = CC.Ink, fontSize = 20.sp, lineHeight = 24.sp), modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
            CalcPill("+", { model.setFormat(model.fmt.kind, model.fmt.digits + 1); onChange() })
        }
        CalcSectionTitle("Answers")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalcPill("Math (3/4, √2)", { model.mathDisplay = true; model.restyle(); onChange() }, Modifier.weight(1f), on = model.mathDisplay)
            CalcPill("Line (decimals)", { model.mathDisplay = false; model.restyle(); onChange() }, Modifier.weight(1f), on = !model.mathDisplay)
        }
        CalcSectionTitle("Complex answers")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalcPill("a + bi", { model.polar = false; model.restyle(); onChange() }, Modifier.weight(1f), on = !model.polar)
            CalcPill("r ∠ θ", { model.polar = true; model.restyle(); onChange() }, Modifier.weight(1f), on = model.polar)
        }
        CalcSectionTitle("Key feel")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalcPill("Haptics on", { model.haptics = true; onChange() }, Modifier.weight(1f), on = model.haptics)
            CalcPill("Haptics off", { model.haptics = false; onChange() }, Modifier.weight(1f), on = !model.haptics)
        }
        CalcSectionTitle("Screen")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LcdThemes.forEachIndexed { i, t ->
                CalcPill(t.name, { model.lcd = i; onChange() }, on = model.lcd == i)
            }
        }
    }
}

// ---- FUNC and HELP ----------------------------------------------------------------------------

internal class FuncInfo(val group: String, val label: String, val insert: String, val help: String, val example: String)

internal val FuncList = listOf(
    FuncInfo("Trigonometry", "sin  cos  tan", "sin(", "Sine, cosine and tangent in the angle unit you chose (Deg, Rad or Grad).", "sin(30) = 1/2"),
    FuncInfo("Trigonometry", "Sin⁻¹  Cos⁻¹  Tan⁻¹", "asin(", "The angle that has a given sine, cosine or tangent.", "asin(0.5) = 30"),
    FuncInfo("Trigonometry", "Cot  Cot⁻¹", "cot(", "Cotangent, and its inverse (ALPHA + the ( key).", "cot(45) = 1"),
    FuncInfo("Trigonometry", "sinh  cosh  tanh", "sinh(", "Hyperbolic functions. Press hyp first, then the trig key.", "sinh(1) = 1.1752"),
    FuncInfo("Powers and logs", "√  ∛  ˣ√", "√(", "Square root, cube root, and the y-th root (3ˣ√8 = 2).", "√(18) = 3√2"),
    FuncInfo("Powers and logs", "x²  x³  xʸ  x⁻¹", "^", "Powers and the reciprocal.", "2^10 = 1024"),
    FuncInfo("Powers and logs", "log  ln  logₓy", "logb(", "Log base 10, natural log, and log to any base: logₓy(base, number).", "logb(2,8) = 3"),
    FuncInfo("Powers and logs", "10ˣ  eˣ", "10^(", "Powers of ten and e (SHIFT + Log, SHIFT + Ln).", "10^(3) = 1000"),
    FuncInfo("Calculus", "d/dx", "d/dx(", "The slope of an expression in x at a point: d/dx(expression, point).", "d/dx(x²,3) = 6"),
    FuncInfo("Calculus", "∫dx", "∫(", "The area under an expression in x: ∫(expression, from, to).", "∫(x²,0,3) = 9"),
    FuncInfo("Calculus", "Σ  Π", "Σ(", "Sum or product of an expression in x for whole numbers: Σ(expression, from, to).", "Σ(x²,1,5) = 55"),
    FuncInfo("Calculus", "Limit", "lim(", "What an expression in x settles to near a point, or as x grows (∞).", "lim(sin(x)÷x,0) = 1"),
    FuncInfo("Numbers", "x!  nPr  nCr", "!", "Factorial, permutations (5P2) and combinations (5C2).", "5C2 = 10"),
    FuncInfo("Numbers", "GCD  LCM", "gcd(", "Greatest common divisor and least common multiple.", "gcd(12,18) = 6"),
    FuncInfo("Numbers", "mod  ÷R", " mod ", "Remainder, or quotient and remainder together: divr(17,5).", "17 mod 5 = 2"),
    FuncInfo("Numbers", "Int  Frac  Sign", "int(", "Whole part, fractional part and sign of a number.", "frac(3.75) = 0.75"),
    FuncInfo("Numbers", "Floor  Ceil  Round", "floor(", "Round down, up, or to the nearest whole number.", "ceil(2.1) = 3"),
    FuncInfo("Numbers", "|x|", "abs(", "Absolute value (SHIFT + hyp).", "abs(-3) = 3"),
    FuncInfo("Numbers", "FACT", "", "Prime factors of the number on the screen (SHIFT + °′″).", "360 → 2³×3²×5"),
    FuncInfo("Angles and coordinates", "°′″", "°", "Type degrees, minutes and seconds: 12°30′15″. Press it on an answer to see it that way.", "12°30′"),
    FuncInfo("Angles and coordinates", "Pol  Rec", "Pol(", "Rectangular to polar (r and θ) and back.", "Pol(1,1) → r = 1.414, θ = 45"),
    FuncInfo("Random", "Ran#  RanInt", "RanInt(", "A random decimal from 0 to 1, or a random whole number in a range.", "RanInt(1,6)"),
    FuncInfo("Verify", "=  ≠  >  <  ≥  ≤", "", "Put a comparison in a sum and it answers True or False. The = chip types the equals sign; ALPHA + x⁻¹ lists the others.", "sin(30)=1÷2 → True"),
    FuncInfo("Memory", "STO  RCL  A–F x y z t M", "", "Store an answer in a letter (SHIFT + RCL) and use it in later sums. M+ and M− change M.", "5 STO A, then 2A = 10"),
    FuncInfo("Memory", "CALC  SOLVE", "", "CALC asks for each letter's value and works the sum out. SOLVE finds x in an equation like x²−4=0.", "x²−4=0, SOLVE, start 1 → 2"),
    FuncInfo("Views", "S⇌D  ENG  SI", "", "Swap exact and decimal, show engineering notation, or an SI prefix (ALPHA + 8).", "12345 → 12.345×10³ or 12.345k"),
)

@Composable
internal fun FuncSheet(visible: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    CalcOverlay(visible, "Functions", onClose) {
        CalcField(query, { query = it }, Modifier.fillMaxWidth(), hint = "Search: integral, root, remainder", number = false)
        val q = query.trim().lowercase()
        val list = FuncList.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.help.lowercase().contains(q) || it.group.lowercase().contains(q) }
        var last = ""
        list.forEach { f ->
            if (f.group != last) { last = f.group; Spacer(Modifier.height(4.dp)); CalcSectionTitle(f.group) }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(CC.Raised).clickable(role = Role.Button, enabled = f.insert.isNotEmpty()) { onPick(f.insert) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(f.label, style = LockInText.Label.copy(color = CC.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Text(f.help, style = LockInText.Sub.copy(color = CC.Ink, fontSize = 14.sp, lineHeight = 19.sp))
                Text(f.example, style = LockInText.Small.copy(color = CC.Violet, fontSize = 12.sp))
            }
        }
        if (list.isEmpty()) Text("Nothing matches", style = LockInText.Sub.copy(color = CC.Dim), modifier = Modifier.padding(8.dp))
    }
}


// ---- Hold a key to see what it does -----------------------------------------------------------

@Composable
internal fun KeyInfoSheet(visible: Boolean, key: CalcKeyDef?, onClose: () -> Unit) {
    CalcOverlay(visible, key?.spoken?.replaceFirstChar { it.uppercase() } ?: "Key", onClose) {
        if (key != null) {
            describeKey(key).forEach { (name, text) ->
                val shape = RoundedCornerShape(14.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(CC.Raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = LockInText.Label.copy(color = if (name.startsWith("ALPHA")) CC.Violet else CC.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    Text(text, style = LockInText.Sub.copy(color = CC.Ink, fontSize = 14.sp, lineHeight = 19.sp))
                }
            }
        }
    }
}

// ---- First time in ----------------------------------------------------------------------------

@Composable
internal fun TipSheet(visible: Boolean, onDone: () -> Unit) {
    CalcOverlay(visible, "Your calculator", onDone) {
        listOf(
            "SHIFT and ALPHA" to "The amber words above the keys need SHIFT, the violet ones need ALPHA. Each works for one key press.",
            "Hold any key" to "Press and hold a key to see everything it can do.",
            "Answers" to "Tap an answer to copy it. Hold the sum to paste. Tap the sum to move the cursor, or tap an answer to edit its sum.",
            "Equations" to "Type an equation like x²−4=0 (use the = chip), then SHIFT + CALC to solve it.",
            "More modes" to "Mode has Statistics, Matrix, Vector, Equations, Table and Graph. Undo brings back anything you cleared.",
        ).forEach { (title, text) ->
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(CC.Raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = LockInText.Label.copy(color = CC.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Text(text, style = LockInText.Sub.copy(color = CC.Ink, fontSize = 14.sp, lineHeight = 19.sp))
            }
        }
        CalcPill("Got it", onDone, Modifier.align(Alignment.End), on = true)
    }
}


// ---- Comparison signs -------------------------------------------------------------------------

@Composable
internal fun CompareSheet(visible: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    CalcOverlay(visible, "Compare", onClose) {
        Text("Put a comparison in a sum and the answer is True or False.", style = LockInText.Sub.copy(color = CC.Dim, fontSize = 14.sp))
        listOf(
            "=" to "equal to", "≠" to "not equal to", ">" to "greater than",
            "<" to "less than", "≥" to "greater than or equal to", "≤" to "less than or equal to",
        ).forEach { (sign, name) -> SheetRow(name, "5 $sign 3", sign) { onPick(sign) } }
    }
}
