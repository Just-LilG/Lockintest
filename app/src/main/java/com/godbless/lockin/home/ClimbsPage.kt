package com.godbless.lockin.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.intro.clamp01
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rise
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private val CardShape = RoundedCornerShape(24.dp)
private val BigShape = RoundedCornerShape(28.dp)
private val Filters = listOf("All", "Summits", "Locked")

/** Every session you have locked in: a month at a glance, then the list, newest first. */
@Composable
internal fun ClimbsPage(onBack: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(0) }
    var open by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
    PageScaffold(wide = true) { entrance ->
        Row(Modifier.fillMaxWidth().rise(entrance, 0)) { com.godbless.lockin.ui.BackButton(onBack) }
        Spacer(Modifier.height(14.dp))
        PageTitle("Climbs", "Every session you have locked in.", entrance)
        Spacer(Modifier.height(20.dp))

        // On a tablet: the month on the left, the list of climbs beside it.
        TwoColumns(leftWeight = 1f, left = {
        MonthCard(Modifier.rise(entrance, 1))
        Spacer(Modifier.height(22.dp))
        }, right = {
        FilterChips(Filters, filter, { filter = it; open = "" }, Modifier.rise(entrance, 2))
        Spacer(Modifier.height(18.dp))

        Column(Modifier.fillMaxWidth().rise(entrance, 3).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 400f))) {
            var shown = 0
            LocalStats.current.history.forEachIndexed { g, group ->
                val rows = group.sessions.withIndex().filter {
                    when (filter) {
                        1 -> it.value.summit
                        2 -> !it.value.summit
                        else -> true
                    }
                }
                if (rows.isEmpty()) return@forEachIndexed
                shown += rows.size
                Text(
                    group.label,
                    style = LockInText.Label.copy(color = LockInColors.Silver),
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp, top = if (g == 0 || shown == rows.size) 0.dp else 8.dp),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(LockInColors.Slate)
                        .border(1.dp, LockInColors.Graphite, CardShape),
                ) {
                    rows.forEachIndexed { n, (i, s) ->
                        if (n > 0) Box(Modifier.fillMaxWidth().padding(start = 74.dp).height(1.dp).background(LockInColors.Graphite))
                        val key = "$g-$i"
                        SessionRow(s, open == key) { open = if (open == key) "" else key }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (shown == 0) {
                Text(
                    if (filter == 0) "No climbs yet. Lock in for the first time and it shows up here." else "Nothing here yet.",
                    style = LockInText.Sub.copy(color = LockInColors.Silver),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        })
    }
    }
}

// ---------------------------------------------------------------------------------------------
// The month
// ---------------------------------------------------------------------------------------------

/** This month's total, a calendar of dots as big and bright as the hours focused, and three numbers. */
@Composable
private fun MonthCard(modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    val month = remember { YearMonth.from(today) }
    val days = month.lengthOfMonth()
    val offset = remember { month.atDay(1).dayOfWeek.value - 1 }
    val rows = (offset + days + 6) / 7
    val stats = LocalStats.current
    val values = stats.monthHours
    val total = values.sum()

    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(350)
        grow.animateTo(1f, tween(1400, easing = UiGlide))
    }
    val monthName = remember { month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(BigShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, BigShape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("$monthName ${month.year}", style = LockInText.Label.copy(color = LockInColors.Silver))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(Locale.US, "%.1f", total),
                        style = LockInText.Title.copy(fontSize = 34.sp, lineHeight = 40.sp),
                    )
                    Text(
                        "h focused",
                        style = LockInText.Sub.copy(color = LockInColors.Silver),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            Row(
                Modifier.height(30.dp).clip(CircleShape).background(LockInColors.LimeWash).padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.ArrowUp, LockInColors.Lime, boxSize = 14.dp, strokeWidth = 2.4f)
                Text(
                    stats.monthDelta,
                    style = LockInText.Label.copy(color = LockInColors.Frost, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            for (letter in listOf("M", "T", "W", "T", "F", "S", "S")) {
                Text(
                    letter,
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(7f / (rows * 0.74f))) {
            val cell = size.width / 7f
            val rowH = size.height / rows
            val maxR = cell * 0.36f
            for (d in 1..days) {
                val idx = offset + d - 1
                val c = Offset((idx % 7 + 0.5f) * cell, (idx / 7 + 0.5f) * rowH)
                val v = values[d - 1]
                // Dots swell in from the first of the month onward.
                val g = clamp01(grow.value * 1.7f - (idx / (offset + days).toFloat()) * 0.7f)
                when {
                    d > today.dayOfMonth -> drawCircle(LockInColors.Graphite, radius = 2.dp.toPx(), center = c)
                    v <= 0f -> drawCircle(LockInColors.Graphite, radius = cell * 0.1f * g, center = c)
                    else -> {
                        val f = (v / 2.5f).coerceIn(0f, 1f)
                        drawCircle(
                            LockInColors.Lime.copy(alpha = 0.40f + 0.60f * f),
                            radius = maxR * (0.42f + 0.58f * f) * g,
                            center = c,
                        )
                    }
                }
                if (d == today.dayOfMonth) {
                    drawCircle(LockInColors.Snow, radius = minOf(cell * 0.44f, rowH * 0.48f), center = c, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            MiniStat(stats.monthSessions.toString(), "sessions", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 14.dp).background(LockInColors.Graphite))
            MiniStat(stats.longestSession, "longest", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 14.dp).background(LockInColors.Graphite))
            MiniStat(stats.monthSummits.toString(), "summits", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
    }
}

// ---------------------------------------------------------------------------------------------
// Filter and list
// ---------------------------------------------------------------------------------------------

@Composable
private fun FilterChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) LockInColors.Lime else LockInColors.Ink, tween(200), label = "chip-bg")
            val edge by animateColorAsState(if (on) LockInColors.Lime else LockInColors.Rim, tween(200), label = "chip-edge")
            Box(
                Modifier
                    .height(38.dp)
                    .clip(ControlShape)
                    .background(bg)
                    .border(1.5.dp, edge, ControlShape)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                )
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, expanded: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.8f, stiffness = 420f), label = "chevron")
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (s.summit) LockInColors.Lime else LockInColors.Graphite),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Peak, if (s.summit) LockInColors.OnLime else LockInColors.Silver, boxSize = 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(climbTitle(s.title, LocalPartnerName.current), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Text(s.timeText, style = LockInText.Small.copy(color = LockInColors.Mist))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMinutes(s.minutes), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                Text(
                    if (s.summit) "Summit" else "Locked",
                    style = LockInText.Small.copy(color = if (s.summit) LockInColors.Lime else LockInColors.Mist),
                )
            }
            GlyphIcon(
                Glyph.Chevron, LockInColors.Mist, boxSize = 16.dp, strokeWidth = 2f,
                modifier = Modifier.padding(start = 8.dp).graphicsLayer { rotationZ = turn },
            )
        }
        Collapse(expanded) {
            Row(
                Modifier.fillMaxWidth().padding(start = 74.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Detail("Pickups", if (s.pickups == 0) "None" else s.pickups.toString(), Modifier.weight(1f))
                Detail("Roped to", LocalPartnerName.current, Modifier.weight(1f))
                Detail("Goal", if (s.summit) "Reached" else "Not yet", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(LockInColors.Graphite).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp))
        Text(value, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp))
    }
}
