@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import androidx.compose.ui.text.input.ImeAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Lengths = listOf(30, 45, 60, 90, 120)
private val Ideas = listOf("Revision", "Reading", "Problem set", "Essay")

/** When a plan starts, in epoch milliseconds, for a day [dayOffset] days from today at [hour]:[minute]. */
private fun startMillis(dayOffset: Int, hour: Int, minute: Int): Long =
    LocalDateTime.of(LocalDate.now().plusDays(dayOffset.toLong()), LocalTime.of(hour, minute))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** The next half hour that is at least 20 minutes away, as minutes since midnight (may pass 24 h). */
private fun suggestedMinute(): Int {
    val now = LocalTime.now()
    val m = now.hour * 60 + now.minute + 20
    return (m + 29) / 30 * 30
}

/** Plan a session with your partner: what, which day, what time and for how long. Both of you see it. */
@Composable
internal fun PlanSheet(
    busy: Boolean,
    error: String?,
    onSave: (title: String, startsAt: Long, minutes: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val partner = LocalPartnerName.current
    val first = remember { suggestedMinute() }
    var title by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(first / 1440) }
    var hour by rememberSaveable { mutableStateOf(first % 1440 / 60) }
    var minute by rememberSaveable { mutableStateOf(first % 60) }
    var length by rememberSaveable { mutableStateOf(60) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }

    val timeText = remember(hour, minute) {
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(LocalTime.of(hour, minute))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .readable(Widths.Sheet)
            .padding(horizontal = 20.dp),
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .border(1.5.dp, LockInColors.Rim, CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Close, LockInColors.Snow, boxSize = 18.dp, strokeWidth = 2f) }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(20.dp))
            Text("Plan a session", style = LockInText.Headline.copy(fontSize = 34.sp))
            Text("$partner sees it too and gets a reminder.", style = LockInText.Sub.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(24.dp))

            LockInField(
                label = "What are you studying?",
                value = title,
                onValueChange = { title = it.take(60) },
                placeholder = "Calculus revision",
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ideas.forEach { idea -> Chip(idea, on = title == idea) { title = idea } }
            }

            Spacer(Modifier.height(24.dp))
            Text("Day", style = LockInText.Label.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in 0..13) {
                    val date = LocalDate.now().plusDays(d.toLong())
                    val label = when (d) {
                        0 -> "Today"
                        1 -> "Tomorrow"
                        else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth}"
                    }
                    Chip(label, on = d == day) { day = d }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Time", style = LockInText.Label.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(ControlShape)
                    .background(LockInColors.Slate)
                    .border(1.5.dp, LockInColors.Rim, ControlShape)
                    .clickable(role = Role.Button) {
                        TimePickerDialog(
                            context,
                            { _, h, m -> hour = h; minute = m; note = null },
                            hour,
                            minute,
                            DateFormat.is24HourFormat(context),
                        ).show()
                    }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(timeText, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 18.sp), modifier = Modifier.weight(1f))
                Text("Change", style = LockInText.Small.copy(color = LockInColors.Silver))
            }

            Spacer(Modifier.height(24.dp))
            Text("Length", style = LockInText.Label.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lengths.forEach { m -> Chip(formatMinutes(m), on = m == length) { length = m } }
            }

            (note ?: error)?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = LockInText.Error)
            }
            Spacer(Modifier.height(20.dp))
        }

        LockInButton(
            "Plan session",
            onClick = {
                val at = startMillis(day, hour, minute)
                when {
                    title.isBlank() -> note = "Give the session a name."
                    at < System.currentTimeMillis() - 120_000L -> note = "That time has already passed."
                    else -> {
                        note = null
                        onSave(title.trim(), at, length)
                    }
                }
            },
            loading = busy,
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(ControlShape)
            .background(if (on) LockInColors.Lime else LockInColors.Slate)
            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp))
    }
}
