@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.LockRepeat
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.UiGlide
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RepeatShape = RoundedCornerShape(22.dp)
private val DayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
private val DayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val RepeatLengths = listOf(30, 60, 90, 120, 180, 240)
private const val Weekdays = 0b0011111
private const val Weekend = 0b1100000
private const val EveryDay = 0b1111111

/** "Weekdays", "Every day", "Weekends" or "Mon, Wed, Fri". */
internal fun daysText(days: Int): String = when (days) {
    EveryDay -> "Every day"
    Weekdays -> "Weekdays"
    Weekend -> "Weekends"
    else -> DayNames.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
}

internal fun timeText(startMinute: Int): String =
    LocalTime.of(startMinute / 60, startMinute % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

/** "Weekdays at 8:00 pm for 2h". */
internal fun repeatText(r: LockRepeat): String = "${daysText(r.days)} at ${timeText(r.startMinute)} for ${formatMinutes(r.seconds / 60)}"

/**
 * Repeating locks on your partner: the ones you've set, and a composer to add one. Each starts on its own at its
 * time (the server starts it even if their phone is off), within their lock rules.
 */
@Composable
internal fun RepeatsSection(lock: LockState, modifier: Modifier = Modifier) {
    val name = LocalPartnerName.current
    val mine = lock.repeats.filter { it.mine }
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))) {
        if (mine.isEmpty() && !adding) {
            Text(
                "Lock $name at the same time on the days you choose, like every weekday evening. It starts by itself.",
                style = LockInText.Small.copy(color = LockInColors.Body),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        mine.forEach { r ->
            RepeatCard(r, onDelete = { lock.removeRepeat(r.id) }, busy = lock.busy)
            Spacer(Modifier.height(10.dp))
        }
        AnimatedContent(
            targetState = adding,
            transitionSpec = {
                (slideInVertically(tween(320, easing = UiGlide)) { it / 3 } togetherWith slideOutVertically(tween(240, easing = UiGlide)) { -it / 3 })
                    .using(null)
            },
            modifier = Modifier.clipToBounds(),
            label = "repeat-add",
        ) { open ->
            if (open) {
                RepeatComposer(lock, onDone = { adding = false }, onCancel = { adding = false; lock.error = null })
            } else {
                LockInButton("Add a repeating lock", onClick = { adding = true }, kind = ButtonKind.Outline)
            }
        }
    }
}

@Composable
private fun RepeatCard(r: LockRepeat, onDelete: () -> Unit, busy: Boolean) {
    var confirm by rememberSaveable(r.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RepeatShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, RepeatShape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timeText(r.startMinute), style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp))
                Text("${daysText(r.days)} · ${formatMinutes(r.seconds / 60)}", style = LockInText.Small.copy(color = LockInColors.Silver))
            }
            SmallPill(if (confirm) "Keep" else "Delete", onClick = { confirm = !confirm })
        }
        Spacer(Modifier.height(10.dp))
        DayDots(r.days)
        if (r.message.isNotBlank() || r.strict) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(r.message.takeIf { it.isNotBlank() }?.let { "“$it”" }, if (r.strict) "No unlock requests" else null).joinToString(" · "),
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
        }
        Collapse(confirm) {
            Column {
                Spacer(Modifier.height(12.dp))
                LockInButton("Delete this repeat", onClick = onDelete, loading = busy, enabled = !busy)
            }
        }
    }
}

/** Seven small dots, lime on the days it repeats. */
@Composable
private fun DayDots(days: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayLetters.forEachIndexed { i, letter ->
            val on = days and (1 shl i) != 0
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (on) LockInColors.Lime else LockInColors.Graphite),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = LockInText.Small.copy(color = if (on) LockInColors.OnLime else LockInColors.Mist, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun RepeatComposer(lock: LockState, onDone: () -> Unit, onCancel: () -> Unit) {
    val name = LocalPartnerName.current
    val capMinutes = ((lock.rules?.theirs?.max ?: 28800) / 60).coerceIn(5, 480)
    var days by rememberSaveable { mutableStateOf(Weekdays) }
    var start by rememberSaveable { mutableStateOf(20 * 60) }
    var minutes by rememberSaveable { mutableStateOf(120) }
    var message by rememberSaveable { mutableStateOf("") }
    var strict by rememberSaveable { mutableStateOf(false) }
    val shownMinutes = minutes.coerceAtMost(capMinutes)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RepeatShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, RepeatShape)
            .padding(16.dp),
    ) {
        Text("Days", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayLetters.forEachIndexed { i, letter ->
                val on = days and (1 shl i) != 0
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(if (on) LockInColors.Lime else LockInColors.Graphite)
                        .springClick(role = Role.Checkbox) { days = days xor (1 shl i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter, style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Body, fontSize = 13.sp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickPick("Weekdays", days == Weekdays) { days = Weekdays }
            QuickPick("Every day", days == EveryDay) { days = EveryDay }
            QuickPick("Weekends", days == Weekend) { days = Weekend }
        }

        Spacer(Modifier.height(18.dp))
        Text("Starts at", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = start,
                modifier = Modifier.weight(1f).clipToBounds(),
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(220, easing = UiGlide)) { if (up) it else -it } togetherWith
                        slideOutVertically(tween(220, easing = UiGlide)) { if (up) -it else it }).using(null)
                },
                label = "repeat-time",
            ) { m -> Text(timeText(m), style = LockInText.Headline.copy(fontSize = 34.sp, lineHeight = 40.sp)) }
            StepButton(Glyph.Minus) { start = (start - 15 + 1440) % 1440 }
            Spacer(Modifier.size(8.dp))
            StepButton(Glyph.Plus) { start = (start + 15) % 1440 }
        }

        Spacer(Modifier.height(16.dp))
        Text("For", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RepeatLengths.forEach { len ->
                val enabled = len <= capMinutes
                val on = shownMinutes == len
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(if (on) LockInColors.Lime else LockInColors.Graphite)
                        .springClick(role = Role.RadioButton, enabled = enabled) { minutes = len },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        formatMinutes(len),
                        style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else if (enabled) LockInColors.Body else LockInColors.Edge, fontSize = 12.sp),
                    )
                }
            }
        }
        if (capMinutes < RepeatLengths.last()) {
            Spacer(Modifier.height(6.dp))
            Text("$name accepts locks up to ${formatMinutes(capMinutes)}.", style = LockInText.Small)
        }

        Spacer(Modifier.height(16.dp))
        LockInField("Note (optional)", message, { if (it.length <= 140) message = it }, "Revision time. Phone down.")
        Spacer(Modifier.height(12.dp))
        ToggleRow("No unlock requests", strict, { strict = it })

        Spacer(Modifier.height(14.dp))
        Text(
            if (days == 0) "Pick at least one day."
            else "$name gets locked ${daysText(days).lowercase(Locale.getDefault()).let { if (days == EveryDay) it else "on $it" }} at ${timeText(start)} for ${formatMinutes(shownMinutes)}.",
            style = LockInText.Small.copy(color = if (days == 0) LockInColors.Flare else LockInColors.Body),
        )
        lock.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = LockInText.Error)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Cancel", onClick = onCancel, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton(
                "Save",
                onClick = { lock.addRepeat(days, start, shownMinutes, message.trim(), strict) { onDone() } },
                modifier = Modifier.weight(1f),
                loading = lock.busy,
                enabled = days != 0 && !lock.busy,
            )
        }
    }
}

@Composable
private fun QuickPick(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .border(1.dp, if (on) LockInColors.Lime else LockInColors.Rim, CircleShape)
            .springClick(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LockInText.Small.copy(color = if (on) LockInColors.Frost else LockInColors.Body))
    }
}

@Composable
private fun StepButton(glyph: Glyph, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .border(1.5.dp, LockInColors.Rim, CircleShape)
            .springClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, LockInColors.Snow, boxSize = 17.dp, strokeWidth = 2f)
    }
}

/** For the locked side: the repeats set on you, so nothing comes as a surprise. */
@Composable
internal fun RepeatsOnMe(lock: LockState, modifier: Modifier = Modifier) {
    val onMe = lock.repeats.filter { !it.mine }
    if (onMe.isEmpty()) return
    val name = LocalPartnerName.current
    Column(modifier.fillMaxWidth()) {
        Text("Repeating locks $name set on you", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(6.dp))
        onMe.forEach { r ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(LockInColors.Lime))
                Text(repeatText(r), style = LockInText.Small.copy(color = LockInColors.Body), modifier = Modifier.padding(start = 10.dp))
            }
        }
        Text(
            "They start by themselves. Switching locks off in your rules stops them.",
            style = LockInText.Small,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
