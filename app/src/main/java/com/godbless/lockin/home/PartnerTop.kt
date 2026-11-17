@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.godbless.lockin.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.Device
import com.godbless.lockin.data.PartnerApp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.usage.durationText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TopShape = RoundedCornerShape(28.dp)
private val PartCard = RoundedCornerShape(24.dp)

/**
 * The top of the Partner tab: one card you swipe. Page one is your partner (the rope, today's minutes for each
 * of you, message, nudge, lock). Page two is their phone today (their apps, tap one to limit it).
 */
@Composable
internal fun PartnerTopPager(
    youName: String,
    status: String,
    active: Boolean,
    usage: UsageState,
    lock: LockState,
    onMessage: () -> Unit,
    onNudge: () -> Unit,
    onOpenLock: () -> Unit,
    onPickApp: (PartnerApp) -> Unit,
    onAllApps: () -> Unit,
    /** The card with every app and the week is open below: the button then says so. */
    allAppsOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    // On a tablet both pages are simply shown, one under the other, sliding in one after the other: the second page
    // was a swipe and two small dots away on a screen with room for it.
    if (com.godbless.lockin.ui.isWide()) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (page in 0..1) {
                val slide = rememberSlideIn(true, page, key = "partner-top-$page")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (page == 0) 304.dp else 350.dp)
                        .graphicsLayer { translationX = slide.value * size.width * 0.3f }
                        .clip(TopShape)
                        .background(LockInColors.Slate)
                        .border(1.dp, LockInColors.Graphite, TopShape)
                        .padding(18.dp),
                ) {
                    if (page == 0) PartnerPage0(youName, status, active, lock, onMessage, onNudge, onOpenLock)
                    else UsagePage(usage, onPickApp, onAllApps, onMessage, allAppsOpen)
                }
            }
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            pageSpacing = 10.dp,
            // Page one is 304 tall and page two 350, as on a tablet. A single 350 left a big empty gap inside page one.
            // The card eases between the two as you swipe.
            modifier = Modifier.fillMaxWidth().height(
                androidx.compose.ui.unit.lerp(304.dp, 350.dp, (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)),
            ),
        ) { page ->
            // Pages tilt and shrink a touch as they slide away, so the swipe has depth.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).let { kotlin.math.abs(it) }.coerceIn(0f, 1f)
                        val sc = 1f - 0.06f * off
                        scaleX = sc
                        scaleY = sc
                        rotationY = 8f * ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                    }
                    .clip(TopShape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, TopShape)
                    .padding(18.dp),
            ) {
                if (page == 0) {
                    PartnerPage0(youName, status, active, lock, onMessage, onNudge, onOpenLock)
                } else {
                    UsagePage(usage, onPickApp, onAllApps, onMessage, allAppsOpen)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Two dots: the current one stretches into a lime pill.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (i in 0..1) {
                val on = pager.currentPage == i
                val w by animateDpAsState(if (on) 22.dp else 7.dp, spring(dampingRatio = 0.6f, stiffness = 400f), label = "dot")
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(7.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(if (on) LockInColors.Lime else LockInColors.Rim)
                        .springClick(role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } },
                )
            }
        }
    }
}

@Composable
private fun PartnerPage0(
    youName: String,
    status: String,
    active: Boolean,
    lock: LockState,
    onMessage: () -> Unit,
    onNudge: () -> Unit,
    onOpenLock: () -> Unit,
) {
    val name = LocalPartnerName.current
    val stats = LocalStats.current
    val goal = LocalGoalMinutes.current * 60f
    val theirGoal = LocalPartnerGoalMinutes.current * 60f
    // The rope tightens the first time this card appears in a run of the app; coming back it is already taut.
    val pull = remember { Animatable(if (HomeIntro.ropePulled) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (HomeIntro.ropePulled) return@LaunchedEffect
        HomeIntro.ropePulled = true
        delay(200)
        pull.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 120f))
    }
    val diff = (stats.todaySeconds - stats.partnerTodaySeconds) / 60
    val nobody = stats.todaySeconds == 0 && stats.partnerTodaySeconds == 0
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Canvas(Modifier.matchParentSize()) {
                // The rope grows out of each ring: it starts on the ring's own line, as thick and as light as the ring's
                // track, then thins and darkens along its length, so ring and rope read as one piece rather than two.
                val cy = 29.dp.toPx()
                val x0 = 56.5.dp.toPx() - 1.dp.toPx()
                val x1 = size.width - 56.5.dp.toPx() + 1.dp.toPx()
                val sag = (50.dp.toPx() - 40.dp.toPx() * pull.value).coerceAtLeast(2.dp.toPx())
                val mid = (x0 + x1) / 2f
                val steps = 48
                val left = ArrayList<Offset>(steps + 1)
                val right = ArrayList<Offset>(steps + 1)
                for (k in 0..steps) {
                    val t = k / steps.toFloat()
                    val u = 1f - t
                    // Quadratic bezier: ends on the rings, lowest point at (mid, cy + sag).
                    val px = u * u * x0 + 2f * u * t * mid + t * t * x1
                    val py = u * u * cy + 2f * u * t * (cy + sag * 2f) + t * t * cy
                    val dx = 2f * u * (mid - x0) + 2f * t * (x1 - mid)
                    val dy = 2f * u * (cy + sag * 2f - cy) + 2f * t * (cy - (cy + sag * 2f))
                    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    val e = kotlin.math.abs(2f * t - 1f)
                    val half = (0.9f + 0.6f * e * e * e).dp.toPx()
                    left.add(Offset(px + dy / len * half, py - dx / len * half))
                    right.add(Offset(px - dy / len * half, py + dx / len * half))
                }
                val rope = Path().apply {
                    moveTo(left[0].x, left[0].y)
                    for (k in 1..steps) lineTo(left[k].x, left[k].y)
                    for (k in steps downTo 0) lineTo(right[k].x, right[k].y)
                    close()
                }
                val track = LockInColors.Graphite
                val rope0 = LockInColors.Edge
                drawPath(
                    rope,
                    Brush.horizontalGradient(
                        0f to track, 0.2f to rope0, 0.8f to rope0, 1f to track,
                        startX = x0, endX = x1,
                    ),
                )
                drawCircle(LockInColors.Slate, radius = 8.dp.toPx(), center = Offset(mid, cy + sag))
                drawCircle(LockInColors.Lime, radius = 5.dp.toPx(), center = Offset(mid, cy + sag))
            }
            RingAvatar(youName.trim().substringBefore(' ').ifEmpty { "You" }, stats.todaySeconds / goal.coerceAtLeast(1f), false, null, "You · ${heroMinutes(stats.todaySeconds / 60)}", Modifier.align(Alignment.TopStart))
            RingAvatar(name, stats.partnerTodaySeconds / theirGoal.coerceAtLeast(1f), true, active, heroMinutes(stats.partnerTodaySeconds / 60), Modifier.align(Alignment.TopEnd))
        }
        Text(name, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "$status · " + when {
                diff > 0 -> "you're $diff min ahead today"
                diff < 0 -> "they're ${-diff} min ahead today"
                nobody -> "nobody has started yet"
                else -> "level today"
            },
            style = LockInText.Small.copy(color = LockInColors.Body),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        LastLockLine(lock)
        Spacer(Modifier.height(12.dp))
        // Locking is the main action here: it leads the row, in lime, and gets the longer half. Messaging is the
        // quieter outlined button beside it. Nudging lives on Home (tap them).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LockLine(lock, onOpenLock, Modifier.weight(1.8f), compact = true, filled = true)
            Spacer(Modifier.width(10.dp))
            LockInButton("Message", onClick = onMessage, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
        }
    }
}

/** An avatar inside a ring that fills with today's progress toward that person's goal. */
@Composable
private fun RingAvatar(letter: String, fraction: Float, filled: Boolean, online: Boolean?, caption: String, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 100f), label = "ring")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val sw = 3.dp.toPx()
                val tl = Offset(sw / 2f, sw / 2f)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(LockInColors.Graphite, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (fill > 0f) drawArc(LockInColors.Lime, -90f, 360f * fill, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Avatar(letter, boxSize = 46.dp, filled = filled, ring = LockInColors.Slate)
            if (online != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(if (online) LockInColors.Lime else LockInColors.Edge),
                )
            }
        }
        Text(caption, style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
    }
}

/** A round nudge button. Its hand gives a little wave when pressed. */
@Composable
private fun NudgeButton(onNudge: () -> Unit) {
    val wave = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.5.dp, LockInColors.Rim, CircleShape)
            .springClick {
                onNudge()
                scope.launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, tween(700))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            Glyph.Bell,
            LockInColors.Lime,
            modifier = Modifier.graphicsLayer { rotationZ = 22f * kotlin.math.sin(wave.value * 4f * Math.PI).toFloat() * (1f - wave.value) },
            boxSize = 20.dp,
            strokeWidth = 2f,
        )
    }
}

/** Page two: their phone today, the top apps with bars that grow in, tap one to limit it. */
@Composable
private fun UsagePage(usage: UsageState, onPickApp: (PartnerApp) -> Unit, onAllApps: () -> Unit, onMessage: () -> Unit, allAppsOpen: Boolean) {
    val name = LocalPartnerName.current
    val data = usage.partner
    Column(Modifier.fillMaxSize()) {
        Text("$name's phone today", style = LockInText.Label.copy(color = LockInColors.Silver))
        when {
            data == null -> Text("Looking at $name's phone…", style = LockInText.Small.copy(color = LockInColors.Body), modifier = Modifier.padding(top = 8.dp))
            data.status == "off" -> PageNote("$name stopped sharing app times.", "Their apps show here again when they turn sharing back on.")
            data.status == "no_access" -> {
                PageNote("$name hasn't allowed app times yet.", "Their phone needs \"usage access\" switched on for LockIn.")
                Spacer(Modifier.height(12.dp))
                SmallPill("Message $name", onMessage)
            }
            data.status == "waiting" -> PageNote("Waiting for $name's phone.", "The first update can take a few minutes.")
            else -> {
                val today = data.today
                val apps = today?.apps.orEmpty().sortedByDescending { it.seconds }.take(4)
                val top = (apps.firstOrNull()?.seconds ?: 1).coerceAtLeast(1)
                Text(formatMinutes((today?.total ?: 0) / 60), style = LockInText.Headline.copy(fontSize = 36.sp, lineHeight = 42.sp))
                Spacer(Modifier.height(8.dp))
                val grow = remember { Animatable(if (HomeIntro.usageGrown) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (HomeIntro.usageGrown) return@LaunchedEffect
                    HomeIntro.usageGrown = true
                    grow.animateTo(1f, tween(800, easing = UiGlide))
                }
                if (apps.isEmpty()) {
                    Text("Nothing used yet today.", style = LockInText.Small.copy(color = LockInColors.Body))
                }
                apps.forEach { app ->
                    val limited = data.limits.any { it.pkg == app.pkg }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .springClick(enabled = app.limitable) { onPickApp(app) }
                            .padding(vertical = 5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (limited) Text("Limited", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 11.sp), modifier = Modifier.padding(end = 8.dp))
                            Text(durationText(app.seconds), style = LockInText.Small.copy(color = LockInColors.Mist))
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(LockInColors.Graphite)) {
                            Box(Modifier.fillMaxWidth((app.seconds.toFloat() / top) * grow.value).height(5.dp).clip(CircleShape).background(if (limited) LockInColors.Lime else LockInColors.Frost))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Tap an app to limit it.", style = LockInText.Small.copy(fontSize = 11.sp))
                Spacer(Modifier.height(6.dp))
                SmallPill(if (allAppsOpen) "Hide all apps" else "All apps and the week", onAllApps)
            }
        }
    }
}

@Composable
private fun PageNote(title: String, body: String) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = LockInText.Title.copy(fontSize = 19.sp, lineHeight = 24.sp))
    Spacer(Modifier.height(4.dp))
    Text(body, style = LockInText.Small.copy(color = LockInColors.Body))
}

// ---------------------------------------------------------------------------------------------
// Their devices
// ---------------------------------------------------------------------------------------------

/**
 * Every phone your partner uses LockIn on. A lock is on the person, so it covers all of them at once; while
 * one runs, each device shows whether it is holding the lock (checked in) or has gone quiet.
 */
@Composable
internal fun DevicesCard(devices: List<Device>, lockOn: Boolean, onLockAll: () -> Unit, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(PartCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, PartCard),
    ) {
        if (devices.isEmpty()) {
            Text(
                "$name's devices show here once they open the latest LockIn.",
                style = LockInText.Small.copy(color = LockInColors.Body),
                modifier = Modifier.padding(18.dp),
            )
            return@Column
        }
        devices.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp).height(1.dp).background(LockInColors.Graphite))
            DeviceRow(d, lockOn)
        }
        if (lockOn || devices.size > 1) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 6.dp)) {
            // One device: the big Lock button on the partner card above already does it, so no second copy here.
            if (lockOn) {
                val holding = devices.count { it.beatAgo in 0..59 }
                Text(
                    "$holding of ${devices.size} holding the lock. A device that's off locks the moment it's back.",
                    style = LockInText.Small.copy(color = LockInColors.Body),
                )
            } else {
                LockInButton("Lock all ${devices.size} devices", onClick = onLockAll)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, lockOn: Boolean) {
    val holding = lockOn && d.beatAgo in 0..59
    val quiet = lockOn && !holding
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (holding) LockInColors.LimeWash else LockInColors.Graphite),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (lockOn) Glyph.Lock else Glyph.Person, if (holding) LockInColors.Lime else if (quiet) LockInColors.Flare else LockInColors.Silver, boxSize = 17.dp, strokeWidth = 2f)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(d.name, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    holding -> "Locked · checked in ${agoShort(d.beatAgo)}" + if (d.escapes > 0) " · tried to leave ${d.escapes}×" else ""
                    quiet && d.beatAgo < 0 -> "Not checked in since the lock began"
                    quiet -> "Quiet for ${agoShort(d.beatAgo)} · locks when it's back"
                    else -> listOf(d.model.takeIf { it.isNotBlank() && it != d.name }, d.os.takeIf { it.isNotBlank() }, "seen ${agoShort(d.seenAgo)}").filterNotNull().joinToString(" · ")
                },
                style = LockInText.Small.copy(color = if (quiet) LockInColors.Flare else LockInColors.Mist),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun agoShort(seconds: Int): String = when {
    seconds < 0 -> "never"
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86_400 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86_400} d ago"
}

// ---------------------------------------------------------------------------------------------
// Together, in one card
// ---------------------------------------------------------------------------------------------

@Composable
internal fun TogetherCard(modifier: Modifier = Modifier) {
    val stats = LocalStats.current
    val last = stats.activity.firstOrNull()
    Column(
        modifier
            .fillMaxWidth()
            .clip(PartCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, PartCard)
            .padding(18.dp),
    ) {
        Text("Together", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Figure(stats.sessionsTogether, "sessions") { "$it" }
            Figure((stats.hoursTogether * 60).toInt(), "focused") { formatMinutes(it) }
            Figure(stats.bestTogetherStreak, "best streak") { if (it == 1) "1 day" else "$it days" }
        }
        if (last != null) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
            Spacer(Modifier.height(10.dp))
            Text("${last.text} · ${last.whenText}", style = LockInText.Small.copy(color = LockInColors.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Figure(value: Int, label: String, format: (Int) -> String) {
    // The numbers climb to their value when the card appears, and again whenever they change.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(value) {
        delay(250)
        shown.animateTo(value.toFloat(), tween(900, easing = UiGlide))
    }
    Column {
        Text(format(shown.value.toInt()), style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 24.sp))
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp))
    }
}

// ---------------------------------------------------------------------------------------------
// Your own devices
// ---------------------------------------------------------------------------------------------

@Composable
internal fun MyDevicesList(devices: DevicesState, modifier: Modifier = Modifier) {
    if (devices.mine.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text("Your devices", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(6.dp))
        devices.mine.forEach { d ->
            val here = d.id == devices.thisId
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name + if (here) " (this phone)" else "", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.os} · LockIn ${d.app} · seen ${agoShort(d.seenAgo)}", style = LockInText.Small.copy(fontSize = 11.sp), maxLines = 1)
                }
                if (!here) SmallPill("Remove", onClick = { devices.forget(d.id) })
            }
        }
        Text("Your partner sees these. A lock covers every one of them.", style = LockInText.Small.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
    }
}


/**
 * The last lock you set, how it ended, and how often it ended in an emergency release this week. The chat tells this
 * story over many lines; here it is one. Slides in from the right when the card appears.
 */
@Composable
private fun LastLockLine(lock: LockState) {
    val mine = lock.history.filter { it.mine && !it.canceled }
    val last = mine.maxByOrNull { it.startedAt } ?: return
    val weekAgo = System.currentTimeMillis() - 7L * 86_400_000L
    val emergencies = mine.count { it.endedBy == "emergency" && it.startedAt >= weekAgo }
    val ended = when (last.endedBy) {
        "time" -> "ran its full time"
        "locker" -> "you ended it"
        "emergency" -> "ended by emergency release"
        "unpaired" -> "ended when you unpaired"
        else -> "ended"
    }
    val slide = rememberSlideIn(true, 2, key = "partner-lastlock")
    Column(Modifier.fillMaxWidth().graphicsLayer { translationX = slide.value * size.width * 0.25f }) {
        Text(
            "Last lock: ${formatMinutes(maxOf(last.seconds / 60, 1))}, $ended",
            style = LockInText.Small.copy(color = LockInColors.Body),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (emergencies >= 2) {
            Text(
                "$emergencies emergency releases this week",
                style = LockInText.Small.copy(color = LockInColors.Flare),
                maxLines = 1,
            )
        }
    }
}
