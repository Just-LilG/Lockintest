package com.godbless.lockin.home

import android.app.Activity
import com.godbless.lockin.ui.handoffSwap
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.godbless.lockin.study.Tool
import com.godbless.lockin.study.ToolDock
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Hides the status and navigation bars while a lock session is open, and brings them back after. */
@Composable
internal fun ImmersiveWhile(active: Boolean) {
    val view = LocalView.current
    LaunchedEffect(active) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (active) {
            // Wait for the screen to finish sliding up, so nothing underneath jumps.
            delay(520)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.context.findActivity()?.window
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** The lock you set on your partner while your own session runs beside it ("Lock together"). */
internal class TogetherLock(
    val message: String,
    val total: Int,
    val remaining: Int,
    val strict: Boolean,
    /** How many times they picked their phone up during the lock. */
    val pickups: Int = 0,
    /** When the lock opens, on this phone's clock (milliseconds). */
    val endsAt: Long = 0L,
)

private val ClockFmt: java.text.SimpleDateFormat get() = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
private fun clockTime(ms: Long): String = ClockFmt.format(java.util.Date(ms))

/** Sizes for the big timer: the roomy one, and the one for short screens or large system fonts. */
private class Digits(val width: Dp, val height: Dp, val style: TextStyle)
private val Roomy get() = Digits(48.dp, 88.dp, TimerStyle)
private val Tight get() = Digits(37.dp, 68.dp, TimerStyle.copy(fontSize = 58.sp, lineHeight = 66.sp))

private const val Countdown = 0
private const val Climbing = 1
private const val Finished = 2

internal fun duration(seconds: Int): String = if (seconds < 60) "${seconds}s" else formatMinutes(seconds / 60)

/**
 * The screen you are on while the phone is locked. It opens with a three-second count while the two of
 * you rope up, then the rope pulls tight and a timer runs over the mountain. It can only be ended by
 * holding the button down, and it finishes on a short summary.
 *
 * [elapsed] counts up from the moment [running] turns true, and is kept after the session ends so the
 * summary can show it.
 */
@Composable
internal fun LockScreen(
    elapsed: Int,
    running: Boolean,
    pickups: Int,
    onRunning: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpenTool: (Tool) -> Unit,
    /** Set while this session runs beside a lock you put on your partner: the screen then shows that lock. */
    together: TogetherLock? = null,
    onMessage: () -> Unit = {},
    /** Cards due in Quiz, shown on its tile. */
    quizDue: Int = 0,
    /** New messages from your partner since this lock began. */
    unread: Int = 0,
    /** Your partner's own name, shown when what you call them is only emoji. */
    partnerRealName: String = "",
    /** A tool, the chat or a page is open over this screen (so the screen isn't idle even if it isn't touched). */
    busyElsewhere: Boolean = false,
    /** You left a lock together before it ended: tell your partner. */
    onLeftTogether: () -> Unit = {},
    /** Send a high five after a lock together. */
    onHighFive: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    var stage by rememberSaveable { mutableStateOf(if (running) Climbing else if (elapsed > 0) Finished else Countdown) }
    var count by rememberSaveable { mutableStateOf(3) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var highFived by rememberSaveable { mutableStateOf(false) }

    // The lock together as last seen: a missed refresh must not swap the screen for a plain timer, and the
    // summary still knows it was a lock together after it ended.
    var snap by remember { mutableStateOf<TogetherLock?>(null) }
    LaunchedEffect(together) { if (together != null) snap = together }
    val shownTogether = together ?: snap

    LaunchedEffect(stage) {
        if (stage == Countdown) {
            while (count > 0) {
                delay(1000)
                count -= 1
                if (count > 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            stage = Climbing
            onRunning()
        }
    }

    // The session was ended from outside (your partner's lock ran out, or they lifted it): go to the summary
    // instead of sitting on a frozen timer, where ending it again would be saved twice.
    LaunchedEffect(running) { if (!running && stage == Climbing) stage = Finished }

    // The rope hangs slack while you count down, then snaps tight with a small bounce.
    val taut = remember { Animatable(0f) }
    LaunchedEffect(stage) { taut.animateTo(if (stage == Countdown) 0f else 1f, spring(dampingRatio = 0.4f, stiffness = 140f)) }

    // After a minute with no touch, the screen goes very dim (a long lock shouldn't drain the battery or burn the
    // screen); any touch brings it back.
    val activity = LocalContext.current.findActivity()
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    fun brightness(v: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.also { it.screenBrightness = v }
    }
    val busy by rememberUpdatedState(busyElsewhere)
    LaunchedEffect(stage) {
        if (stage != Climbing) return@LaunchedEffect
        while (true) {
            delay(4000)
            if (busy) lastTouch = System.currentTimeMillis()
            val idle = System.currentTimeMillis() - lastTouch > 60_000L
            if (idle && !dimmed) { brightness(0.03f); dimmed = true }
            if (!idle && dimmed) { brightness(-1f); dimmed = false }
        }
    }
    DisposableEffect(Unit) { onDispose { brightness(-1f) } }

    val stats = LocalStats.current
    // A session that has ended is already part of today's total, so only a running one is added on top.
    val doneSeconds = stats.todaySeconds + if (running) elapsed else 0
    val partnerSeconds = stats.partnerTodaySeconds
    val cutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val topInset = maxOf(44.dp, cutout + 12.dp)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            // Watch every touch (without taking it) so the dimmed screen wakes up.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = System.currentTimeMillis()
                        if (dimmed) { brightness(-1f); dimmed = false }
                    }
                }
            }
            // Swallow touches so nothing on the pages underneath can be reached.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // A short screen or a big system font: smaller timer, a shorter mountain, and the page scrolls.
        val compact = maxHeight < 700.dp || LocalDensity.current.fontScale > 1.2f
        val digits = if (compact) Tight else Roomy

        val top: @Composable () -> Unit = {
            AnimatedContent(
                targetState = stage,
                modifier = Modifier.fillMaxWidth().padding(top = topInset).clipToBounds(),
                transitionSpec = { handoffSwap(ms = 380) },
                label = "lock-top",
            ) { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    when (s) {
                        Countdown -> CountdownTop(count)
                        Climbing -> if (shownTogether != null) TogetherTop(shownTogether, together != null, elapsed, pickups, partnerRealName, digits) else TimerTop(elapsed, partnerSeconds, digits)
                        else -> SummaryTop(elapsed, shownTogether)
                    }
                }
            }
        }
        val scene: @Composable (Modifier) -> Unit = { m ->
            ClimbScene(
                doneSeconds = doneSeconds,
                partnerSeconds = partnerSeconds,
                taut = { taut.value },
                ground = LockInColors.Ink,
                // A soft edge at the top, so the contour lines don't start on a hard line under the timer.
                modifier = m.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to LockInColors.Ink, 1f to Color.Transparent, startY = 0f, endY = 110.dp.toPx()))
                },
                frameMillis = 50L,
                youActive = true,
                partnerActive = shownTogether != null,
                slips = pickups,
                partnerSlips = shownTogether?.pickups ?: 0,
            )
        }
        val bottom: @Composable () -> Unit = {
            AnimatedContent(
                targetState = stage,
                modifier = Modifier.fillMaxWidth(),
                // The old controls drop away and the new ones rise from below, one after the other.
                transitionSpec = { handoffSwap(dirIn = 1, dirOut = 1, ms = 380) },
                label = "lock-bottom",
            ) { s ->
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
                    when (s) {
                        Countdown -> LockInButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline)
                        Climbing -> {
                            ToolDock(onOpen = onOpenTool, badges = if (quizDue > 0) mapOf(Tool.Quiz to "$quizDue due") else emptyMap())
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when {
                                    shownTogether == null -> "The rope is tight. Stay off your phone."
                                    shownTogether.pickups == 0 -> "${LocalPartnerName.current} hasn't picked up their phone."
                                    shownTogether.pickups == 1 -> "${LocalPartnerName.current} picked up their phone once."
                                    else -> "${LocalPartnerName.current} picked up their phone ${shownTogether.pickups} times."
                                },
                                style = LockInText.Small.copy(color = LockInColors.Body),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            if (shownTogether != null) {
                                LockInButton(if (unread > 0) "Message · $unread new" else "Message", onClick = onMessage, kind = ButtonKind.Outline)
                                Spacer(Modifier.height(10.dp))
                            }
                            HoldToEnd(
                                onEnd = { if (shownTogether != null) confirmLeave = true else { onEnd(); stage = Finished } },
                                label = if (shownTogether != null) "Hold to leave early" else "Hold to end session",
                                compact = true,
                            )
                        }
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SummaryStat("Locked", duration(elapsed), Modifier.weight(1f))
                                SummaryStat("Pickups", pickups.toString(), Modifier.weight(1f))
                                SummaryStat("Today", formatMinutes(doneSeconds / 60), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(14.dp))
                            if (shownTogether != null) {
                                LockInButton(
                                    if (highFived) "High five sent" else "Send ${LocalPartnerName.current} a high five",
                                    onClick = { if (!highFived) { highFived = true; onHighFive() } },
                                    kind = ButtonKind.Outline,
                                    enabled = !highFived,
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            LockInButton("Done", onClick = onClose)
                        }
                    }
                }
            }
        }

        if (compact) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                top()
                scene(Modifier.fillMaxWidth().height(240.dp))
                bottom()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                top()
                scene(Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp))
                bottom()
            }
        }

        // Leaving a lock together early: your partner stays locked, and they'll see that you left.
        AnimatedVisibility(
            visible = confirmLeave,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
        ) {
            val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(sheet)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, sheet)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
            ) {
                val name = LocalPartnerName.current
                Text("Leave early?", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "$name stays locked" + (shownTogether?.endsAt?.takeIf { it > 0L }?.let { " until ${clockTime(it)}" } ?: "") +
                        ". They'll see that you left, and this session will be saved as ${duration(elapsed)}.",
                    style = LockInText.Small.copy(color = LockInColors.Body),
                )
                Spacer(Modifier.height(18.dp))
                LockInButton("Stay locked", onClick = { confirmLeave = false })
                Spacer(Modifier.height(10.dp))
                LockInButton("Leave early", onClick = {
                    confirmLeave = false
                    onEnd()
                    onLeftTogether()
                    stage = Finished
                }, kind = ButtonKind.Outline)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top of the screen, one per stage
// ---------------------------------------------------------------------------------------------

internal val TimerStyle: TextStyle get() = TextStyle(
    fontFamily = com.godbless.lockin.ui.Fonts.Display,
    fontSize = 76.sp,
    lineHeight = 84.sp,
    letterSpacing = (-0.4f).sp,
    color = LockInColors.Snow,
)

@Composable
private fun CountdownTop(count: Int) {
    Text("Roping up with ${LocalPartnerName.current}", style = LockInText.Label.copy(color = LockInColors.Silver))
    Spacer(Modifier.height(6.dp))
    RollingChar(
        c = if (count > 0) ('0' + count) else '0',
        width = 100.dp,
        height = 140.dp,
        style = TimerStyle.copy(fontSize = 128.sp, lineHeight = 136.sp),
    )
    Text("Your phone locks when the rope pulls tight.", style = LockInText.Sub.copy(color = LockInColors.Body))
}

@Composable
private fun TimerTop(elapsed: Int, partnerSeconds: Int, digits: Digits) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(LocalPartnerName.current, boxSize = 42.dp, filled = true, ring = LockInColors.Ink)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                "${LocalPartnerName.current} is locked in",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text("${formatMinutes(partnerSeconds / 60)} today", style = LockInText.Small.copy(color = LockInColors.Mist))
        }
    }
    Spacer(Modifier.height(22.dp))
    Text("Locked session", style = LockInText.Label.copy(color = LockInColors.Silver))
    // Every character sits in a fixed-width slot and rolls up when it changes, so the timer never jiggles.
    Row {
        formatClock(elapsed).forEach { ch ->
            RollingChar(ch, width = digits.width, height = digits.height, style = digits.style)
        }
    }
    val left = LocalGoalMinutes.current * 60 - (LocalStats.current.todaySeconds + elapsed)
    Text(
        if (left > 0) "${formatMinutes((left + 59) / 60)} to your daily goal." else "Goal reached. Keep climbing.",
        style = LockInText.Sub.copy(color = LockInColors.Body),
    )
}

/**
 * The top of the screen while you and your partner are locked together: who and until when, the message you wrote
 * for them (if you wrote one), and the time until it opens with a bar that fills as it runs. Your own session
 * still counts up underneath, for your history. [fresh] is false when the lock hasn't been refreshed lately.
 */
@Composable
private fun TogetherTop(t: TogetherLock, fresh: Boolean, elapsed: Int, myPickups: Int, realName: String, digits: Digits) {
    val name = LocalPartnerName.current
    val haptic = LocalHapticFeedback.current
    // The time left follows the clock the lock ends on (this runs again every second as [elapsed] ticks), so a
    // refresh that arrives late doesn't freeze it.
    val left = if (t.endsAt > 0L) ((t.endsAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0) else t.remaining.coerceAtLeast(0)
    LaunchedEffect(left) {
        if (left in 1..10) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (left == 0 && t.total > 0 && elapsed > 3) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, boxSize = 42.dp, filled = true, ring = LockInColors.Ink)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                "Locked together with $name",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // When what you call them is only an emoji, their real name is here.
            val sub = listOfNotNull(
                realName.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
                "For ${formatMinutes(t.total / 60)}",
                t.endsAt.takeIf { it > 0L }?.let { "until ${clockTime(it)}" },
            ).joinToString(" · ")
            Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // A live chip instead of a second "Locked" label: a small dot breathes while you are both in.
        val beat by androidx.compose.animation.core.rememberInfiniteTransition(label = "beat").animateFloat(
            1f, 1.5f,
            androidx.compose.animation.core.infiniteRepeatable(tween(1100, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "dot",
        )
        Row(
            Modifier.height(32.dp).clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).graphicsLayer { scaleX = if (fresh) beat else 1f; scaleY = if (fresh) beat else 1f }.clip(CircleShape).background(LockInColors.OnLime))
            Text("Both locked", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (t.message.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text("Your message to $name", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        // The message as they see it. Long ones are cut at three lines; tap to read all of it.
        var open by remember(t.message) { mutableStateOf(false) }
        val bubble = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(bubble)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, bubble)
                .clickable(role = Role.Button, onClickLabel = if (open) "Show less" else "Show the whole message") { open = !open }
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                t.message,
                style = LockInText.Sub.copy(color = LockInColors.Snow, fontSize = 16.sp, lineHeight = 22.sp),
                maxLines = if (open) 12 else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(if (left == 0) "Opening" else "Opens in", style = LockInText.Label.copy(color = LockInColors.Silver))
    // Lime for the last minute.
    val style = if (left in 1..60) digits.style.copy(color = LockInColors.Lime) else digits.style
    Row {
        formatClock(left).forEach { ch -> RollingChar(ch, width = digits.width, height = digits.height, style = style) }
    }
    // How far through it is.
    val progress by animateFloatAsState(
        if (t.total > 0) (1f - left / t.total.toFloat()).coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = 0.9f, stiffness = 120f),
        label = "lock-progress",
    )
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(LockInColors.Graphite)) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(LockInColors.Lime))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "${if (myPickups == 0) "You haven't picked up your phone" else if (myPickups == 1) "You picked up your phone once" else "You picked up your phone $myPickups times"}.",
        style = LockInText.Small.copy(color = LockInColors.Mist),
    )
}

@Composable
private fun SummaryTop(elapsed: Int, together: TogetherLock?) {
    Text("Session complete", style = LockInText.Label.copy(color = LockInColors.Silver))
    Spacer(Modifier.height(4.dp))
    Text("Nice climb.", style = LockInText.Headline.copy(fontSize = 44.sp, lineHeight = 50.sp))
    Spacer(Modifier.height(8.dp))
    val left = LocalGoalMinutes.current * 60 - LocalStats.current.todaySeconds
    val name = LocalPartnerName.current
    val them = when {
        together == null -> "$name was with you."
        together.pickups == 0 -> "$name held on too, without picking up once."
        together.pickups == 1 -> "$name held on too, with 1 pickup."
        else -> "$name held on too, with ${together.pickups} pickups."
    }
    Text(
        "You locked in for ${duration(elapsed)}. $them " +
            if (left > 0) "${formatMinutes((left + 59) / 60)} to go on today's goal." else "You made the summit.",
        style = LockInText.Sub.copy(color = LockInColors.Body),
    )
}

/** One character that rolls up out of its slot when it changes. [width] is shrunk for a colon. */
@Composable
internal fun RollingChar(c: Char, width: Dp, height: Dp, style: TextStyle) {
    AnimatedContent(
        targetState = c,
        modifier = Modifier.width(if (c == ':') width * 0.46f else width).height(height).clipToBounds(),
        transitionSpec = {
            slideInVertically(tween(300, easing = UiGlide)) { it } togetherWith
                slideOutVertically(tween(300, easing = UiGlide)) { -it }
        },
        label = "char",
    ) { ch ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(ch.toString(), style = style)
        }
    }
}

@Composable
internal fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
    }
}

// ---------------------------------------------------------------------------------------------
// Hold to end
// ---------------------------------------------------------------------------------------------

/**
 * Ending early takes a deliberate hold: a fill sweeps across, and letting go before it is full undoes it.
 * [compact] is the quiet text-sized version for things you should not reach for by accident.
 */
@Composable
internal fun HoldToEnd(
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Hold to end session",
    holdingLabel: String = "Keep holding",
    millis: Int = 2200,
    compact: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val holding by remember { derivedStateOf { fill.value > 0.02f } }
    // A small tick at each quarter (each second on a long hold), so you can feel it filling.
    val steps = if (millis >= 4000) millis / 1000 else 4
    LaunchedEffect(Unit) {
        var last = 0
        androidx.compose.runtime.snapshotFlow { (fill.value * steps).toInt() }.collect { q ->
            if (q in 1 until steps && q > last) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            last = q
        }
    }
    // On a long hold the label counts the seconds still to go.
    val secsLeft = kotlin.math.ceil((1f - fill.value) * millis / 1000f).toInt().coerceAtLeast(1)
    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else 56.dp)
            .clip(ControlShape)
            .border(1.5.dp, if (compact) LockInColors.Graphite else LockInColors.Rim, ControlShape)
            .pointerInput(millis) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            fill.animateTo(1f, tween(millis, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnd()
                            // Ready for the next hold (leaving a lock together asks first, and may be answered "stay").
                            fill.animateTo(0f, tween(260, easing = UiGlide))
                        }
                        tryAwaitRelease()
                        if (fill.value < 1f) {
                            job.cancel()
                            // Let go early and the fill springs back, with a little overshoot.
                            scope.launch { fill.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 260f)) }
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                // A screen reader can't hold a press: its double tap does the same thing.
                onClick(label = label) { onEnd(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(LockInColors.Graphite, size = Size(size.width * fill.value, size.height)) },
        )
        Text(
            if (holding) (if (millis >= 4000) "$holdingLabel · $secsLeft" else holdingLabel) else label,
            style = if (compact) {
                LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp)
            } else {
                LockInText.Button.copy(color = LockInColors.Snow)
            },
        )
    }
}
