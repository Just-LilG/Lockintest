@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.godbless.lockin.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.ControlShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rise
import androidx.compose.ui.graphics.graphicsLayer
import com.godbless.lockin.ui.riseSpring
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle as DateStyle
import java.util.Locale

private val CardShape = RoundedCornerShape(24.dp)
private val BigShape = RoundedCornerShape(28.dp)

/** Who you are roped to: the rope between you, how today compares, what is planned and what happened. */
@Composable
internal fun PartnerPage(
    youName: String,
    busy: Boolean,
    usage: UsageState,
    lock: LockState,
    onPickApp: (com.godbless.lockin.data.PartnerApp) -> Unit,
    partner: PartnerState,
    devices: DevicesState,
    onMessage: () -> Unit,
    onNudge: () -> Unit,
    onOpenLock: () -> Unit,
    plans: PlanState,
    onPlan: () -> Unit,
) {
    PageScaffold(wide = true, key = "tab-partner") { entrance ->
        val stats = LocalStats.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PageTitle(
                    "Partner",
                    when (stats.daysTogether) {
                        0 -> "Roped together since today."
                        1 -> "Roped together for 1 day."
                        else -> "Roped together for ${stats.daysTogether} days."
                    },
                    entrance,
                )
            }
            // On a tablet the chat opens beside the page, so a Message button up here is always in reach.
            if (com.godbless.lockin.ui.isWide()) {
                val slide = rememberSlideIn(true, 1, key = "partner-message")
                SmallPill("Message", onMessage, Modifier.graphicsLayer { translationX = slide.value * 160.dp.toPx() })
            }
        }
        Spacer(Modifier.height(20.dp))

        val partnerName = LocalPartnerName.current
        val checkedAt = usage.partner?.reportedAt ?: 0L
        // Real status only: what the lock says, or when their phone last reported in.
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(30_000)
            }
        }
        val recent = checkedAt > 0L && tick - checkedAt < 15 * 60_000L
        val status = when {
            lock.theirs != null -> "Locked by you"
            checkedAt <= 0L -> "Waiting for their phone to check in"
            recent -> "Phone active"
            else -> "Last seen ${agoText(tick - checkedAt)}"
        }
        // One card you swipe: your partner, then their phone today.
        var showAllApps by rememberSaveable { mutableStateOf(false) }
        // On a tablet: them (card, apps, devices) on the left; plans, together and your side on the right.
        TwoColumns(left = {
        PartnerTopPager(
            youName = youName,
            status = status,
            active = recent || lock.theirs != null,
            usage = usage,
            lock = lock,
            onMessage = onMessage,
            onNudge = onNudge,
            onOpenLock = onOpenLock,
            onPickApp = onPickApp,
            onAllApps = { showAllApps = !showAllApps },
            allAppsOpen = showAllApps,
            modifier = Modifier.riseSpring(entrance, 1),
        )
        // Opening it scrolls the card into view, so the tap visibly does something.
        val allAppsInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        LaunchedEffect(showAllApps) {
            if (showAllApps) {
                delay(300)
                allAppsInView.bringIntoView()
            }
        }
        Collapse(showAllApps) {
            Column(Modifier.bringIntoViewRequester(allAppsInView)) {
                Spacer(Modifier.height(12.dp))
                TheirPhoneCard(usage, onPickApp, onMessage, startOpen = true)
            }
        }
        Spacer(Modifier.height(22.dp))

        SectionTitle("$partnerName's devices", Modifier.riseSpring(entrance, 2))
        Spacer(Modifier.height(10.dp))
        DevicesCard(devices.partner, lock.theirs != null, onOpenLock, Modifier.riseSpring(entrance, 2))
        Spacer(Modifier.height(22.dp))
        }, right = {
        SectionTitle("Coming up", Modifier.riseSpring(entrance, 3))
        Spacer(Modifier.height(10.dp))
        UpcomingList(plans, onPlan, Modifier.riseSpring(entrance, 3))
        Spacer(Modifier.height(14.dp))

        TogetherCard(Modifier.riseSpring(entrance, 4))
        Spacer(Modifier.height(14.dp))

        // Your own side (sharing, permissions, limits on you, your devices) stays one tap away.
        var showYours by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .riseSpring(entrance, 5)
                .clip(CardShape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, CardShape)
                .springClick { showYours = !showYours }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your phone", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                Text(
                    when {
                        !usage.sharing -> "Not sharing app times"
                        usage.limitsOnMe.isNotEmpty() -> "Sharing, ${usage.limitsOnMe.size} limit${if (usage.limitsOnMe.size == 1) "" else "s"} set on you"
                        else -> "Sharing app times"
                    } + if (devices.mine.size > 1) " · ${devices.mine.size} devices" else "",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
            val turn by androidx.compose.animation.core.animateFloatAsState(if (showYours) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "plus")
            GlyphIcon(Glyph.Plus, LockInColors.Mist, modifier = Modifier.graphicsLayer { rotationZ = turn }, boxSize = 18.dp, strokeWidth = 2f)
        }
        Collapse(showYours) {
            Column {
                Spacer(Modifier.height(14.dp))
                YourSideCard(usage)
                Spacer(Modifier.height(14.dp))
                MyDevicesList(devices, Modifier.padding(horizontal = 6.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        UnpairCard(partner, Modifier.riseSpring(entrance, 6))
        Spacer(Modifier.height(12.dp))
        })
    }
}

private fun agoText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

// ---------------------------------------------------------------------------------------------
// The rope
// ---------------------------------------------------------------------------------------------

@Composable
private fun RopeCard(youName: String, status: String, active: Boolean, onMessage: () -> Unit, onNudge: () -> Unit, modifier: Modifier = Modifier) {
    // The rope drops in slack, then pulls up with a small bounce.
    val pull = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        pull.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 120f))
    }
    val first = youName.trim().substringBefore(' ').ifEmpty { "You" }

    Column(
        modifier
            .fillMaxWidth()
            .clip(BigShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, BigShape)
            .padding(18.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.matchParentSize()) {
                // The rope runs from avatar centre to avatar centre; the avatars sit on top of its ends.
                val cy = 32.dp.toPx()
                val x0 = 32.dp.toPx()
                val x1 = size.width - 32.dp.toPx()
                val sag = (62.dp.toPx() - 50.dp.toPx() * pull.value).coerceAtLeast(2.dp.toPx())
                val mid = (x0 + x1) / 2f
                val rope = Path().apply {
                    moveTo(x0, cy)
                    quadraticBezierTo(mid, cy + sag * 2f, x1, cy)
                }
                drawPath(rope, LockInColors.Frost, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                // The knot halfway along.
                val knotY = cy + sag
                drawCircle(LockInColors.Ink, radius = 8.dp.toPx(), center = Offset(mid, knotY))
                drawCircle(LockInColors.Lime, radius = 5.dp.toPx(), center = Offset(mid, knotY))
            }
            Column(Modifier.align(Alignment.TopStart), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(first, boxSize = 64.dp, ring = LockInColors.Slate)
                Text("You", style = LockInText.Small.copy(color = LockInColors.Silver), modifier = Modifier.padding(top = 6.dp).width(64.dp), textAlign = TextAlign.Center)
            }
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Avatar(LocalPartnerName.current, boxSize = 64.dp, filled = true, ring = LockInColors.Slate)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(LockInColors.Slate)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(if (active) LockInColors.Lime else LockInColors.Edge),
                    )
                }
                Text(LocalPartnerName.current, style = LockInText.Small.copy(color = LockInColors.Silver), modifier = Modifier.padding(top = 6.dp).width(64.dp), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(LocalPartnerName.current, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
        Text(status, style = LockInText.Sub.copy(color = LockInColors.Body))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Message", onClick = onMessage, modifier = Modifier.weight(1f))
            LockInButton("Nudge", onClick = onNudge, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Today, side by side
// ---------------------------------------------------------------------------------------------

@Composable
private fun TodayCard(modifier: Modifier = Modifier) {
    val you = LocalStats.current.todaySeconds
    val kofi = LocalStats.current.partnerTodaySeconds
    val goal = LocalGoalMinutes.current * 60f
    val partnerGoal = LocalPartnerGoalMinutes.current * 60f
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(400)
        grow.animateTo(1f, tween(1200, easing = UiGlide))
    }
    val diff = (you - kofi) / 60
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        Text("Today", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(14.dp))
        ProgressRow("You", you, goal, grow.value, LockInColors.Lime)
        Spacer(Modifier.height(12.dp))
        ProgressRow(LocalPartnerName.current, kofi, partnerGoal, grow.value, LockInColors.Frost)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                diff > 0 -> "You are $diff min ahead of ${LocalPartnerName.current}."
                diff < 0 -> "${LocalPartnerName.current} is ${-diff} min ahead of you."
                else -> "You are level with ${LocalPartnerName.current}."
            },
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
    }
}

@Composable
private fun ProgressRow(name: String, seconds: Int, goal: Float, grow: Float, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), modifier = Modifier.width(48.dp))
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(CircleShape)
                .background(LockInColors.Graphite),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((seconds / goal).coerceIn(0f, 1f) * grow)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            formatMinutes(seconds / 60),
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
            textAlign = TextAlign.End,
            modifier = Modifier.width(66.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Coming up
// ---------------------------------------------------------------------------------------------

@Composable
private fun UpcomingList(plans: PlanState, onPlan: () -> Unit, modifier: Modifier = Modifier) {
    // Tap a session to open it: who planned it and a way to cancel.
    var open by rememberSaveable { mutableStateOf("") }
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape),
    ) {
        plans.plans.forEachIndexed { i, u ->
            val date = java.time.Instant.ofEpochMilli(u.startsAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val next = i == 0
            val expanded = open == u.id
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(1.dp).background(LockInColors.Graphite))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { open = if (expanded) "" else u.id }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (next) LockInColors.Lime else LockInColors.Graphite),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        date.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()),
                        style = LockInText.Small.copy(color = if (next) LockInColors.OnLime else LockInColors.Mist, fontSize = 11.sp, lineHeight = 13.sp),
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        style = LockInText.Title.copy(fontSize = 19.sp, lineHeight = 22.sp, color = if (next) LockInColors.OnLime else LockInColors.Snow),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(u.title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Text(
                        "${planWhen(u.startsAt)} for ${formatMinutes(u.minutes)}",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                }
                GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
            }
            Collapse(expanded) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 82.dp, end = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (u.mine) "You planned this." else "${u.by.ifBlank { "Your partner" }} planned this.",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .height(36.dp)
                            .clip(ControlShape)
                            .border(1.5.dp, LockInColors.Rim, ControlShape)
                            .clickable(enabled = !plans.busy, role = Role.Button) { plans.cancel(u) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Cancel session", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp)) }
                }
            }
        }
        if (plans.plans.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
        Row(
            Modifier.fillMaxWidth().springClick(onClick = onPlan).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // With nothing planned the plus turns a quarter every few seconds, asking to be tapped.
            val spin = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(plans.plans.isEmpty()) {
                if (!plans.plans.isEmpty() || com.godbless.lockin.ui.MotionState.reduced) return@LaunchedEffect
                delay(1500)
                repeat(3) {
                    spin.snapTo(0f)
                    spin.animateTo(90f, spring(dampingRatio = 0.5f, stiffness = 140f))
                    delay(3500)
                }
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .then(if (plans.plans.isEmpty()) Modifier.background(LockInColors.Lime) else Modifier.border(1.5.dp, LockInColors.Rim, RoundedCornerShape(18.dp))),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    Glyph.Plus,
                    if (plans.plans.isEmpty()) LockInColors.OnLime else LockInColors.Snow,
                    modifier = Modifier.graphicsLayer { rotationZ = spin.value },
                    boxSize = 22.dp,
                    strokeWidth = 2f,
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    if (plans.plans.isEmpty()) "Plan a session with ${LocalPartnerName.current}" else "Plan another session",
                    style = LockInText.Label.copy(color = if (plans.plans.isEmpty()) LockInColors.Snow else LockInColors.Silver, fontSize = 15.sp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (plans.plans.isEmpty()) Text("Pick a time and you both get a reminder.", style = LockInText.Small.copy(color = LockInColors.Mist))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActivityFeed(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp),
    ) {
        val everything = LocalStats.current.activity
        var more by rememberSaveable { mutableStateOf(false) }
        val feed = if (more) everything else everything.take(4)
        if (feed.isEmpty()) {
            Text(
                "Nothing yet. Sessions you and ${LocalPartnerName.current} lock in will show up here.",
                style = LockInText.Sub.copy(color = LockInColors.Silver),
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        feed.forEachIndexed { i, a ->
            val last = i == feed.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (a.strong) LockInColors.Lime else LockInColors.Edge),
                    )
                    if (!last) Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(LockInColors.Graphite))
                }
                Column(Modifier.padding(start = 10.dp, bottom = 18.dp)) {
                    Text(a.text, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                    Text(a.whenText, style = LockInText.Small.copy(color = LockInColors.Mist))
                }
            }
        }
        if (everything.size > 4) {
            Box(Modifier.padding(bottom = 18.dp)) { SmallPill(if (more) "Show less" else "Show all ${everything.size}", { more = !more }) }
        }
    }
}


/** One line for the chat header: what their phone is doing, from when it last reported (0 if it never has). */
internal fun partnerStatusLine(reportedAt: Long, now: Long): String = when {
    reportedAt <= 0L -> "Roped together"
    now - reportedAt < 15 * 60_000L -> "Phone active"
    else -> "Last seen " + agoShort(((now - reportedAt) / 1000L).toInt().coerceAtLeast(0))
}
