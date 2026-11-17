package com.godbless.lockin.home

import kotlinx.coroutines.flow.first
import com.godbless.lockin.ui.calmFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.R
import com.godbless.lockin.intro.BASELINE
import com.godbless.lockin.intro.ClimberShapes
import com.godbless.lockin.intro.ClimberSpec
import com.godbless.lockin.intro.ContourField
import com.godbless.lockin.intro.FlagMesh
import com.godbless.lockin.intro.Rig
import com.godbless.lockin.intro.SCENE_W
import com.godbless.lockin.intro.SCENE_X
import com.godbless.lockin.intro.SCENE_Y
import com.godbless.lockin.intro.drawClimber
import com.godbless.lockin.intro.drawMountain
import com.godbless.lockin.intro.drawRope
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide

/**
 * Today's climb. The mountain scene is the progress bar: you and your partner climb toward the flag
 * as the day's focus adds up, and starting a session pulls the rope between you taut.
 */
@Composable
internal fun AlpineHero(
    locked: Boolean,
    elapsed: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** With a partner: their lock status and the way into locking them, and tapping their climber nudges them. */
    lock: LockState? = null,
    onOpenLock: () -> Unit = {},
    onNudge: (() -> Unit)? = null,
    /** Tapping today's goal opens where it is set. */
    onGoal: (() -> Unit)? = null,
    /** The page's scroll in pixels: the scene drifts a little slower than the card, for depth. */
    parallax: () -> Float = { 0f },
    /** Height of the mountain. On a big screen Home passes more, so the card fills the screen. */
    sceneHeight: androidx.compose.ui.unit.Dp = 290.dp,
    /** A full page covers Home: the scene and the Start sheen stop animating. */
    covered: Boolean = false,
) {
    val stats = LocalStats.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // A nudge plays once per tap (about 1.6 s) and can't be stacked while it plays.
    val nudge = remember { Animatable(0f) }
    // When you last nudged, so the caption can say so and a second tap right after is ignored.
    var lastNudge by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableLongStateOf(0L) }
    val clockNow by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val hourNow = remember(clockNow) { java.time.LocalTime.now().hour }
    val tapPartner: (() -> Unit)? = onNudge?.let { send ->
        {
            if (!nudge.isRunning && System.currentTimeMillis() - lastNudge > 20_000L) {
                lastNudge = System.currentTimeMillis()
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                send()
                scope.launch {
                    nudge.snapTo(0f)
                    launch {
                        kotlinx.coroutines.delay(640)
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    nudge.animateTo(1f, tween(1600, easing = androidx.compose.animation.core.LinearEasing))
                    nudge.snapTo(0f)
                }
            }
        }
    }
    // Today's total already holds every finished session; the one in progress is added while it runs.
    val doneSeconds = stats.todaySeconds + if (locked) elapsed else 0
    val partnerSeconds = stats.partnerTodaySeconds

    // The rope sags while you are free and snaps tight with a small bounce when the phone locks.
    val taut = remember { Animatable(0f) }
    LaunchedEffect(locked) { taut.animateTo(if (locked) 1f else 0f, spring(dampingRatio = 0.4f, stiffness = 140f)) }

    // Today's minutes count up the first time Home opens in this run of the app, and glide when they change. Coming
    // back from another tab shows the number already there (it used to count up from 0 on every visit, which looked
    // like your progress had reset).
    val counted = remember { Animatable(HomeIntro.countedMinutes ?: 0f) }
    var firstCount by remember { androidx.compose.runtime.mutableStateOf(HomeIntro.countedMinutes == null) }
    LaunchedEffect(doneSeconds / 60) {
        val target = (doneSeconds / 60).toFloat()
        if (firstCount) {
            // Nothing to count to yet (today's total may still be loading): wait for a real number.
            if (target <= 0f) return@LaunchedEffect
            HomeIntro.countedMinutes = 0f
            firstCount = false
            // The count starts when the number does: not while the welcome page still covers Home, and not during the
            // move, when the number is still tucked away (it shows once the move has landed).
            com.godbless.lockin.ui.EntranceGate.open()
            androidx.compose.runtime.snapshotFlow { SceneHandoff.chromeShown }.first { it }
            kotlinx.coroutines.delay(350)
            counted.animateTo(target, tween(1300, easing = UiGlide))
        } else if (counted.value != target) {
            counted.animateTo(target, tween(600, easing = UiGlide))
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { counted.value }.collect { if (!firstCount) HomeIntro.countedMinutes = it }
    }
    // The chip and the captions follow the number while it counts, so "Goal reached" never sits beside "0 min".
    val shownSeconds = if (locked || kotlin.math.abs(counted.value - (doneSeconds / 60)) < 1f) doneSeconds else (counted.value * 60f).toInt()

    val goalSeconds = LocalGoalMinutes.current * 60
    val partnerGoalSeconds = LocalPartnerGoalMinutes.current * 60
    // Where the welcome page puts the mountain, and what it needs to know to land there.
    SideEffect {
        SceneHandoff.reachYou = reach(doneSeconds, goalSeconds)
        SceneHandoff.reachPartner = reach(partnerSeconds, partnerGoalSeconds)
    }
    val density = LocalDensity.current
    SideEffect {
        SceneHandoff.corner = with(density) { 28.dp.toPx() }
        SceneHandoff.border = with(density) { 1.dp.toPx() }
    }
    // The number, the chip and the name tags arrive just after the scene has landed.
    val shown = SceneHandoff.chromeShown
    val reveal = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(shown) { reveal.animateTo(if (shown) 1f else 0f, tween(if (shown) 520 else 240, easing = UiGlide)) }

    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(sceneHeight)
                .clipToBounds()
                .onGloballyPositioned { SceneHandoff.box = it.boundsInRoot() },
        ) {
            ClimbScene(
                doneSeconds = doneSeconds,
                partnerSeconds = partnerSeconds,
                taut = { taut.value },
                ground = LockInColors.Slate,
                // While the welcome page is still drawing the mountain, this copy stays out of sight.
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (SceneHandoff.sceneHidden) 0f else 1f
                        translationY = parallax() * 0.22f
                    },
                tagScale = { reveal.value },
                settleOnReturn = true,
                nudge = { nudge.value },
                onPartnerTap = tapPartner,
                paused = covered,
                youActive = locked,
                // Your partner is climbing for real while a lock you set on them runs.
                partnerActive = lock?.theirs != null && clockNow.let { it >= lock.theirsStartsAt && it < lock.theirsEndsAt },
                partnerSlips = lock?.theirs?.pickups ?: 0,
            )

            // A soft patch of card colour behind the number, so stars and clouds never sit under the text.
            // (An oval that has fully faded by the box's edges. It used to be a circle wider than the box was tall, so
            // it was cut off by the bottom of its own box: a hard line across the sky, most visible on a warm dawn.)
            Canvas(Modifier.align(Alignment.TopStart).size(width = 250.dp, height = 170.dp)) {
                val r = size.height
                withTransform({ scale(size.width / size.height, 1f, Offset.Zero) }) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to LockInColors.Slate.copy(alpha = 0.78f),
                            0.55f to LockInColors.Slate.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            center = Offset(0f, 0f),
                            radius = r,
                        ),
                        radius = r,
                        center = Offset(0f, 0f),
                    )
                }
            }

            // Big number, top left. It slides to the session timer while the phone is locked.
            AnimatedContent(
                targetState = locked,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 18.dp)
                    .height(92.dp)
                    .clipToBounds()
                    .graphicsLayer { translationY = -(1f - reveal.value) * 120.dp.toPx() },
                transitionSpec = {
                    // One full height each way inside a clipped window, so the two numbers never overlap.
                    (slideInVertically(tween(360, easing = UiGlide)) { it } togetherWith
                        slideOutVertically(tween(360, easing = UiGlide)) { -it })
                        .using(null)
                },
                label = "hero-number",
            ) { isLocked ->
                Column(Modifier.widthIn(min = 150.dp, max = 300.dp)) {
                    Text(
                        if (isLocked) "Locked session" else "Today's climb",
                        style = LockInText.Label.copy(color = LockInColors.Silver),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isLocked) formatClock(elapsed) else heroMinutes(counted.value.toInt()),
                        style = LockInText.Headline.copy(fontSize = 40.sp, lineHeight = 46.sp),
                    )
                    Text(
                        if (isLocked) "Phone locked with ${LocalPartnerName.current}" else "of your ${formatMinutes(LocalGoalMinutes.current)} goal",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = if (!isLocked && onGoal != null) {
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onGoal)
                        } else Modifier,
                    )
                }
            }

            val goalPct = if (goalSeconds > 0) (shownSeconds * 100L / goalSeconds).toInt() else 0
            val partnerFirst = LocalPartnerName.current.trim().substringBefore(' ').let { if (it.length > 10) it.take(9) + "…" else it }
            val chipText = when {
                locked -> "Locked"
                goalSeconds > 0 && shownSeconds >= goalSeconds -> "Goal reached"
                shownSeconds > 0 -> "$goalPct% of goal"
                lock != null && partnerSeconds > 0 -> "$partnerFirst · ${formatMinutes(partnerSeconds / 60)} in"
                else -> "Ready"
            }
            StatusChip(
                locked,
                chipText,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp)
                    .graphicsLayer { translationY = -(1f - reveal.value) * 120.dp.toPx() },
            )
        }

        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val left = (goalSeconds - shownSeconds) / 60
                Text(
                    when {
                        locked -> "The rope is tight. Stay off your phone."
                        goalSeconds > 0 && left <= 0 -> "Goal reached. You made the summit."
                        shownSeconds == 0 && stats.restToday -> "Rest day. Your streak is safe."
                        shownSeconds == 0 && hourNow < 5 -> "Sleep is part of the climb."
                        !stats.todayCounted && stats.streak > 0 && shownSeconds == 0 ->
                            "Lock in today to keep your ${stats.streak}-day streak"
                        shownSeconds == 0 -> "Your first climb of the day starts here"
                        else -> "$left min to go"
                    },
                    style = LockInText.Small.copy(color = LockInColors.Body),
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The caption is a button too: tapping it nudges, same as tapping their climber.
                    modifier = if (tapPartner != null && !locked) Modifier.clip(CircleShape).clickable { tapPartner() } else Modifier,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(LockInColors.Lime))
                    val agoMin = ((clockNow - lastNudge) / 60_000L).toInt()
                    Text(
                        when {
                            onNudge != null && !locked && lastNudge > 0L && clockNow - lastNudge < 3_600_000L ->
                                if (agoMin < 1) "Nudged just now" else "Nudged ${agoMin}m ago"
                            onNudge != null && !locked -> "Tap ${LocalPartnerName.current} to nudge"
                            else -> "Roped to ${LocalPartnerName.current}"
                        },
                        style = LockInText.Small.copy(color = LockInColors.Body),
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box {
                LockInButton(
                    if (locked) "End session" else "Start",
                    onClick = onToggle,
                    kind = if (locked) ButtonKind.Outline else ButtonKind.Primary,
                )
                // A band of light sweeps across Start every few seconds, inviting a tap.
                if (!locked && !covered) StartSheen(Modifier.matchParentSize())
            }
            // The way into locking your partner now lives behind Start. While a lock you set is running, its
            // countdown (and an unlock request) still shows here.
            if (lock != null && lock.theirs != null) {
                Spacer(Modifier.height(10.dp))
                LockLine(lock, onOpenLock)
            }
        }
    }
}

@Composable
private fun StatusChip(locked: Boolean, text: String, modifier: Modifier = Modifier) {
    val bg = if (locked) LockInColors.Snow else LockInColors.Graphite
    val fg = if (locked) LockInColors.Ink else LockInColors.Body
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (locked) {
            GlyphIcon(Glyph.Lock, fg, boxSize = 14.dp, strokeWidth = 2.2f)
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        }
        Text(
            text,
            style = LockInText.Label.copy(color = fg, fontSize = 13.sp),
            maxLines = 1,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

/**
 * A slanted band of light that slides across the Start button. It sweeps three times each time Home comes to the
 * front, then rests: a loop that never ends keeps the screen redrawing (and the battery draining) for nothing.
 */
@Composable
private fun StartSheen(modifier: Modifier = Modifier) {
    val sweep = remember { Animatable(0f) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            kotlinx.coroutines.delay(1400)
            repeat(3) {
                sweep.snapTo(0f)
                sweep.animateTo(1f, tween(1100, easing = UiGlide))
                kotlinx.coroutines.delay(2100)
            }
            sweep.snapTo(0f)
        }
    }
    Canvas(modifier.clip(CircleShape)) {
        val v = sweep.value
        if (v <= 0f || v >= 1f) return@Canvas
        val band = size.height * 1.1f
        val x = -band + (size.width + band * 2) * v
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, size.height)
            lineTo(x + band * 0.45f, 0f)
            lineTo(x + band * 0.45f + band * 0.5f, 0f)
            lineTo(x + band * 0.5f, size.height)
            close()
        }
        drawPath(p, Color.White.copy(alpha = 0.32f))
    }
}

/** "0 min", "42 min", "1h 20m": minutes spelled out under an hour, so "0m" next to a mountain can't read as metres. */
internal fun heroMinutes(minutes: Int): String = if (minutes < 60) "$minutes min" else formatMinutes(minutes)
