package com.godbless.lockin.study

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
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
import kotlinx.coroutines.flow.collectLatest
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

private val radicalDigits = Regex("√(\\d+)")

private val stackedAnswer = Regex("^(−?)([^/ ]+)/([^/ ]+)$")

private val plainPower = Regex("\\^(−?[0-9]+(?:\\.[0-9]+)?|[A-Za-z])")

private data class KeyFlags(val shift: Boolean, val alpha: Boolean, val second: Boolean, val hyp: Boolean)

/** How long a key must be held before it shows its help instead of typing. */
private const val KeyHoldMs = 600L

@Composable
internal fun CalculatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lockin_calc", Context.MODE_PRIVATE) }
    // The work, letters, memory and history come back after the app was closed, not only after a rotation.
    val model = rememberSaveable(saver = ModelSaver) { CalcModel.decode(prefs.getString("model", "") ?: "").also { it.settle() } }
    var tick by remember { mutableIntStateOf(0) }
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
    // Save a moment after the last change. Reading the tick here (not in the body) keeps the screen itself from redrawing per key.
    LaunchedEffect(Unit) {
        snapshotFlow { tick }.collectLatest {
            delay(600)
            prefs.edit().putString("model", model.encode()).apply()
        }
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

    // The sum updates first and the buzz comes after, so the vibration never holds the display up.
    fun press(k: CalcKeyDef) {
        go(model.press(k))
        bump()
        buzz(if (k.act == KeyAct.Eq) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
    }

    fun move(dir: Int) {
        model.move(dir)
        bump()
        buzz(HapticFeedbackType.TextHandleMove)
    }

    /** A key that typed the moment it was touched turns out to be held for its help: take that press back exactly. */
    fun undoPress() {
        if (model.pressPushed && model.undo()) bump()
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

    // The keypad gets the same three callbacks every time, so pressing a key redraws the screen and display, not all 40 keys.
    val pressNow by rememberUpdatedState<(CalcKeyDef) -> Unit>(::press)
    val moveNow by rememberUpdatedState<(Int) -> Unit>(::move)
    val infoNow by rememberUpdatedState<(CalcKeyDef) -> Unit>(::showInfo)
    val onKey = remember { { k: CalcKeyDef -> pressNow(k) } }
    val onArrow = remember { { d: Int -> moveNow(d) } }
    val onKeyInfo = remember { { k: CalcKeyDef -> infoNow(k) } }
    val undoPressNow by rememberUpdatedState<() -> Unit>(::undoPress)
    val onUndoPress = remember { { undoPressNow() } }
    val readTick = remember { { tick } }

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

    // While a menu is open the screen follows the model (a setting changes, history is cleared). Otherwise it does not
    // subscribe at all, so typing redraws only the display, and the toolbar and keypad only when something they show changes.
    if (sheet != CalcSheet.None) tick

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Box(Modifier.fillMaxSize().calcChassis()) {
        val toolRow: @Composable () -> Unit = {
            ToolSection(
                model, readTick,
                { sheet = it },
                { model.lcd = (model.lcd + 1) % LcdThemes.size; bump() },
                { model.insert("="); bump() },
                { if (model.undo()) bump() },
                { model.polar = !model.polar; model.restyle(); bump() },
            )
        }
        val lcdSection: @Composable (Modifier) -> Unit = { mod ->
            LcdSection(
                model, readTick, notice, sheet == CalcSheet.Sto, sheet == CalcSheet.Rcl,
                { model.tapCursor(it); bump() }, { go(Req.Copy); bump() }, { copyDecimal() }, { go(Req.Paste); bump() },
                { model.changeAngle(AngleMode.Rad); notice = "Radians on"; bump() },
                mod,
            )
        }
        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(top = topPad).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopRow(onBack)
                    Spacer(Modifier.height(8.dp))
                    lcdSection(Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                    toolRow()
                }
                KeypadSection(model, readTick, onKey, onArrow, onKeyInfo, onUndoPress, Modifier.weight(1.45f).fillMaxHeight())
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
                    lcdSection(Modifier.heightIn(min = 118.dp))
                    Spacer(Modifier.height(6.dp))
                } else {
                    TopRow(onBack)
                    Spacer(Modifier.height(8.dp))
                    lcdSection(Modifier.heightIn(min = 168.dp))
                    Spacer(Modifier.height(8.dp))
                    toolRow()
                    Spacer(Modifier.height(6.dp))
                }
                KeypadSection(model, readTick, onKey, onArrow, onKeyInfo, onUndoPress, Modifier.weight(1f).fillMaxWidth())
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
            if (sheet == CalcSheet.History) model.history.map { prettySum(it.first) to it.second } else emptyList(),
            { i -> model.recallHistory(i); sheet = CalcSheet.None; bump() },
            { i -> model.insertResult(i); sheet = CalcSheet.None; bump() },
            { model.clearHistory(); bump() },
            { sheet = CalcSheet.None },
        )
        SettingsSheet(sheet == CalcSheet.Settings, model, readTick, { bump() }, { sheet = CalcSheet.None })
        KeyInfoSheet(sheet == CalcSheet.KeyInfo, infoKey) { sheet = CalcSheet.None }
        TipSheet(sheet == CalcSheet.Tip) { closeSheet() }
        CompareSheet(sheet == CalcSheet.Compare, { sign -> model.insert(sign); sheet = CalcSheet.None; bump() }, { sheet = CalcSheet.None })
    }
}

/** The display. It redraws on every key press, and nothing else on the screen has to. */
@Composable
private fun LcdSection(
    model: CalcModel,
    tick: () -> Int,
    notice: String,
    sto: Boolean,
    rcl: Boolean,
    onCursor: (Int) -> Unit,
    onCopy: () -> Unit,
    onCopyDecimal: () -> Unit,
    onPaste: () -> Unit,
    onRad: () -> Unit,
    modifier: Modifier,
) {
    val version = tick()
    var frameMs by remember { mutableStateOf(0f) }
    if (model.showSpeed) {
        // Debug readout: the keypad logic time, and how long until the next frame after the key went down.
        LaunchedEffect(version) {
            val t0 = model.lastPressNanos
            if (t0 != 0L) {
                withFrameNanos { }
                frameMs = (System.nanoTime() - t0) / 1_000_000f
            }
        }
    }
    val kind = when {
        model.error.isNotEmpty() -> 3
        model.done -> 2
        model.preview.isNotEmpty() -> 1
        else -> 0
    }
    val radHint = notice.isEmpty() && model.caption.isEmpty() && model.needsRad()
    val speed = if (model.showSpeed) "keys: logic ${model.lastLogicMicros} µs, to next frame ${"%.1f".format(frameMs)} ms" else ""
    val lcd = LcdView(
        theme = LcdThemes[model.lcd.coerceIn(0, LcdThemes.lastIndex)],
        shift = model.shift, alpha = model.alpha, second = model.second, hyp = model.hyp, mem = model.memory != 0.0,
        sto = sto, rcl = rcl,
        fmtLabel = model.fmt.label, displayLabel = if (model.mathDisplay) "MATH" else "LINE",
        angleLabel = model.angle.label.uppercase(), complex = model.cmplx,
        caption = if (notice.isNotEmpty()) notice else if (model.caption.isNotEmpty()) model.caption else if (radHint) "Trig in calculus is in degrees: tap for radians" else speed,
        input = model.inputText, cursor = model.inputCursor, showCaret = !model.done,
        result = model.resultText(), kind = kind, lines = if (model.done) model.lines else emptyList(),
        stack = model.mathDisplay && !model.flip && model.done && !model.eng && !model.si && !model.dms,
    )
    CalcLcd(lcd, onCursor, onCopy, onCopyDecimal, onPaste, if (radHint) onRad else null, modifier)
}

/** The toolbar. It only redraws when something it shows changes (Undo available, complex mode, screen colour). */
@Composable
private fun ToolSection(
    model: CalcModel,
    tick: () -> Int,
    open: (CalcSheet) -> Unit,
    nextTheme: () -> Unit,
    typeEquals: () -> Unit,
    undo: () -> Unit,
    flipPolar: () -> Unit,
) {
    val canUndo by remember(model) { derivedStateOf { tick(); model.canUndo } }
    val complex by remember(model) { derivedStateOf { tick(); model.cmplx } }
    val polar by remember(model) { derivedStateOf { tick(); model.polar } }
    val theme by remember(model) { derivedStateOf { tick(); model.lcd } }
    ToolRow(theme, complex, polar, canUndo, open, nextTheme, typeEquals, undo, flipPolar)
}

/** The keys. They only redraw when SHIFT, ALPHA, 2nd or hyp changes: typing digits does not touch them. */
@Composable
private fun KeypadSection(
    model: CalcModel,
    tick: () -> Int,
    onKey: (CalcKeyDef) -> Unit,
    onArrow: (Int) -> Unit,
    onKeyInfo: (CalcKeyDef) -> Unit,
    onUndoPress: () -> Unit,
    modifier: Modifier,
) {
    val flags by remember(model) { derivedStateOf { tick(); KeyFlags(model.shift, model.alpha, model.second, model.hyp) } }
    CalcKeypad(flags, onKey, onArrow, onKeyInfo, onUndoPress, modifier)
}

@Composable
private fun TopRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        CalcBrandRow(Modifier.weight(1f).padding(start = 14.dp))
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
            .lcdBezel()
            .padding(5.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lighter(Color(v.theme.screen), 0.06f), Color(v.theme.screen), darker(Color(v.theme.screen), 0.05f))))
            .lcdGlass()
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

/** The sum being typed. A sum with templates (fractions, powers, roots, |x|, ×10 exponents) is drawn by [FracInput], any other by [PlainInput]. */
@Composable
private fun LcdInput(text: String, cursor: Int, showCaret: Boolean, ink: Color, onCursor: (Int) -> Unit, onPaste: () -> Unit) {
    val nodes = remember(text) { if (hasTemplates(text)) parseSum(text) else null }
    if (nodes != null) FracInput(nodes, text, cursor, showCaret, ink, onCursor, onPaste)
    else PlainInput(text, cursor, showCaret, ink, onCursor, onPaste)
}

/** Everything the pieces of a fraction-aware input line need to share. */
private class InputCtx(
    val ink: Color,
    val cursor: Int,
    val showCaret: Boolean,
    val caretOn: Boolean,
    val owner: SumText?,
    val layouts: HashMap<SumText, TextLayoutResult>,
    val coords: HashMap<SumText, LayoutCoordinates>,
    val onLayout: () -> Unit,
    val onCursor: (Int) -> Unit,
    val onPaste: () -> Unit,
)

/**
 * The sum with real stacked fractions: a bar with the top over the bottom (nested ones too), an empty part shown as
 * a small box, and the caret inside whichever part you are editing. Each piece of text is its own field that knows
 * where it starts in the sum, so taps and the caret still line up with the real text.
 */
@Composable
private fun FracInput(nodes: List<SumNode>, text: String, cursor: Int, showCaret: Boolean, ink: Color, onCursor: (Int) -> Unit, onPaste: () -> Unit) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    var caretOn by remember { mutableStateOf(true) }
    var layoutTick by remember { mutableIntStateOf(0) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val layouts = remember(nodes) { HashMap<SumText, TextLayoutResult>() }
    val coords = remember(nodes) { HashMap<SumText, LayoutCoordinates>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            caretOn = !caretOn
        }
    }
    val owner = caretPiece(nodes, cursor.coerceIn(0, text.length))
    // Keep the caret in view when the sum is wider than the screen.
    LaunchedEffect(cursor, text, layoutTick, viewport) {
        val o = owner ?: return@LaunchedEffect
        val lay = layouts[o] ?: return@LaunchedEffect
        val c = coords[o] ?: return@LaunchedEffect
        val root = rootCoords ?: return@LaunchedEffect
        if (!c.isAttached || !root.isAttached) return@LaunchedEffect
        val r = lay.getCursorRect((cursor - o.start).coerceIn(0, o.text.length))
        val x = c.positionInRoot().x - root.positionInRoot().x + scroll.value + r.left
        val pad = 24
        if (x > scroll.value + viewport - pad) scroll.scrollTo((x - viewport + pad).toInt())
        else if (x < scroll.value + pad) scroll.scrollTo((x - pad).toInt().coerceAtLeast(0))
    }
    val ctx = InputCtx(ink, cursor, showCaret, caretOn, owner, layouts, coords, { layoutTick++ }, onCursor, onPaste)
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .onGloballyPositioned { rootCoords = it }
            .horizontalScroll(scroll),
    ) {
        SumRow(nodes, 0, ctx, false)
    }
}

/** Type size by how deeply a part is nested (a fraction's top, an exponent…): the further in, the smaller. */
private fun sizeFor(depth: Int) = when (depth) { 0 -> 25.sp; 1 -> 20.sp; else -> 16.sp }

/** [boxed]: this row is the inside of a template, so an empty piece shows as a little box to type into. */
@Composable
private fun SumRow(nodes: List<SumNode>, depth: Int, ctx: InputCtx, boxed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        nodes.forEach { n ->
            when (n) {
                is SumText -> SumLeaf(n, depth, ctx, boxed)
                is SumFrac -> Column(Modifier.padding(horizontal = 3.dp).width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                    SumRow(n.top, depth + 1, ctx, true)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(ctx.ink))
                    SumRow(n.bottom, depth + 1, ctx, true)
                }
                is SumGroup -> SumGroupView(n, depth, ctx)
            }
        }
    }
}

/**
 * The templates. A power raises its exponent (the base is just the text before it), a root is a radical sign with a bar
 * over what is inside (an nth root has its small index in front), |x| gets real bars, and the ×10 of 1.5E3 raises its
 * exponent. The brackets that belong to the template are not drawn.
 */
@Composable
private fun SumGroupView(g: SumGroup, depth: Int, ctx: InputCtx) {
    val size = sizeFor(depth)
    val small = sizeFor(depth + 1)
    val glyph = LockInText.Title.copy(color = ctx.ink, fontSize = size, lineHeight = size * 1.28f)
    when (g.kind) {
        GroupKind.Pow -> Box(Modifier.padding(bottom = 16.dp)) { SumRow(g.body, depth + 1, ctx, true) }
        GroupKind.Sci -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("×10", style = glyph, maxLines = 1, softWrap = false)
            Box(Modifier.padding(bottom = 16.dp)) { SumRow(g.body, depth + 1, ctx, true) }
        }
        GroupKind.Log -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("log", style = glyph, maxLines = 1, softWrap = false)
            // the base: small, and down below the line
            Box(Modifier.padding(top = 14.dp)) { SumRow(g.base ?: emptyList(), depth + 1, ctx, true) }
            Text("(", style = glyph, maxLines = 1, softWrap = false)
            SumRow(g.body, depth, ctx, true)
            Text(")", style = glyph, maxLines = 1, softWrap = false)
        }
        GroupKind.Abs -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("|", style = glyph, maxLines = 1, softWrap = false)
            SumRow(g.body, depth, ctx, true)
            Text("|", style = glyph, maxLines = 1, softWrap = false)
        }
        GroupKind.Sqrt, GroupKind.Cbrt, GroupKind.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
            when (g.kind) {
                GroupKind.Cbrt -> Box(Modifier.padding(bottom = 12.dp)) {
                    Text("3", style = LockInText.Title.copy(color = ctx.ink, fontSize = small, lineHeight = small * 1.2f), maxLines = 1, softWrap = false)
                }
                GroupKind.Root -> g.index?.let { Box(Modifier.padding(bottom = 12.dp)) { SumLeaf(it, depth + 1, ctx, true) } }
                else -> {}
            }
            Text("√", style = glyph.copy(fontSize = size * 1.1f), maxLines = 1, softWrap = false)
            Box(
                Modifier
                    .drawBehind { drawLine(ctx.ink, Offset(0f, 1.dp.toPx()), Offset(this.size.width, 1.dp.toPx()), 2.dp.toPx()) }
                    .padding(top = 4.dp, start = 1.dp, end = 3.dp),
            ) {
                SumRow(g.body, depth, ctx, true)
            }
        }
    }
}

@Composable
private fun SumLeaf(leaf: SumText, depth: Int, ctx: InputCtx, boxed: Boolean) {
    val size = sizeFor(depth)
    val isOwner = ctx.owner === leaf
    // An empty part inside a template is drawn as a little box, so you can see where to type.
    val slot = leaf.text.isEmpty() && (depth > 0 || boxed)
    val slotShape = RoundedCornerShape(4.dp)
    Text(
        remember(leaf.text) { styledSum(leaf.text.ifEmpty { " " }) },
        style = LockInText.Title.copy(color = ctx.ink, fontSize = size, lineHeight = size * 1.28f, shadow = lcdInkShadow(ctx.ink)),
        maxLines = 1,
        softWrap = false,
        onTextLayout = {
            // Only tell the line to re-check the caret when this piece's size really changed (no feedback loop).
            val before = ctx.layouts.put(leaf, it)
            if (before == null || before.size != it.size) ctx.onLayout()
        },
        modifier = Modifier
            .then(if (slot) Modifier.defaultMinSize(minWidth = 20.dp).border(1.dp, ctx.ink.copy(alpha = 0.4f), slotShape).padding(horizontal = 2.dp) else Modifier)
            .onGloballyPositioned { ctx.coords[leaf] = it }
            .pointerInput(leaf) {
                detectTapGestures(
                    onTap = { p -> ctx.layouts[leaf]?.let { ctx.onCursor(leaf.start + it.getOffsetForPosition(p).coerceAtMost(leaf.text.length)) } },
                    onLongPress = { ctx.onPaste() },
                )
            }
            .drawBehind {
                val l = ctx.layouts[leaf]
                if (isOwner && ctx.showCaret && ctx.caretOn && l != null) {
                    val r = l.getCursorRect((ctx.cursor - leaf.start).coerceIn(0, leaf.text.length))
                    drawRect(ctx.ink, Offset(r.left, r.top), Size(2.dp.toPx(), r.height))
                }
            },
    )
}

/** The sum being typed: tap to put the cursor there, hold to paste, drag to scroll a long sum. */
@Composable
private fun PlainInput(text: String, cursor: Int, showCaret: Boolean, ink: Color, onCursor: (Int) -> Unit, onPaste: () -> Unit) {
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
            remember(text) { styledSum(text.ifEmpty { " " }) },
            style = LockInText.Title.copy(color = ink, fontSize = 25.sp, lineHeight = 32.sp, shadow = lcdInkShadow(ink)),
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

private val fractionInSum = Regex("(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)")

/**
 * The sum as drawn on the input line. A number typed over a number (the a/b key, or 3/4 pasted) is drawn raised over
 * lowered around a fraction slash, not as a plain ÷; any other / is drawn as ÷. The text keeps its length, so the
 * caret and taps still line up with the real sum.
 */
private fun styledSum(text: String): AnnotatedString {
    val shown = StringBuilder(text)
    val raised = ArrayList<IntRange>()
    val lowered = ArrayList<IntRange>()
    for (m in fractionInSum.findAll(text)) {
        val top = m.groups[1]!!.range
        val bottom = m.groups[2]!!.range
        raised += top
        lowered += bottom
        shown.setCharAt(top.last + 1, '⁄')
    }
    for (i in text.indices) {
        if (text[i] == '/' && shown[i] == '/' && !text.startsWith("d/dx(", i - 1)) shown.setCharAt(i, '÷')
    }
    // A plain 2^3 (pasted, or from an old sum): the exponent rises and the ^ mark shrinks away, so it reads as 2³.
    val caretMarks = ArrayList<Int>()
    for (m in plainPower.findAll(text)) {
        raised += m.groups[1]!!.range
        caretMarks += m.range.first
    }
    return buildAnnotatedString {
        append(shown.toString())
        for (i in caretMarks) addStyle(SpanStyle(fontSize = 1.sp), i, i + 1)
        for (r in raised) addStyle(SpanStyle(fontSize = 17.sp, baselineShift = BaselineShift(0.45f)), r.first, r.last + 1)
        for (r in lowered) addStyle(SpanStyle(fontSize = 17.sp, baselineShift = BaselineShift(-0.2f)), r.first, r.last + 1)
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
            val m = if (v.stack && v.kind == 2) stackedAnswer.find(shown) else null
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
        for (m in radicalDigits.findAll(text)) {
            if (m.range.first > last) out += false to text.substring(last, m.range.first)
            out += true to m.groupValues[1]
            last = m.range.last + 1
        }
        if (last < text.length) out += false to text.substring(last)
        out
    }
    val style = LockInText.Headline.copy(color = color, fontSize = fontSize, lineHeight = fontSize * 1.18f, shadow = lcdInkShadow(color))
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
private fun CalcKeypad(flags: KeyFlags, onPress: (CalcKeyDef) -> Unit, onMove: (Int) -> Unit, onInfo: (CalcKeyDef) -> Unit, onUndoPress: () -> Unit, modifier: Modifier = Modifier) {
    // There are no gaps between the cells: each key's touch area runs right up to its neighbours, so a tap that lands
    // between two keys still counts. The gaps you see are padding inside each cell.
    Column(modifier) {
        // SHIFT, ALPHA and the direction pad, with MODE and 2nd, two columns either side.
        Row(Modifier.fillMaxWidth().weight(2.35f)) {
            CalcKeys.LeftTop.forEach { col ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    col.forEach { k -> KeyCell(k, flags, onPress, onInfo, onUndoPress, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
            DirectionPad(onMove, Modifier.weight(2f).fillMaxHeight().padding(start = 3.dp, end = 3.dp, top = 16.dp, bottom = 3.dp))
            CalcKeys.RightTop.forEach { col ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    col.forEach { k -> KeyCell(k, flags, onPress, onInfo, onUndoPress, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        }
        CalcKeys.SciRows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1.1f)) {
                row.forEach { k -> KeyCell(k, flags, onPress, onInfo, onUndoPress, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        CalcKeys.NumRows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1.22f)) {
                row.forEach { k -> KeyCell(k, flags, onPress, onInfo, onUndoPress, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/**
 * A key with its SHIFT (amber) and ALPHA (violet) jobs printed above it. The whole cell, labels and margins included, is
 * the touch target. A press counts when the finger lifts, wherever on the screen it slid to, so quick, sloppy thumb taps
 * are not lost. Holding for [KeyHoldMs] shows what the key does instead.
 */
@Composable
private fun KeyCell(k: CalcKeyDef, flags: KeyFlags, onPress: (CalcKeyDef) -> Unit, onInfo: (CalcKeyDef) -> Unit, onUndoPress: () -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val pressNow by rememberUpdatedState(onPress)
    val infoNow by rememberUpdatedState(onInfo)
    val undoNow by rememberUpdatedState(onUndoPress)
    // Digits, operators, = and the clear keys type the instant they are touched, not when the finger lifts: no wait, and a
    // quick tap can't be lost. (A key with SHIFT/ALPHA/2nd/hyp active still waits for the lift, since it may open a menu.)
    val fireOnDown = (k.style == KeyStyle.Num || k.style == KeyStyle.Op || k.style == KeyStyle.Eq || k.style == KeyStyle.Del) &&
        !flags.shift && !flags.alpha && !flags.second && !flags.hyp
    val fireNow by rememberUpdatedState(fireOnDown)
    val label = keyLabel(k, flags.shift, flags.hyp, flags.second)
    val lit = (k.act == KeyAct.Shift && flags.shift) || (k.act == KeyAct.Alpha && flags.alpha) ||
        (k.act == KeyAct.Second && flags.second) || (k.act == KeyAct.Hyp && flags.hyp)
    Column(
        modifier
            .pointerInput(k) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    val early = fireNow
                    if (early) pressNow(k)
                    var released = false
                    val shortPress = withTimeoutOrNull(KeyHoldMs) {
                        var over = false
                        while (!over) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                over = true
                            } else if (change.changedToUpIgnoreConsumed()) {
                                change.consume(); released = true; over = true
                            } else if (!change.pressed) {
                                over = true // cancelled by the system
                            } else {
                                change.consume()
                            }
                        }
                        true
                    }
                    if (shortPress == null) {
                        // Held: show the key's help (taking back what this touch already typed), then wait for the lift.
                        if (early) undoNow()
                        infoNow(k)
                        var over = false
                        while (!over) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.changedToUpIgnoreConsumed()) over = true else change.consume()
                        }
                    } else if (released && !early) {
                        pressNow(k)
                    }
                    pressed = false
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = if (label == k.label) k.spoken else label
                this.onClick(label = null) { pressNow(k); true }
                this.onLongClick(label = "Show what this key does") { infoNow(k); true }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(15.dp).padding(start = 6.dp),
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
        KeyButton(k, label, lit, label != k.label, pressed, Modifier.weight(1f).fillMaxWidth().padding(start = 3.dp, end = 3.dp, bottom = 5.dp))
    }
}

/** The key as drawn. It only draws: touches are handled by the [KeyCell] around it, so the press squeeze never moves the touch area. */
@Composable
private fun KeyButton(k: CalcKeyDef, label: String, lit: Boolean, alternate: Boolean, pressed: Boolean, modifier: Modifier) {
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
    val skirt = darker(face, 0.6f)
    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f - 0.18f * drop), Color.White.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.38f)))
    Box(
        modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze; translationY = drop * 2.dp.toPx() }
            .drawBehind {
                val r = CornerRadius(radius.toPx())
                // The shadow the key throws on the body, then the side wall of the cap (shorter once it is pressed in).
                val lift = 3.dp.toPx() * (1f - 0.5f * drop)
                drawRoundRect(Color.Black.copy(alpha = 0.30f), topLeft = Offset(-1.dp.toPx(), lift + 2.dp.toPx()), size = Size(size.width + 2.dp.toPx(), size.height + 1.dp.toPx()), cornerRadius = r)
                drawRoundRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset(-2.dp.toPx(), lift + 3.5.dp.toPx()), size = Size(size.width + 4.dp.toPx(), size.height + 2.dp.toPx()), cornerRadius = r)
                drawRoundRect(skirt, topLeft = Offset(0f, lift), size = size, cornerRadius = r)
            }
            .clip(shape)
            .background(keyFace(face))
            .drawBehind {
                // A soft dish in the middle of the cap and a bright line along its upper edge.
                val inset = 4.dp.toPx()
                val dish = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent, Color.White.copy(alpha = 0.09f)))
                drawRoundRect(dish, topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), cornerRadius = CornerRadius((radius.toPx() - 3.dp.toPx()).coerceAtLeast(4f)))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f * (1f - drop)), Color.Transparent), startY = 0f, endY = size.height * 0.42f),
                    size = Size(size.width, size.height * 0.45f), cornerRadius = CornerRadius(radius.toPx()),
                )
                if (lit) drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = CornerRadius(radius.toPx()))
            }
            .border(1.dp, rim, shape),
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
                    // Printed light on dark caps casts a little shadow; dark print on a light cap gets a pale lower edge instead.
                    shadow = if (fg.luminance() < 0.3f) Shadow(Color.White.copy(alpha = 0.35f), Offset(0f, 1.5f), 0f) else Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 2f),
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
    val squeeze by animateFloatAsState(if (lit >= 0) 0.97f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "pad")
    val moveNow by rememberUpdatedState(onMove)
    Box(
        modifier
            // The touch handling sits outside the squeeze below, so the pad never shrinks out from under your finger.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val nx = (down.position.x - size.width / 2f) / (size.width / 2f)
                    val ny = (down.position.y - size.height / 2f) / (size.height / 2f)
                    val dir = if (abs(nx) > abs(ny)) (if (nx < 0) 0 else 1) else (if (ny < 0) 2 else 3)
                    lit = dir
                    moveNow(dir) // moves the moment you touch, no waiting for the lift
                    var interval = 380L // hold to keep moving
                    var holding = true
                    while (holding) {
                        val lifted = withTimeoutOrNull(interval) {
                            var over = false
                            while (!over) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed || change.changedToUpIgnoreConsumed()) over = true else change.consume()
                            }
                            true
                        }
                        if (lifted == null) { moveNow(dir); interval = 70L } else holding = false
                    }
                }
            }
            .semantics { contentDescription = "Direction pad: left and right move the cursor, up and down show earlier sums" }
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .drawBehind {
                // A round pad with a steel ring, a dished face split into four, and a centre button.
                val d = minOf(size.width, size.height)
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = d / 2f - 1.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.30f), r + 1.dp.toPx(), c + Offset(0f, 5.dp.toPx()))
                drawCircle(darker(CC.KeyFn, 0.6f), r, c + Offset(0f, 3.dp.toPx()))
                // the metal ring
                drawCircle(
                    Brush.sweepGradient(
                        listOf(Color(0xFF8E919D), Color(0xFFE4E6EE), Color(0xFF6A6D79), Color(0xFFC9CCD7), Color(0xFF7C7F8B), Color(0xFFE4E6EE), Color(0xFF8E919D)),
                        center = c,
                    ),
                    r, c,
                )
                drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(1.dp.toPx()))
                // the face
                val face = r * 0.86f
                drawCircle(Brush.verticalGradient(listOf(lighter(CC.KeyFn, 0.14f), CC.KeyFn, darker(CC.KeyFn, 0.22f)), startY = c.y - face, endY = c.y + face), face, c)
                drawCircle(Color.Black.copy(alpha = 0.40f), face, c, style = Stroke(1.5.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.10f), face - 1.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                // the four directions are separated by grooves
                val groove = Color(0xFF0F1014)
                val k = 0.7071f
                for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                    val from = c + Offset(sx * k * face * 0.30f, sy * k * face * 0.30f)
                    val to = c + Offset(sx * k * face * 0.97f, sy * k * face * 0.97f)
                    drawLine(groove, from, to, 2.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.10f), from + Offset(1f, 1.5f), to + Offset(1f, 1.5f), 1.dp.toPx())
                }
                // the centre button, a little proud of the face
                val hub = face * 0.30f
                drawCircle(Color.Black.copy(alpha = 0.45f), hub + 1.5.dp.toPx(), c + Offset(0f, 1.5.dp.toPx()))
                drawCircle(Brush.verticalGradient(listOf(lighter(CC.KeyFn, 0.22f), darker(CC.KeyFn, 0.25f)), startY = c.y - hub, endY = c.y + hub), hub, c)
                drawCircle(Color.White.copy(alpha = 0.20f), hub, c, style = Stroke(1.dp.toPx()))
                // the arrows, and the amber glow under the one being pressed
                val a = d * 0.052f
                val dist = face * 0.66f
                fun arrow(dir: Int, cx: Float, cy: Float) {
                    val on = lit == dir
                    if (on) drawCircle(Brush.radialGradient(listOf(CC.Amber.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = a * 3.4f), a * 3.4f, Offset(cx, cy))
                    val p = Path()
                    when (dir) {
                        0 -> { p.moveTo(cx - a, cy); p.lineTo(cx + a * 0.7f, cy - a); p.lineTo(cx + a * 0.7f, cy + a) }
                        1 -> { p.moveTo(cx + a, cy); p.lineTo(cx - a * 0.7f, cy - a); p.lineTo(cx - a * 0.7f, cy + a) }
                        2 -> { p.moveTo(cx, cy - a); p.lineTo(cx - a, cy + a * 0.7f); p.lineTo(cx + a, cy + a * 0.7f) }
                        else -> { p.moveTo(cx, cy + a); p.lineTo(cx - a, cy - a * 0.7f); p.lineTo(cx + a, cy - a * 0.7f) }
                    }
                    p.close()
                    if (!on) drawPath(p, Color.Black.copy(alpha = 0.5f), style = Stroke(1.dp.toPx()))
                    drawPath(p, if (on) CC.Amber else Color(0xFFD5D7E0))
                }
                arrow(0, c.x - dist, c.y)
                arrow(1, c.x + dist, c.y)
                arrow(2, c.x, c.y - dist)
                arrow(3, c.x, c.y + dist)
            },
    )
}
