package com.godbless.lockin.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.godbless.lockin.push.NotifyKind
import com.godbless.lockin.push.NotifyPrefs
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.rise

/** The pages that open from the You tab. */
internal enum class YouSub { None, Profile, Notifications, Partner, Security, Settings, Year }

/**
 * You: who you are, your climb drawn as a mountain, today and this month side by side, and the pages for your
 * notifications, partner and sign-in. Everything moves with slides and springs, never fades.
 */
@Composable
internal fun YouPage(
    you: YouState,
    paired: Boolean,
    onOpen: (YouSub) -> Unit,
    plans: PlanState,
    onGoal: () -> Unit,
    onAvatar: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val stats = LocalStats.current
    val goal = plans.goalMinutes
    val notifyOn = NotifyKind.entries.count { NotifyPrefs.on(context, it) }
    val notifyTotal = NotifyKind.entries.size
    val rank = remember(stats.totalHours) {
        val alt = altitudeOf(stats.totalHours)
        val top = Ranks.last().second
        val ranges = alt / top
        Ranks.last { it.second <= alt % top }.first + if (ranges > 0) " · Range ${ranges + 1}" else ""
    }

    PageScaffold(wide = true) { entrance ->
        Spacer(Modifier.height(10.dp))
        ProfileHeader(
            name = you.displayName,
            since = you.sinceText,
            todayFraction = if (goal <= 0) 0f else stats.todaySeconds / 60f / goal,
            onEdit = { onOpen(YouSub.Profile) },
            modifier = Modifier.rise(entrance, 0),
            onSettings = { onOpen(YouSub.Settings) },
            rank = rank,
            tint = Identity.tint,
            glyph = Identity.glyph,
            onAvatar = onAvatar,
        )
        Spacer(Modifier.height(18.dp))

        // On a tablet: your climb and numbers on the left, settings on the right.
        TwoColumns(left = {
        AltitudeHero(altitudeOf(stats.totalHours), Modifier.rise(entrance, 1))
        Spacer(Modifier.height(10.dp))

        // Today and this month side by side, always the same height.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).rise(entrance, 2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TodayCard(
                goalMin = goal,
                todaySeconds = stats.todaySeconds,
                week = stats.week,
                streak = stats.streak,
                onGoal = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onGoal() },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                restToday = stats.restToday,
                freezeUsed = stats.freezeUsedOn >= 0,
            )
            MonthCard(
                monthHours = stats.monthHours,
                goalMin = goal,
                delta = stats.monthDelta,
                longest = stats.longestSession,
                summits = stats.monthSummits,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onOpen = { onOpen(YouSub.Year) },
            )
        }
        Spacer(Modifier.height(12.dp))
        RecapCard(stats, Modifier.rise(entrance, 3))
        Spacer(Modifier.height(12.dp))
        AchievementsCard(stats, Modifier.rise(entrance, 3))
        Spacer(Modifier.height(12.dp))
        RecordsCard(stats, Modifier.rise(entrance, 4))
        Spacer(Modifier.height(26.dp))
        }, right = {
        SectionTitle("Settings", Modifier.rise(entrance, 3))
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .rise(entrance, 4)
                .clip(YouCard)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, YouCard),
        ) {
            TileRow(Glyph.Bell, "Notifications", if (notifyOn == notifyTotal) "All on" else if (notifyOn == 0) "All off" else "$notifyOn of $notifyTotal on", { onOpen(YouSub.Notifications) })
            TileDivider()
            TileRow(Glyph.People, "Your partner", if (paired) LocalPartnerName.current else "Not paired", { onOpen(YouSub.Partner) })
            TileDivider()
            TileRow(Glyph.Gear, "Settings", "Look, security, account", { onOpen(YouSub.Settings) })
        }
        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            "LockIn ${com.godbless.lockin.BuildConfig.VERSION_NAME}",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            modifier = Modifier.fillMaxWidth().rise(entrance, 6),
            textAlign = TextAlign.Center,
        )
        })
    }
}
