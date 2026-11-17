@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import com.godbless.lockin.ui.firstGlyph
import com.godbless.lockin.ui.safeTake
import com.godbless.lockin.ui.calmFloat
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.Crew
import com.godbless.lockin.data.CrewMember
import com.godbless.lockin.data.CrewSession
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rise
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val CardShape = RoundedCornerShape(24.dp)
private val SessionLengths = listOf(25, 45, 60, 90, 120)
private val SessionStarts = listOf("Now" to 0, "15m" to 15, "30m" to 30, "1h" to 60)

// =============================================================================================
// The tab
// =============================================================================================

/**
 * Crews: 3 to 8 friends on one rope. Before you're in one, the tab offers to start or join a crew; once you
 * are, it shows the rope (everyone's climber, higher for more hours this week), the crew session, the week's
 * board and the chat.
 */
@Composable
internal fun CrewTab(crew: CrewState, onOpenChat: () -> Unit) {
    PageScaffold(wide = true, key = "tab-crew") { entrance ->
        val c = crew.crew
        AnimatedContent(
            targetState = when {
                !crew.loaded && c == null && crew.failed -> 3
                !crew.loaded && c == null -> 0
                c == null -> 1
                else -> 2
            },
            transitionSpec = {
                (slideInVertically(tween(360, easing = UiGlide)) { it / 4 } togetherWith slideOutVertically(tween(260, easing = UiGlide)) { -it / 4 })
                    .using(SizeTransform(clip = true))
            },
            label = "crew-tab",
        ) { stage ->
            Column(Modifier.fillMaxWidth()) {
                when (stage) {
                    0 -> PageTitle("Crew", "Finding your crew…", entrance)
                    3 -> CrewUnavailable(crew, entrance)
                    1 -> NoCrew(crew, entrance)
                    else -> crew.crew?.let { CrewHome(crew, it, onOpenChat, entrance) }
                }
            }
        }
    }
}

// =============================================================================================
// No crew yet: start one or join one
// =============================================================================================

@Composable
private fun NoCrew(crew: CrewState, entrance: androidx.compose.runtime.State<Float>) {
    PageTitle("Crew", "Lock in together, 3 to 8 of you.", entrance)
    if (crew.removed) {
        Spacer(Modifier.height(12.dp))
        Text(
            "You're no longer in your last crew. Its owner removed you. You can join another one or start your own.",
            style = LockInText.Small.copy(color = LockInColors.Flare),
            modifier = Modifier.padding(horizontal = 4.dp).rise(entrance, 1),
        )
    }
    Spacer(Modifier.height(18.dp))
    TwoColumns(leftWeight = 1f, left = {
    // The rope, waiting for climbers: five dim ones sway on it.
    CrewRope(
        members = listOf("A", "K", "E", "Y", "S").mapIndexed { i, n -> CrewMember("ghost$i", n, "message", 0, 0) },
        me = null,
        session = null,
        ghost = true,
        modifier = Modifier.rise(entrance, 1),
    )
    Spacer(Modifier.height(18.dp))
    Column(Modifier.rise(entrance, 2).padding(horizontal = 4.dp)) {
        Point("Start a session, and whoever taps \"I'm in\" gets locked with you.")
        Point("A crew chat where each message rings with its sender's own sound.")
        Point("A board of who held the most hours this week, a weekly goal, and a streak the whole crew keeps alive.")
    }
    Spacer(Modifier.height(20.dp))
    }, right = {

    var mode by rememberSaveable { mutableStateOf(0) } // 0 start, 1 join
    Column(
        Modifier
            .fillMaxWidth()
            .rise(entrance, 3)
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(16.dp),
    ) {
        Segmented(listOf("Start a crew", "Join with a code"), mode) { mode = it; crew.error = null }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320, easing = UiGlide)) { it * dir } togetherWith slideOutHorizontally(tween(320, easing = UiGlide)) { -it * dir })
                    .using(SizeTransform(clip = true))
            },
            label = "crew-mode",
        ) { m ->
            Column(Modifier.fillMaxWidth()) {
                if (m == 0) {
                    var name by rememberSaveable { mutableStateOf("") }
                    LockInField(label = "Crew name", value = name, onValueChange = { name = it.safeTake(40) }, placeholder = "Night Owls")
                    Spacer(Modifier.height(14.dp))
                    LockInButton("Start the crew", onClick = { crew.create(name) }, loading = crew.busy, enabled = name.isNotBlank())
                    Spacer(Modifier.height(8.dp))
                    Text("You'll get a 6-letter code to send your friends.", style = LockInText.Small.copy(color = LockInColors.Mist))
                } else {
                    var code by rememberSaveable { mutableStateOf("") }
                    LockInField(
                        label = "Crew code",
                        value = code,
                        onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(6) },
                        placeholder = "ABC234",
                    )
                    Spacer(Modifier.height(14.dp))
                    LockInButton("Join the crew", onClick = { crew.join(code) }, loading = crew.busy, enabled = code.length == 6)
                }
            }
        }
        crew.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }
    }
    })
}

@Composable
private fun Point(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(LockInColors.Lime))
        Text(text, style = LockInText.Sub.copy(color = LockInColors.Body), modifier = Modifier.padding(start = 12.dp))
    }
}

// =============================================================================================
// In a crew
// =============================================================================================

@Composable
private fun CrewHome(state: CrewState, c: Crew, onOpenChat: () -> Unit, entrance: androidx.compose.runtime.State<Float>) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().rise(entrance, 0), verticalAlignment = Alignment.CenterVertically) {
        CrewBadge(c.name, c.emoji, c.color, 46.dp, Modifier.padding(start = 4.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(c.name, style = LockInText.Headline.copy(fontSize = 30.sp, lineHeight = 36.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${c.members.size} on the rope" + if (c.streak >= 2) " · ${c.streak}-day streak" else "",
                style = LockInText.Sub.copy(color = LockInColors.Silver),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Invite: the share sheet with the code in it.
        Row(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, CircleShape)
                .springClick(onClick = { shareInvite(context, c) })
                .semantics { contentDescription = "Invite friends to the crew" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Share, LockInColors.Lime, boxSize = 16.dp, strokeWidth = 2f)
            Text("Invite", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp), modifier = Modifier.padding(start = 7.dp))
        }
        Box(
            Modifier
                .padding(start = 6.dp)
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Open the crew menu") { state.menuOpen = true }
                .semantics { contentDescription = "Crew menu" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Gear, LockInColors.Silver, boxSize = 22.dp, strokeWidth = 2f) }
    }
    Spacer(Modifier.height(16.dp))
    val live = c.session != null
    // On a tablet: the rope and the session on the left; the board, the chat and the goal on the right.
    TwoColumns(left = {
    // While a session runs it comes first: that is what the crew is here for right now.
    if (live) {
        SessionCard(state, c, Modifier.rise(entrance, 1))
        Spacer(Modifier.height(14.dp))
    }
    val ghosts = (3 - c.members.size).coerceAtLeast(0)
    CrewRope(
        members = c.members + List(ghosts) { CrewMember("ghost$it", "+", "message", 0, 0) },
        me = c.me,
        session = c.session,
        ghost = false,
        modifier = Modifier.rise(entrance, 1),
        onMember = { state.memberOpen = it },
        onInvite = { shareInvite(context, c) },
    )
    if (ghosts > 0) {
        Spacer(Modifier.height(10.dp))
        Text(
            "The crew starts counting at 3. Tap an empty spot on the rope to invite someone.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            modifier = Modifier.padding(horizontal = 6.dp).rise(entrance, 1),
        )
    }
    if (!live) {
        Spacer(Modifier.height(16.dp))
        SessionCard(state, c, Modifier.rise(entrance, 2))
    }
    Spacer(Modifier.height(14.dp))
    CrewStandingCard(state, c, Modifier.rise(entrance, 3))
    Spacer(Modifier.height(24.dp))
    }, right = {
    CrewGoalCard(state, c, Modifier.rise(entrance, 3))
    Spacer(Modifier.height(24.dp))
    var range by rememberSaveable { mutableStateOf(0) } // 0 this week, 1 last week, 2 all time
    SectionTitle(listOf("This week", "Last week", "All time")[range], Modifier.rise(entrance, 3))
    Spacer(Modifier.height(10.dp))
    Box(Modifier.rise(entrance, 3).padding(horizontal = 4.dp)) { Segmented(listOf("Week", "Last", "All time"), range) { range = it } }
    Spacer(Modifier.height(12.dp))
    state.note?.let { n ->
        LaunchedEffect(n) { delay(2600); if (state.note == n) state.note = null }
        Text(n, style = LockInText.Small.copy(color = LockInColors.Lime), modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    }
    state.error?.let { Text(it, style = LockInText.Error, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) }
    Board(c, state, range, Modifier.rise(entrance, 3))
    Spacer(Modifier.height(14.dp))
    CrewRecapCard(c, Modifier.rise(entrance, 4))
    Spacer(Modifier.height(24.dp))
    ChatPreview(state, c, onOpenChat, Modifier.rise(entrance, 4))
    Spacer(Modifier.height(24.dp))
    })
}

// =============================================================================================
// The rope
// =============================================================================================

/**
 * Everyone's climber on one rope up a slope. The more hours someone held this week, the higher they sit.
 * Climbers in the running session wear a lime ring that breathes; someone who left early carries a coral dot.
 * The rope sways a little, and climbers spring to their places when the numbers change.
 */
@Composable
private fun CrewRope(
    members: List<CrewMember>,
    me: String?,
    session: CrewSession?,
    ghost: Boolean,
    modifier: Modifier = Modifier,
    onMember: (String) -> Unit = {},
    onInvite: () -> Unit = {},
) {
    val t by rememberInfiniteTransition(label = "rope").calmFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "sway",
    )
    val top = (members.maxOfOrNull { it.week } ?: 0).coerceAtLeast(1)
    // Ordered along the rope by hours, so the leader is highest; ties keep the server's order.
    val order = members.sortedWith(compareBy<CrewMember>({ it.week }, { if (it.id.startsWith("ghost")) 0 else 1 }))
    val running = session != null && session.startsIn == 0
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape),
    ) {
        val w = maxWidth
        val h = maxHeight
        // The slope and the rope along it.
        Canvas(Modifier.fillMaxSize()) {
            // The slope runs just under the rope, then on to the edge.
            val ground = Path().apply {
                moveTo(0f, size.height)
                val first = ropePoint(0f, size.width, size.height)
                lineTo(0f, first.y + 8.dp.toPx())
                for (i in 0..40) {
                    val q = ropePoint(i / 40f, size.width, size.height)
                    lineTo(q.x, q.y + 8.dp.toPx())
                }
                val last = ropePoint(1f, size.width, size.height)
                lineTo(size.width, last.y + 2.dp.toPx())
                lineTo(size.width, size.height)
                close()
            }
            drawPath(ground, LockInColors.Graphite.copy(alpha = 0.55f))
            val rope = Path()
            val steps = 40
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val p = ropePoint(f, size.width, size.height)
                val sway = sin(t + f * 6f) * 3.dp.toPx() * (1f - f * 0.5f)
                if (i == 0) rope.moveTo(p.x, p.y + sway) else rope.lineTo(p.x, p.y + sway)
            }
            drawPath(rope, (if (ghost) LockInColors.Rim else LockInColors.Lime).copy(alpha = if (ghost) 1f else 0.85f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            // The summit flag.
            val peak = ropePoint(1f, size.width, size.height)
            drawLine(LockInColors.Silver, Offset(peak.x - 14.dp.toPx(), peak.y), Offset(peak.x - 14.dp.toPx(), peak.y - 26.dp.toPx()), 2.dp.toPx())
            val flag = Path().apply {
                val fx = peak.x - 14.dp.toPx()
                val fy = peak.y - 26.dp.toPx()
                val wave = sin(t * 2f) * 2.dp.toPx()
                moveTo(fx, fy)
                quadraticBezierTo(fx - 8.dp.toPx(), fy + 4.dp.toPx() + wave, fx - 16.dp.toPx(), fy + 5.dp.toPx())
                lineTo(fx, fy + 11.dp.toPx())
                close()
            }
            drawPath(flag, if (ghost) LockInColors.Rim else LockInColors.Lime)
        }
        // The climbers: lifted by hours, spread so nobody overlaps.
        val shares = spacedShares(order.mapIndexed { i, m ->
            val spread = if (order.size == 1) 0.5f else 0.1f + 0.8f * i / (order.size - 1)
            if (ghost) spread else 0.06f + 0.84f * (0.5f * spread + 0.5f * m.week / top.toFloat())
        })
        order.forEachIndexed { i, m ->
            key(m.id) {
                val share = shares[i]
                val along = remember { Animatable(0f) }
                LaunchedEffect(share) { along.animateTo(share, spring(dampingRatio = 0.62f, stiffness = 90f)) }
                val p = ropePoint(along.value, w.value, h.value)
                val sway = sin(t + along.value * 6f) * 3f * (1f - along.value * 0.5f)
                val inNow = running && session != null && m.id in session.joined
                val early = session != null && m.id in session.left
                val empty = ghost || m.id.startsWith("ghost")
                Climber(
                    member = m,
                    you = m.id == me,
                    inNow = inNow,
                    early = early,
                    ghost = empty,
                    phase = t + i,
                    // Name (14 dp) above a 38 dp box whose 30 dp avatar rests on the rope.
                    modifier = Modifier
                        .offset(x = (p.x - 26f).dp, y = (p.y - 48f + sway).dp)
                        .then(
                            if (ghost) Modifier
                            else Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button, onClickLabel = if (empty) "Invite someone" else "Open ${crewName(m, me)}") {
                                    if (empty) onInvite() else onMember(m.id)
                                },
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = when {
                                empty -> "Empty spot on the rope. Invite someone."
                                else -> crewName(m, me) + (if (inNow) ", locked in now" else "") + (if (early) ", left a session early" else "") + ", " + crewHours(m.week) + " this week"
                            }
                        },
                )
            }
        }
    }
}

/**
 * Keeps climbers (in rope order) at least a climber's width apart, pushing later ones up the rope and, if
 * that runs past the top, the whole line back down.
 */
private fun spacedShares(raw: List<Float>, gap: Float = 0.11f, low: Float = 0.04f, high: Float = 0.92f): List<Float> {
    if (raw.isEmpty()) return raw
    val out = raw.toMutableList()
    for (i in 1 until out.size) out[i] = maxOf(out[i], out[i - 1] + gap)
    val over = out.last() - high
    if (over > 0f) for (i in out.indices) out[i] -= over
    for (i in out.indices.reversed()) {
        if (i < out.size - 1) out[i] = minOf(out[i], out[i + 1] - gap)
        out[i] = out[i].coerceAtLeast(low)
    }
    return out
}

/** A point on the rope, [f] from bottom left (0) to the summit (1), in the same units as [w] and [h]. */
private fun ropePoint(f: Float, w: Float, h: Float): Offset {
    val u = 1f - f
    // The same curve as the slope, a little above it.
    val x0 = w * 0.04f; val y0 = h * 0.88f
    val x1 = w * 0.36f; val y1 = h * 0.84f
    val x2 = w * 0.6f; val y2 = h * 0.5f
    val x3 = w * 0.9f; val y3 = h * 0.3f
    val x = u * u * u * x0 + 3 * u * u * f * x1 + 3 * u * f * f * x2 + f * f * f * x3
    val y = u * u * u * y0 + 3 * u * u * f * y1 + 3 * u * f * f * y2 + f * f * f * y3
    return Offset(x, y)
}

@Composable
private fun Climber(member: CrewMember, you: Boolean, inNow: Boolean, early: Boolean, ghost: Boolean, phase: Float, modifier: Modifier) {
    val pulse = if (inNow) 0.5f + 0.5f * sin(phase * 2f) else 0f
    Column(modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (ghost) " " else crewName(member, if (you) member.id else null).substringBefore(' '),
            style = LockInText.Small.copy(color = if (you) LockInColors.Lime else LockInColors.Silver, fontSize = 11.sp, lineHeight = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(14.dp),
        )
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (inNow) {
                Box(
                    Modifier
                        .size(38.dp)
                        .graphicsLayer { scaleX = 1f + 0.12f * pulse; scaleY = 1f + 0.12f * pulse }
                        .clip(CircleShape)
                        .background(LockInColors.LimeWash),
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            ghost -> LockInColors.Graphite
                            you -> Identity.tint ?: LockInColors.Lime
                            else -> LockInColors.Snow
                        },
                    )
                    .border(if (inNow) 2.dp else 0.dp, if (inNow) LockInColors.Lime else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ghost) "+" else if (you) (Identity.glyph ?: member.name.firstGlyph()) else member.name.firstGlyph(),
                    style = LockInText.Label.copy(
                        color = if (ghost) LockInColors.Mist else LockInColors.Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            if (early) {
                Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(LockInColors.Flare))
            }
        }
    }
}

// =============================================================================================
// The session
// =============================================================================================

/** Live seconds for a session read [CrewSession.readAt]: (until it starts, left once it runs). */
@Composable
private fun rememberSessionClock(s: CrewSession?): Pair<Int, Int> {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s?.id, s?.readAt) {
        while (s != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (s == null) return 0 to 0
    val gone = ((now - s.readAt) / 1000L).toInt().coerceAtLeast(0)
    val untilStart = (s.startsIn - gone).coerceAtLeast(0)
    val left = if (untilStart > 0) s.seconds else (s.remaining - (gone - s.startsIn).coerceAtLeast(0)).coerceAtLeast(0)
    return untilStart to left
}

@Composable
private fun SessionCard(state: CrewState, c: Crew, modifier: Modifier = Modifier) {
    val s = c.session
    val (untilStart, left) = rememberSessionClock(s)
    val stage = when {
        s == null || (untilStart == 0 && left == 0) -> 0
        untilStart > 0 -> 1
        else -> 2
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(if (stage == 2) 1.5.dp else 1.dp, if (stage == 2) LockInColors.Lime else LockInColors.Graphite, CardShape)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
            .padding(18.dp),
    ) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (slideInVertically(tween(320, easing = UiGlide)) { it / 3 } togetherWith slideOutVertically(tween(240, easing = UiGlide)) { -it / 3 })
                    .using(SizeTransform(clip = true))
            },
            label = "crew-session",
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                when {
                    shown == 0 || s == null -> StartSession(state)
                    else -> LiveSession(state, c, s, untilStart, left, running = shown == 2)
                }
            }
        }
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error)
        }
    }
}

@Composable
private fun StartSession(state: CrewState) {
    var minutes by rememberSaveable { mutableStateOf(45) }
    var startIn by rememberSaveable { mutableStateOf(0) }
    var custom by rememberSaveable { mutableStateOf(false) }
    Text("Start a crew session", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
    Text(
        "You're in straight away. The crew gets a tap to join you, and everyone who joins is locked together.",
        style = LockInText.Small.copy(color = LockInColors.Body),
    )
    Spacer(Modifier.height(14.dp))
    if (custom) {
        Text("How long", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp))
        Spacer(Modifier.height(8.dp))
        Chips(SessionLengths.map { formatMinutes(it) to it }, minutes) { minutes = it }
        Spacer(Modifier.height(12.dp))
        Text("Starts", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp))
        Spacer(Modifier.height(8.dp))
        Chips(SessionStarts, startIn) { startIn = it }
        Spacer(Modifier.height(16.dp))
    }
    LockInButton(
        if (startIn == 0) "Start · ${formatMinutes(minutes)}" else "Start in ${SessionStarts.first { it.second == startIn }.first} · ${formatMinutes(minutes)}",
        onClick = { state.start(minutes, startIn) },
        loading = state.busy,
    )
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (custom) "Fewer options" else "Customize length or start time",
            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { custom = !custom }.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LiveSession(state: CrewState, c: Crew, s: CrewSession, untilStart: Int, left: Int, running: Boolean) {
    val me = c.me
    val iAmIn = me in s.joined
    val iLeft = me in s.left
    val by = if (s.by == me) "You" else s.byName.substringBefore(' ')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .height(30.dp)
                .clip(CircleShape)
                .background(if (running) LockInColors.Lime else LockInColors.Graphite)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Lock, if (running) LockInColors.OnLime else LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f)
            Text(
                if (running) "Locked in" else "Starting soon",
                style = LockInText.Label.copy(color = if (running) LockInColors.OnLime else LockInColors.Silver, fontSize = 12.sp),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("$by started ${formatMinutes(s.seconds / 60)}", style = LockInText.Small.copy(color = LockInColors.Mist))
    }
    Spacer(Modifier.height(14.dp))
    // The big clock, and a ring that drains as it runs.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(formatClock(if (running) left else untilStart), style = LockInText.Headline.copy(fontSize = 52.sp, lineHeight = 58.sp))
            Text(if (running) "left" else "until it starts", style = LockInText.Sub.copy(color = LockInColors.Silver))
        }
        val frac by animateFloatAsState(
            if (running && s.seconds > 0) left / s.seconds.toFloat() else 1f,
            spring(dampingRatio = 0.9f, stiffness = 60f),
            label = "ring",
        )
        Canvas(Modifier.size(64.dp)) {
            val sw = 6.dp.toPx()
            val tl = Offset(sw / 2f, sw / 2f)
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(LockInColors.Graphite, 0f, 360f, false, tl, sz, style = Stroke(sw))
            drawArc(if (running) LockInColors.Lime else LockInColors.Edge, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
    Spacer(Modifier.height(14.dp))
    // Who's in.
    val ins = c.members.filter { it.id in s.joined }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(30.dp).width((22 * ins.size.coerceAtMost(6) + 10).dp)) {
            ins.take(6).forEachIndexed { i, m ->
                Box(
                    Modifier
                        .offset(x = (i * 22).dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (m.id == me) (Identity.tint ?: LockInColors.Lime) else LockInColors.Snow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (m.id == me) (Identity.glyph ?: m.name.firstGlyph()) else m.name.firstGlyph(), style = LockInText.Label.copy(color = LockInColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        Text(
            buildString {
                append("${ins.size} in")
                val waiting = c.members.size - ins.size - s.left.size
                if (waiting > 0) append(" · $waiting not yet")
                if (s.left.isNotEmpty()) append(" · ${s.left.size} left early")
            },
            style = LockInText.Small.copy(color = LockInColors.Body),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    when {
        iLeft -> Text(
            "You left this one early. It shows on the board this week.",
            style = LockInText.Small.copy(color = LockInColors.Flare),
        )
        iAmIn && !running -> {
            Text("You're in. Your phone locks when it starts, even if LockIn is closed.", style = LockInText.Small.copy(color = LockInColors.Body))
            Spacer(Modifier.height(12.dp))
            LockInButton("Step out", onClick = state::leaveSession, kind = ButtonKind.Outline, loading = state.busy)
        }
        iAmIn -> {
            // Leaving once it has begun is a real choice: it takes a second tap.
            var sure by remember(s.id) { mutableStateOf(false) }
            LaunchedEffect(sure) { if (sure) { delay(3500); sure = false } }
            LockInButton(
                if (sure) "Tap again to leave early" else "Leave early",
                onClick = { if (sure) state.leaveSession() else sure = true },
                kind = ButtonKind.Outline,
                loading = state.busy,
            )
            Spacer(Modifier.height(6.dp))
            Text("The crew sees who leaves early.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        else -> {
            LockInButton(
                if (running) "I'm in · ${formatClock(left)} left" else "I'm in",
                onClick = state::joinSession,
                loading = state.busy,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (running) "Your phone locks right away, until the session ends." else "Your phone locks when it starts.",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// =============================================================================================
// The board
// =============================================================================================

@Composable
private fun Board(c: Crew, state: CrewState, range: Int, modifier: Modifier = Modifier) {
    // What each person shows depends on the range picked: this week, the week before, or ever.
    fun secs(m: CrewMember) = when (range) { 1 -> m.prev; 2 -> m.total; else -> m.week }
    val ranked = c.members.sortedWith(compareByDescending<CrewMember> { secs(it) })
    val lastRank = c.members.sortedByDescending { it.prev }.map { it.id }
    val top = (ranked.maxOfOrNull { secs(it) } ?: 0).coerceAtLeast(1)
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(vertical = 6.dp),
    ) {
        if (ranked.all { secs(it) == 0 }) {
            Text(
                when (range) {
                    1 -> "Nobody held a session last week."
                    2 -> "No crew hours yet. Start a session and get everyone on the rope."
                    else -> "No crew hours yet this week. Start a session and get everyone on the rope."
                },
                style = LockInText.Small.copy(color = LockInColors.Body),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        ranked.forEachIndexed { i, m ->
            val you = m.id == c.me
            val value = secs(m)
            val nudge = if (range == 0) nudgeFor(c, m) else null
            // Up or down since last week, only when there was a last week to compare with.
            val before = lastRank.indexOf(m.id)
            val moved = if (range == 0 && c.members.any { it.prev > 0 } && m.week > 0) before - i else 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Open ${crewName(m, c.me)}") { state.memberOpen = m.id }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Number ${i + 1}, ${crewName(m, c.me)}, ${crewHours(value)}" +
                            (if (moved > 0) ", up $moved since last week" else if (moved < 0) ", down ${-moved} since last week" else "")
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${i + 1}",
                        style = LockInText.Label.copy(color = if (i == 0 && value > 0) LockInColors.Lime else LockInColors.Mist, fontSize = 14.sp),
                    )
                    if (moved != 0) {
                        Text(
                            if (moved > 0) "▲" else "▼",
                            style = LockInText.Small.copy(color = if (moved > 0) LockInColors.Lime else LockInColors.Flare, fontSize = 8.sp, lineHeight = 10.sp),
                        )
                    }
                }
                Avatar(
                    m.name,
                    boxSize = 34.dp,
                    filled = you,
                    me = you,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            crewName(m, c.me),
                            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (range == 0 && m.early > 0) {
                            // Neutral on purpose: it happens, the crew just sees it.
                            Text(
                                if (m.early == 1) "1 early exit" else "${m.early} early exits",
                                style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // The bar grows into place.
                    val grow = remember { Animatable(0f) }
                    LaunchedEffect(value, top) { grow.animateTo(value / top.toFloat(), spring(dampingRatio = 0.8f, stiffness = 120f)) }
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(LockInColors.Graphite, size = size, cornerRadius = r)
                        val wv = size.width * grow.value
                        if (wv > 0f) drawRoundRect(if (you) (Identity.tint ?: LockInColors.Lime) else LockInColors.Frost, size = Size(wv.coerceAtLeast(size.height), size.height), cornerRadius = r)
                    }
                }
                if (nudge != null) {
                    NudgePill(nudge) {
                        when (nudge) {
                            CrewNudge.Poke -> state.poke(m.id, m.name.substringBefore(' '))
                            CrewNudge.Back -> state.cheer(m.id, m.name.substringBefore(' '), back = true)
                            CrewNudge.Cheer -> state.cheer(m.id, m.name.substringBefore(' '), back = false)
                        }
                    }
                }
                Text(
                    crewHours(value),
                    style = LockInText.Label.copy(color = LockInColors.Frost, fontSize = 14.sp),
                    modifier = Modifier.padding(start = 12.dp).width(58.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// =============================================================================================
// Chat preview and leaving
// =============================================================================================

@Composable
private fun ChatPreview(state: CrewState, c: Crew, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val unread = state.unreadCount
    val unreadAny = state.hasUnread
    // The last two things people said (events like "Sam joined" don't count), or what the server knows of the latest.
    val said = state.lines.filter { it.kind == "text" }.takeLast(2)
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, if (unreadAny) LockInColors.Lime else LockInColors.Graphite, CardShape)
            .springClick(onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "Crew chat" + if (unread > 0) ", $unread unread" else if (unreadAny) ", new messages" else ""
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(LockInColors.Graphite), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Crew, LockInColors.Lime, boxSize = 22.dp, strokeWidth = 2f)
            if (unreadAny) Box(Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(LockInColors.Lime))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Crew chat", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                if (unread > 0) {
                    Text(
                        if (unread > 9) "9+" else "$unread",
                        style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 11.sp),
                        modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            if (said.isEmpty()) {
                val r = c.recent
                Text(
                    if (r == null) "Say hi to the crew." else "${if (r.mine) "You" else r.name.substringBefore(' ')}: ${r.body}",
                    style = LockInText.Small.copy(color = if (unreadAny) LockInColors.Frost else LockInColors.Mist),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                said.forEach { l ->
                    Text(
                        "${if (l.mine) "You" else l.name.substringBefore(' ')}: ${if (l.voice != null) "Voice message" else l.body}",
                        style = LockInText.Small.copy(color = if (unreadAny) LockInColors.Frost else LockInColors.Mist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 16.dp, strokeWidth = 2f)
    }
}

// =============================================================================================
// Small pieces
// =============================================================================================

@Composable
private fun Chips(options: List<Pair<String, Int>>, picked: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val on = value == picked
            val pop by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "chip")
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
                Text(label, style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp))
            }
        }
    }
}

/** Two choices on one track; the lime pill springs to the picked one. */
@Composable
private fun Segmented(labels: List<String>, picked: Int, onPick: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(LockInColors.Ink)
            .padding(4.dp),
    ) {
        val each = maxWidth / labels.size
        val x by androidx.compose.animation.core.animateDpAsState(each * picked, spring(dampingRatio = 0.72f, stiffness = 420f), label = "seg")
        Box(Modifier.offset(x = x).size(width = each, height = maxHeight).clip(CircleShape).background(LockInColors.Lime))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, l ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(CircleShape).clickable(role = Role.Tab) { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(l, style = LockInText.Label.copy(color = if (i == picked) LockInColors.OnLime else LockInColors.Silver, fontSize = 14.sp))
                }
            }
        }
    }
}


/** The first look for your crew failed: say why, and offer to try again. */
@Composable
private fun CrewUnavailable(crew: CrewState, entrance: androidx.compose.runtime.State<Float>) {
    PageTitle("Crew", if (crew.notReady) "Coming very soon" else "Couldn't load", entrance)
    Spacer(Modifier.height(18.dp))
    CrewRope(
        members = listOf("A", "K", "E", "Y", "S").mapIndexed { i, n -> CrewMember("ghost$i", n, "message", 0, 0) },
        me = null,
        session = null,
        ghost = true,
        modifier = Modifier.rise(entrance, 1),
    )
    Spacer(Modifier.height(18.dp))
    Text(
        crew.error ?: "Couldn't reach your crew.",
        style = LockInText.Sub.copy(color = LockInColors.Body),
        modifier = Modifier.padding(horizontal = 4.dp).rise(entrance, 2),
    )
    Spacer(Modifier.height(16.dp))
    LockInButton("Try again", onClick = crew::retry, modifier = Modifier.rise(entrance, 3), kind = ButtonKind.Outline)
}
