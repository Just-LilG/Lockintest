package com.godbless.lockin.lock

import com.godbless.lockin.ui.calmFloat
import androidx.compose.runtime.setValue
import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide

/**
 * What covers every other app while a partner's lock runs. It slides up into place, the padlock swings shut,
 * and a ring keeps breathing round it. The only ways out are back into LockIn (where you can message or ask your partner)
 * and the emergency dialer.
 */
@Composable
internal fun LockCover(
    by: String,
    secondsLeft: Int,
    escapes: Int,
    onBack: () -> Unit,
    onEmergencyCall: () -> Unit,
    crew: Boolean = false,
    floating: Boolean = false,
    allowed: List<com.godbless.lockin.data.AllowedApp> = emptyList(),
    onOpenApp: (String) -> Unit = {},
    /** Tic-tac-toe with your partner, for a break. Null when there is no partner lock to take a break from. */
    game: com.godbless.lockin.data.TttRepository? = null,
) {
    var playing by remember { androidx.compose.runtime.mutableStateOf(false) }
    val rise = remember { Animatable(1f) }
    val shut = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 180f))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        shut.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 300f))
    }
    val breathe by rememberInfiniteTransition(label = "cover").calmFloat(
        0f, (2.0 * Math.PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "breathe",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            // Swallow every touch that isn't on a button.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = rise.value * size.height * 0.35f }
                .readable(Widths.Sheet)
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The padlock: its shackle drops shut with a bounce, and a lime ring breathes round it.
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    val r = size.minDimension / 2f
                    val pulse = 0.5f + 0.5f * kotlin.math.sin(breathe)
                    val sw = (3f + 2f * pulse).dp.toPx()
                    val inset = sw / 2f + (6f * (1f - pulse)).dp.toPx()
                    drawArc(
                        LockInColors.Lime, 0f, 360f, false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(sw),
                    )
                    // Body.
                    val bw = r * 0.78f
                    val bh = r * 0.62f
                    val bx = center.x - bw / 2f
                    val by = center.y - bh * 0.15f
                    drawRoundRect(
                        LockInColors.Lime, topLeft = Offset(bx, by), size = Size(bw, bh),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.14f),
                    )
                    // Shackle: starts lifted, drops into the body.
                    val lift = (1f - shut.value) * r * 0.32f
                    val sw2 = r * 0.11f
                    val sx = bw * 0.62f
                    drawArc(
                        LockInColors.Lime, 180f, 180f, false,
                        topLeft = Offset(center.x - sx / 2f, by - sx * 0.62f - lift),
                        size = Size(sx, sx),
                        style = Stroke(sw2, cap = StrokeCap.Round),
                    )
                    drawLine(LockInColors.Lime, Offset(center.x - sx / 2f, by - sx * 0.12f - lift), Offset(center.x - sx / 2f, by + 2f - lift * 0.2f), sw2, StrokeCap.Round)
                    drawLine(LockInColors.Lime, Offset(center.x + sx / 2f, by - sx * 0.12f - lift), Offset(center.x + sx / 2f, by + 2f), sw2, StrokeCap.Round)
                    // Keyhole.
                    drawCircle(LockInColors.Ink, radius = r * 0.08f, center = Offset(center.x, by + bh * 0.42f))
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(if (crew) "Locked in with $by" else "Locked by $by", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 15.sp))
            if (floating) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Another app is open in a small window. Swipe it away in your recent apps to get back to LockIn.",
                    style = LockInText.Small.copy(color = LockInColors.Flare),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(6.dp))
            // The time left rolls down a digit at a time.
            AnimatedContent(
                targetState = clock(secondsLeft),
                modifier = Modifier.clipToBounds(),
                transitionSpec = {
                    (slideInVertically(tween(260, easing = UiGlide)) { -it } togetherWith
                        slideOutVertically(tween(260, easing = UiGlide)) { it }).using(null)
                },
                label = "left",
            ) { t -> Text(t, style = LockInText.Headline.copy(fontSize = 56.sp, lineHeight = 62.sp)) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Stay with it. Your phone opens again when the lock ends.",
                style = LockInText.Sub.copy(color = LockInColors.Body),
                textAlign = TextAlign.Center,
            )
            if (escapes > 0) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .border(1.dp, LockInColors.Graphite, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.Lock, LockInColors.Lime, boxSize = 14.dp, strokeWidth = 2.2f)
                    Text(
                        if (crew) (if (escapes == 1) "1 try to leave · leaving early shows on the board" else "$escapes tries to leave · leaving early shows on the board")
                        else if (escapes == 1) "1 try to leave · $by can see this" else "$escapes tries to leave · $by can see this",
                        style = LockInText.Small.copy(color = LockInColors.Frost),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            LockInButton("Back to LockIn", onClick = onBack)
            // The apps your partner allows during this lock: one tap opens each.
            if (allowed.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("$by allows", style = LockInText.Small.copy(color = LockInColors.Mist))
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    allowed.forEach { app ->
                        Text(
                            app.label,
                            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                            maxLines = 1,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(LockInColors.LimeWash)
                                .clickable(role = Role.Button) { onOpenApp(app.pkg) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            if (game != null && !crew) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Take a break: tic-tac-toe with $by",
                    style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, LockInColors.Rim, CircleShape)
                        .clickable(role = Role.Button) { playing = true }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Emergency call",
                style = LockInText.Label.copy(color = LockInColors.Flare, fontSize = 14.sp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onEmergencyCall)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Only your partner or the timer can end this lock.",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The break: a game of tic-tac-toe slides up over the lock. The countdown keeps running above it.
        androidx.compose.animation.AnimatedVisibility(
            visible = playing && game != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LockInColors.Ink)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {})
                    .readable(Widths.Sheet)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .border(1.dp, LockInColors.Graphite, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.Lock, LockInColors.Lime, boxSize = 14.dp, strokeWidth = 2.2f)
                    Text(
                        "Locked by $by · ${clock(secondsLeft)} left",
                        style = LockInText.Small.copy(color = LockInColors.Frost),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                if (game != null) TicTacToe(game, by, onBack = { playing = false })
            }
        }
    }
}

private fun clock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
