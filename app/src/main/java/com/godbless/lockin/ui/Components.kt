@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.godbless.lockin.R
import com.godbless.lockin.intro.ContourField
import com.godbless.lockin.intro.clamp01
import com.godbless.lockin.intro.easeOutCubic
import kotlin.math.hypot
import kotlin.math.max

private val GlideCurve = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** The curve most slides use. With reduced motion on it jumps straight to the end, so slides finish at once. */
internal val UiGlide = androidx.compose.animation.core.Easing { f ->
    if (MotionState.reduced) (if (f <= 0f) 0f else 1f) else GlideCurve.transform(f)
}

// ---------------------------------------------------------------------------------------------
// Entrance: rows of a form slide up one after another. Nothing fades.
// ---------------------------------------------------------------------------------------------

/** Runs 0 to 1 over the entrance of a screen or sheet face. Read it inside [rise] only. */
@Composable
fun rememberEntrance(millis: Int = 900): State<Float> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(if (MotionState.reduced) 1 else millis, easing = LinearEasing)) }
    return a.asState()
}

/** Slides a row up into place. [index] sets how late in the entrance it starts. */
fun Modifier.rise(entrance: State<Float>, index: Int): Modifier = graphicsLayer {
    val p = easeOutCubic(clamp01((entrance.value * 0.9f - index * 0.06f) / 0.5f))
    translationY = (1f - p) * 26.dp.toPx()
}

/**
 * A livelier [rise] for Home: each row travels further, overshoots a touch and settles, and grows from
 * slightly small to full size on the way. Still no fades.
 */
fun Modifier.riseSpring(entrance: State<Float>, index: Int): Modifier = graphicsLayer {
    val x = clamp01((entrance.value * 1.1f - index * 0.07f) / 0.55f)
    val p = com.godbless.lockin.intro.easeOutBack(x, 1.25f)
    translationY = (1f - p) * 44.dp.toPx()
    val sc = 0.955f + 0.045f * clamp01(p)
    scaleX = sc
    scaleY = sc
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

enum class ButtonKind { Primary, Outline }

/** Every control (buttons, fields, code boxes, banners) shares this one corner style. */
val ControlShape = RoundedCornerShape(percent = 50)

/** True while the on-screen keyboard is up, so screens can drop what they don't need to show. */
val LocalKeyboardUp = compositionLocalOf { false }

/** Slides a block open or shut (never fades). */
@Composable
fun Collapse(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(tween(260, easing = UiGlide)),
        exit = shrinkVertically(tween(220, easing = UiGlide)),
    ) { content() }
}

/** Thin progress bar for the sign-up steps; each segment wipes in from the left. */
@Composable
fun StepBar(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            val fill by animateFloatAsState(if (i <= current) 1f else 0f, tween(450, easing = UiGlide), label = "step-fill")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(LockInColors.Graphite),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(LockInColors.Lime))
            }
        }
    }
}

/**
 * Pill button. It squeezes a little when pressed. The label slides up out of the way for a spinner
 * while [loading], and a tick draws itself when [success] is true.
 */
@Composable
fun LockInButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    loading: Boolean = false,
    success: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squeeze by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "press",
    )
    val shape = ControlShape
    val primary = kind == ButtonKind.Primary
    val fg by androidx.compose.animation.animateColorAsState(
        when {
            !enabled -> LockInColors.Mist
            primary -> LockInColors.OnLime
            else -> LockInColors.Snow
        },
        tween(220, easing = UiGlide),
        label = "button-fg",
    )
    val fill by androidx.compose.animation.animateColorAsState(if (enabled) LockInColors.Lime else LockInColors.Graphite, tween(220, easing = UiGlide), label = "button-fill")
    val rim by androidx.compose.animation.animateColorAsState(if (enabled) LockInColors.Rim else LockInColors.Graphite, tween(220, easing = UiGlide), label = "button-rim")
    val base = modifier
        .fillMaxWidth()
        .height(56.dp)
        .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
        .clip(shape)
    val styled = if (primary) {
        base.background(fill)
    } else {
        base.border(1.5.dp, rim, shape)
    }
    Box(
        styled.clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled && !loading && !success,
            role = Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = if (success) 2 else if (loading) 1 else 0,
            transitionSpec = {
                slideInVertically(tween(260, easing = UiGlide)) { it } togetherWith
                    slideOutVertically(tween(260, easing = UiGlide)) { -it }
            },
            label = "button-state",
        ) { state ->
            when (state) {
                2 -> CheckMark(color = fg, boxSize = 26.dp)
                1 -> RingSpinner(color = fg)
                else -> Text(text, style = LockInText.Button.copy(color = fg))
            }
        }
    }
}

/**
 * A ring that turns at a steady speed with a fixed-length gap. (Material's own indeterminate ring grows from a
 * dot and shrinks back, which on a long wait looks like it keeps breaking.)
 */
@Composable
fun RingSpinner(color: Color, modifier: Modifier = Modifier, boxSize: Dp = 22.dp) {
    val turn by androidx.compose.animation.core.rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ring-turn",
    )
    Canvas(modifier.size(boxSize)) {
        val w = 2.2.dp.toPx()
        drawArc(
            color = color,
            startAngle = turn - 90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w / 2f, w / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** A tick that draws itself. */
@Composable
fun CheckMark(color: Color, boxSize: Dp) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(380, easing = UiGlide)) }
    Canvas(Modifier.size(boxSize)) {
        val k = this.size.width / 24f
        val ax = 5f * k
        val ay = 12.5f * k
        val bx = 10f * k
        val by = 17.5f * k
        val cx = 19f * k
        val cy = 7f * k
        val l1 = hypot(bx - ax, by - ay)
        val l2 = hypot(cx - bx, cy - by)
        val drawn = progress.value * (l1 + l2)
        val path = Path()
        path.moveTo(ax, ay)
        if (drawn <= l1) {
            val f = drawn / l1
            path.lineTo(ax + (bx - ax) * f, ay + (by - ay) * f)
        } else {
            path.lineTo(bx, by)
            val f = (drawn - l1) / l2
            path.lineTo(bx + (cx - bx) * f, by + (cy - by) * f)
        }
        drawPath(path, color, style = Stroke(width = 2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Round "Continue with Google" button that sits beside a primary button. */
@Composable
fun GoogleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squeeze by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "google-press",
    )
    Box(
        modifier
            .size(56.dp)
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(CircleShape)
            .border(1.5.dp, LockInColors.Rim, CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Continue with Google" },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(26.dp))
    }
}

/** The main action and the Google button side by side. The Google button tucks away while the keyboard is up. */
@Composable
fun ActionRow(
    text: String,
    onClick: () -> Unit,
    onGoogle: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    success: Boolean = false,
    enabled: Boolean = true,
) {
    val keyboardUp = LocalKeyboardUp.current
    val googleWidth by animateDpAsState(if (keyboardUp) 0.dp else 68.dp, tween(260, easing = UiGlide), label = "google-width")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LockInButton(text, onClick, modifier = Modifier.weight(1f), loading = loading, success = success, enabled = enabled)
        Box(Modifier.width(googleWidth).clipToBounds()) {
            Row(Modifier.requiredWidth(68.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(12.dp))
                GoogleButton(onGoogle)
            }
        }
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val p = Path().apply {
                moveTo(size.width * 0.625f, size.height * 0.208f)
                lineTo(size.width * 0.333f, size.height * 0.5f)
                lineTo(size.width * 0.625f, size.height * 0.792f)
            }
            drawPath(
                p, LockInColors.Snow,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Text field
// ---------------------------------------------------------------------------------------------

/**
 * A labelled text field. The ring slides from grey to white on focus and to red on error, the field
 * shakes when [shakeSignal] goes up while it has an error, the error text slides open, and a tick
 * pops in when [valid].
 */
@Composable
fun LockInField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isPassword: Boolean = false,
    error: String? = null,
    invalid: Boolean = false,
    valid: Boolean = false,
    shakeSignal: Int = 0,
    autofillTypes: List<AutofillType> = emptyList(),
    autoFocus: Boolean = false,
    onBlur: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // If the keyboard is already up (moving between sign-up steps), hand focus to this field at once.
    // Waiting lets the old field leave first, which makes the keyboard drop and pop back up.
    val keyboardWasUp = LocalKeyboardUp.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // From a closed keyboard, wait for the sheet to finish rising before bringing it up.
            kotlinx.coroutines.delay(if (keyboardWasUp) 30L else 480L)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val hasError = error != null || invalid
    val shape = ControlShape

    val ring by animateColorAsState(
        when {
            hasError -> LockInColors.Flare
            focused -> LockInColors.Lime
            else -> LockInColors.Rim
        },
        tween(220),
        label = "ring",
    )
    val ringWidth by animateDpAsState(if (focused || hasError) 2.dp else 1.5.dp, tween(180), label = "ring-width")
    val labelColor by animateColorAsState(
        if (focused) LockInColors.Snow else LockInColors.Body,
        tween(200),
        label = "label",
    )
    val strike by animateFloatAsState(if (revealed) 1f else 0f, tween(220, easing = UiGlide), label = "eye")

    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeSignal) {
        if (shakeSignal > 0 && hasError) {
            for (v in floatArrayOf(-10f, 8f, -6f, 4f, 0f)) shake.animateTo(v, tween(55, easing = LinearEasing))
        }
    }

    val lastError = remember { mutableStateOf("") }
    if (error != null) lastError.value = error

    Column(modifier.fillMaxWidth().graphicsLayer { translationX = shake.value.dp.toPx() }) {
        if (label.isNotEmpty()) {
            Text(label, style = LockInText.Label.copy(color = labelColor))
            Spacer(Modifier.height(8.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(shape)
                .background(LockInColors.Ink)
                .border(ringWidth, ring, shape)
                .padding(start = 22.dp, end = if (isPassword) 8.dp else 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LockInText.Input,
                cursorBrush = SolidColor(LockInColors.Lime),
                visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { onDone() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (isPassword) 50.dp else 30.dp)
                    .focusRequester(focusRequester)
                    .then(if (autofillTypes.isEmpty()) Modifier else Modifier.autofill(autofillTypes, onValueChange))
                    .onFocusChanged { st ->
                        if (focused && !st.isFocused) onBlur()
                        focused = st.isFocused
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = LockInText.Input.copy(color = LockInColors.Mist))
                        inner()
                    }
                },
            )
            if (isPassword) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { revealed = !revealed }
                        .semantics { contentDescription = if (revealed) "Hide password" else "Show password" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(22.dp)) { drawEye(strike) }
                }
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = valid && !hasError,
                    enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f)),
                    exit = scaleOut(tween(120)),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    CheckMark(color = LockInColors.Lime, boxSize = 18.dp)
                }
            }
        }
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically(tween(260, easing = UiGlide)),
            exit = shrinkVertically(tween(200, easing = UiGlide)),
        ) {
            Text(lastError.value, style = LockInText.Error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The eye draws its strike-through as [strike] goes from 0 to 1. */
private fun DrawScope.drawEye(strike: Float) {
    val k = size.width / 24f
    val eye = Path().apply {
        moveTo(2f * k, 12f * k)
        cubicTo(2f * k, 12f * k, 5.5f * k, 5.5f * k, 12f * k, 5.5f * k)
        cubicTo(18.5f * k, 5.5f * k, 22f * k, 12f * k, 22f * k, 12f * k)
        cubicTo(22f * k, 12f * k, 18.5f * k, 18.5f * k, 12f * k, 18.5f * k)
        cubicTo(5.5f * k, 18.5f * k, 2f * k, 12f * k, 2f * k, 12f * k)
        close()
    }
    val style = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(eye, LockInColors.Body, style = style)
    drawCircle(LockInColors.Body, radius = 3f * k, center = Offset(12f * k, 12f * k), style = style)
    if (strike > 0f) {
        val end = 4f + 16f * strike
        drawLine(
            LockInColors.Body, Offset(4f * k, 4f * k), Offset(end * k, end * k),
            strokeWidth = 2f * k, cap = StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Password strength
// ---------------------------------------------------------------------------------------------

private fun hasDigit(s: String) = s.any { it.isDigit() }
private fun hasMixedCase(s: String) = s.any { it.isUpperCase() } && s.any { it.isLowerCase() }
private fun hasSymbol(s: String) = s.any { !it.isLetterOrDigit() }

private fun passwordScore(p: String): Int {
    if (p.length < 8) return 0
    var score = 1
    if (hasDigit(p)) score++
    if (score == 2 && hasMixedCase(p)) score++
    if (score == 3 && (hasSymbol(p) || p.length >= 12)) score++
    return score
}

private fun passwordHint(p: String): String = when {
    p.length < 8 -> "Use at least 8 characters"
    !hasDigit(p) -> "Add a number"
    !hasMixedCase(p) -> "Mix upper and lower case"
    !(hasSymbol(p) || p.length >= 12) -> "Add a symbol or make it longer"
    else -> "Great password"
}

/** Four segments that fill as the password gets stronger, with one hint for the next improvement. */
@Composable
fun PasswordMeter(password: String, modifier: Modifier = Modifier) {
    val score = passwordScore(password)
    val tone = when (score) {
        0, 1 -> LockInColors.Flare
        2 -> LockInColors.Silver
        3 -> LockInColors.Frost
        else -> LockInColors.Lime
    }
    AnimatedVisibility(
        visible = password.isNotEmpty(),
        enter = expandVertically(tween(260, easing = UiGlide)),
        exit = shrinkVertically(tween(200, easing = UiGlide)),
        modifier = modifier,
    ) {
        Column(Modifier.padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 4) {
                    val fill by animateFloatAsState(if (score > i) 1f else 0f, tween(300, easing = UiGlide), label = "segment")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LockInColors.Graphite),
                    ) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(tone))
                    }
                }
            }
            Text(
                passwordHint(password),
                style = LockInText.Small.copy(color = if (score == 0) LockInColors.Mist else tone),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** A gentle up-and-down drift for an icon that is waiting on something. */
@Composable
fun rememberFloat(amplitude: Dp = 4.dp): State<Float> {
    val transition = rememberInfiniteTransition(label = "float")
    val v = transition.calmFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float-value",
    )
    return v
}

// ---------------------------------------------------------------------------------------------
// One-time code, switch, fingerprint
// ---------------------------------------------------------------------------------------------

/**
 * Six boxes for a one-time code. One hidden text field catches the typing (and pasting), the boxes
 * just show it. Each digit pops in, the active box has a bright ring, and the row shakes on [shakeSignal].
 */
@Composable
fun OtpField(
    value: String,
    onValueChange: (String) -> Unit,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    hasError: Boolean = false,
    shakeSignal: Int = 0,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeSignal) {
        if (shakeSignal > 0) {
            for (v in floatArrayOf(-12f, 10f, -7f, 4f, 0f)) shake.animateTo(v, tween(55, easing = LinearEasing))
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            onValueChange(digits)
            if (digits.length == 6) onComplete(digits)
        },
        singleLine = true,
        textStyle = LockInText.Input.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "6-digit code" },
        decorationBox = { innerTextField ->
            Box {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 6) {
                    val filled = i < value.length
                    val active = focused && i == minOf(value.length, 5)
                    val ring by animateColorAsState(
                        when {
                            hasError -> LockInColors.Flare
                            active -> LockInColors.Snow
                            else -> LockInColors.Rim
                        },
                        tween(180),
                        label = "otp-ring",
                    )
                    val pop by animateFloatAsState(
                        if (filled) 1f else 0.4f,
                        spring(dampingRatio = 0.5f, stiffness = 600f),
                        label = "otp-pop",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(ControlShape)
                            .background(LockInColors.Ink)
                            .border(if (active || hasError) 2.dp else 1.5.dp, ring, ControlShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (filled) {
                            Text(
                                value[i].toString(),
                                style = LockInText.Title.copy(fontSize = 24.sp),
                                modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
                            )
                        }
                    }
                }
            }
            // The real (invisible) field has to sit on top of the boxes. If it is never placed, Android
            // anchors the "Paste" chip and the cursor handle at the top-left corner of the screen.
            Box(Modifier.matchParentSize()) { innerTextField() }
            }
        },
    )
}

/** A label with an on/off switch that slides. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val thumb by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "thumb",
    )
    val track by animateColorAsState(if (checked) LockInColors.Lime else LockInColors.Rim, tween(200), label = "track")
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(track)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .offset(x = thumb)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) LockInColors.OnLime else LockInColors.Mist),
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(label, style = LockInText.Sub)
    }
}

/** A fingerprint drawn from arcs, for the sign-in-faster prompt and the returning-user button. */
@Composable
fun FingerprintIcon(color: Color, boxSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(boxSize)) {
        val k = this.size.width / 24f
        val style = Stroke(width = 1.6f * k, cap = StrokeCap.Round)
        fun arc(r: Float, start: Float, sweep: Float) {
            drawArc(
                color, start, sweep, false,
                topLeft = Offset((12f - r) * k, (12f - r) * k),
                size = Size(2f * r * k, 2f * r * k),
                style = style,
            )
        }
        arc(9f, 200f, 140f)
        arc(6.5f, 190f, 230f)
        arc(4f, 170f, 250f)
        arc(1.6f, 0f, 360f)
        drawLine(color, Offset(12f * k, 13.5f * k), Offset(12f * k, 18f * k), strokeWidth = 1.6f * k, cap = StrokeCap.Round)
        drawLine(color, Offset(7.5f * k, 14f * k), Offset(7.5f * k, 19f * k), strokeWidth = 1.6f * k, cap = StrokeCap.Round)
        drawLine(color, Offset(16.5f * k, 12.5f * k), Offset(16.5f * k, 17f * k), strokeWidth = 1.6f * k, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------------------------
// Screen scaffolding
// ---------------------------------------------------------------------------------------------

/** Topographic lines fading into the ground colour, behind the top of a screen. */
@Composable
fun ContourBackdrop(modifier: Modifier = Modifier) {
    val field = remember { ContourField() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { field.draw(this, 10f, size.width, size.height) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, LockInColors.Ink))),
        )
    }
}

@Composable
fun AuthScaffold(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        ContourBackdrop(Modifier.fillMaxWidth().height(320.dp))
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .readable(Widths.Sheet)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        ) {
            if (onBack != null) BackButton(onBack)
            content()
        }
    }
}
