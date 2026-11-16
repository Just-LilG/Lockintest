package com.godbless.lockin.home

import com.godbless.lockin.ui.calmFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The ranks on the way up. Every focused hour is 100 m, so the summit is 60 hours of locking in. */
internal val Ranks = listOf("Foothills" to 0, "Ridge" to 2000, "Face" to 4000, "Summit" to 6000)

internal val YouCard = RoundedCornerShape(22.dp)
private val HeroShape = RoundedCornerShape(26.dp)
// Steps between the background and the text colour, so the altitude scene follows the theme.
private val HeroSky: Color get() = LockInColors.tone(0.02f)
private val RidgeBack: Color get() = LockInColors.tone(if (LockInColors.palette.dark) 0.055f else 0.11f)
private val RidgeFront: Color get() = LockInColors.tone(if (LockInColors.palette.dark) 0.085f else 0.20f)
private val DayAhead: Color get() = LockInColors.tone(0.065f)

/** One soft spring for everything on the You tab, so the motion feels like one piece. */
private fun <T> youSpring() =
    if (com.godbless.lockin.ui.MotionState.reduced) spring<T>(dampingRatio = 1f, stiffness = 3000f) else spring<T>(dampingRatio = 0.82f, stiffness = 110f)

internal fun altitudeOf(totalHours: Float): Int = (totalHours * 100f).roundToInt()

/** A tap target that sinks a little under your finger and springs back. No fades. */
internal fun Modifier.springClick(role: Role = Role.Button, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// ---------------------------------------------------------------------------------------------
// Profile header
// ---------------------------------------------------------------------------------------------

/** Your avatar in a ring that fills with today's progress toward your goal, your name, and Edit. */
@Composable
internal fun ProfileHeader(
    name: String,
    since: String,
    todayFraction: Float,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    rank: String = "",
    tint: Color? = null,
    glyph: String? = null,
    onAvatar: (() -> Unit)? = null,
) {
    val fill by animateFloatAsState(todayFraction.coerceIn(0f, 1f), youSpring(), label = "today-ring")
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(62.dp)) {
                val stroke = 3.5.dp.toPx()
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(LockInColors.Graphite, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (fill > 0f) drawArc(LockInColors.Lime, -90f, 360f * fill, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Avatar(
                name, boxSize = 48.dp, filled = true, ring = LockInColors.Ink, tint = tint, glyph = glyph,
                modifier = if (onAvatar != null) Modifier.springClick(onClick = onAvatar).semantics { contentDescription = "Change your avatar" } else Modifier,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = LockInText.Title.copy(fontSize = 26.sp, lineHeight = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rank.isNotBlank()) Text(rank, style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(since, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1)
        }
        Box(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .border(1.dp, LockInColors.Rim, CircleShape)
                .springClick(onClick = onEdit)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Edit", style = LockInText.Label.copy(color = LockInColors.Snow)) }
        if (onSettings != null) {
            Spacer(Modifier.width(8.dp))
            // Settings: theme, colours and the rest.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, LockInColors.Rim, CircleShape)
                    .springClick(onClick = onSettings)
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Gear, LockInColors.Snow, boxSize = 18.dp, strokeWidth = 1.9f) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The mountain
// ---------------------------------------------------------------------------------------------

/**
 * Your climb as a mountain. The ridges spring up into place, contour lines drift slowly behind them, and your
 * dot walks up the trail while the altitude counts up.
 */
@Composable
internal fun AltitudeHero(altitude: Int, modifier: Modifier = Modifier) {
    val top = Ranks.last().second.toFloat()
    // Past the summit you start the next range: the mountain resets, and the counter keeps going.
    val ranges = altitude / top.toInt()
    val local = altitude % top.toInt()
    val target = (local / top).coerceIn(0f, 1f)
    val walk = remember { Animatable(0f) }
    val lift = remember { Animatable(1f) }
    LaunchedEffect(Unit) { lift.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 60f)) }
    LaunchedEffect(target) {
        delay(300)
        walk.animateTo(target, tween(1700, easing = UiGlide))
    }
    val drift by rememberInfiniteTransition(label = "contours").calmFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "drift",
    )
    val shown = if (target <= 0f) altitude else (altitude * (walk.value / target)).roundToInt()
    val rank = Ranks.last { it.second <= local }
    val next = Ranks.firstOrNull { it.second > local }

    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 10.sp) }
    val labels = remember(measurer) { Ranks.map { measurer.measure(AnnotatedString(it.first), labelStyle) } }

    Box(
        modifier
            .fillMaxWidth()
            .height(184.dp)
            .clip(HeroShape)
            .background(HeroSky)
            .border(1.dp, LockInColors.Graphite, HeroShape)
            .semantics {
                contentDescription = "Altitude ${String.format(Locale.US, "%,d", altitude)} metres. " +
                    (if (ranges > 0) "Range ${ranges + 1}. " else "") + "Rank ${rank.first}." +
                    (if (next != null) " ${next.second - local} metres to ${next.first}." else "")
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Contour lines: gentle waves that slide sideways forever (one period per loop, so it never jumps).
            val period = w / 1.6f
            for (i in 0 until 3) {
                val y = h * (0.20f + 0.13f * i)
                val amp = h * 0.035f
                val shift = (drift + i * 0.33f) % 1f * period
                val wave = Path().apply {
                    var x = -period + shift
                    moveTo(x, y)
                    while (x < w + period) {
                        quadraticBezierTo(x + period / 4f, y - amp, x + period / 2f, y)
                        quadraticBezierTo(x + period * 0.75f, y + amp, x + period, y)
                        x += period
                    }
                }
                drawPath(wave, LockInColors.Graphite, style = Stroke(1.dp.toPx()))
            }

            // The ridges rise into place, the far one a little behind the near one.
            translate(top = lift.value * 40.dp.toPx()) {
                val back = Path().apply {
                    moveTo(0f, h * 0.78f)
                    lineTo(w * 0.15f, h * 0.58f); lineTo(w * 0.27f, h * 0.66f); lineTo(w * 0.44f, h * 0.38f)
                    lineTo(w * 0.55f, h * 0.47f); lineTo(w * 0.70f, h * 0.24f); lineTo(w * 0.80f, h * 0.35f)
                    lineTo(w * 0.92f, h * 0.22f); lineTo(w, h * 0.30f); lineTo(w, h + 60f); lineTo(0f, h + 60f); close()
                }
                drawPath(back, RidgeBack)
            }
            translate(top = lift.value * 70.dp.toPx()) {
                val front = Path().apply {
                    moveTo(0f, h * 0.90f)
                    lineTo(w * 0.18f, h * 0.70f); lineTo(w * 0.33f, h * 0.80f); lineTo(w * 0.50f, h * 0.56f)
                    lineTo(w * 0.64f, h * 0.67f); lineTo(w * 0.80f, h * 0.47f); lineTo(w, h * 0.56f)
                    lineTo(w, h + 90f); lineTo(0f, h + 90f); close()
                }
                drawPath(front, RidgeFront)
            }

            // The trail starts well inside the card, so your dot is never clipped by the corner.
            val trail = Path().apply {
                moveTo(w * 0.09f, h * 0.84f)
                cubicTo(w * 0.25f, h * 0.78f, w * 0.33f, h * 0.73f, w * 0.44f, h * 0.68f)
                cubicTo(w * 0.56f, h * 0.62f, w * 0.64f, h * 0.57f, w * 0.73f, h * 0.52f)
                cubicTo(w * 0.81f, h * 0.48f, w * 0.86f, h * 0.45f, w * 0.91f, h * 0.42f)
            }
            translate(top = lift.value * 70.dp.toPx()) {
                val pm = PathMeasure().apply { setPath(trail, false) }
                val length = pm.length
                drawPath(
                    trail, if (LockInColors.palette.dark) LockInColors.Rim else LockInColors.Edge,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))),
                )
                if (walk.value > 0f) {
                    val walked = Path()
                    pm.getSegment(0f, length * walk.value, walked, true)
                    drawPath(walked, LockInColors.Lime, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
                Ranks.forEachIndexed { i, r ->
                    val p = pm.getPosition(length * (r.second / top))
                    val reached = walk.value * top + 0.5f >= r.second
                    val lay = labels[i]
                    val color = if (reached) LockInColors.Frost else LockInColors.Mist
                    when {
                        i == Ranks.lastIndex -> {
                            val pole = 15.dp.toPx()
                            val flagColor = if (reached) LockInColors.Lime else LockInColors.Edge
                            drawLine(flagColor, p, Offset(p.x, p.y - pole), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                            val flag = Path().apply {
                                moveTo(p.x, p.y - pole); lineTo(p.x + 10.dp.toPx(), p.y - pole + 4.dp.toPx()); lineTo(p.x, p.y - pole + 8.dp.toPx()); close()
                            }
                            drawPath(flag, flagColor)
                        }
                        i == 0 -> drawCircle(LockInColors.Lime, radius = 4.dp.toPx(), center = p)
                        else -> {
                            drawCircle(HeroSky, radius = 6.5.dp.toPx(), center = p)
                            if (reached) drawCircle(LockInColors.Lime, radius = 4.dp.toPx(), center = p)
                            else drawCircle(LockInColors.Edge, radius = 4.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
                            drawText(lay, color = color, topLeft = Offset(p.x - lay.size.width / 2f, p.y - 10.dp.toPx() - lay.size.height))
                        }
                    }
                }
                val me = pm.getPosition(length * walk.value)
                drawCircle(LockInColors.Lime.copy(alpha = 0.22f), radius = 12.dp.toPx(), center = me)
                drawCircle(LockInColors.Snow, radius = 7.dp.toPx(), center = me)
                drawCircle(HeroSky, radius = 2.5.dp.toPx(), center = me)
            }
        }

        Column(Modifier.padding(start = 16.dp, top = 14.dp)) {
            Text("Altitude", style = LockInText.Small.copy(color = LockInColors.Silver))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%,d", shown), style = LockInText.Title.copy(fontSize = 30.sp, lineHeight = 34.sp))
                Text("m", style = LockInText.Sub.copy(color = LockInColors.Silver, fontSize = 13.sp), modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp), horizontalAlignment = Alignment.End) {
            Text(if (ranges > 0) "${rank.first} · Range ${ranges + 1}" else rank.first, style = LockInText.Label.copy(color = LockInColors.Frost, fontSize = 13.sp))
            Text(
                if (next != null) "${String.format(Locale.US, "%,d", next.second - local)} m to ${next.first}" else "You made it",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Today and this month, side by side
// ---------------------------------------------------------------------------------------------

/** Today against your goal as a ring, the goal's - and +, and a dot for each day you locked in this week. */
@Composable
internal fun TodayCard(goalMin: Int, todaySeconds: Int, week: FloatArray, streak: Int, onGoal: () -> Unit, modifier: Modifier = Modifier, restToday: Boolean = false, freezeUsed: Boolean = false) {
    val fraction = if (goalMin <= 0) 0f else (todaySeconds / 60f / goalMin).coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        delay(200)
        sweep.animateTo(fraction, youSpring())
    }
    val todayIndex = LocalDate.now().dayOfWeek.value - 1
    Column(
        modifier
            .clip(YouCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, YouCard)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Today", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(LockInColors.Graphite, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (sweep.value > 0f) {
                    drawArc(LockInColors.Lime, -90f, 360f * sweep.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatMinutes(todaySeconds / 60), style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 24.sp))
                AnimatedContent(
                    targetState = goalMin,
                    modifier = Modifier.clipToBounds(),
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(youSpring()) { if (up) it else -it } togetherWith
                            slideOutVertically(youSpring()) { if (up) -it else it }).using(null)
                    },
                    label = "goal",
                ) { m -> Text("of ${formatMinutes(m)}", style = LockInText.Small.copy(color = LockInColors.Mist)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .border(1.dp, LockInColors.Rim, CircleShape)
                .springClick(onClick = onGoal)
                .padding(horizontal = 14.dp)
                .semantics { contentDescription = "Daily goal ${formatMinutes(goalMin)}. Tap to change" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Goal", style = LockInText.Small.copy(color = LockInColors.Silver))
            Text(formatMinutes(goalMin), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
            GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 12.dp, strokeWidth = 2f)
        }
        Spacer(Modifier.height(12.dp))
        WeekDots(week, todayIndex)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                restToday && streak > 0 -> "Rest day · $streak-day streak"
                restToday -> "Rest day"
                freezeUsed -> "$streak days · freeze used"
                streak == 0 -> "No streak yet"
                streak == 1 -> "1-day streak"
                else -> "$streak-day streak"
            },
            style = LockInText.Small.copy(fontSize = 11.sp, lineHeight = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A dot per day this week. Each one springs up in turn; lime where you locked in, a ring around today. */
@Composable
private fun WeekDots(week: FloatArray, today: Int) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(350)
        pop.animateTo(1f, tween(700, easing = LinearEasing))
    }
    Row(
        Modifier.semantics { contentDescription = "${week.count { it > 0f }} of the last days this week you locked in" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 7) {
            val t = ((pop.value * 8f - i) ).coerceIn(0f, 1f)
            val scale by animateFloatAsState(if (t > 0f) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "dot")
            val on = week[i] > 0f
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(if (on) LockInColors.Lime else LockInColors.Graphite)
                    .then(if (i == today && !on) Modifier.border(1.5.dp, LockInColors.Snow, CircleShape) else Modifier),
            )
        }
    }
}

/** This month: hours and how it compares, a small calendar that sweeps in, and your longest session and summits. */
@Composable
internal fun MonthCard(monthHours: FloatArray, goalMin: Int, delta: String, longest: String, summits: Int, modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val days = month.lengthOfMonth()
    val blanks = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val total = monthHours.sum()
    val goalHours = (goalMin / 60f).coerceAtLeast(0.25f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        sweep.animateTo(1f, tween(1000, easing = UiGlide))
    }
    Column(
        modifier
            .clip(YouCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, YouCard)
            .springClick(onClick = onOpen)
            .padding(14.dp)
            .semantics { contentDescription = "This month ${formatMinutes((total * 60).roundToInt())}. $delta. Tap to see your whole year" },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault()), style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.weight(1f))
                GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 12.dp, strokeWidth = 2f)
            }
            Text(formatMinutes((total * 60).roundToInt()), style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 24.sp))
            Text(
                delta,
                style = LockInText.Small.copy(
                    color = when {
                        delta.startsWith("-") -> LockInColors.Flare
                        delta.startsWith("+") -> LockInColors.Lime
                        else -> LockInColors.Mist
                    },
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        val rows = (blanks + days + 6) / 7
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (c in 0 until 7) {
                        val index = r * 7 + c - blanks
                        val cellShape = RoundedCornerShape(3.dp)
                        // Squares grow in one after another across the month.
                        val grow = ((sweep.value * (days + 6)) - index).coerceIn(0f, 1f)
                        val cell = Modifier.weight(1f).aspectRatio(1f)
                        if (index < 0 || index >= days) {
                            Spacer(cell)
                        } else {
                            val future = index + 1 > today.dayOfMonth
                            val hours = monthHours.getOrElse(index) { 0f }
                            val color = when {
                                future -> DayAhead
                                hours <= 0f -> LockInColors.Graphite
                                else -> LockInColors.Lime.copy(alpha = 0.3f + 0.7f * (hours / goalHours).coerceIn(0f, 1f))
                            }
                            val isToday = index + 1 == today.dayOfMonth
                            Box(
                                cell
                                    .graphicsLayer { scaleX = grow; scaleY = grow }
                                    .clip(cellShape)
                                    .background(color)
                                    .then(if (isToday) Modifier.border(1.5.dp, LockInColors.Snow, cellShape) else Modifier),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MiniLine("Longest", longest)
            MiniLine("Summits", "$summits")
        }
    }
}

@Composable
private fun MiniLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp), modifier = Modifier.weight(1f))
        Text(value, style = LockInText.Small.copy(color = LockInColors.Frost, fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Settings rows with glyph tiles
// ---------------------------------------------------------------------------------------------

@Composable
internal fun TileRow(glyph: Glyph, title: String, trailing: String?, onClick: () -> Unit, danger: Boolean = false) {
    val tint = if (danger) LockInColors.Flare else LockInColors.Lime
    Row(
        Modifier.fillMaxWidth().springClick(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (danger) LockInColors.Flare.copy(alpha = 0.14f) else LockInColors.LimeWash),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, tint, boxSize = 16.dp, strokeWidth = 2f) }
        Text(
            title,
            style = LockInText.Label.copy(color = if (danger) LockInColors.Flare else LockInColors.Snow, fontSize = 15.sp),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            maxLines = 1,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
        if (!danger) GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2f)
    }
}

@Composable
internal fun TileDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp).height(1.dp).background(LockInColors.Graphite))
}
