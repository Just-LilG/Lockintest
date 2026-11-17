@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import com.godbless.lockin.ui.firstGlyph
import com.godbless.lockin.ui.handoffSwap
import com.godbless.lockin.ui.calmFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.LockRecord
import com.godbless.lockin.data.TheirLock
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rise
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(24.dp)
private val InnerShape = RoundedCornerShape(18.dp)

private val Presets = listOf(25, 45, 60, 120, 180)
private const val StepMinutes = 5
private val StartOffsets = listOf("Now" to 0, "30m" to 30, "1h" to 60, "3h" to 180, "6h" to 360)
private val RuleCaps = listOf(60, 120, 240, 480)

/** [untilStart] is the wait before a scheduled lock begins (0 once it runs); [left] is the time to its end, both live. */
internal class LockClock(val untilStart: Int, val left: Int)

@Composable
internal fun rememberLockClock(lock: LockState): LockClock {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lock.theirs?.id) {
        while (lock.theirs != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (lock.theirs == null) return LockClock(0, 0)
    val at = maxOf(now, System.currentTimeMillis())
    return LockClock(
        untilStart = ((lock.theirsStartsAt - at) / 1000L).toInt().coerceAtLeast(0),
        left = ((lock.theirsEndsAt - at) / 1000L).toInt().coerceAtLeast(0),
    )
}

// ---------------------------------------------------------------------------------------------
// The entry card, on Home and on the Partner tab
// ---------------------------------------------------------------------------------------------

/**
 * The way in to locking your partner. Bright while nothing runs so it can't be missed; while a lock runs it
 * shows the countdown and anything that needs you (an unlock request).
 */
@Composable
internal fun LockEntryCard(lock: LockState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val clock = rememberLockClock(lock)
    val left = clock.left
    val t = lock.theirs
    val active = t != null && left > 0
    val waiting = active && clock.untilStart > 0
    val asked = active && !waiting && t != null && t.asked
    val bright = !active
    val fg = if (bright) LockInColors.OnLime else LockInColors.Snow
    val sub = if (bright) LockInColors.OnLime else LockInColors.Mist
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (bright) LockInColors.Lime else LockInColors.Slate)
            .border(if (asked) 2.dp else 1.dp, if (bright) LockInColors.Lime else if (asked) LockInColors.Lime else LockInColors.Graphite, CardShape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (bright) LockInColors.OnLime else LockInColors.Graphite),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Lock, if (bright) LockInColors.Lime else LockInColors.Lime, boxSize = 22.dp, strokeWidth = 2f)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                when {
                    asked -> "$name asked to be unlocked"
                    waiting -> if (name.any { it.isLetterOrDigit() }) "$name's lock is set" else "Lock is set"
                    active -> "$name is locked"
                    else -> "Lock ${partnerPossessive(name)} phone"
                },
                style = LockInText.Title.copy(fontSize = 19.sp, lineHeight = 24.sp, color = fg),
            )
            Text(
                when {
                    waiting -> "Starts in ${formatClock(clock.untilStart)}"
                    active && t != null -> "${formatClock(left)} left" + if (t.pickups > 0) ", ${t.pickups} pickup${if (t.pickups == 1) "" else "s"}" else ""
                    else -> "Set the time, add a message, and the rope pulls tight."
                },
                style = LockInText.Small.copy(color = sub),
            )
        }
        GlyphIcon(Glyph.Chevron, sub, boxSize = 18.dp, strokeWidth = 2f)
    }
}

/** What the lock line says, and which kind of state that is (the kind decides when the words slide). */
internal class LockLineText(val kind: Int, val text: String)

/**
 * The way into locking your partner, as one line under Start on Home. It shows the live state of a lock
 * you set: waiting to start, time left, or that they asked to be let out (then it glows lime).
 */
@Composable
internal fun LockLine(lock: LockState, onOpen: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, filled: Boolean = false) {
    val name = LocalPartnerName.current
    val clock = rememberLockClock(lock)
    val left = clock.left
    val t = lock.theirs
    val active = t != null && left > 0
    val waiting = active && clock.untilStart > 0
    val asked = active && !waiting && t != null && t.asked
    val shape = CircleShape
    Row(
        modifier
            .fillMaxWidth()
            .height(if (compact) 56.dp else 48.dp)
            .clip(shape)
            // Filled is the lime main-action look (the Partner tab); the default is the quiet outline.
            .background(if (filled) LockInColors.Lime else androidx.compose.ui.graphics.Color.Transparent)
            .border(if (asked) 2.dp else 1.dp, if (filled || asked) LockInColors.Lime else LockInColors.Rim, shape)
            .springClick(onClick = onOpen)
            .padding(start = 6.dp, end = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The padlock rattles every few seconds while nothing is set, inviting a tap. While a lock runs, a ring
        // around it drains as the time runs out.
        val clockT by androidx.compose.animation.core.rememberInfiniteTransition(label = "lock-line").calmFloat(
            0f, 5f,
            androidx.compose.animation.core.infiniteRepeatable(tween(5000, easing = androidx.compose.animation.core.LinearEasing)),
            label = "t",
        )
        val total = (t?.seconds ?: 0).coerceAtLeast(1)
        val ring by androidx.compose.animation.core.animateFloatAsState(
            if (active && !waiting) (left.toFloat() / total).coerceIn(0f, 1f) else 0f,
            androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 120f),
            label = "ring",
        )
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (ring > 0f) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val sw = 2.5.dp.toPx()
                    drawArc(
                        if (filled) LockInColors.OnLime else LockInColors.Lime, -90f, 360f * ring, false,
                        topLeft = androidx.compose.ui.geometry.Offset(sw / 2f, sw / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            Box(
                Modifier
                    .size(if (ring > 0f) 30.dp else 36.dp)
                    .graphicsLayer {
                        if (!active) {
                            // A quick shake at the start of each 5 s loop: a decaying wobble.
                            val k = clockT.coerceAtMost(0.7f) / 0.7f
                            rotationZ = if (clockT < 0.7f) 14f * kotlin.math.sin(k * 6f * Math.PI).toFloat() * (1f - k) else 0f
                        }
                    }
                    .clip(CircleShape)
                    .background(if (filled) LockInColors.OnLime else if (active) LockInColors.Lime else LockInColors.Graphite),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Lock, if (filled) LockInColors.Lime else if (active) LockInColors.OnLime else LockInColors.Lime, boxSize = 17.dp, strokeWidth = 2.2f)
            }
        }
        // Keyed on the kind of state, so the wording slides when it changes but the ticking clock updates in place.
        val kind = when { asked -> 3; waiting -> 2; active -> 1; else -> 0 }
        val line = when (kind) {
            3 -> "$name asked to be unlocked"
            2 -> (if (name.any { it.isLetterOrDigit() }) "$name's lock" else "Their lock") + " starts in ${formatClock(clock.untilStart)}"
            1 -> "$name is locked · ${formatClock(left)} left"
            else -> "Lock ${partnerPossessive(name)} phone"
        }
        AnimatedContent(
            targetState = LockLineText(kind, line),
            contentKey = { it.kind },
            modifier = Modifier.weight(1f).padding(start = if (compact) 10.dp else 12.dp).clipToBounds(),
            transitionSpec = {
                (slideInVertically(tween(320, easing = UiGlide)) { it } togetherWith slideOutVertically(tween(320, easing = UiGlide)) { -it }).using(null)
            },
            label = "lock-line",
        ) { state ->
            Text(state.text, style = LockInText.Label.copy(color = if (filled) LockInColors.OnLime else LockInColors.Snow, fontSize = if (compact) 14.sp else 15.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (!compact) GlyphIcon(Glyph.Chevron, if (filled) LockInColors.OnLime else LockInColors.Mist, boxSize = 16.dp, strokeWidth = 2f)
    }
}

// ---------------------------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------------------------

/** Everything about locking your partner: set one, watch it, and look back at the ones before. */
@Composable
internal fun LockPartnerPage(lock: LockState, usage: UsageState, onBack: () -> Unit, onMessage: () -> Unit, onTogether: () -> Unit) {
    val name = LocalPartnerName.current
    LaunchedEffect(Unit) {
        lock.loadHistory()
        lock.loadRules()
        lock.loadRepeats()
    }
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        PageScaffold(wide = true) { entrance ->
            val clock = rememberLockClock(lock)
            val left = clock.left
            val theirs = lock.theirs
            val active = theirs != null && left > 0
            // 0: nothing set, 1: set for later and waiting, 2: running.
            val stage = if (!active) 0 else if (clock.untilStart > 0) 1 else 2

            Row(Modifier.fillMaxWidth().rise(entrance, 0)) { BackButton(onBack) }
            Spacer(Modifier.height(14.dp))
            PageTitle(
                "Lock $name",
                when (stage) {
                    1 -> "The lock is set for later."
                    2 -> "$name is locked in."
                    else -> "Pick a time and say why."
                },
                entrance,
            )
            Spacer(Modifier.height(20.dp))

            // On a tablet: the lock itself on the left; repeating, history and rules on the right.
            TwoColumns(leftWeight = 1f, left = {
            AnimatedContent(
                targetState = stage,
                modifier = Modifier.fillMaxWidth().clipToBounds().rise(entrance, 1),
                transitionSpec = { handoffSwap(ms = 320) },
                label = "lock-page-top",
            ) { shownStage ->
                val t = lock.theirs
                when {
                    shownStage == 2 && t != null -> LiveCard(lock, t, left, onMessage)
                    shownStage == 1 && t != null -> ScheduledCard(lock, t, clock.untilStart, onMessage)
                    else -> Composer(lock, onTogether)
                }
            }
            Spacer(Modifier.height(24.dp))
            }, right = {
            // Everything else sits behind tabs, one at a time, so the page stays short.
            var tab by rememberSaveable { mutableStateOf(0) }
            val repeatCount = lock.repeats.count { it.mine }
            LockTabs(
                listOf(if (repeatCount > 0) "Repeating · $repeatCount" else "Repeating", "History", "Rules"),
                tab,
                { tab = it },
                Modifier.rise(entrance, 2),
            )
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxWidth().rise(entrance, 3),
                transitionSpec = {
                    // Slides the way you moved: right for a tab to the right, left for one to the left.
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(340, easing = UiGlide)) { it * dir } togetherWith
                        slideOutHorizontally(tween(340, easing = UiGlide)) { -it * dir })
                        .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.85f, stiffness = 380f) })
                },
                label = "lock-tabs",
            ) { shown ->
                when (shown) {
                    0 -> RepeatsSection(lock)
                    1 -> HistorySection(lock.history)
                    else -> Column {
                        AllowedAppsCard(lock, usage)
                        Spacer(Modifier.height(12.dp))
                        RulesCard(lock)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Setting a lock
// ---------------------------------------------------------------------------------------------

@Composable
private fun Composer(lock: LockState, onTogether: () -> Unit, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val theirRule = lock.rules?.theirs
    val capMinutes = ((theirRule?.max ?: 28800) / 60).coerceIn(5, 480)
    val allowed = theirRule?.allowed != false
    // What you picked last time comes back: how long, "No unlock requests" and "Lock together". The message and the
    // start time are different every time, so they begin empty and "Now".
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("lock_choices", android.content.Context.MODE_PRIVATE)
    var minutes by rememberSaveable { mutableStateOf(prefs.getInt("minutes", 45)) }
    var message by rememberSaveable { mutableStateOf("") }
    var strict by rememberSaveable { mutableStateOf(prefs.getBoolean("strict", false)) }
    var together by rememberSaveable { mutableStateOf(prefs.getBoolean("together", false)) }
    var startIn by rememberSaveable { mutableStateOf(0) }
    // Which drawer is open under the two pills: 0 none, 1 when it starts, 2 options. One at a time.
    var drawer by rememberSaveable { mutableStateOf(0) }
    val shown = minutes.coerceIn(5, capMinutes)

    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepButton(Glyph.Minus, enabled = shown > 5) { minutes = (shown - StepMinutes).coerceAtLeast(5) }
            // The number rolls up or down as it changes.
            AnimatedContent(
                targetState = shown,
                modifier = Modifier.weight(1f).clipToBounds(),
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(dampingRatio = 0.75f, stiffness = 500f)) { if (up) it else -it } togetherWith
                        slideOutVertically(spring(dampingRatio = 0.75f, stiffness = 500f)) { if (up) -it else it })
                        .using(null)
                },
                contentAlignment = Alignment.Center,
                label = "minutes",
            ) { m ->
                Text(
                    formatMinutes(m),
                    style = LockInText.Headline.copy(fontSize = 44.sp, lineHeight = 50.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            StepButton(Glyph.Plus, enabled = shown < capMinutes) { minutes = (shown + StepMinutes).coerceAtMost(capMinutes) }
        }
        Spacer(Modifier.height(14.dp))
        ChoiceRow(Presets.filter { it <= capMinutes }.map { formatMinutes(it) to it }, shown) { minutes = it }

        Spacer(Modifier.height(16.dp))
        LockInField(
            label = "",
            value = message,
            onValueChange = { message = it.take(140) },
            placeholder = "Say why. $name sees it when locked",
        )

        Spacer(Modifier.height(12.dp))
        val startLabel = StartOffsets.firstOrNull { it.second == startIn }?.first ?: "Now"
        val optionCount = (if (strict) 1 else 0) + (if (together && startIn == 0) 1 else 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerPill(
                if (startIn == 0) "Starts now" else "Starts in $startLabel",
                open = drawer == 1,
                set = startIn > 0,
                modifier = Modifier.weight(1f),
            ) { drawer = if (drawer == 1) 0 else 1 }
            DrawerPill(
                if (optionCount == 0) "Options" else "Options · $optionCount",
                open = drawer == 2,
                set = optionCount > 0,
                modifier = Modifier.weight(1f),
            ) { drawer = if (drawer == 2) 0 else 2 }
        }

        Collapse(drawer == 1) {
            Column {
                Spacer(Modifier.height(12.dp))
                ChoiceRow(StartOffsets, startIn) {
                    startIn = it
                    if (it > 0) together = false
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (startIn > 0) "$name's phone locks ${planWhen(System.currentTimeMillis() + startIn * 60_000L).lowercase()}. They get a heads-up now."
                    else "$name's phone locks the moment you tap the button.",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
        }
        Collapse(drawer == 2) {
            Column {
                Spacer(Modifier.height(14.dp))
                ToggleRow("No unlock requests", strict, { strict = it })
                Text(
                    if (strict) "$name can't ask to be let out. Only you or the timer can end the lock."
                    else "$name can ask to be unlocked, and you decide.",
                    style = LockInText.Small.copy(color = LockInColors.Body),
                )
                if (startIn == 0) {
                    Spacer(Modifier.height(10.dp))
                    ToggleRow("Lock together", together, { together = it })
                    Text(
                        if (together) "Your own focus session starts at the same moment."
                        else "Only $name's phone locks.",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                    )
                }
            }
        }

        if (theirRule != null && theirRule.max < 28800) {
            Spacer(Modifier.height(10.dp))
            Text("$name accepts locks up to ${formatMinutes(theirRule.max / 60)}.", style = LockInText.Small.copy(color = LockInColors.Mist))
        }
        if (!allowed) {
            Spacer(Modifier.height(14.dp))
            Text("$name has switched locks off for now. They can turn them back on in Rules on their phone.", style = LockInText.Error)
        }
        lock.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }
        Spacer(Modifier.height(16.dp))
        LockInButton(
            if (startIn > 0) "Lock $name in $startLabel" else "Lock $name for ${formatMinutes(shown)}",
            onClick = {
                prefs.edit().putInt("minutes", shown).putBoolean("strict", strict).putBoolean("together", together).apply()
                lock.lock(shown, message, strict, together && startIn == 0, startIn) { if (together && startIn == 0) onTogether() }
            },
            loading = lock.busy,
            enabled = allowed,
        )
    }
}

/** A row of equal chips; the picked one fills lime with a small spring pop. */
@Composable
private fun ChoiceRow(options: List<Pair<String, Int>>, picked: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val on = value == picked
            val pop by androidx.compose.animation.core.animateFloatAsState(
                if (on) 1f else 0f,
                spring(dampingRatio = 0.5f, stiffness = 500f),
                label = "chip",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .graphicsLayer { val k = 1f + 0.12f * pop * (1f - pop); scaleX = k; scaleY = k }
                    .clip(ControlShape)
                    .background(if (on) LockInColors.Lime else LockInColors.Slate)
                    .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
                    .clickable(role = Role.Tab) { onPick(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp))
            }
        }
    }
}

/** A pill that opens a drawer below it. The chevron turns to show it's open; a lime rim means it's been changed. */
@Composable
private fun DrawerPill(label: String, open: Boolean, set: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val turn by androidx.compose.animation.core.animateFloatAsState(
        if (open) -90f else 90f,
        spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "chevron",
    )
    Row(
        modifier
            .height(42.dp)
            .clip(ControlShape)
            .background(if (set) LockInColors.LimeWash else LockInColors.Slate)
            .border(1.5.dp, if (open || set) LockInColors.Lime else LockInColors.Rim, ControlShape)
            .springClick(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        GlyphIcon(Glyph.Chevron, LockInColors.Mist, Modifier.graphicsLayer { rotationZ = turn }, boxSize = 14.dp, strokeWidth = 2f)
    }
}

/** Three tabs on one track. The lime pill springs across to the one you pick. */
@Composable
private fun LockTabs(labels: List<String>, picked: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CircleShape)
            .padding(4.dp),
    ) {
        val each = maxWidth / labels.size
        val x by androidx.compose.animation.core.animateDpAsState(
            each * picked,
            spring(dampingRatio = 0.72f, stiffness = 420f),
            label = "tab",
        )
        Box(
            Modifier
                // An offset, not padding: the spring overshoots a little past the first tab, and padding can't go negative.
                .offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }
                .size(width = each, height = maxHeight)
                .clip(CircleShape)
                .background(LockInColors.Lime),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = i == picked
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Silver, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) LockInColors.Rim else LockInColors.Graphite, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (enabled) LockInColors.Snow else LockInColors.Edge, boxSize = 20.dp, strokeWidth = 2f)
    }
}

// ---------------------------------------------------------------------------------------------
// Watching a lock
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(lock: LockState, t: TheirLock, left: Int, onMessage: () -> Unit, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val progress = if (t.seconds > 0) (1f - left.toFloat() / t.seconds).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(32.dp).clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Lock, LockInColors.OnLime, boxSize = 14.dp, strokeWidth = 2.2f)
                Text("Locked", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 13.sp), modifier = Modifier.padding(start = 7.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Set for ${formatMinutes(t.seconds / 60)}" + if (t.extended > 0) ", +${t.extended / 60}m added" else "",
                style = LockInText.Small.copy(color = LockInColors.Mist),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(formatClock(left), style = LockInText.Headline.copy(fontSize = 60.sp, lineHeight = 66.sp))
        Text("left", style = LockInText.Sub.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(LockInColors.Graphite, size = size, cornerRadius = r)
            val w = size.width * progress
            if (w > 0f) drawRoundRect(LockInColors.Lime, size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
        }

        if (t.message.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("You told $name", style = LockInText.Small.copy(color = LockInColors.Mist))
            Text("“${t.message}”", style = LockInText.Sub.copy(color = LockInColors.Snow))
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip(if (t.pickups == 0) "No pickups yet" else "${t.pickups} pickup${if (t.pickups == 1) "" else "s"}", strong = t.pickups == 0)
            if (t.escapes > 0) InfoChip(if (t.escapes == 1) "Tried to leave once" else "Tried to leave ${t.escapes} times")
            if (t.strict) InfoChip("No unlock requests")
            if (t.together) InfoChip("Locked together")
        }

        // Their phone checks in every 15 s while the lock runs. If it goes quiet, it was switched off, lost its
        // signal, or LockIn was stopped: say so plainly, so a phone can't drop out of the lock unnoticed.
        val quiet = t.beatAgo >= 60
        Collapse(quiet) {
            Column {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(InnerShape)
                        .border(1.5.dp, LockInColors.Flare, InnerShape)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(LockInColors.Flare))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            "$name's phone hasn't checked in for ${formatMinutes(maxOf(1, t.beatAgo / 60))}",
                            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            "It may be off or offline, or LockIn was stopped. The lock picks up again the moment it's back, and you'll hear if it was stopped.",
                            style = LockInText.Small.copy(color = LockInColors.Body),
                        )
                    }
                }
            }
        }

        if (t.asked) {
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(InnerShape)
                    .border(1.5.dp, LockInColors.Lime, InnerShape)
                    .padding(14.dp),
            ) {
                Text("$name asked to be unlocked", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                if (t.reason.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("“${t.reason}”", style = LockInText.Small.copy(color = LockInColors.Body))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LockInButton("Unlock", onClick = { lock.answer(true) }, modifier = Modifier.weight(1f), loading = lock.busy)
                    LockInButton("Not yet", onClick = { lock.answer(false) }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline, enabled = !lock.busy)
                }
            }
        } else if (t.answer == "denied") {
            Spacer(Modifier.height(12.dp))
            Text("You said not yet. $name can ask again in a few minutes.", style = LockInText.Small.copy(color = LockInColors.Mist))
        }

        lock.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("+15 min", { lock.extend(15) }, Modifier.weight(1f))
            SmallPill("+30 min", { lock.extend(30) }, Modifier.weight(1f))
            SmallPill("Message", onMessage, Modifier.weight(1f))
        }
        // A break for both of you: tic-tac-toe, played while the lock holds.
        val ttt = LocalTtt.current
        if (ttt != null) {
            var playing by remember { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            SmallPill(if (playing) "Close the game" else "Play tic-tac-toe with $name", { playing = !playing }, Modifier.fillMaxWidth())
            Collapse(playing) {
                Column(Modifier.padding(top = 16.dp)) {
                    com.godbless.lockin.lock.TicTacToe(ttt, name, onBack = { playing = false })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LockInButton("Unlock $name early", onClick = lock::unlock, kind = ButtonKind.Outline, loading = lock.busy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledCard(lock: LockState, t: TheirLock, untilStart: Int, onMessage: () -> Unit, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        Row(
            Modifier.height(32.dp).clip(CircleShape).background(LockInColors.Graphite).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Calendar, LockInColors.Silver, boxSize = 14.dp, strokeWidth = 2f)
            Text("Scheduled", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp), modifier = Modifier.padding(start = 7.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(formatClock(untilStart), style = LockInText.Headline.copy(fontSize = 60.sp, lineHeight = 66.sp))
        Text(
            "until $name's phone locks, " + planWhen(lock.theirsStartsAt).lowercase(),
            style = LockInText.Sub.copy(color = LockInColors.Silver),
        )
        Spacer(Modifier.height(8.dp))
        Text("Then it holds for ${formatMinutes(t.seconds / 60)}.", style = LockInText.Small.copy(color = LockInColors.Mist))
        if (t.message.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text("\u201C${t.message}\u201D", style = LockInText.Sub.copy(color = LockInColors.Snow))
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.strict) InfoChip("No unlock requests")
        }
        lock.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Message", onClick = onMessage, modifier = Modifier.weight(1f))
            LockInButton("Cancel lock", onClick = lock::unlock, modifier = Modifier.weight(1f), kind = ButtonKind.Outline, loading = lock.busy)
        }
    }
}

@Composable
private fun InfoChip(text: String, strong: Boolean = false) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (strong) LockInColors.LimeWash else LockInColors.Graphite)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = LockInText.Small.copy(color = if (strong) LockInColors.Frost else LockInColors.Silver, fontSize = 13.sp))
    }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

@Composable
private fun HistorySection(records: List<LockRecord>, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    var all by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        val mine = records.filter { it.mine && !it.canceled }
        if (mine.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Glyph.Lock, mine.size.toFloat(), 0, "", "locks set", Modifier.weight(1f).fillMaxHeight())
                StatTile(Glyph.Timer, mine.sumOf { it.held } / 3600f, 1, "h", "hours held", Modifier.weight(1f).fillMaxHeight())
                StatTile(Glyph.Flame, mine.count { it.pickups == 0 }.toFloat(), 0, "", "no pickups", Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(14.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, CardShape),
        ) {
            if (records.isEmpty()) {
                Text(
                    "No locks yet. Each one you set, or $name sets on you, shows up here.",
                    style = LockInText.Sub.copy(color = LockInColors.Silver),
                    modifier = Modifier.padding(18.dp),
                )
            }
            val shown = if (all) records else records.take(5)
            shown.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LockInColors.Graphite))
                HistoryRow(r, name)
            }
            if (records.size > 5) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
                Box(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { all = !all }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (all) "Show less" else "Show all ${records.size}", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(r: LockRecord, name: String) {
    val who = if (r.mine) "You locked $name" else "$name locked you"
    val ended = if (r.canceled) "Canceled before it began" else when (r.endedBy) {
        "time" -> "Finished"
        "locker" -> "Unlocked early"
        "emergency" -> "Emergency release"
        "unpaired" -> "Ended when you unpaired"
        else -> "Ended"
    }
    val facts = buildList {
        if (r.canceled) {
            add("Set for ${formatMinutes(maxOf(r.seconds / 60, 1))}")
            add(ended)
        } else {
            add("Held ${duration(r.held)} of ${formatMinutes(maxOf(r.seconds / 60, 1))}")
            add(if (r.pickups == 0) "no pickups" else "${r.pickups} pickup${if (r.pickups == 1) "" else "s"}")
            add(ended)
            if (r.together) add("together")
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(who, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (r.startedAt > 0L) Text(planWhen(r.startedAt), style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 12.sp))
        }
        Text(facts.joinToString(", "), style = LockInText.Small.copy(color = LockInColors.Mist))
        if (r.message.isNotBlank()) {
            Text("“${r.message}”", style = LockInText.Small.copy(color = LockInColors.Body), maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rules
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RulesCard(lock: LockState, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val rules = lock.rules
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        if (rules == null) {
            Text("Loading your rules…", style = LockInText.Small.copy(color = LockInColors.Body))
            return@Column
        }
        Text("Locks on your phone", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(10.dp))
        ToggleRow("Let $name lock me", rules.mine.allowed, { lock.saveRules(it, rules.mine.max) })
        Text(
            if (rules.mine.allowed) "$name can lock your phone. You can ask to be let out, but only $name or the timer can end it."
            else "$name can't lock your phone until you switch this back on.",
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
        Collapse(rules.mine.allowed) {
            Column {
                Spacer(Modifier.height(16.dp))
                Text("Longest lock I accept", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleCaps.forEach { cap ->
                        val on = rules.mine.max / 60 == cap
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(ControlShape)
                                .background(if (on) LockInColors.Lime else LockInColors.Slate)
                                .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
                                .clickable(role = Role.Tab) { lock.saveRules(true, cap * 60) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                formatMinutes(cap),
                                style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (!rules.theirs.allowed) "$name has locks switched off."
            else "$name accepts locks up to ${formatMinutes(rules.theirs.max / 60)}.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
        )
        Spacer(Modifier.height(14.dp))
        RepeatsOnMe(lock)
    }
}


// ---------------------------------------------------------------------------------------------
// Allowed during locks
// ---------------------------------------------------------------------------------------------

/**
 * Up to 3 apps your partner can keep using while you have their phone locked (and the ones they allowed on
 * yours). You pick from the apps their phone shares, most used first. Each pick springs into its slot.
 */
@Composable
private fun AllowedAppsCard(lock: LockState, usage: UsageState, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val allowed = lock.allowed
    val theirs = allowed?.theirs.orEmpty()
    var picking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(picking) { if (picking && usage.partner == null) usage.refresh(true) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
            .padding(18.dp),
    ) {
        Text("Allowed during locks", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(4.dp))
        Text(
            "Up to ${com.godbless.lockin.data.MaxAllowedApps} apps $name can still open while you have their phone locked.",
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
        Spacer(Modifier.height(14.dp))
        // Three slots: the apps you allowed, then empty ones.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until com.godbless.lockin.data.MaxAllowedApps) {
                val app = theirs.getOrNull(i)
                androidx.compose.animation.AnimatedContent(
                    targetState = app,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 420f)) { it } togetherWith
                            slideOutVertically(tween(200, easing = UiGlide)) { -it })
                            .using(SizeTransform(clip = true))
                    },
                    label = "slot",
                ) { a ->
                    AllowedSlot(a, onRemove = { a?.let { lock.disallow(it.pkg) } }, onAdd = { picking = true })
                }
            }
        }
        lock.allowError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }

        Collapse(picking && theirs.size < com.godbless.lockin.data.MaxAllowedApps) {
            Column {
                Spacer(Modifier.height(16.dp))
                val partner = usage.partner
                // Their apps over the last week, most used first, without the ones already allowed.
                val apps = remember(partner, theirs) {
                    partner?.days.orEmpty()
                        .flatMap { it.apps }
                        .filter { it.limitable && theirs.none { t -> t.pkg == it.pkg } }
                        .groupBy { it.pkg }
                        .map { (pkg, list) -> Triple(pkg, list.first().label, list.sumOf { it.seconds }) }
                        .sortedByDescending { it.third }
                        .take(12)
                }
                when {
                    partner == null -> Text("Loading $name's apps…", style = LockInText.Small.copy(color = LockInColors.Mist))
                    apps.isEmpty() -> Text(
                        "$name's apps show up here once they share app usage (Partner tab, their side).",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                    else -> {
                        Text("Pick an app", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp))
                        Spacer(Modifier.height(8.dp))
                        apps.forEach { (pkg, label, secs) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(ControlShape)
                                    .clickable(role = Role.Button) {
                                        lock.allow(com.godbless.lockin.data.AllowedApp(pkg, label))
                                        if (theirs.size + 1 >= com.godbless.lockin.data.MaxAllowedApps) picking = false
                                    }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(LockInColors.Graphite), contentAlignment = Alignment.Center) {
                                    Text(label.firstGlyph(), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
                                }
                                Text(label, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                                Text(formatMinutes((secs / 60).coerceAtLeast(1)) + " this week", style = LockInText.Small.copy(color = LockInColors.Mist))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Done",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { picking = false }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }

        // The other way round: what your partner allows on your phone.
        val mine = allowed?.mine.orEmpty()
        Spacer(Modifier.height(14.dp))
        Text(
            if (mine.isEmpty()) "$name hasn't allowed any apps during your locks."
            else "During $name's locks you can still open ${mine.joinToString(", ") { it.label }}.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
        )
    }
}

@Composable
private fun AllowedSlot(app: com.godbless.lockin.data.AllowedApp?, onRemove: () -> Unit, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    if (app == null) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(shape)
                .border(1.5.dp, LockInColors.Rim, shape)
                .springClick(onClick = onAdd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlyphIcon(Glyph.Plus, LockInColors.Lime, boxSize = 20.dp, strokeWidth = 2.2f)
            Spacer(Modifier.height(6.dp))
            Text("Add app", style = LockInText.Small.copy(color = LockInColors.Silver))
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(shape)
            .background(LockInColors.LimeWash)
            .border(1.5.dp, LockInColors.Lime, shape),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(LockInColors.Lime), contentAlignment = Alignment.Center) {
                Text(app.label.firstGlyph(), style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 16.sp))
            }
            Spacer(Modifier.height(6.dp))
            Text(app.label, style = LockInText.Small.copy(color = LockInColors.Snow, fontSize = 12.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(LockInColors.Graphite)
                .clickable(role = Role.Button) { onRemove() }
                .semantics { contentDescription = "Remove ${app.label}" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Close, LockInColors.Snow, boxSize = 10.dp, strokeWidth = 2.2f)
        }
    }
}
