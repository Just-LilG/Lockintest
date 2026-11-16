@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.godbless.lockin.data.AllowedApp
import com.godbless.lockin.study.Tool
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.Widths
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a lock your partner set ended. */
internal enum class PartnerLockEnd { Running, Finished, Released, Lifted }

private class AskReason(val value: String, val title: String, val detail: String)

private val AskReasons = listOf(
    AskReason("Urgent", "Something urgent", "I need my phone for something that can't wait"),
    AskReason("Break", "A short break", "A few minutes off, then I'll lock back in"),
    AskReason("Done early", "I'm done", "I finished what I had to do"),
)
private val QuickReplies = listOf("👋", "💪", "Almost done?", "Thanks!")

private const val Locked = 0
private const val Over = 1

/** The screen goes dark and quiet after this long without a touch. */
private const val IdleMillis = 45_000L

/** The single column on a phone, and on a tablet held upright. */
private val Column1 = 560.dp

/** Card corners, used everywhere on this page so it reads as one family. */
private val CardShape = RoundedCornerShape(22.dp)
private val TileShape = RoundedCornerShape(18.dp)
private val ChipShape = RoundedCornerShape(14.dp)

/**
 * The screen you get when your partner locks your phone. You did not choose this one, so there is no
 * normal way out: the timer counts down to when it opens, you can message your partner or ask them to
 * unlock you. There is no release of your own: the only other exit, an emergency call, sits quietly at the bottom.
 *
 * Layout: one column on phones and upright tablets (at most [Column1] wide, centred); two columns on wide
 * screens (the countdown and the mountain on the left, everything you can do on the right). In both, Message,
 * Ask and the emergency call are pinned to the bottom so they are always in reach.
 *
 * [askedAt] is when your request went out (0 if unknown) and [retryAt] when asking is allowed again after a
 * "not yet" (0 if unknown). [onOpenApp] returns false when the app could not be opened.
 */
@Composable
internal fun PartnerLockScreen(
    remaining: Int,
    total: Int,
    end: PartnerLockEnd,
    pickups: Int,
    asked: Boolean,
    message: String,
    strict: Boolean,
    together: Boolean,
    denied: Boolean,
    onMessage: () -> Unit,
    onAsk: (String) -> Unit,
    onEmergency: () -> Unit,
    onClose: () -> Unit,
    onOpenTool: (Tool) -> Unit,
    allowed: List<AllowedApp> = emptyList(),
    onOpenApp: (String) -> Boolean = { true },
    askedAt: Long = 0L,
    retryAt: Long = 0L,
    onCancelAsk: () -> Unit = {},
    onQuick: (String) -> Unit = {},
    onCall: () -> Unit = {},
    /** A tool, the chat or a page is open over this screen (so the screen isn't idle even if it isn't touched). */
    busyElsewhere: Boolean = false,
    /** Messages from your partner since the lock began (the Message button counts them). */
    unread: Int = 0,
    /** Their newest unread text message, shown above the buttons so you needn't open the chat to see it. */
    incoming: String? = null,
    /** Times you have used the emergency release on a lock of theirs in the last week. */
    emergencies: Int = 0,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val name = LocalPartnerName.current
    val stage = if (end == PartnerLockEnd.Running) Locked else Over

    // The break: tic-tac-toe with the partner who holds the lock.
    var playing by remember { mutableStateOf(false) }
    // The "ask to unlock" sheet.
    var asking by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = playing || asking) {
        if (asking) asking = false else playing = false
    }
    // A request that went out closes the sheet.
    LaunchedEffect(asked) { if (asked) asking = false }

    // The rope is tight the whole time your partner holds it.
    val taut = remember { Animatable(0f) }
    LaunchedEffect(Unit) { taut.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 140f)) }

    // After a while with no touch the screen turns to a dark, still clock (a long lock shouldn't drain the battery
    // or keep tempting you). Any touch brings it back.
    val activity = context.findActivity()
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    fun brightness(v: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.also { it.screenBrightness = v }
    }
    val busy by rememberUpdatedState(busyElsewhere || playing || asking)
    LaunchedEffect(stage) {
        if (stage != Locked) {
            if (dimmed) { brightness(-1f); dimmed = false }
            return@LaunchedEffect
        }
        while (true) {
            delay(2000)
            if (busy) lastTouch = System.currentTimeMillis()
            val idle = System.currentTimeMillis() - lastTouch > IdleMillis
            if (idle && !dimmed) { brightness(0.03f); dimmed = true }
            if (!idle && dimmed) { brightness(-1f); dimmed = false }
        }
    }
    DisposableEffect(Unit) { onDispose { brightness(-1f) } }

    val stats = LocalStats.current
    val elapsed = (total - remaining).coerceAtLeast(0)
    val progress = if (total > 0) (elapsed / total.toFloat()).coerceIn(0f, 1f) else 0f
    val now = System.currentTimeMillis()

    // Halfway, and the last five minutes: a chip slides open under the bar, the avatar hops and the phone buzzes once.
    // A lock that is already past the mark when the screen opens (after a restart) stays quiet.
    val bounce = remember { Animatable(0f) }
    var milestone by remember { mutableStateOf<String?>(null) }
    val half = total >= 600 && progress >= 0.5f
    val nearEnd = total >= 600 && remaining in 1..300
    var halfDone by remember { mutableStateOf(half) }
    var nearDone by remember { mutableStateOf(nearEnd) }
    LaunchedEffect(half) {
        if (half && !halfDone && stage == Locked) {
            halfDone = true
            milestone = "Halfway there"
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { bounce.snapTo(0f); bounce.animateTo(1f, tween(800, easing = UiGlide)) }
            delay(5000)
            milestone = null
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && !nearDone && stage == Locked) {
            nearDone = true
            milestone = "Last 5 minutes"
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { bounce.snapTo(0f); bounce.animateTo(1f, tween(800, easing = UiGlide)) }
            delay(6000)
            milestone = null
        }
    }
    // Finished on your own phone: two short buzzes as the page turns to "You're free".
    LaunchedEffect(stage, end) {
        if (stage == Over && end == PartnerLockEnd.Finished) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(180)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val opensAt = remember(remaining / 30, total) {
        timeFormat.format(java.util.Date(System.currentTimeMillis() + remaining * 1000L))
    }
    val startedAt = remember(total, elapsed / 30) {
        timeFormat.format(java.util.Date(System.currentTimeMillis() - elapsed * 1000L))
    }

    // Real bar sizes, not guesses: a tall cut-out or three-button navigation moves these.
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topInset = maxOf(statusTop + 16.dp, cutout + 16.dp, 32.dp)
    val bottomInset = maxOf(navBottom + 12.dp, 20.dp)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
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
        val screenW = maxWidth
        val screenH = maxHeight
        // Two columns only when there is real width to spare: tablets on their side, big foldables open.
        val twoColumns = screenW >= 840.dp || (screenW >= 720.dp && screenW > screenH)
        val big = screenW >= 600.dp

        AnimatedContent(
            targetState = stage,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (slideInVertically(tween(420, easing = UiGlide)) { it } togetherWith
                    slideOutVertically(tween(420, easing = UiGlide)) { -it / 3 }).using(SizeTransform(clip = true))
            },
            label = "partner-lock-stage",
        ) { s ->
            if (s == Locked) {
                // ---- The pieces, placed by the layouts below ----
                val hero: @Composable (Dp) -> Unit = { width ->
                    LockHero(
                        remaining = remaining, total = total, progress = progress, opensAt = opensAt, startedAt = startedAt,
                        together = together, strict = strict, message = message, fresh = elapsed < 60,
                        milestone = milestone, bounce = { bounce.value },
                        clockWidth = width, clockMax = if (big) 112f else 92f,
                    )
                }
                val scene: @Composable (Dp) -> Unit = { width ->
                    SceneCard(width) {
                        ClimbScene(
                            doneSeconds = elapsed,
                            partnerSeconds = stats.partnerTodaySeconds,
                            taut = { taut.value },
                            ground = LockInColors.Ink,
                            modifier = Modifier.fillMaxSize(),
                            frameMillis = 66L,
                            youActive = true,
                            slips = pickups,
                            goalOverrideSeconds = total,
                            youLabel = "You · ${(progress * 100).toInt()}% of the way",
                            paused = dimmed || playing,
                        )
                    }
                }
                val body: @Composable () -> Unit = {
                    LockBody(
                        name = name,
                        partnerToday = stats.partnerTodaySeconds,
                        allowed = allowed,
                        canPlay = LocalTtt.current != null,
                        columns = if (big && !twoColumns) 5 else 4,
                        onQuick = onQuick,
                        onOpenTool = onOpenTool,
                        onPlay = { playing = true },
                        onOpenApp = onOpenApp,
                    )
                }
                val actions: @Composable (Boolean) -> Unit = { rule ->
                    PinnedBar(rule = rule, bottomInset = bottomInset) {
                        LockedActions(
                            name = name, strict = strict, asked = asked, denied = denied, askedAt = askedAt, retryAt = retryAt,
                            now = now,
                            onMessage = onMessage,
                            onOpenAsk = { asking = true },
                            onCancelAsk = onCancelAsk,
                            onEmergency = onEmergency,
                            onCall = onCall,
                            unread = unread,
                            incoming = incoming,
                            emergencies = emergencies,
                        )
                    }
                }

                if (twoColumns) {
                    val outer = minOf(screenW, Widths.Wide)
                    val side = 40.dp
                    val gap = 40.dp
                    val left = (outer - side * 2 - gap) * 0.54f
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = ((screenW - outer) / 2).coerceAtLeast(0.dp) + side),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        // Left: the countdown and the climb, centred in the height.
                        Column(
                            Modifier
                                .width(left)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = screenH)
                                    .padding(top = topInset, bottom = bottomInset),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                hero(left)
                                Spacer(Modifier.height(28.dp))
                                scene(left)
                            }
                        }
                        // Right: everything you can do, with the buttons pinned under it.
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            val scroll = rememberScrollState()
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(scroll)
                                    .padding(top = topInset, bottom = 24.dp),
                            ) { body() }
                            actions(scroll.canScrollForward)
                        }
                    }
                } else {
                    val col = minOf(screenW, Column1)
                    val inner = col - 40.dp
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scroll),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(
                                Modifier
                                    .width(col)
                                    .padding(start = 20.dp, end = 20.dp, top = topInset, bottom = 24.dp),
                            ) {
                                hero(inner)
                                Spacer(Modifier.height(24.dp))
                                scene(inner)
                                Spacer(Modifier.height(28.dp))
                                body()
                            }
                        }
                        actions(scroll.canScrollForward)
                    }
                }
            } else {
                OverPage(
                    end = end, total = total, remaining = remaining, pickups = pickups, streak = stats.streak,
                    screenW = screenW, screenH = screenH, topInset = topInset, bottomInset = bottomInset,
                    scene = { width ->
                        SceneCard(width) {
                            ClimbScene(
                                doneSeconds = elapsed,
                                partnerSeconds = stats.partnerTodaySeconds,
                                taut = { taut.value },
                                ground = LockInColors.Ink,
                                modifier = Modifier.fillMaxSize(),
                                frameMillis = 66L,
                                youActive = false,
                                slips = pickups,
                                goalOverrideSeconds = total,
                            )
                        }
                    },
                    onMessage = onMessage,
                    onClose = onClose,
                    onThanks = { onQuick("Thanks for the lock 🙌") },
                )
            }
        }

        // Ask for an unlock: a sheet that slides up over the bottom of the page.
        if (asking) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { asking = false },
            )
        }
        AnimatedVisibility(
            visible = asking,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(260, easing = UiGlide)) { it },
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                AskSheet(
                    name = name,
                    bottomInset = bottomInset,
                    modifier = Modifier.widthIn(max = Widths.Sheet).fillMaxWidth(),
                    onSend = { text ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAsk(text)
                    },
                    onClose = { asking = false },
                )
            }
        }

        // The break slides up over the lock; the countdown keeps running above the board.
        val ttt = LocalTtt.current
        AnimatedVisibility(
            visible = playing && ttt != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LockInColors.Ink)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .widthIn(max = Widths.Sheet)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = topInset, bottom = bottomInset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Pill(text = "Locked by $name · ${formatClock(remaining)} left")
                    Spacer(Modifier.height(24.dp))
                    if (ttt != null) com.godbless.lockin.lock.TicTacToe(ttt, name, onBack = { playing = false })
                }
            }
        }

        // Quiet mode: a dark screen with just the time left. It wipes down from the top and a touch wipes it away.
        AnimatedVisibility(
            visible = dimmed && stage == Locked,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(420, easing = UiGlide)) { -it },
            exit = slideOutVertically(tween(300, easing = UiGlide)) { -it },
        ) {
            QuietClock(remaining = remaining, progress = progress, opensAt = opensAt, name = name)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The hero: who locked you, the countdown and how far through you are
// ---------------------------------------------------------------------------------------------

@Composable
private fun LockHero(
    remaining: Int,
    total: Int,
    progress: Float,
    opensAt: String,
    startedAt: String,
    together: Boolean,
    strict: Boolean,
    message: String,
    fresh: Boolean,
    milestone: String?,
    bounce: () -> Float,
    clockWidth: Dp,
    clockMax: Float,
) {
    val name = LocalPartnerName.current
    // Who and how long.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            name,
            boxSize = 44.dp,
            filled = true,
            ring = LockInColors.Lime,
            modifier = Modifier.graphicsLayer {
                val sc = 1f + 0.3f * kotlin.math.sin(Math.PI * bounce()).toFloat()
                scaleX = sc
                scaleY = sc
            },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                "Locked by $name",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    together -> "You're locked in together"
                    strict -> "No unlock requests this time"
                    else -> "Calls still come through"
                },
                style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Pill(text = heroMinutes(total / 60), glyph = Glyph.Lock)
    }

    // What your partner wrote when they locked you. Nothing is shown when they wrote nothing.
    if (message.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        PartnerNote(message, fresh)
    }

    Spacer(Modifier.height(if (message.isNotBlank()) 22.dp else 28.dp))

    // The countdown.
    val spoken = remember(remaining / 60, opensAt) {
        val m = remaining / 60
        (if (m >= 60) "${m / 60} hours ${m % 60} minutes" else if (m <= 0) "less than a minute" else "$m minutes") + " left, opens at $opensAt"
    }
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Opens in $spoken" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "OPENS IN",
            style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 12.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(4.dp))
        BigClock(remaining, clockWidth, clockMax)
    }

    Spacer(Modifier.height(16.dp))
    LockProgress(progress, startedAt, opensAt)

    // A chip that slides open under the bar for a few seconds at halfway and with five minutes left.
    var lastMilestone by remember { mutableStateOf("") }
    LaunchedEffect(milestone) { if (milestone != null) lastMilestone = milestone }
    AnimatedVisibility(
        visible = milestone != null,
        enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 400f)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.height(34.dp).clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(milestone ?: lastMilestone, style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp)) }
        }
    }
}

/**
 * The time left as big rolling digits. Every character has a fixed slot (so "1" doesn't make the row twitch),
 * the size is worked out from the width it has (so seven characters for an hour or more still fit), and it
 * ignores the system font size, since the width does the sizing.
 */
@Composable
private fun BigClock(remaining: Int, width: Dp, maxSize: Float) {
    val text = formatClock(remaining)
    val colons = text.count { it == ':' }
    val digits = text.length - colons
    val digitShare = 0.64f
    val colonShare = 0.32f
    val fs = minOf(maxSize, width.value * 0.96f / (digits * digitShare + colons * colonShare))
    val style = TimerStyle.copy(fontSize = fs.sp, lineHeight = (fs * 1.05f).sp, letterSpacing = 0.sp)
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
        Row(horizontalArrangement = Arrangement.Center) {
            // Keyed from the right, so the seconds keep their slots when the hours digit goes away.
            text.forEachIndexed { i, ch ->
                key(text.length - i) {
                    RollDigit(ch, (fs * if (ch == ':') colonShare else digitShare).dp, (fs * 1.0f).dp, style)
                }
            }
        }
    }
}

@Composable
private fun RollDigit(c: Char, width: Dp, height: Dp, style: TextStyle) {
    AnimatedContent(
        targetState = c,
        modifier = Modifier.width(width).height(height).clipToBounds(),
        transitionSpec = {
            (slideInVertically(tween(260, easing = UiGlide)) { it } togetherWith
                slideOutVertically(tween(260, easing = UiGlide)) { -it }).using(SizeTransform(clip = true))
        },
        contentAlignment = Alignment.Center,
        label = "digit",
    ) { ch ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                ch.toString(),
                style = style,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.wrapContentHeight(unbounded = true),
            )
        }
    }
}

/** A slim bar that fills as the lock runs, with when it started, how far in you are and when it opens. */
@Composable
private fun LockProgress(progress: Float, startedAt: String, opensAt: String) {
    val shown by animateFloatAsState(progress, tween(900, easing = LinearEasing), label = "lock-progress")
    val track = LockInColors.Graphite
    val fill = LockInColors.Lime
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val r = size.height / 2f
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        val w = (size.width * shown).coerceAtLeast(size.height)
        drawRoundRect(fill, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Started $startedAt", style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            "${(progress * 100).toInt()}%",
            style = LockInText.Small.copy(color = LockInColors.Lime, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        Text(
            "Opens $opensAt",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Your partner's words, quoted, with their initial. Folds to one line after the first minute; a tap opens it. */
@Composable
private fun PartnerNote(message: String, fresh: Boolean) {
    val name = LocalPartnerName.current
    var open by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .clickable(enabled = overflow || open, role = Role.Button) { open = !open }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "“",
            style = LockInText.Title.copy(color = LockInColors.Lime, fontSize = 34.sp, lineHeight = 34.sp),
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                message.trim(),
                style = LockInText.Sub.copy(color = LockInColors.Snow, fontSize = 15.sp, lineHeight = 21.sp, fontStyle = FontStyle.Italic),
                maxLines = if (open) Int.MAX_VALUE else if (fresh) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) overflow = it.hasVisualOverflow },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "— $name" + if (overflow && !open) " · tap to read" else "",
                style = LockInText.Small.copy(color = LockInColors.Mist),
            )
        }
    }
}

/** The mountain in a card of its own, at the shape the scene was drawn for (so the flag is never cut off). */
@Composable
private fun SceneCard(width: Dp, content: @Composable () -> Unit) {
    val h = (width * 0.56f).coerceIn(150.dp, 340.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(h)
            .clip(CardShape)
            .background(LockInColors.Ink)
            .border(1.dp, LockInColors.Graphite, CardShape),
    ) { content() }
}

// ---------------------------------------------------------------------------------------------
// The body: things you can do while you wait
// ---------------------------------------------------------------------------------------------

@Composable
private fun LockBody(
    name: String,
    partnerToday: Int,
    allowed: List<AllowedApp>,
    canPlay: Boolean,
    columns: Int,
    onQuick: (String) -> Unit,
    onOpenTool: (Tool) -> Unit,
    onPlay: () -> Unit,
    onOpenApp: (String) -> Boolean,
) {
    SectionTitle(
        "Send $name a quick note",
        if (partnerToday >= 60) "$name: ${formatMinutes(partnerToday / 60)} today" else null,
    )
    QuickReplyRow(onQuick)

    Spacer(Modifier.height(28.dp))
    SectionTitle("Study while you wait")
    val tiles = buildList {
        add(TileSpec(Tool.Textbooks.label, "Read your books", Glyph.Book) { onOpenTool(Tool.Textbooks) })
        add(TileSpec(Tool.Quiz.label, "Review your cards", Glyph.Cards) { onOpenTool(Tool.Quiz) })
        add(TileSpec(Tool.Calculator.label, "Work it out", Glyph.Calculator) { onOpenTool(Tool.Calculator) })
        if (canPlay) add(TileSpec("Tic-tac-toe", "Play $name", null, onPlay))
    }
    tiles.chunked(2).forEachIndexed { row, pair ->
        if (row > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { ActionTile(it, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    if (allowed.isNotEmpty()) {
        Spacer(Modifier.height(28.dp))
        SectionTitle("$name lets you use")
        AllowedAppGrid(allowed, columns, onOpenApp)
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** One tap says something without opening the chat. The chip that was sent says so for a moment. */
@Composable
private fun QuickReplyRow(onQuick: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var sent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sent) { if (sent != null) { delay(1600); sent = null } }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickReplies.forEach { text ->
            val done = sent == text
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val squeeze by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "chip")
            Box(
                Modifier
                    .height(42.dp)
                    .widthIn(min = 52.dp)
                    .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
                    .clip(ChipShape)
                    .background(if (done) LockInColors.LimeWash else LockInColors.Slate)
                    .border(1.dp, if (done) LockInColors.Lime else LockInColors.Graphite, ChipShape)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        sent = text
                        onQuick(text)
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (done) "Sent ✓" else text,
                    style = LockInText.Label.copy(color = if (done) LockInColors.Lime else LockInColors.Snow, fontSize = 15.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

private class TileSpec(val title: String, val detail: String, val glyph: Glyph?, val onClick: () -> Unit)

/** A tool tile: icon in a soft accent circle, a name and a line of detail. Squeezes a little when pressed. */
@Composable
private fun ActionTile(spec: TileSpec, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tile")
    Row(
        modifier
            .height(72.dp)
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(TileShape)
            .background(LockInColors.Slate)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = spec.onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(LockInColors.LimeWash),
            contentAlignment = Alignment.Center,
        ) {
            if (spec.glyph != null) GlyphIcon(spec.glyph, LockInColors.Lime, boxSize = 22.dp)
            else GridGlyph(LockInColors.Lime, 20.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                spec.title,
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                spec.detail,
                style = LockInText.Small.copy(color = LockInColors.Mist),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tic-tac-toe board, for the game tile. */
@Composable
private fun GridGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.085f
        val a = w / 3f
        val b = w * 2f / 3f
        drawLine(color, Offset(a, w * 0.06f), Offset(a, w * 0.94f), sw, StrokeCap.Round)
        drawLine(color, Offset(b, w * 0.06f), Offset(b, w * 0.94f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.06f, a), Offset(w * 0.94f, a), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.06f, b), Offset(w * 0.94f, b), sw, StrokeCap.Round)
    }
}

/**
 * The apps your partner lets you use, in a grid like a home screen: the app's own icon (no extra frame) and its
 * name. One that isn't installed is greyed and says so when tapped.
 */
@Composable
private fun AllowedAppGrid(apps: List<AllowedApp>, columns: Int, onOpen: (String) -> Boolean) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        apps.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    val info = remember(app.pkg) {
                        runCatching {
                            val pm = context.packageManager
                            val label = pm.getApplicationLabel(pm.getApplicationInfo(app.pkg, 0)).toString()
                            val icon = pm.getApplicationIcon(app.pkg).toBitmap(144, 144).asImageBitmap()
                            label to icon
                        }.getOrNull()
                    }
                    val label = info?.first ?: app.label
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectIsPressedAsState()
                    val squeeze by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "app")
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                                val ok = info != null && onOpen(app.pkg)
                                if (!ok) Toast.makeText(context, if (info == null) "$label isn't installed on this phone." else "Couldn't open $label.", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(48.dp).graphicsLayer { scaleX = squeeze; scaleY = squeeze },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (info != null) {
                                Image(info.second, contentDescription = null, modifier = Modifier.size(48.dp))
                            } else {
                                Box(
                                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(LockInColors.Graphite),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(label.take(1).uppercase(), style = LockInText.Title.copy(fontSize = 18.sp, color = LockInColors.Mist))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            style = LockInText.Small.copy(color = if (info != null) LockInColors.Frost else LockInColors.Mist),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pinned actions
// ---------------------------------------------------------------------------------------------

/** The bar at the bottom. A hairline shows over it only while there is more page underneath. */
@Composable
private fun PinnedBar(rule: Boolean, bottomInset: Dp, content: @Composable () -> Unit) {
    val ruleColor = LockInColors.Graphite
    val ruleOn by animateFloatAsState(if (rule) 1f else 0f, tween(200), label = "rule")
    Box(
        Modifier
            .fillMaxWidth()
            .background(LockInColors.Ink)
            .drawBehind {
                if (ruleOn > 0f) drawLine(ruleColor, Offset(size.width * (1f - ruleOn) / 2f, 0f), Offset(size.width * (1f + ruleOn) / 2f, 0f), 1.dp.toPx())
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = Column1)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset),
        ) { content() }
    }
}

private fun clockLeft(ms: Long): String {
    val s = (ms / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun agoText(ms: Long): String {
    val m = (ms / 60_000L).toInt()
    return when {
        m < 1 -> "just now"
        m == 1 -> "1 min ago"
        m < 60 -> "$m min ago"
        else -> "${m / 60}h ago"
    }
}

@Composable
private fun LockedActions(
    name: String,
    strict: Boolean,
    asked: Boolean,
    denied: Boolean,
    askedAt: Long,
    retryAt: Long,
    now: Long,
    onMessage: () -> Unit,
    onOpenAsk: () -> Unit,
    onCancelAsk: () -> Unit,
    onEmergency: () -> Unit,
    onCall: () -> Unit,
    unread: Int = 0,
    incoming: String? = null,
    emergencies: Int = 0,
) {
    // Their newest message slides open above the buttons; tapping it opens the chat.
    var lastIncoming by remember { mutableStateOf("") }
    LaunchedEffect(incoming) { if (incoming != null) lastIncoming = incoming }
    AnimatedVisibility(
        visible = incoming != null,
        enter = expandVertically(spring(dampingRatio = 0.75f, stiffness = 420f)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
    ) {
        Row(
            Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .clip(TileShape)
                .background(LockInColors.Slate)
                .clickable(role = Role.Button, onClick = onMessage)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name, boxSize = 30.dp, filled = true, ring = LockInColors.Slate)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(name, style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp))
                Text(
                    incoming ?: lastIncoming,
                    style = LockInText.Sub.copy(color = LockInColors.Snow, fontSize = 15.sp, lineHeight = 20.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Reply", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
        }
    }

    val cooling = denied && retryAt > now
    // What the request is doing, in words.
    val note: String? = when {
        strict -> null
        asked -> "Waiting for $name" + if (askedAt > 0L) " · asked ${agoText(now - askedAt)}" else ""
        cooling -> "$name said not yet · you can ask again in ${clockLeft(retryAt - now)}"
        denied -> "$name said not yet"
        else -> null
    }
    if (note != null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            // While a request is out, a dot breathes beside the words: it is waiting, not stuck.
            val dot = if (asked) LockInColors.Lime else LockInColors.Flare
            val breathe by rememberInfiniteTransition(label = "ask-dot").animateFloat(
                0.7f, 1.3f,
                infiniteRepeatable(tween(900, easing = UiGlide), RepeatMode.Reverse),
                label = "breathe",
            )
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(8.dp)
                    .graphicsLayer { val s = if (asked) breathe else 1f; scaleX = s; scaleY = s }
                    .clip(CircleShape)
                    .background(dot),
            )
            Text(note, style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            LockInButton("Message", onClick = onMessage)
            // Unread count as a badge on the button's corner rather than in its words.
            if (unread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-10).dp, y = (-6).dp)
                        .height(22.dp)
                        .widthIn(min = 22.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Flare)
                        .border(2.dp, LockInColors.Ink, CircleShape)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (unread > 9) "9+" else unread.toString(), style = LockInText.Label.copy(color = Color.White, fontSize = 11.sp, lineHeight = 13.sp))
                }
            }
        }
        if (!strict) {
            when {
                asked -> LockInButton("Cancel request", onClick = onCancelAsk, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
                cooling -> LockInButton("Ask in ${clockLeft(retryAt - now)}", onClick = {}, modifier = Modifier.weight(1f), kind = ButtonKind.Outline, enabled = false)
                else -> LockInButton("Ask to unlock", onClick = onOpenAsk, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    EmergencyBar(onCall = onCall)
}

/**
 * The one way out that is always there: an emergency call (one tap, the cover steps aside for the dialer). A partner's
 * lock has no release of its own; only they, the timer or an unpair end it. (A lock you set yourself keeps its
 * hold-to-end, on LockScreen.)
 */
@Composable
private fun EmergencyBar(onCall: () -> Unit) {
    val flare = LockInColors.Flare
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .clickable(role = Role.Button, onClick = onCall)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PhoneGlyph(flare, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text("Emergency call", style = LockInText.Label.copy(color = flare, fontSize = 14.sp), maxLines = 1)
    }
}

@Composable
private fun PhoneGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        val p = Path().apply {
            moveTo(6.6f * k, 2.8f * k)
            lineTo(9.6f * k, 2.8f * k)
            lineTo(11f * k, 7.6f * k)
            lineTo(8.7f * k, 9.6f * k)
            cubicTo(9.9f * k, 12.3f * k, 11.7f * k, 14.1f * k, 14.4f * k, 15.3f * k)
            lineTo(16.4f * k, 13f * k)
            lineTo(21.2f * k, 14.4f * k)
            lineTo(21.2f * k, 17.4f * k)
            cubicTo(21.2f * k, 19.6f * k, 19.6f * k, 21.2f * k, 17.4f * k, 21.2f * k)
            cubicTo(9.2f * k, 20.6f * k, 3.4f * k, 14.8f * k, 2.8f * k, 6.6f * k)
            cubicTo(2.8f * k, 4.4f * k, 4.4f * k, 2.8f * k, 6.6f * k, 2.8f * k)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.9f * k, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** A small rounded label: an optional glyph and a few words. */
@Composable
private fun Pill(text: String, glyph: Glyph? = null) {
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, LockInColors.Lime, boxSize = 15.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = LockInText.Label.copy(color = LockInColors.Frost, fontSize = 13.sp), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// When it's over
// ---------------------------------------------------------------------------------------------

/**
 * The end of a lock: the climb, what happened in a line, three numbers, and the way back. Centred in the
 * height, so a short page doesn't leave a hole under it.
 */
@Composable
private fun OverPage(
    end: PartnerLockEnd,
    total: Int,
    remaining: Int,
    pickups: Int,
    streak: Int,
    screenW: Dp,
    screenH: Dp,
    topInset: Dp,
    bottomInset: Dp,
    scene: @Composable (Dp) -> Unit,
    onMessage: () -> Unit,
    onClose: () -> Unit,
    onThanks: () -> Unit,
) {
    val name = LocalPartnerName.current
    val col = minOf(screenW, Column1)
    val inner = col - 40.dp
    val lockedFor = if (end == PartnerLockEnd.Finished) total else (total - remaining).coerceAtLeast(0)
    val kicker = when (end) {
        PartnerLockEnd.Released -> "Released early"
        PartnerLockEnd.Lifted -> "Unlocked early"
        else -> "Lock complete"
    }
    val kickerColor = if (end == PartnerLockEnd.Released) LockInColors.Flare else LockInColors.Lime
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .width(col)
                    // The pinned buttons take about this much; the rest is the page's to centre in.
                    .heightIn(min = (screenH - 150.dp - bottomInset).coerceAtLeast(0.dp))
                    .padding(start = 20.dp, end = 20.dp, top = topInset, bottom = 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                scene(inner)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(kickerColor))
                    Spacer(Modifier.width(8.dp))
                    Text(kicker.uppercase(), style = LockInText.Small.copy(color = kickerColor, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (end == PartnerLockEnd.Released) "You're out." else "You're free.",
                    style = LockInText.Headline.copy(fontSize = 40.sp, lineHeight = 46.sp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (end) {
                        PartnerLockEnd.Released -> "You used the emergency release after ${duration(lockedFor)}. $name has been told."
                        PartnerLockEnd.Lifted -> "$name unlocked you after ${duration(lockedFor)}."
                        else -> "You stayed locked for the full ${duration(total)}" +
                            if (pickups == 0) " without picking up your phone." else "."
                    },
                    style = LockInText.Sub.copy(color = LockInColors.Body),
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(CardShape).background(LockInColors.Slate).padding(vertical = 16.dp),
                ) {
                    OverStat(duration(lockedFor), "Locked", Modifier.weight(1f))
                    StatRule()
                    OverStat(pickups.toString(), if (pickups == 1) "Pickup" else "Pickups", Modifier.weight(1f))
                    StatRule()
                    OverStat(streak.toString(), "Day streak", Modifier.weight(1f))
                }
            }
        }
        PinnedBar(rule = false, bottomInset = bottomInset) {
            var thanked by rememberSaveable { mutableStateOf(false) }
            if (end == PartnerLockEnd.Finished) {
                LockInButton(
                    if (thanked) "Thanks sent" else "Send thanks to $name",
                    onClick = { thanked = true; onThanks() },
                    kind = ButtonKind.Outline,
                    enabled = !thanked,
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Message", onClick = onMessage, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
                LockInButton("Done", onClick = onClose, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1)
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1)
    }
}

@Composable
private fun StatRule() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(LockInColors.Graphite))
}

// ---------------------------------------------------------------------------------------------
// Ask sheet and quiet clock
// ---------------------------------------------------------------------------------------------

/** Pick why, add a note if you like, then send. Nothing goes out until you press Send. */
@Composable
private fun AskSheet(name: String, bottomInset: Dp, modifier: Modifier = Modifier, onSend: (String) -> Unit, onClose: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val top = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier
            .clip(top)
            .background(LockInColors.Slate)
            .pointerInput(Unit) { detectTapGestures { } }
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 4.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(LockInColors.Rim))
        }
        Spacer(Modifier.height(18.dp))
        Text("Ask $name to unlock you", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
        Spacer(Modifier.height(4.dp))
        Text("$name gets a notification and can unlock you or say not yet.", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 13.sp))
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AskReasons.forEach { r ->
                val on = reason == r.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TileShape)
                        .background(if (on) LockInColors.LimeWash else LockInColors.Ink)
                        .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Graphite, TileShape)
                        .clickable(role = Role.RadioButton) { reason = r.value }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                        Text(r.detail, style = LockInText.Small.copy(color = LockInColors.Mist))
                    }
                    Box(
                        Modifier
                            .padding(start = 12.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (on) LockInColors.Lime else LockInColors.Rim, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(LockInColors.Lime))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        LockInField(
            label = "Add a note (optional)",
            value = note,
            onValueChange = { note = it.take(60) },
            placeholder = "e.g. my mum is calling",
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Not now", onClick = onClose, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton(
                "Send request",
                onClick = {
                    val r = reason ?: return@LockInButton
                    onSend((if (note.isBlank()) r else "$r: ${note.trim()}").take(80))
                },
                modifier = Modifier.weight(1f),
                enabled = reason != null,
            )
        }
    }
}

/** The dark clock: only the time left, a thin ring and when it opens. OLED black, and nothing moves but the digits. */
@Composable
private fun QuietClock(remaining: Int, progress: Float, opensAt: String, name: String) {
    val lime = LockInColors.Lime
    val track = LockInColors.Graphite
    // The ring wanders a few dp every minute, so a static picture never sits on the same OLED pixels for hours.
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        while (true) {
            delay(60_000)
            launch { dx.animateTo((rnd.nextFloat() - 0.5f) * 56f, tween(6000, easing = UiGlide)) }
            dy.animateTo((rnd.nextFloat() - 0.5f) * 56f, tween(6000, easing = UiGlide))
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { IntOffset(dx.value.dp.roundToPx(), dy.value.dp.roundToPx()) }.size(260.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(260.dp)) {
                val s = 3.dp.toPx()
                val arc = Size(size.width - s, size.height - s)
                drawArc(track.copy(alpha = 0.5f), 0f, 360f, false, topLeft = Offset(s / 2f, s / 2f), size = arc, style = Stroke(s))
                drawArc(lime.copy(alpha = 0.55f), -90f, 360f * progress, false, topLeft = Offset(s / 2f, s / 2f), size = arc, style = Stroke(s, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    Text(
                        formatClock(remaining),
                        style = TimerStyle.copy(fontSize = if (remaining >= 3600) 44.sp else 56.sp, lineHeight = 62.sp, color = Color(0xFF8A8A8A)),
                    )
                }
                Text("opens at $opensAt", style = LockInText.Small.copy(color = Color(0xFF5A5A5A)))
            }
        }
        Text(
            "Locked by $name · tap to wake",
            style = LockInText.Small.copy(color = Color(0xFF4A4A4A)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
    }
}
