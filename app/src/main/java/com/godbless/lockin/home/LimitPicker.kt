package com.godbless.lockin.home

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.usage.durationText

private val Presets = listOf(15, 30, 60, 120, 180)
private const val Step = 5
private const val MaxMinutes = 24 * 60

/**
 * Sets a daily limit on one app on your partner's phone. [usedSeconds] is what they have used it for today,
 * [currentMinutes] the limit already in place (0 for none).
 */
@Composable
internal fun LimitPicker(
    appName: String,
    usedSeconds: Int,
    currentMinutes: Int,
    busy: Boolean,
    error: String?,
    onSave: (minutes: Int) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    val partner = LocalPartnerName.current
    var minutes by rememberSaveable(appName) {
        mutableStateOf(if (currentMinutes > 0) currentMinutes else ((usedSeconds / 60 / 15 + 1) * 15).coerceIn(15, 120))
    }
    val overToday = usedSeconds >= minutes * 60

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
            Text("Limit $appName", style = LockInText.Headline.copy(fontSize = 34.sp))
            Text(
                "$partner has used it for ${durationText(usedSeconds)} today.",
                style = LockInText.Sub.copy(color = LockInColors.Silver),
            )
            Spacer(Modifier.height(28.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                RoundStep(Glyph.Minus, enabled = minutes > Step) { minutes = (minutes - Step).coerceAtLeast(Step) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatMinutes(minutes), style = LockInText.Headline.copy(fontSize = 56.sp, lineHeight = 60.sp), textAlign = TextAlign.Center)
                    Text("a day", style = LockInText.Sub.copy(color = LockInColors.Silver))
                }
                RoundStep(Glyph.Plus, enabled = minutes < MaxMinutes) { minutes = (minutes + Step).coerceAtMost(MaxMinutes) }
            }
            Spacer(Modifier.height(24.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.forEach { p ->
                    val on = p == minutes
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(ControlShape)
                            .background(if (on) LockInColors.Lime else LockInColors.Slate)
                            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, ControlShape)
                            .clickable(role = Role.Tab) { minutes = p },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(formatMinutes(p), style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (overToday) "$partner has already used more than this today, so $appName is blocked on their phone as soon as you set it, until tomorrow."
                else "It applies on $partner's phone straight away and resets at midnight. $partner sees it in the chat.",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = LockInText.Error)
            }
            Spacer(Modifier.height(20.dp))
        }

        LockInButton(
            if (currentMinutes > 0) "Change limit to ${formatMinutes(minutes)}" else "Set ${formatMinutes(minutes)} limit",
            onClick = { onSave(minutes) },
            loading = busy,
        )
        if (currentMinutes > 0) {
            Spacer(Modifier.height(10.dp))
            LockInButton("Remove limit", onClick = onRemove, kind = ButtonKind.Outline, enabled = !busy)
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun RoundStep(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .border(1.5.dp, LockInColors.Rim, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, if (enabled) LockInColors.Snow else LockInColors.Mist, boxSize = 22.dp, strokeWidth = 2f) }
}
