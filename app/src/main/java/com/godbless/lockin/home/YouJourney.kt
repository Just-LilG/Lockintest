@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.rise
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateStyle
import java.util.Locale

private val Tile = RoundedCornerShape(22.dp)

@Composable
private fun JourneyCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Tile)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, Tile)
            .padding(16.dp),
    ) { content() }
}

private fun dayName(epoch: Long, style: DateStyle = DateStyle.FULL): String =
    LocalDate.ofEpochDay(epoch).dayOfWeek.getDisplayName(style, Locale.getDefault())

private fun dayText(epoch: Long): String {
    val d = LocalDate.ofEpochDay(epoch)
    return d.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " + d.month.getDisplayName(DateStyle.SHORT, Locale.getDefault())
}

// ---------------------------------------------------------------------------------------------
// Achievements
// ---------------------------------------------------------------------------------------------

private class Badge(val title: String, val detail: String, val glyph: Glyph, val earned: Boolean)

private fun badgesFor(s: Stats): List<Badge> {
    fun hours(from: Int, to: Int) = (from until to).sumOf { s.hourBuckets[it % 24] } / 3600f
    return listOf(
        Badge("First climb", "Lock in for the first time.", Glyph.Peak, s.totalHours > 0f || s.history.isNotEmpty()),
        Badge("Three in a row", "Lock in three days running.", Glyph.Flame, s.longestStreak >= 3),
        Badge("Week on the rope", "A seven-day streak.", Glyph.Flame, s.longestStreak >= 7),
        Badge("A month strong", "A thirty-day streak.", Glyph.Calendar, s.longestStreak >= 30),
        Badge("First summit", "One session of 90 minutes or more.", Glyph.Peak, s.allSummits >= 1),
        Badge("Ten summits", "Reach ten summits.", Glyph.Peak, s.allSummits >= 10),
        Badge("Ten hours", "Ten hours locked in, all told.", Glyph.Timer, s.totalHours >= 10f),
        Badge("Fifty hours", "Fifty hours locked in.", Glyph.Timer, s.totalHours >= 50f),
        Badge("A hundred hours", "A hundred hours locked in.", Glyph.Star, s.totalHours >= 100f),
        Badge("Marathon", "One session of three hours or more.", Glyph.Target, s.longestEverSeconds >= 3 * 3600),
        Badge("Early bird", "An hour of focus between 5 and 8 in the morning.", Glyph.Book, hours(5, 8) >= 1f),
        Badge("Night owl", "An hour of focus between 10 at night and 2 in the morning.", Glyph.Book, hours(22, 26) >= 1f),
    )
}

/** Badges you have earned, and the ones still ahead. Tap one to read what it asks for. */
@Composable
internal fun AchievementsCard(stats: Stats, modifier: Modifier = Modifier) {
    val badges = remember(stats) { badgesFor(stats) }
    var picked by remember { mutableStateOf<Int?>(null) }
    JourneyCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Achievements", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text("${badges.count { it.earned }} of ${badges.size}", style = LockInText.Small.copy(color = LockInColors.Mist))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            badges.forEachIndexed { i, b ->
                Column(
                    Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .springClick { picked = if (picked == i) null else i }
                        .padding(vertical = 4.dp)
                        .semantics { contentDescription = "${b.title}. ${if (b.earned) "Earned" else "Not yet"}. ${b.detail}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (b.earned) LockInColors.Lime else LockInColors.Graphite)
                            .then(if (picked == i) Modifier.border(2.dp, LockInColors.Snow, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(b.glyph, if (b.earned) LockInColors.OnLime else LockInColors.Edge, boxSize = 26.dp, strokeWidth = 2f) }
                    Text(
                        b.title,
                        style = LockInText.Small.copy(color = if (b.earned) LockInColors.Snow else LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp),
                        modifier = Modifier.padding(top = 6.dp),
                        maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = picked,
            transitionSpec = { (slideInVertically(spring(1f, 500f)) { it / 2 } togetherWith slideOutVertically(spring(1f, 500f)) { -it / 2 }).using(null) },
            label = "badge-detail",
        ) { i ->
            val b = i?.let { badges.getOrNull(it) }
            if (b != null) {
                Text(
                    (if (b.earned) "Earned · " else "Next · ") + b.detail,
                    style = LockInText.Small.copy(color = if (b.earned) LockInColors.Lime else LockInColors.Body),
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else Spacer(Modifier.height(0.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RecordsCard(stats: Stats, modifier: Modifier = Modifier) {
    val peakHour = stats.hourBuckets.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    JourneyCard(modifier) {
        Text("Your records", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
        Spacer(Modifier.height(8.dp))
        RecordLine("Longest session", if (stats.longestEverSeconds == 0) "None yet" else formatMinutes(maxOf(stats.longestEverSeconds / 60, 1)))
        RecordLine(
            "Best day",
            if (stats.bestDaySeconds == 0) "None yet" else formatMinutes(stats.bestDaySeconds / 60) + (if (stats.bestDayEpoch >= 0) " · " + dayText(stats.bestDayEpoch) else ""),
        )
        RecordLine("Best week", if (stats.bestWeekSeconds == 0) "None yet" else formatMinutes(stats.bestWeekSeconds / 60))
        RecordLine("Longest streak", if (stats.longestStreak == 1) "1 day" else "${stats.longestStreak} days")
        RecordLine("Summits", "${stats.allSummits}")
        RecordLine("You focus best", peakHour?.let { clockHour(it) } ?: "Not enough yet", last = true)
    }
}

private fun clockHour(hour: Int): String {
    val next = (hour + 1) % 24
    fun f(h: Int) = if (ClockPrefs.is24h) String.format(Locale.US, "%02d:00", h) else (if (h % 12 == 0) 12 else h % 12).toString() + (if (h < 12) " am" else " pm")
    return "${f(hour)} to ${f(next)}"
}

@Composable
private fun RecordLine(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 14.sp), modifier = Modifier.weight(1f))
        Text(value, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), maxLines = 1)
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
}

// ---------------------------------------------------------------------------------------------
// Weekly recap
// ---------------------------------------------------------------------------------------------

private fun recapText(s: Stats): String {
    val daysOn = s.week.count { it > 0f }
    val diff = s.weekSeconds - s.lastWeekSeconds
    val vs = when {
        s.lastWeekSeconds == 0 -> ""
        diff >= 0 -> " (${formatMinutes(diff / 60)} more than last week)"
        else -> " (${formatMinutes(-diff / 60)} less than last week)"
    }
    return "My week on LockIn: ${formatMinutes(s.weekSeconds / 60)} locked in across $daysOn ${if (daysOn == 1) "day" else "days"}$vs." +
        (if (s.streak > 1) " ${s.streak}-day streak." else "") + " Climb with me."
}

/** This week at a glance, with a button that shares it as a message. */
@Composable
internal fun RecapCard(stats: Stats, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val daysOn = stats.week.count { it > 0f }
    val bestIndex = stats.week.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0f }?.index
    val max = (stats.week.maxOrNull() ?: 0f).coerceAtLeast(0.01f)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(0.8f, 80f)) }
    JourneyCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("This week", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                Text(
                    "${formatMinutes(stats.weekSeconds / 60)} · $daysOn ${if (daysOn == 1) "day" else "days"}" + if (stats.lastWeekSeconds > 0) " · ${stats.weekDelta} on last week" else "",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, LockInColors.Rim, CircleShape)
                    .springClick { shareText(context, recapText(stats)) }
                    .semantics { contentDescription = "Share this week" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Share, LockInColors.Snow, boxSize = 20.dp, strokeWidth = 2f) }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for (i in 0 until 7) {
                val h = stats.week[i]
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((6 + 36 * (h / max) * grow.value).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (i == bestIndex) LockInColors.Lime else if (h > 0f) LockInColors.Lime.copy(alpha = 0.45f) else LockInColors.Graphite),
                        )
                    }
                    Text(
                        DayOfWeek.of(i + 1).getDisplayName(DateStyle.NARROW, Locale.getDefault()),
                        style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (bestIndex != null) {
            Text(
                "Best day: ${DayOfWeek.of(bestIndex + 1).getDisplayName(DateStyle.FULL, Locale.getDefault())}, ${formatMinutes((stats.week[bestIndex] * 60).toInt())}",
                style = LockInText.Small.copy(color = LockInColors.Body),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------------------------------------------------------------------------------------
// The year
// ---------------------------------------------------------------------------------------------

private val Cell = 14.dp
private val CellGap = 3.dp
private const val Weeks = 53

/** The last year, a square per day, brighter the closer you got to your goal. Scrolls sideways; tap a day for its numbers. */
@Composable
internal fun YearPage(stats: Stats, goalMin: Int, onBack: () -> Unit) {
    val today = remember { LocalDate.now() }
    val start = remember { today.minusWeeks((Weeks - 1).toLong()).with(DayOfWeek.MONDAY) }
    val goalSeconds = (goalMin * 60).coerceAtLeast(900)
    var sel by remember { mutableLongStateOf(-1L) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { if (scroll.value == 0) scroll.scrollTo(scroll.maxValue) }

    val yearStart = today.minusDays(364).toEpochDay()
    val activeDays = stats.dayTotals.count { it.key >= yearStart && it.value > 0 }
    val yearSeconds = stats.dayTotals.filter { it.key >= yearStart }.values.sum()
    val capped = stats.exportRows.size >= 1000

    YouSubFrame("Your year", "Every day you locked in.", onBack) { entrance ->
        JourneyCard(Modifier.rise(entrance, 1)) {
            Row {
                YearNumber("Focused", formatMinutes(yearSeconds / 60), Modifier.weight(1f))
                YearNumber("Active days", "$activeDays", Modifier.weight(1f))
                YearNumber("Best streak", "${stats.longestStreak}", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        JourneyCard(Modifier.rise(entrance, 2)) {
            val measurer = rememberTextMeasurer()
            val labelStyle = remember { TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 10.sp) }
            val monthLabels = remember(start) {
                (0 until Weeks).mapNotNull { w ->
                    val d = start.plusWeeks(w.toLong())
                    if (w == 0 || d.month != d.minusWeeks(1).month) w to d.month.getDisplayName(DateStyle.SHORT, Locale.getDefault()) else null
                }
            }
            val lay = remember(monthLabels, measurer) { monthLabels.map { it.first to measurer.measure(AnnotatedString(it.second), labelStyle) } }
            Row {
                // Day names stay put while the grid scrolls.
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(CellGap)) {
                    for (r in 0 until 7) {
                        Box(Modifier.height(Cell).width(28.dp), contentAlignment = Alignment.CenterStart) {
                            if (r % 2 == 0) Text(DayOfWeek.of(r + 1).getDisplayName(DateStyle.SHORT, Locale.getDefault()), style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 10.sp, lineHeight = 12.sp), maxLines = 1)
                        }
                    }
                }
                Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                    val widthDp = (Cell + CellGap) * Weeks
                    val heightDp = 18.dp + (Cell + CellGap) * 7
                    Canvas(
                        Modifier
                            .size(widthDp, heightDp)
                            .semantics { contentDescription = "Year of activity. $activeDays active days." }
                            .pointerInput(start) {
                                detectTapGestures { p ->
                                    val step = (Cell + CellGap).toPx()
                                    val col = (p.x / step).toInt()
                                    val row = ((p.y - 18.dp.toPx()) / step).toInt()
                                    if (col in 0 until Weeks && row in 0..6) {
                                        val d = start.plusDays((col * 7 + row).toLong())
                                        sel = if (d <= today) d.toEpochDay() else -1L
                                    }
                                }
                            },
                    ) {
                        val step = (Cell + CellGap).toPx()
                        val cell = Cell.toPx()
                        val top = 18.dp.toPx()
                        lay.forEach { (w, text) -> drawText(text, color = LockInColors.Mist, topLeft = Offset(w * step, 0f)) }
                        for (w in 0 until Weeks) for (r in 0..6) {
                            val d = start.plusDays((w * 7 + r).toLong())
                            if (d > today) continue
                            val secs = stats.dayTotals[d.toEpochDay()] ?: 0
                            val color = if (secs <= 0) LockInColors.Graphite else LockInColors.Lime.copy(alpha = 0.3f + 0.7f * (secs.toFloat() / goalSeconds).coerceIn(0f, 1f))
                            drawRoundRect(color, Offset(w * step, top + r * step), Size(cell, cell), CornerRadius(3.dp.toPx()))
                            if (d.toEpochDay() == sel) drawRoundRect(LockInColors.Snow, Offset(w * step, top + r * step), Size(cell, cell), CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = sel,
                transitionSpec = { (slideInVertically(spring(1f, 500f)) { it / 2 } togetherWith slideOutVertically(spring(1f, 500f)) { -it / 2 }).using(null) },
                label = "year-detail",
            ) { day ->
                val secs = stats.dayTotals[day] ?: 0
                val n = stats.dayCounts[day] ?: 0
                Text(
                    when {
                        day < 0 -> "Tap a day to see it."
                        secs <= 0 -> "${dayText(day)} · nothing locked in"
                        else -> "${dayText(day)} · ${formatMinutes(maxOf(secs / 60, 1))} · $n ${if (n == 1) "session" else "sessions"}"
                    },
                    style = LockInText.Small.copy(color = if (day < 0) LockInColors.Mist else LockInColors.Snow),
                )
            }
        }
        if (capped) {
            Spacer(Modifier.height(10.dp))
            Text("This shows your latest 1,000 sessions, so a very busy year may start part way through.", style = LockInText.Small, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun YearNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp), maxLines = 1)
    }
}
