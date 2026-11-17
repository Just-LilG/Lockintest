package com.godbless.lockin.home

import com.godbless.lockin.ui.firstGlyph
import com.godbless.lockin.ui.calmFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(24.dp)

// ---------------------------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------------------------

@Composable
internal fun Avatar(
    letter: String,
    modifier: Modifier = Modifier,
    boxSize: androidx.compose.ui.unit.Dp = 46.dp,
    filled: Boolean = false,
    ring: Color = LockInColors.Slate,
    /** A chosen colour and a symbol to show instead of the initial (the classic look). */
    tint: Color? = null,
    glyph: String? = null,
    /** This is you: your own avatar choice (illustrated, or colour and symbol) is used. */
    me: Boolean = false,
    /** Picker preview only: show this style ("" for classic) and shuffle code instead of the saved ones. */
    previewStyle: String? = null,
    previewSeed: String? = null,
    previewLook: String? = null,
) {
    // You get what you picked. Everyone else is drawn from what the server says they picked (AvatarBook), or by DiceBear's
    // default style from a hash of their first name if they never chose.
    var style: String
    var seed = DiceBear.seedFor(letter)
    var look = ""
    var useTint = tint
    var useGlyph = glyph
    if (me) {
        style = previewStyle ?: Identity.avatarStyle
        look = previewLook ?: Identity.avatarLook
        seed = (previewSeed ?: Identity.avatarSeed).ifBlank { Identity.defaultSeed.ifBlank { seed } }
        if (previewStyle == null) {
            useTint = tint ?: Identity.tint
            useGlyph = glyph ?: Identity.glyph
        }
    } else if (tint == null && glyph == null) {
        val spec = com.godbless.lockin.data.AvatarBook[letter]
        val specStyle = spec?.style
        if (spec == null) {
            style = DiceBear.DefaultStyle
        } else if (specStyle == null) {
            style = DiceBear.DefaultStyle
            seed = spec.seed.ifBlank { seed }
        } else if (specStyle.isEmpty()) {
            style = ""
            useTint = AvatarColors.getOrNull(spec.color)
            useGlyph = spec.glyph.ifBlank { null }
        } else {
            // Only styles this app knows are drawn; anything else (a newer app's style) falls back to the default.
            style = if (DiceBear.Styles.any { it.first == specStyle }) specStyle else DiceBear.DefaultStyle
            seed = spec.seed.ifBlank { seed }
            look = spec.look
        }
    } else {
        style = ""
    }
    val art = if (style.isNotEmpty()) rememberDiceBear(style, seed, look) else null
    val bg = useTint ?: if (filled) LockInColors.Snow else LockInColors.Graphite
    val fg = if (useTint != null) LockInColors.Ink else if (filled) LockInColors.Ink else LockInColors.Snow
    Box(
        modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(ring)
            .padding(2.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            androidx.compose.foundation.Image(
                bitmap = art,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // The letter shows while the picture loads, and stays if it can't be fetched (offline).
            Text(useGlyph ?: letter.firstGlyph(), style = LockInText.Title.copy(fontSize = (boxSize.value * 0.4f).sp, color = fg))
        }
    }
}

@Composable
internal fun SmallPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(40.dp)
            .clip(ControlShape)
            .border(1.5.dp, LockInColors.Rim, ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp), modifier = Modifier.weight(1f))
        if (trailing != null) {
            Row(
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onTrailing).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(trailing, style = LockInText.Sub.copy(color = LockInColors.Silver))
                GlyphIcon(Glyph.Chevron, LockInColors.Silver, boxSize = 16.dp, strokeWidth = 2f)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Partner
// ---------------------------------------------------------------------------------------------

/** Who you are roped to: two overlapping avatars, the partner's status, and a nudge button. */
@Composable
internal fun PartnerCard(name: String, youLetter: String, onNudge: () -> Unit, modifier: Modifier = Modifier, status: String = "Roped to you") {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 84.dp, height = 48.dp)) {
            Avatar(youLetter, Modifier.align(Alignment.CenterStart), boxSize = 48.dp, me = true)
            Box(Modifier.align(Alignment.CenterEnd)) {
                Avatar(name, boxSize = 48.dp, filled = true)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Lime),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Text(status, style = LockInText.Small.copy(color = LockInColors.Body))
        }
        SmallPill("Nudge", onNudge)
    }
}

// ---------------------------------------------------------------------------------------------
// Next session
// ---------------------------------------------------------------------------------------------

/** The next planned session with your partner, with how long until it starts. */
@Composable
internal fun NextSessionCard(next: com.godbless.lockin.data.Plan?, partnerName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember(next) { System.currentTimeMillis() }
    val minutesLeft = if (next == null) 0 else ((next.startsAt - now) / 60_000L).toInt()
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(if (next == null) LockInColors.Graphite else LockInColors.Lime),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (next == null) Glyph.Plus else Glyph.Timer, if (next == null) LockInColors.Snow else LockInColors.OnLime, boxSize = 24.dp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Next session", style = LockInText.Small.copy(color = LockInColors.Mist))
            Text(
                next?.title ?: "Nothing planned yet",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                when {
                    next == null -> "Pick a time to study with $partnerName."
                    minutesLeft > 0 -> "${planWhen(next.startsAt)}. Starts in ${formatMinutes(minutesLeft)}."
                    else -> "Started at ${planWhen(next.startsAt).substringAfter(", ")}. Lock in now."
                },
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
    }
}

// ---------------------------------------------------------------------------------------------
// Stat tiles
// ---------------------------------------------------------------------------------------------

/** A number that counts up to [target] when it first appears. */
@Composable
internal fun StatTile(
    glyph: Glyph,
    target: Float,
    decimals: Int,
    suffix: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val v = remember { Animatable(0f) }
    LaunchedEffect(target) {
        delay(300)
        v.animateTo(target, tween(1100, easing = UiGlide))
    }
    val shown = if (decimals == 0) v.value.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", v.value)
    Column(
        modifier
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(16.dp),
    ) {
        GlyphIcon(glyph, LockInColors.Silver, boxSize = 22.dp)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(shown, style = LockInText.Title.copy(fontSize = 30.sp, lineHeight = 34.sp))
            if (suffix.isNotEmpty()) {
                Text(suffix, style = LockInText.Sub.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
            }
        }
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
    }
}

// ---------------------------------------------------------------------------------------------
// This week's range
// ---------------------------------------------------------------------------------------------

private val DayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
private val DayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * The week as seven bars, one per day, as tall as the time focused. Bars rise from the ground when the card
 * scrolls into view, today is lime, the dashed line is the daily goal, and tapping a day shows its time.
 */
@Composable
internal fun WeekRange(values: FloatArray, today: Int, goalHours: Float, modifier: Modifier = Modifier, visible: Boolean = true, partner: FloatArray? = null) {
    // The bars rise the first time the card scrolls into view in this run of the app; after that they are just there.
    val grow = remember { Animatable(if (HomeIntro.weekGrown) 1f else 0f) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (grow.value >= 1f) return@LaunchedEffect
        HomeIntro.weekGrown = true
        com.godbless.lockin.ui.EntranceGate.open()
        delay(150)
        grow.animateTo(1f, tween(1300, easing = UiGlide))
    }
    // The tapped day, or -1. Tapping it again puts the tip away.
    var picked by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val measurer = rememberTextMeasurer()
    val goalLabel = remember(measurer, LockInColors.palette, goalHours) {
        measurer.measure(
            AnnotatedString("${formatMinutes((goalHours * 60).toInt())} goal"),
            TextStyle(fontFamily = Fonts.Body, fontSize = 11.sp, color = LockInColors.Mist),
        )
    }
    val total = values.sum()
    val showPartner = partner != null && partner.any { it > 0f }
    val summary = values.indices.joinToString(", ") { "${DayNames[it]} ${formatMinutes((values[it] * 60f).roundToInt())}" }
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("This week", style = LockInText.Label.copy(color = LockInColors.Silver))
                Text(
                    heroMinutes((total * 60f * grow.value).roundToInt()),
                    style = LockInText.Title.copy(fontSize = 30.sp, lineHeight = 36.sp),
                )
            }
            val stats = LocalStats.current
            // Up is lime; level or down is quiet grey, so zero never looks like a win.
            Row(
                Modifier.height(30.dp).clip(CircleShape)
                    .background(if (stats.weekUp) LockInColors.LimeWash else LockInColors.Graphite)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!stats.weekFlat) GlyphIcon(
                    Glyph.ArrowUp,
                    if (stats.weekUp) LockInColors.Lime else LockInColors.Silver,
                    modifier = Modifier.graphicsLayer { rotationZ = if (stats.weekUp) 0f else 180f },
                    boxSize = 14.dp,
                    strokeWidth = 2.4f,
                )
                Text(
                    if (stats.weekFlat) "Same as last week" else "${stats.weekDelta} on last week",
                    style = LockInText.Label.copy(color = if (stats.weekUp || stats.weekFlat) LockInColors.Frost else LockInColors.Silver, fontSize = 12.sp),
                    modifier = Modifier.padding(start = if (stats.weekFlat) 0.dp else 5.dp),
                )
            }
        }
        val st = LocalStats.current
        Text(
            buildString {
                append(if (st.streak == 1) "1-day streak" else "${st.streak}-day streak")
                append("  ·  ")
                append(if (st.monthSessions == 1) "1 climb this month" else "${st.monthSessions} climbs this month")
            },
            style = LockInText.Small.copy(color = LockInColors.Mist),
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .semantics {
                    contentDescription = "Time focused each day this week. $summary."
                    // Screen readers can't tap a point on a drawing, so each day is an action of its own.
                    customActions = (0..6).map { i ->
                        CustomAccessibilityAction(DayNames[i] + " " + formatMinutes((values[i] * 60f).roundToInt())) {
                            picked = if (picked == i) -1 else i
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val padX = 12.dp.toPx()
                        val slot = (size.width - 2 * padX) / 7f
                        val i = ((pos.x - padX) / slot).toInt().coerceIn(0, 6)
                        picked = if (picked == i) -1 else i
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val padX = 12.dp.toPx()
            val base = h - 2.dp.toPx()
            val top = 30.dp.toPx()
            val usable = base - top
            val maxV = maxOf(goalHours * 1.25f, (values.maxOrNull() ?: 0f) * 1.12f, 0.25f)
            val slot = (w - 2 * padX) / 7f
            val barW = (slot * 0.52f).coerceAtMost(24.dp.toPx())
            val minBar = 4.dp.toPx()
            fun cx(i: Int) = padX + slot * (i + 0.5f)
            fun barH(v: Float) = (v / maxV).coerceIn(0f, 1f) * usable * grow.value

            // Daily goal.
            val goalY = base - (goalHours / maxV).coerceIn(0f, 1f) * usable
            drawLine(
                LockInColors.Rim, Offset(0f, goalY), Offset(w, goalY),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
            )
            drawText(goalLabel, topLeft = Offset(w - goalLabel.size.width - 2.dp.toPx(), goalY - goalLabel.size.height - 2.dp.toPx()))

            // A week with nothing in it yet: faint ghost bars and a line, instead of an empty hole.
            if (total <= 0f && !showPartner) {
                val ghost = floatArrayOf(0.34f, 0.52f, 0.42f, 0.64f, 0.38f, 0.56f, 0.28f)
                for (i in 0..6) {
                    val gh = ghost[i] * usable * grow.value
                    drawRoundRect(
                        LockInColors.Graphite.copy(alpha = 0.55f), Offset(cx(i) - barW / 2f, base - gh), Size(barW, gh),
                        CornerRadius(6.dp.toPx()),
                    )
                }
                val hint = measurer.measure(
                    AnnotatedString("Your week starts today"),
                    TextStyle(fontFamily = Fonts.Body, fontSize = 13.sp, color = LockInColors.Silver, fontWeight = FontWeight.Medium),
                )
                drawText(hint, topLeft = Offset((w - hint.size.width) / 2f, top + (usable - hint.size.height) / 2f))
            }

            // Your partner's days sit behind yours as faint bars, so "together" shows in the week too.
            if (showPartner && partner != null) {
                for (i in 0..6) {
                    val pv = partner[i]
                    if (pv <= 0f) continue
                    val ph = maxOf((pv / maxV).coerceIn(0f, 1f) * usable * grow.value, minBar)
                    drawRoundRect(
                        LockInColors.Mist.copy(alpha = 0.28f), Offset(cx(i) - barW / 2f, base - ph), Size(barW, ph),
                        CornerRadius(6.dp.toPx()),
                    )
                }
            }

            for (i in 0..6) {
                val v = values[i]
                val x = cx(i) - barW / 2f
                if (v <= 0f) {
                    if (total <= 0f && !showPartner) continue
                    when {
                        // A day that has passed with nothing in it: a solid rest mark.
                        i < today -> drawRoundRect(
                            LockInColors.Graphite, Offset(x, base - 3.dp.toPx()), Size(barW, 3.dp.toPx()),
                            CornerRadius(1.5.dp.toPx()),
                        )
                        // Today, still open: a lime mark that says "you can still fill this".
                        i == today -> drawRoundRect(
                            LockInColors.Lime.copy(alpha = 0.7f), Offset(x, base - 3.dp.toPx()), Size(barW, 3.dp.toPx()),
                            CornerRadius(1.5.dp.toPx()),
                        )
                        // A day to come: a thinner, fainter dash.
                        else -> drawRoundRect(
                            LockInColors.Graphite.copy(alpha = 0.5f), Offset(x + barW * 0.25f, base - 2.dp.toPx()), Size(barW * 0.5f, 2.dp.toPx()),
                            CornerRadius(1.dp.toPx()),
                        )
                    }
                    continue
                }
                val bh = maxOf(barH(v), minBar)
                val color = when {
                    i == picked -> LockInColors.Snow
                    i == today -> LockInColors.Lime
                    else -> LockInColors.Frost.copy(alpha = 0.55f)
                }
                drawRoundRect(color, Offset(x, base - bh), Size(barW, bh), CornerRadius(6.dp.toPx()))
            }

            // The tip for the tapped day, above its bar.
            if (picked in 0..6) {
                val v = values[picked]
                val text = measurer.measure(
                    AnnotatedString(formatMinutes((v * 60f).roundToInt())),
                    TextStyle(fontFamily = Fonts.Body, fontSize = 12.sp, color = LockInColors.Ink, fontWeight = FontWeight.SemiBold),
                )
                val pw = text.size.width + 16.dp.toPx()
                val ph = text.size.height + 8.dp.toPx()
                val barTop = base - maxOf(barH(v), minBar)
                val px = (cx(picked) - pw / 2f).coerceIn(0f, maxOf(0f, w - pw))
                val py = (barTop - ph - 6.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(LockInColors.Snow, Offset(px, py), Size(pw, ph), CornerRadius(ph / 2f))
                drawText(text, topLeft = Offset(px + 8.dp.toPx(), py + 4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp)) {
            for (i in 0..6) {
                val on = i == today || i == picked
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        DayLetters[i],
                        style = LockInText.Small.copy(
                            color = if (on) LockInColors.Snow else LockInColors.Mist,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Recent climbs
// ---------------------------------------------------------------------------------------------

/** "Locked by X" is saved with the name as it was then; show the name as it is now, so it matches the rest of Home. */
internal fun climbTitle(title: String, partner: String): String =
    if (title.startsWith("Locked by ") && partner.isNotBlank()) "Locked by $partner" else title

/** "Lock Sam's phone", or "Lock their phone" when the name is only emoji, which doesn't take an 's. */
internal fun partnerPossessive(name: String): String =
    if (name.any { it.isLetterOrDigit() }) "$name's" else "their"

@Composable
internal fun RecentClimbs(climbs: List<Climb>, modifier: Modifier = Modifier, visible: Boolean = true, quick: Int = 0) {
    val partner = LocalPartnerName.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape),
    ) {
        if (climbs.isEmpty()) {
            Text(
                "No climbs yet. Tap Start on the mountain to lock in.",
                style = LockInText.Sub.copy(color = LockInColors.Silver),
                modifier = Modifier.padding(18.dp),
            )
        }
        climbs.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 76.dp).height(1.dp).background(LockInColors.Graphite))
            val byPartner = c.title.startsWith("Locked by ")
            // Each row slides in from the right, one after the other, when the list scrolls into view.
            val slide = rememberSlideIn(visible, i, key = "recent-$i")
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = slide.value * size.width * 0.35f }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (c.summit) LockInColors.Lime else LockInColors.Graphite),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        if (byPartner && !c.summit) Glyph.Lock else Glyph.Peak,
                        if (c.summit) LockInColors.OnLime else if (byPartner) LockInColors.Lime else LockInColors.Silver,
                        boxSize = 22.dp,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        climbTitle(c.title, partner),
                        style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(c.whenText, style = LockInText.Small.copy(color = LockInColors.Mist))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMinutes(c.minutes), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                    Text(
                        when {
                            c.summit -> "Summit"
                            byPartner -> "Partner lock"
                            else -> "Focus"
                        },
                        style = LockInText.Small.copy(color = if (c.summit) LockInColors.Frost else LockInColors.Mist),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom bar
// ---------------------------------------------------------------------------------------------

/** [id] is the page the tab opens (page 1, Climbs, is no longer a tab: it opens from Home's "See all"). */
private class Tab(val id: Int, val label: String, val glyph: Glyph)

private val Tabs = listOf(
    Tab(0, "Home", Glyph.Peak),
    Tab(2, "Partner", Glyph.People),
    Tab(3, "Crew", Glyph.Crew),
    Tab(4, "You", Glyph.Person),
)

/** A floating bar. The bright pill under the active tab slides to wherever you tap. */
@Composable
internal fun HomeBottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape),
    ) {
        val itemW = maxWidth / Tabs.size
        val at = Tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        val x by animateDpAsState(itemW * at, spring(dampingRatio = 0.7f, stiffness = 380f), label = "tab-pill")
        Box(
            Modifier
                .offset(x = x)
                .width(itemW)
                .fillMaxHeight()
                .padding(6.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(LockInColors.Lime),
        )
        Row(Modifier.fillMaxSize()) {
            Tabs.forEach { tab ->
                val active = tab.id == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(30.dp))
                        .clickable(role = Role.Tab) { onSelect(tab.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphIcon(tab.glyph, if (active) LockInColors.Ink else LockInColors.Mist, boxSize = 24.dp, strokeWidth = 1.9f)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        style = LockInText.Small.copy(
                            color = if (active) LockInColors.Ink else LockInColors.Mist,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Side rail (tablets)
// ---------------------------------------------------------------------------------------------

/**
 * The tabs down the left side on a big screen, in a tall floating pill. The bright pill under the active tab
 * springs up and down to wherever you tap, like the bottom bar does sideways.
 */
@Composable
internal fun HomeRail(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    val item = 76.dp
    Box(
        modifier
            .width(76.dp)
            .height(item * Tabs.size + 12.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .padding(vertical = 6.dp),
    ) {
        val y by animateDpAsState(item * Tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0), spring(dampingRatio = 0.7f, stiffness = 380f), label = "rail-pill")
        Box(
            Modifier
                .offset(y = y)
                .fillMaxWidth()
                .height(item)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(LockInColors.Lime),
        )
        Column(Modifier.fillMaxWidth()) {
            Tabs.forEach { tab ->
                val active = tab.id == selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(item)
                        .clip(RoundedCornerShape(30.dp))
                        .clickable(role = Role.Tab) { onSelect(tab.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphIcon(tab.glyph, if (active) LockInColors.Ink else LockInColors.Mist, boxSize = 24.dp, strokeWidth = 1.9f)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = LockInText.Small.copy(
                            color = if (active) LockInColors.Ink else LockInColors.Mist,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Makes a full-screen page swallow every touch. Without it, a tap in a gap between its buttons (or anywhere it has no
 * clickable of its own) falls through to whatever is underneath, such as Home's cards behind the calculator.
 */
internal fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}


/**
 * A one-time line that says the climbers can be touched (tap to wave or nudge, hold for the time). It shows the
 * first time Home opens, stays about nine seconds or until tapped, and never comes back.
 */
@Composable
internal fun ClimberHint(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.applicationContext.getSharedPreferences("lockin_hints", android.content.Context.MODE_PRIVATE) }
    var show by remember { androidx.compose.runtime.mutableStateOf(!prefs.getBoolean("climber_touch", false)) }
    fun done() {
        show = false
        prefs.edit().putBoolean("climber_touch", true).apply()
    }
    LaunchedEffect(show) {
        if (show) {
            delay(9_000)
            done()
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.shrinkVertically(),
    ) {
        Text(
            "Tap a climber to wave. Hold one to see their time.",
            style = LockInText.Small.copy(color = LockInColors.Silver),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { done() }
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}
