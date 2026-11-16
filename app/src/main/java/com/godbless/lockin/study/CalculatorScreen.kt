package com.godbless.lockin.study

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlinx.coroutines.delay
import kotlin.math.abs

// A full scientific calculator, laid out like the real thing: an LCD that shows exact answers (3/4, √2/2, 3π/4)
// with a live preview while you type, SHIFT and ALPHA layers printed above every key, a direction pad, and
// menus for CONST, CONV, FUNC, history and settings. The brains are in CalcModel; this file only draws them.
// Available during a lock, so homework sums never need another app.

private val ModelSaver = Saver<CalcModel, String>(save = { it.encode() }, restore = { CalcModel.decode(it) })

/** What the LCD shows, worked out once per redraw. */
private class LcdView(
    val theme: LcdTheme,
    val shift: Boolean, val alpha: Boolean, val second: Boolean, val hyp: Boolean, val mem: Boolean, val sto: Boolean, val rcl: Boolean,
    val fmtLabel: String, val displayLabel: String, val angleLabel: String, val complex: Boolean,
    val caption: String, val input: String, val cursor: Int, val showCaret: Boolean,
    val result: String, val kind: Int, // 0 nothing yet, 1 live preview, 2 answer, 3 error
    val lines: List<Pair<String, String>>,
    val stack: Boolean,
)

private class KeyFlags(val shift: Boolean, val alpha: Boolean, val second: Boolean, val hyp: Boolean)

@Composable
internal fun CalculatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lockin_calc", Context.MODE_PRIVATE) }
    // The work, letters, memory and history come back after the app was closed, not only after a rotation.
    val model = rememberSaveable(saver = ModelSaver) { CalcModel.decode(prefs.getString("model", "") ?: "").also { it.settle() } }
    var tick by remember { mutableIntStateOf(0) }
    val version = tick // reading it makes this redraw whenever the model changes
    var sheet by rememberSaveable { mutableStateOf(if (prefs.getBoolean("tip_seen", false)) CalcSheet.None else CalcSheet.Tip) }
    var page by rememberSaveable { mutableStateOf(CalcPage.Comp) }
    var notice by remember { mutableStateOf("") }
    var infoKey by remember { mutableStateOf<CalcKeyDef?>(null) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp >= 600
    val compact = !landscape && cfg.screenHeightDp < 720

    // Keep the screen on while the calculator is open: nobody wants it to sleep mid-sum.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(version) {
        delay(600)
        prefs.edit().putString("model", model.encode()).apply()
    }
    DisposableEffect(Unit) {
        onDispose { prefs.edit().putString("model", model.encode()).apply() }
    }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            delay(1400)
            notice = ""
        }
    }

    fun bump() { tick++ }

    fun buzz(t: HapticFeedbackType) { if (model.haptics) haptic.performHapticFeedback(t) }

    fun closeSheet() {
        if (sheet == CalcSheet.Tip) prefs.edit().putBoolean("tip_seen", true).apply()
        sheet = CalcSheet.None
    }

    fun copyDecimal() {
        clipboard.setText(AnnotatedString(model.copyDecimal()))
        notice = "Copied as decimal"
    }

    fun showInfo(k: CalcKeyDef) {
        buzz(HapticFeedbackType.LongPress)
        infoKey = k
        sheet = CalcSheet.KeyInfo
    }

    fun go(req: Req) {
        when (req) {
            Req.None -> {}
            Req.Mode -> sheet = CalcSheet.Mode
            Req.Const -> sheet = CalcSheet.Const
            Req.Conv -> sheet = CalcSheet.Conv
            Req.Func -> sheet = CalcSheet.Func
            Req.Sto -> sheet = CalcSheet.Sto
            Req.Rcl -> sheet = CalcSheet.Rcl
            Req.History -> sheet = CalcSheet.History
            Req.Compare -> sheet = CalcSheet.Compare
            Req.Stat -> page = CalcPage.Stat
            Req.Matrix -> page = CalcPage.Matrix
            Req.Vector -> page = CalcPage.Vector
            Req.Distr -> page = CalcPage.Distr
            Req.Base -> page = CalcPage.Base
            Req.Copy -> { clipboard.setText(AnnotatedString(model.copyText())); notice = "Copied" }
            Req.Paste -> {
                val text = clipboard.getText()?.text.orEmpty()
                model.pasteText(text)
                notice = if (text.isBlank()) "Nothing to paste" else "Pasted"
            }
        }
    }

    fun press(k: CalcKeyDef) {
        buzz(if (k.act == KeyAct.Eq) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        go(model.press(k))
        bump()
    }

    fun move(dir: Int) {
        buzz(HapticFeedbackType.TextHandleMove)
        model.move(dir)
        bump()
    }

    fun pickMode(i: Int) {
        sheet = CalcSheet.None
        when (i) {
            0 -> { model.setComplex(false); page = CalcPage.Comp; notice = "Calculate" }
            1 -> { model.setComplex(true); page = CalcPage.Comp; notice = "Complex" }
            2 -> page = CalcPage.Base
            3 -> page = CalcPage.Stat
            4 -> page = CalcPage.Matrix
            5 -> page = CalcPage.Vector
            6 -> page = CalcPage.Distr
            7 -> page = CalcPage.Eqn
            8 -> page = CalcPage.Table
            9 -> page = CalcPage.Graph
            else -> page = CalcPage.Ratio
        }
        bump()
    }

    // ---- The other pages -------------------------------------------------------------------
    when (page) {
        CalcPage.Stat -> { StatPage { page = CalcPage.Comp }; return }
        CalcPage.Matrix -> { MatrixPage { page = CalcPage.Comp }; return }
        CalcPage.Vector -> { VectorPage { page = CalcPage.Comp }; return }
        CalcPage.Distr -> { DistrPage { page = CalcPage.Comp }; return }
        CalcPage.Base -> { BasePage { page = CalcPage.Comp }; return }
        CalcPage.Eqn -> { EqnPage { page = CalcPage.Comp }; return }
        CalcPage.Table -> { TablePage { page = CalcPage.Comp }; return }
        CalcPage.Graph -> { GraphPage { page = CalcPage.Comp }; return }
        CalcPage.Ratio -> { RatioPage { page = CalcPage.Comp }; return }
        CalcPage.Comp -> {}
    }

    val kind = when {
        model.error.isNotEmpty() -> 3
        model.done -> 2
        model.preview.isNotEmpty() -> 1
        else -> 0
    }
    val resultText = model.resultText()
    val radHint = notice.isEmpty() && model.caption.isEmpty() && model.needsRad()
    val lcd = LcdView(
        theme = LcdThemes[model.lcd.coerceIn(0, LcdThemes.lastIndex)],
        shift = model.shift, alpha = model.alpha, second = model.second, hyp = model.hyp, mem = model.memory != 0.0,
        sto = sheet == CalcSheet.Sto, rcl = sheet == CalcSheet.Rcl,
        fmtLabel = model.fmt.label, displayLabel = if (model.mathDisplay) "MATH" else "LINE",
        angleLabel = model.angle.label.uppercase(), complex = model.cmplx,
        caption = if (notice.isNotEmpty()) notice else if (model.caption.isNotEmpty()) model.caption else if (radHint) "Trig in calculus is in degrees: tap for radians" else "",
        input = model.inputText, cursor = model.inputCursor, showCaret = !model.done,
        result = resultText, kind = kind, lines = if (model.done) model.lines else emptyList(),
        stack = model.mathDisplay && !model.flip && model.done && !model.eng && !model.si && !model.dms,
    )
    val flags = KeyFlags(model.shift, model.alpha, model.second, model.hyp)

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Box(Modifier.fillMaxSize().background(CC.Body)) {
        val toolRow: @Composable () -> Unit = {
            ToolRow(
                model.lcd, model.cmplx, model.polar, model.canUndo,
                { sheet = it },
                { model.lcd = (model.lcd + 1) % LcdThemes.size; bump() },
                { model.insert("="); bump() },
                { if (model.undo()) bump() },
                { model.polar = !model.polar; model.restyle(); bump() },
            )
        }
        val captionTap: (() -> Unit)? = if (radHint) ({ model.changeAngle(AngleMode.Rad); notice = "Radians on"; bump() }) else null
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(top = topPad).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopRow(onBack)
                    Spacer(Modifier.height(8.dp))
                    CalcLcd(lcd, { model.tapCursor(it); bump() }, { go(Req.Copy); bump() }, { copyDecimal() }, { go(Req.Paste); bump() }, captionTap, Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                    toolRow()
                }
                CalcKeypad(flags, ::press, ::move, ::showInfo, Modifier.weight(1.45f).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(top = topPad).navigationBarsPadding().readable(Widths.Sheet).padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp),
            ) {
                if (compact) {
                    // Short phones: the back button shares a line with the toolbar so the keys get the room.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackButton(onBack)
                        Box(Modifier.weight(1f)) { toolRow() }
                    }
                    Spacer(Modifier.height(6.dp))
                    CalcLcd(lcd, { model.tapCursor(it); bump() }, { go(Req.Copy); bump() }, { copyDecimal() }, { go(Req.Paste); bump() }, captionTap, Modifier.heightIn(min = 118.dp))
                    Spacer(Modifier.height(6.dp))
                } else {
                    TopRow(onBack)
                    Spacer(Modifier.height(8.dp))
                    CalcLcd(lcd, { model.tapCursor(it); bump() }, { go(Req.Copy); bump() }, { copyDecimal() }, { go(Req.Paste); bump() }, captionTap, Modifier.heightIn(min = 168.dp))
                    Spacer(Modifier.height(8.dp))
                    toolRow()
                    Spacer(Modifier.height(6.dp))
                }
                CalcKeypad(flags, ::press, ::move, ::showInfo, Modifier.weight(1f).fillMaxWidth())
            }
        }

        // ---- Menus that rise from the bottom ---------------------------------------------------
        ModeSheet(sheet == CalcSheet.Mode, model.cmplx, { pickMode(it) }, { sheet = CalcSheet.None })
        ConstSheet(sheet == CalcSheet.Const, { c -> model.insertConstant(c); sheet = CalcSheet.None; bump() }, { sheet = CalcSheet.None })
        ConvSheet(
            sheet == CalcSheet.Conv,
            if (sheet == CalcSheet.Conv) model.peekValue() else null,
            { c -> model.convert(c); sheet = CalcSheet.None; bump() },
            { sheet = CalcSheet.None },
        )
        FuncSheet(sheet == CalcSheet.Func, { text -> if (text.isNotEmpty()) model.insert(text); sheet = CalcSheet.None; bump() }, { sheet = CalcSheet.None })
        val storing = sheet == CalcSheet.Sto
        VarSheet(
            sheet == CalcSheet.Sto || sheet == CalcSheet.Rcl,
            storing,
            if (sheet == CalcSheet.Sto || sheet == CalcSheet.Rcl) {
                CalcKeys.letters.associateWith { n -> Calc.format(if (n == "M") model.memory else model.vars[n] ?: 0.0, model.fmt) }
            } else emptyMap(),
            { name ->
                if (storing) { model.store(name); notice = "Stored in $name" } else model.recall(name)
                sheet = CalcSheet.None
                bump()
            },
            { sheet = CalcSheet.None },
        )
        HistorySheet(
            sheet == CalcSheet.History,
            model.history.toList(),
            { i -> model.recallHistory(i); sheet = CalcSheet.None; bump() },
            { i -> model.insertResult(i); sheet = CalcSheet.None; bump() },
            { model.clearHistory(); bump() },
            { sheet = CalcSheet.None },
        )
        SettingsSheet(sheet == CalcSheet.Settings, model, version, { bump() }, { sheet = CalcSheet.None })
        KeyInfoSheet(sheet == CalcSheet.KeyInfo, infoKey) { sheet = CalcSheet.None }
        TipSheet(sheet == CalcSheet.Tip) { closeSheet() }
        CompareSheet(sheet == CalcSheet.Compare, { sign -> model.insert(sign); sheet = CalcSheet.None; bump() }, { sheet = CalcSheet.None })
    }
}

@Composable
private fun TopRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Text("Calculator", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp, color = CC.Ink), modifier = Modifier.weight(1f).padding(start = 14.dp))
    }
}

@Composable
private fun ToolRow(
    themeIndex: Int,
    complex: Boolean,
    polar: Boolean,
    canUndo: Boolean,
    open: (CalcSheet) -> Unit,
    nextTheme: () -> Unit,
    typeEquals: () -> Unit,
    undo: () -> Unit,
    flipPolar: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CalcPill("Undo", undo, enabled = canUndo)
        CalcPill("=", typeEquals)
        CalcPill("History", { open(CalcSheet.History) })
        CalcPill("Mode", { open(CalcSheet.Mode) })
        if (complex) CalcPill(if (polar) "r ∠ θ" else "a + bi", flipPolar, on = true)
        CalcPill("Convert", { open(CalcSheet.Conv) })
        CalcPill("Constants", { open(CalcSheet.Const) })
        CalcPill("Functions", { open(CalcSheet.Func) })
        CalcPill("Screen: " + LcdThemes[themeIndex.coerceIn(0, LcdThemes.lastIndex)].name, nextTheme)
        CalcPill("Settings", { open(CalcSheet.Settings) })
    }
}

// ---- The LCD ----------------------------------------------------------------------------------

@Composable
private fun Flag(text: String, on: Boolean, ink: Color, dim: Color) {
    Text(
        text,
        style = LockInText.Small.copy(color = if (on) ink else dim.copy(alpha = 0.5f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        maxLines = 1,
    )
}

@Composable
private fun CalcLcd(
    v: LcdView,
    onCursor: (Int) -> Unit,
    onCopy: () -> Unit,
    onCopyDecimal: () -> Unit,
    onPaste: () -> Unit,
    onCaption: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ink = Color(v.theme.ink)
    val dim = Color(v.theme.dim)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(v.theme.screen))
            .border(2.dp, Color(0x33000000), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            // The little flags along the top, ghosted when off, like the segments of a real LCD.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Flag("S", v.shift, ink, dim)
                Flag("A", v.alpha, ink, dim)
                Flag("2nd", v.second, ink, dim)
                Flag("HYP", v.hyp, ink, dim)
                Flag("M", v.mem, ink, dim)
                if (v.sto) Flag("STO", true, ink, dim)
                if (v.rcl) Flag("RCL", true, ink, dim)
                Spacer(Modifier.weight(1f))
                if (v.complex) Flag("CMPLX", true, ink, dim)
                Flag(v.fmtLabel, true, ink, dim)
                Flag(v.displayLabel, true, ink, dim)
                Flag(v.angleLabel, true, ink, dim)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                v.caption.ifEmpty { " " },
                style = LockInText.Small.copy(color = if (onCaption != null) Color(0xFF8A5A00) else dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = if (onCaption != null) Modifier.clickable(role = Role.Button, onClick = onCaption) else Modifier,
            )
            LcdInput(v.input, v.cursor, v.showCaret, ink, onCursor, onPaste)
        }
        LcdResult(v, ink, dim, onCopy, onCopyDecimal)
    }
}

/** The sum being typed: tap to put the cursor there, hold to paste, drag to scroll a long sum. */
@Composable
private fun LcdInput(text: String, cursor: Int, showCaret: Boolean, ink: Color, onCursor: (Int) -> Unit, onPaste: () -> Unit) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            caretOn = !caretOn
        }
    }
    LaunchedEffect(cursor, text, layout, viewport) {
        val r = layout?.getCursorRect(cursor.coerceIn(0, text.length)) ?: return@LaunchedEffect
        val pad = 24
        if (r.right > scroll.value + viewport - pad) scroll.scrollTo((r.right - viewport + pad).toInt())
        else if (r.left < scroll.value + pad) scroll.scrollTo((r.left - pad).toInt().coerceAtLeast(0))
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { viewport = it.width }.horizontalScroll(scroll)) {
        Text(
            text.ifEmpty { " " },
            style = LockInText.Title.copy(color = ink, fontSize = 25.sp, lineHeight = 32.sp),
            maxLines = 1,
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = Modifier
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { p -> layout?.let { onCursor(it.getOffsetForPosition(p)) } },
                        onLongPress = { onPaste() },
                    )
                }
                .drawBehind {
                    val l = layout
                    if (showCaret && caretOn && l != null) {
                        val r = l.getCursorRect(cursor.coerceIn(0, text.length))
                        drawRect(ink, Offset(r.left, r.top), Size(2.dp.toPx(), r.height))
                    }
                },
        )
    }
}

/** The answer. Tap it to copy. Exact answers draw as stacked fractions and proper roots. */
@Composable
private fun LcdResult(v: LcdView, ink: Color, dim: Color, onCopy: () -> Unit, onCopyDecimal: () -> Unit) {
    val color = when (v.kind) {
        3 -> Color(0xFFB3261E)
        1 -> ink.copy(alpha = 0.62f)
        else -> ink
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onCopy() }, onLongPress = { onCopyDecimal() }) }
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Result " + v.result },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (v.lines.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End) {
                v.lines.forEach { (name, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (name.isNotEmpty()) Text("$name =", style = LockInText.Small.copy(color = dim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                        MathText(value, 30.sp, color)
                    }
                }
            }
        } else {
            val shown = v.result.ifEmpty { "0" }
            val tint = if (v.result.isEmpty()) dim else color
            val m = if (v.stack && v.kind == 2) Regex("^(−?)([^/ ]+)/([^/ ]+)$").find(shown) else null
            Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true), verticalAlignment = Alignment.CenterVertically) {
                if (m != null) {
                    val (sign, top, bottom) = m.destructured
                    if (sign.isNotEmpty()) MathText("−", 38.sp, tint)
                    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                        MathText(top, 32.sp, tint)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(tint))
                        MathText(bottom, 32.sp, tint)
                    }
                } else {
                    MathText(shown, if (v.kind == 3) 22.sp else 40.sp, tint)
                }
            }
        }
    }
}

/** Text where √ followed by digits is drawn with a bar over the number, like a real root sign. */
@Composable
private fun MathText(text: String, fontSize: TextUnit, color: Color) {
    val parts = remember(text) {
        val out = ArrayList<Pair<Boolean, String>>()
        var last = 0
        for (m in Regex("√(\\d+)").findAll(text)) {
            if (m.range.first > last) out += false to text.substring(last, m.range.first)
            out += true to m.groupValues[1]
            last = m.range.last + 1
        }
        if (last < text.length) out += false to text.substring(last)
        out
    }
    val style = LockInText.Headline.copy(color = color, fontSize = fontSize, lineHeight = fontSize * 1.18f)
    Row(verticalAlignment = Alignment.Bottom) {
        parts.forEach { (radical, s) ->
            if (!radical) {
                Text(s, style = style, maxLines = 1, softWrap = false)
            } else {
                Text("√", style = style, maxLines = 1)
                Box(
                    Modifier
                        .drawBehind { drawLine(color, Offset(0f, 2.dp.toPx()), Offset(size.width, 2.dp.toPx()), 2.dp.toPx()) }
                        .padding(top = 4.dp),
                ) {
                    Text(s, style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ---- The keys ---------------------------------------------------------------------------------

@Composable
private fun CalcKeypad(flags: KeyFlags, onPress: (CalcKeyDef) -> Unit, onMove: (Int) -> Unit, onInfo: (CalcKeyDef) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // SHIFT, ALPHA and the direction pad, with MODE and 2nd, two columns either side.
        Row(Modifier.fillMaxWidth().weight(2.35f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalcKeys.LeftTop.forEach { col ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    col.forEach { k -> KeyCell(k, flags, onPress, onInfo, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
            DirectionPad(onMove, Modifier.weight(2f).fillMaxHeight().padding(top = 16.dp, bottom = 3.dp))
            CalcKeys.RightTop.forEach { col ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    col.forEach { k -> KeyCell(k, flags, onPress, onInfo, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        }
        CalcKeys.SciRows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1.1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k -> KeyCell(k, flags, onPress, onInfo, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        CalcKeys.NumRows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1.22f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k -> KeyCell(k, flags, onPress, onInfo, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** A key with its SHIFT (amber) and ALPHA (violet) jobs printed above it. */
@Composable
private fun KeyCell(k: CalcKeyDef, flags: KeyFlags, onPress: (CalcKeyDef) -> Unit, onInfo: (CalcKeyDef) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(15.dp).padding(start = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            k.shift?.let {
                Text(
                    it.label,
                    style = LockInText.Small.copy(color = CC.Amber.copy(alpha = if (flags.shift) 1f else 0.78f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            k.alpha?.let {
                Text(
                    it.label,
                    style = LockInText.Small.copy(color = CC.Violet.copy(alpha = if (flags.alpha) 1f else 0.78f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        val label = keyLabel(k, flags.shift, flags.hyp, flags.second)
        val lit = (k.act == KeyAct.Shift && flags.shift) || (k.act == KeyAct.Alpha && flags.alpha) ||
            (k.act == KeyAct.Second && flags.second) || (k.act == KeyAct.Hyp && flags.hyp)
        KeyButton(k, label, lit, label != k.label, { onPress(k) }, { onInfo(k) }, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun KeyButton(k: CalcKeyDef, label: String, lit: Boolean, alternate: Boolean, onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val clickNow by rememberUpdatedState(onClick)
    val longNow by rememberUpdatedState(onLong)
    val squeeze by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 520f), label = "key")
    val drop by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "drop")
    val big = k.style == KeyStyle.Num || k.style == KeyStyle.Op || k.style == KeyStyle.Del || k.style == KeyStyle.Eq
    val face = when (k.style) {
        KeyStyle.Shift -> if (lit) Color(0xFFFFE08A) else CC.Amber
        KeyStyle.Alpha -> if (lit) CC.Violet else CC.AlphaKey
        KeyStyle.Mode -> if (lit) CC.Violet else CC.KeyFn
        KeyStyle.Del -> CC.Orange
        KeyStyle.Fn -> CC.KeyFn
        KeyStyle.Num, KeyStyle.Op, KeyStyle.Eq -> CC.KeyNum
    }
    val fg = when {
        k.style == KeyStyle.Shift || k.style == KeyStyle.Del -> Color(0xFF1A1405)
        k.style == KeyStyle.Mode && lit -> CC.Body
        k.style == KeyStyle.Alpha -> Color.White
        alternate -> CC.Violet
        else -> Color(0xFFF4F5F8)
    }
    val radius = if (big) 16.dp else 12.dp
    val shape = RoundedCornerShape(radius)
    val top = lerp(face, Color.White, 0.10f)
    val edge = lerp(face, Color.Black, 0.55f)
    Box(
        modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze; translationY = drop * 2.dp.toPx() }
            .drawBehind { drawRoundRect(edge, topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = CornerRadius(radius.toPx())) }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, face)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { clickNow() },
                    onLongPress = { longNow() },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = if (label == k.label) k.spoken else label
                this.onClick(label = null) { clickNow(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (k.label == "a/b") {
            FractionGlyph(fg)
        } else {
            val size = when {
                k.style == KeyStyle.Num && label.length == 1 -> 25.sp
                k.style == KeyStyle.Op || k.style == KeyStyle.Eq -> 27.sp
                k.style == KeyStyle.Del -> 17.sp
                k.style == KeyStyle.Shift || k.style == KeyStyle.Alpha || k.style == KeyStyle.Mode -> 14.sp
                label.length <= 2 -> 19.sp
                label.length <= 4 -> 16.sp
                else -> 13.sp
            }
            Text(
                label,
                style = LockInText.Label.copy(
                    color = fg,
                    fontSize = size,
                    fontWeight = if (big || k.style == KeyStyle.Shift || k.style == KeyStyle.Alpha) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The fraction key's face: one box over another, with a bar between. */
@Composable
private fun FractionGlyph(color: Color) {
    Canvas(Modifier.size(width = 22.dp, height = 26.dp)) {
        val w = this.size.width
        val h = this.size.height
        val bw = w * 0.5f
        val bh = h * 0.27f
        val line = Stroke(width = 1.6.dp.toPx())
        drawRect(color, Offset((w - bw) / 2f, 1.dp.toPx()), Size(bw, bh), style = line)
        drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), 2.dp.toPx())
        drawRect(color, Offset((w - bw) / 2f, h - bh - 1.dp.toPx()), Size(bw, bh), style = line)
    }
}

/**
 * The direction pad: ◀ ▶ move the cursor, ▲ ▼ step through earlier sums. Diagonals split it into four
 * presses, like the real one; the pressed arrow lights amber.
 */
@Composable
private fun DirectionPad(onMove: (Int) -> Unit, modifier: Modifier = Modifier) {
    var lit by remember { mutableIntStateOf(-1) }
    LaunchedEffect(lit) { if (lit >= 0) { delay(160); lit = -1 } }
    val shape = RoundedCornerShape(38.dp)
    val squeeze by animateFloatAsState(if (lit >= 0) 0.97f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "pad")
    Box(
        modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .drawBehind { drawRoundRect(lerp(CC.KeyFn, Color.Black, 0.55f), topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = CornerRadius(38.dp.toPx())) }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(CC.KeyFn, Color.White, 0.08f), CC.KeyFn)))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val dx = p.x - this.size.width / 2f
                    val dy = p.y - this.size.height / 2f
                    // Scale by the pad's shape so the diagonals meet the corners.
                    val nx = dx / (this.size.width / 2f)
                    val ny = dy / (this.size.height / 2f)
                    val dir = if (abs(nx) > abs(ny)) (if (nx < 0) 0 else 1) else (if (ny < 0) 2 else 3)
                    lit = dir
                    onMove(dir)
                }
            }
            .semantics { contentDescription = "Direction pad: left and right move the cursor, up and down show earlier sums" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val line = Color(0xFF15161B)
            drawLine(line, Offset(w * 0.08f, h * 0.08f), Offset(w * 0.92f, h * 0.92f), 2.dp.toPx())
            drawLine(line, Offset(w * 0.92f, h * 0.08f), Offset(w * 0.08f, h * 0.92f), 2.dp.toPx())
            drawCircle(line, radius = minOf(w, h) * 0.11f, center = Offset(w / 2f, h / 2f))
            val a = minOf(w, h) * 0.09f
            fun arrow(dir: Int, cx: Float, cy: Float) {
                val p = Path()
                when (dir) {
                    0 -> { p.moveTo(cx - a, cy); p.lineTo(cx + a * 0.7f, cy - a); p.lineTo(cx + a * 0.7f, cy + a) }
                    1 -> { p.moveTo(cx + a, cy); p.lineTo(cx - a * 0.7f, cy - a); p.lineTo(cx - a * 0.7f, cy + a) }
                    2 -> { p.moveTo(cx, cy - a); p.lineTo(cx - a, cy + a * 0.7f); p.lineTo(cx + a, cy + a * 0.7f) }
                    else -> { p.moveTo(cx, cy + a); p.lineTo(cx - a, cy - a * 0.7f); p.lineTo(cx + a, cy - a * 0.7f) }
                }
                p.close()
                drawPath(p, if (lit == dir) CC.Amber else Color(0xFFC9CBD6))
            }
            arrow(0, w * 0.17f, h / 2f)
            arrow(1, w * 0.83f, h / 2f)
            arrow(2, w / 2f, h * 0.17f)
            arrow(3, w / 2f, h * 0.83f)
        }
    }
}
