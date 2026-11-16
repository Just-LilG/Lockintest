package com.godbless.lockin.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.godbless.lockin.ui.Collapse
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.PartnerApp
import com.godbless.lockin.data.PartnerDay
import com.godbless.lockin.data.PartnerLimit
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.usage.UsageAccess
import com.godbless.lockin.usage.durationText
import java.time.format.TextStyle as DateStyle
import java.util.Locale

private val Card = RoundedCornerShape(24.dp)
private val Chip = RoundedCornerShape(10.dp)

/** What is on your partner's phone: today's time, the apps behind it with a way to limit each, and the week. */
@Composable
internal fun TheirPhoneCard(usage: UsageState, onPick: (PartnerApp) -> Unit, onMessage: () -> Unit, modifier: Modifier = Modifier, startOpen: Boolean = false) {
    val name = LocalPartnerName.current
    val data = usage.partner
    Column(
        modifier
            .fillMaxWidth()
            .clip(Card)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, Card)
            .padding(18.dp),
    ) {
        when {
            data == null -> Text("Looking at $name's phone…", style = LockInText.Small.copy(color = LockInColors.Body))
            data.status == "off" -> Notice(
                "$name stopped sharing.",
                "You can't see their app times or set limits until they turn sharing back on.",
            )
            data.status == "no_access" -> {
                Notice(
                    "$name hasn't allowed app times yet.",
                    "Their phone needs \"usage access\" switched on for LockIn. Message them to do it.",
                )
                Spacer(Modifier.height(14.dp))
                SmallPill("Message $name", onMessage)
            }
            data.status == "waiting" -> Notice(
                "Waiting for $name's phone.",
                "Their first update can take a few minutes. Their phone has to be on and have a connection.",
            )
            else -> UsageBody(usage, name, onPick, startOpen)
        }
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Text(title, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
    Spacer(Modifier.height(4.dp))
    Text(body, style = LockInText.Small.copy(color = LockInColors.Body))
}

@Composable
private fun UsageBody(usage: UsageState, name: String, onPick: (PartnerApp) -> Unit, startOpen: Boolean) {
    val data = usage.partner ?: return
    val today = data.today
    val everyApp = today?.apps.orEmpty().sortedByDescending { it.seconds }.take(8)
    var all by rememberSaveable { mutableStateOf(startOpen) }
    val apps = if (all) everyApp else everyApp.take(3)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, easing = UiGlide)) }

    Text("$name's phone today", style = LockInText.Label.copy(color = LockInColors.Silver))
    Spacer(Modifier.height(6.dp))
    Text(
        durationText(today?.total ?: 0).let { if (it == "under a minute") "0 m" else it },
        style = LockInText.Headline.copy(fontSize = 40.sp),
    )
    Text(updated(data.reportedAt), style = LockInText.Small.copy(color = LockInColors.Mist))
    Spacer(Modifier.height(16.dp))

    if (apps.isEmpty()) {
        Text("No app has been used for more than half a minute yet today.", style = LockInText.Small.copy(color = LockInColors.Body))
    } else {
        // One scale for every bar, wide enough for the biggest time or limit.
        val scale = apps.maxOf { a -> maxOf(a.seconds, data.limits.firstOrNull { it.pkg == a.pkg }?.seconds ?: 0) }.coerceAtLeast(60)
        apps.forEachIndexed { i, app ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            AppRow(app, data.limits.firstOrNull { it.pkg == app.pkg }, scale, grow.value) { onPick(app) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap an app to limit it. It applies on $name's phone straight away.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    val week = data.days.reversed()
    val hasWeek = week.size > 1 && week.any { it.total > 0 }
    Collapse(all && hasWeek) {
        Column {
            Spacer(Modifier.height(20.dp))
            Text("Last 7 days", style = LockInText.Label.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(10.dp))
            WeekBars(week, grow.value)
        }
    }
    if (everyApp.size > 3 || hasWeek) {
        Spacer(Modifier.height(14.dp))
        SmallPill(if (all) "Show less" else "All apps and the week", { all = !all })
    }
}

@Composable
private fun AppRow(app: PartnerApp, limit: PartnerLimit?, scale: Int, grow: Float, onClick: () -> Unit) {
    val over = limit != null && app.seconds >= limit.seconds
    val tap = if (app.limitable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(Modifier.fillMaxWidth().clip(Chip).then(tap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(app.label, style = LockInText.Sub.copy(color = LockInColors.Snow), modifier = Modifier.weight(1f), maxLines = 1)
            if (limit != null) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(Chip)
                        .background(if (over) LockInColors.Flare else LockInColors.LimeWash)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (over) "Limit reached" else "Limit ${durationText(limit.seconds)}",
                        style = LockInText.Small.copy(color = if (over) LockInColors.Ink else LockInColors.Lime, fontSize = 12.sp),
                    )
                }
            }
            Text(durationText(app.seconds), style = LockInText.Sub.copy(color = LockInColors.Silver))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(LockInColors.Graphite, size = size, cornerRadius = r)
            val w = size.width * (app.seconds / scale.toFloat()).coerceIn(0f, 1f) * grow
            if (w > 0f) drawRoundRect(if (over) LockInColors.Flare else LockInColors.Lime, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
            if (limit != null) {
                // A tick where the limit sits on the same scale.
                val x = size.width * (limit.seconds / scale.toFloat()).coerceIn(0f, 1f)
                drawRect(LockInColors.Snow, topLeft = Offset((x - 1.dp.toPx()).coerceAtLeast(0f), -2.dp.toPx()), size = Size(2.dp.toPx(), size.height + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun WeekBars(days: List<PartnerDay>, grow: Float) {
    val top = days.maxOf { it.total }.coerceAtLeast(1)
    // No fixed height: the two lines under each bar (the day and its time) need their full room.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, d ->
            val today = i == days.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val h = (size.height * d.total / top * grow).coerceAtLeast(if (d.total > 0) 4.dp.toPx() else 2.dp.toPx())
                    drawRoundRect(
                        if (today) LockInColors.Lime else LockInColors.Rim,
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    d.day.dayOfWeek.getDisplayName(DateStyle.SHORT, Locale.getDefault()).take(2),
                    style = LockInText.Small.copy(color = if (today) LockInColors.Snow else LockInColors.Mist, fontSize = 11.sp),
                )
                Text(
                    if (d.total >= 60) formatMinutes(d.total / 60) else "–",
                    style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 10.sp),
                )
            }
        }
    }
}

private fun updated(at: Long): String {
    if (at <= 0L) return "Waiting for the first update"
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated $minutes m ago"
        minutes < 24 * 60 -> "Updated ${minutes / 60} h ago"
        else -> "Last update ${minutes / (24 * 60)} d ago"
    }
}

/** Your own side: what you share, what LockIn is allowed to read, and the limits your partner set on you. */
@Composable
internal fun YourSideCard(usage: UsageState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = LocalPartnerName.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(Card)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, Card)
            .padding(18.dp),
    ) {
        Text("Your phone", style = LockInText.Label.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(10.dp))
        ToggleRow("Share my app times with $name", usage.sharing, usage::setSharing)
        Spacer(Modifier.height(4.dp))
        Text(
            if (usage.sharing) "$name sees how long you use each app, and can set a daily limit on any of them. Switch this off and they see nothing and can't set new limits."
            else "$name can't see your app times. Existing limits stay until they remove them.",
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
        usage.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = LockInText.Error)
        }

        if (!usage.access) {
            Spacer(Modifier.height(16.dp))
            Text(
                "LockIn can't read app times on this phone yet. Android asks you to switch on \"usage access\" for LockIn.",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
            Spacer(Modifier.height(12.dp))
            LockInButton("Allow usage access", onClick = { UsageAccess.open(context) }, kind = ButtonKind.Outline)
        }

        if (usage.limitsOnMe.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Limits $name set on you", style = LockInText.Label.copy(color = LockInColors.Silver))
            Spacer(Modifier.height(8.dp))
            usage.limitsOnMe.forEach {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.label, style = LockInText.Sub.copy(color = LockInColors.Snow), modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${durationText(it.seconds)} a day", style = LockInText.Sub.copy(color = LockInColors.Silver))
                }
            }
            Spacer(Modifier.height(6.dp))
            val guard = remember { LockGuard(context) }
            if (usage.owner) {
                Text("When time is up, LockIn switches the app off until tomorrow.", style = LockInText.Small.copy(color = LockInColors.Mist))
            } else {
                Text(
                    "When time is up, LockIn covers the app with a block screen until tomorrow.",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
                if (!usage.overlay) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "To cover an app, LockIn needs \"display over other apps\". Without it you only get a notification.",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                    )
                    Spacer(Modifier.height(12.dp))
                    LockInButton("Allow display over apps", onClick = { context.startActivity(guard.overlayIntent()) }, kind = ButtonKind.Outline)
                }
            }
        }
    }
}
