package com.godbless.lockin.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.Crew
import com.godbless.lockin.data.CrewMember
import com.godbless.lockin.data.CrewStanding
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val CardShape = RoundedCornerShape(24.dp)
private val GoalChoices = listOf(120, 300, 600, 1200, 2400)

/** "5h 30m", "45m", "0m". */
internal fun crewHours(seconds: Int): String {
    // One rule everywhere (board, goal, recap): round to the nearest minute, and say "<1m" for a little.
    val m = (seconds + 30) / 60
    return when {
        seconds <= 0 -> "0m"
        m <= 0 -> "<1m"
        m < 60 -> "${m}m"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }
}

// ---------------------------------------------------------------------------------------------
// The crew's weekly climb
// ---------------------------------------------------------------------------------------------

/** One goal for the whole crew: everyone's held time adds up to a bar. Anyone can set it. Also the crew streak. */
@Composable
internal fun CrewGoalCard(state: CrewState, c: Crew, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val has = c.goalMin > 0
    val reached = has && c.weekTotal >= c.goalMin * 60
    val target = if (has) (c.weekTotal / (c.goalMin * 60f)).coerceIn(0f, 1f) else 0f
    val grow = remember { Animatable(0f) }
    LaunchedEffect(target) { grow.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 90f)) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(if (reached) 1.5.dp else 1.dp, if (reached) LockInColors.Lime else LockInColors.Graphite, CardShape)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Crew goal this week", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp))
                if (has) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(crewHours(c.weekTotal), style = LockInText.Title.copy(fontSize = 28.sp, lineHeight = 34.sp))
                        Text(" of ${crewHours(c.goalMin * 60)}", style = LockInText.Sub.copy(color = LockInColors.Silver, fontSize = 15.sp), modifier = Modifier.padding(bottom = 3.dp))
                    }
                } else {
                    Text("No goal yet", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
                }
            }
            Text(
                if (editing) "Done" else if (has) "Change" else "Set one",
                style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { editing = !editing }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        if (has) {
            Spacer(Modifier.height(10.dp))
            // Where the crew should be by now if it spread the goal evenly over the week.
            val pace = if (c.weekStart > 0) ((System.currentTimeMillis() / 1000L - c.weekStart) / (7 * 86400f)).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(LockInColors.Graphite, size = size, cornerRadius = r)
                val w = size.width * grow.value
                if (w > 0f) drawRoundRect(LockInColors.Lime, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
                if (!reached && pace > 0.02f) {
                    val x = size.width * pace
                    drawLine(LockInColors.Snow, androidx.compose.ui.geometry.Offset(x, -2.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height + 2.dp.toPx()), 2.dp.toPx())
                }
            }
            Spacer(Modifier.height(8.dp))
            val behind = !reached && pace > 0f && c.weekTotal < (c.goalMin * 60 * pace).toInt()
            Text(
                when {
                    reached -> "You did it together. Goal reached."
                    behind -> "${crewHours(c.goalMin * 60 - c.weekTotal)} to go. A little behind the pace line, one session catches up."
                    else -> "${crewHours(c.goalMin * 60 - c.weekTotal)} to go. On pace."
                },
                style = LockInText.Small.copy(color = if (reached) LockInColors.Lime else LockInColors.Body),
            )
            // Mon to Sun: how much the crew held each day.
            val perDay = List(7) { d -> c.members.sumOf { it.days.getOrElse(d) { 0 } } }
            val peak = (perDay.maxOrNull() ?: 0).coerceAtLeast(1)
            val today = if (c.weekStart > 0) ((System.currentTimeMillis() / 1000L - c.weekStart) / 86400L).toInt().coerceIn(0, 6) else -1
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                perDay.forEachIndexed { d, secs ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((6 + 30 * secs / peak.toFloat()).dp.coerceAtMost(36.dp))
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (secs > 0) (if (d == today) LockInColors.Lime else LockInColors.Frost) else LockInColors.Graphite),
                            )
                        }
                        Text(
                            DayOfWeek.of(d + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = LockInText.Small.copy(color = if (d == today) LockInColors.Snow else LockInColors.Mist, fontSize = 10.sp, lineHeight = 14.sp),
                        )
                    }
                }
            }
        } else if (!editing) {
            Spacer(Modifier.height(4.dp))
            Text("Pick a number of hours the crew holds together each week. The bar fills as anyone holds a session.", style = LockInText.Small.copy(color = LockInColors.Body))
        }
        if (editing) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalChoices.forEach { m ->
                    val on = m == c.goalMin
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(ControlShape)
                            .background(if (on) LockInColors.Lime else LockInColors.Slate)
                            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
                            .clickable(role = Role.Button) { state.setGoal(m); editing = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${m / 60}h", style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp))
                    }
                }
            }
            if (has) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clear the goal",
                    style = LockInText.Small.copy(color = LockInColors.Flare),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { state.setGoal(0); editing = false }.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Flame, if (c.streak > 0) LockInColors.Lime else LockInColors.Edge, boxSize = 18.dp, strokeWidth = 2f)
            Text(
                when {
                    c.streak >= 2 -> "${c.streak}-day crew streak"
                    c.streak == 1 -> "1-day crew streak"
                    else -> "No crew streak yet"
                },
                style = LockInText.Label.copy(color = if (c.streak > 0) LockInColors.Snow else LockInColors.Silver, fontSize = 14.sp),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            if (c.streak > 0) "Keep it going: two of you holding 10 minutes together each day."
            else "Two or more of you holding 10 minutes together in a session starts one.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Standing sessions
// ---------------------------------------------------------------------------------------------

private val TimeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/** A slot's day and time in this phone's own time. */
private fun localSlot(wkMinute: Int): Pair<DayOfWeek, java.time.LocalTime> {
    val monday = ZonedDateTime.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS)
    val z = monday.plusMinutes(wkMinute.toLong()).withZoneSameInstant(ZoneId.systemDefault())
    return z.dayOfWeek to z.toLocalTime()
}

/** This phone's day and time as minutes from Monday 00:00 UTC. */
private fun toWeekMinute(day: DayOfWeek, minuteOfDay: Int): Int {
    val local = ZonedDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS)
        .with(TemporalAdjusters.nextOrSame(day)).plusMinutes(minuteOfDay.toLong())
    val utc = local.withZoneSameInstant(ZoneOffset.UTC)
    val monday = utc.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS)
    return ChronoUnit.MINUTES.between(monday, utc).toInt().coerceIn(0, 10079)
}

/** "next Tue" style hint: how far off this slot's next occurrence is. */
private fun nextSlotText(s: CrewStanding): String {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS)
    var at = monday.plusMinutes(s.wkMinute.toLong())
    if (at.isBefore(now)) at = at.plusWeeks(1)
    val mins = ChronoUnit.MINUTES.between(now, at)
    return when {
        mins < 60 -> "in ${mins}m"
        mins < 24 * 60 -> "in ${mins / 60}h"
        else -> "in ${mins / (24 * 60)}d"
    }
}

private fun slotText(s: CrewStanding): String {
    val (day, time) = localSlot(s.wkMinute)
    return "${day.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${TimeFmt.format(time)}"
}

/** Weekly meeting times for the crew. Each phone reminds 10 minutes before. */
@Composable
internal fun CrewStandingCard(state: CrewState, c: Crew, modifier: Modifier = Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableStateOf(DayOfWeek.MONDAY.value) }
    var minuteOfDay by rememberSaveable { mutableStateOf(20 * 60) }
    var length by rememberSaveable { mutableStateOf(45) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Standing sessions", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
                Text("Times the crew meets every week. Everyone gets a reminder 10 minutes before.", style = LockInText.Small.copy(color = LockInColors.Body))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (c.standing.isEmpty() && !adding) {
            Text("Nothing planned yet. A regular time is what turns a crew into a habit.", style = LockInText.Small.copy(color = LockInColors.Mist))
            Spacer(Modifier.height(10.dp))
        }
        c.standing.filter { it.id !in state.hiddenStanding }.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Calendar, LockInColors.Lime, boxSize = 20.dp, strokeWidth = 2f)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(slotText(s), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                    Text(
                        "${formatMinutes(s.minutes)} · ${nextSlotText(s)}" + if (s.byName.isNotBlank()) " · by ${if (s.by == c.me) "you" else s.byName.substringBefore(' ')}" else "",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                }
                // Only whoever added it, or the crew owner, can take it away.
                if (s.by == c.me || c.mine || s.by.isBlank()) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Remove this standing session") { state.removeStandingSoon(s.id) },
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Close, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2.2f) }
                }
            }
        }
        if (state.undoStanding != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LockInColors.Graphite)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Standing session removed.", style = LockInText.Small.copy(color = LockInColors.Body), modifier = Modifier.weight(1f))
                Text(
                    "Undo",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { state.undoStandingRemoval() }.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        if (adding) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { d ->
                    val on = d.value == day
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(if (on) LockInColors.Lime else LockInColors.Slate)
                            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, CircleShape)
                            .clickable(role = Role.Tab) { day = d.value },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Step(Glyph.Minus) { minuteOfDay = (minuteOfDay - 30 + 1440) % 1440 }
                Text(
                    TimeFmt.format(java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)),
                    style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    modifier = Modifier.width(120.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Step(Glyph.Plus) { minuteOfDay = (minuteOfDay + 30) % 1440 }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 60, 90, 120).forEach { m ->
                    val on = m == length
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(ControlShape)
                            .background(if (on) LockInColors.Lime else LockInColors.Slate)
                            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
                            .clickable(role = Role.Tab) { length = m },
                        contentAlignment = Alignment.Center,
                    ) { Text(formatMinutes(m), style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            LockInButton("Add it", onClick = { state.addStanding(toWeekMinute(DayOfWeek.of(day), minuteOfDay), length); adding = false }, loading = state.busy)
            Spacer(Modifier.height(6.dp))
            LockInButton("Cancel", onClick = { adding = false }, kind = ButtonKind.Outline)
        } else if (c.standing.size < 6) {
            LockInButton("Add a standing session", onClick = { adding = true }, kind = ButtonKind.Outline)
        }
    }
}

@Composable
private fun Step(glyph: Glyph, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, LockInColors.Rim, CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, LockInColors.Snow, boxSize = 16.dp, strokeWidth = 2f) }
}

// ---------------------------------------------------------------------------------------------
// Last week
// ---------------------------------------------------------------------------------------------

@Composable
internal fun CrewRecapCard(c: Crew, modifier: Modifier = Modifier) {
    val r = c.lastWeek ?: return
    val hit = c.goalMin > 0 && r.total >= c.goalMin * 60
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        Text("Last week", style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(crewHours(r.total), style = LockInText.Title.copy(fontSize = 28.sp, lineHeight = 34.sp))
            Text(" together in ${r.sessions} ${if (r.sessions == 1) "session" else "sessions"}", style = LockInText.Sub.copy(color = LockInColors.Silver, fontSize = 15.sp), modifier = Modifier.padding(bottom = 3.dp))
        }
        if (r.topName.isNotBlank() && r.topSeconds > 0) {
            Spacer(Modifier.height(8.dp))
            val you = c.members.firstOrNull { it.name == r.topName }?.id == c.me
            Text(
                "Top climber: ${if (you) "you" else r.topName.substringBefore(' ')}, ${crewHours(r.topSeconds)}.",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
        }
        if (hit) {
            Text("The crew hit its goal.", style = LockInText.Small.copy(color = LockInColors.Lime), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Poke and cheer
// ---------------------------------------------------------------------------------------------

internal enum class CrewNudge(val label: String) { Poke("Poke"), Back("Get back on"), Cheer("Cheer") }

/** What you can send a member right now: a poke while a session runs and they haven't joined, a "get back on" if they left early, otherwise a cheer. */
internal fun nudgeFor(c: Crew, m: CrewMember): CrewNudge? {
    if (m.id == c.me) return null
    val s = c.session
    if (s != null && s.startsIn == 0 && m.id !in s.joined) return if (m.id in s.left) CrewNudge.Back else CrewNudge.Poke
    if (m.early > 0) return CrewNudge.Back
    // A cheer means something after real effort: 10+ minutes held this week.
    return if (m.week >= 600) CrewNudge.Cheer else null
}

@Composable
internal fun NudgePill(nudge: CrewNudge, onClick: () -> Unit) {
    Text(
        nudge.label,
        style = LockInText.Label.copy(color = if (nudge == CrewNudge.Poke) LockInColors.OnLime else LockInColors.Lime, fontSize = 12.sp),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (nudge == CrewNudge.Poke) LockInColors.Lime else LockInColors.Slate)
            .border(1.dp, LockInColors.Lime, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
