package com.godbless.lockin.home

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.push.Push
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.StepBar
import com.godbless.lockin.ui.rememberEntrance
import com.godbless.lockin.ui.rise
import com.godbless.lockin.usage.UsageAccess

private val CardShape = RoundedCornerShape(24.dp)

/** Everything LockIn asks the phone for, with a plain reason for each. */
internal enum class Need(val title: String, val why: String) {
    Notifications("Notifications", "Hear when your partner messages you, locks your phone or plans a session."),
    AppTimes("App times", "Lets LockIn see how long apps are used, so you and your partner can set limits."),
    StayOnTop("Stay on top", "Shows the block screen over an app that hit its limit, and pulls LockIn back during a session."),
    DeviceAdmin("Device admin", "Lets LockIn lock the screen, and stops it being uninstalled while you are locked in."),
    KeepRunning("Keep running", "Stops the phone putting LockIn to sleep while the screen is off, so locks still arrive."),
    ExactTimes("Exact times", "Starts a lock your partner scheduled, and your session reminders, right on time."),
    Guard("Protection", "Switching LockIn off, or removing it, needs your partner's OK. Also covers the screen if \"display over other apps\" gets turned off."),
}

internal fun Need.isOn(context: Context, guard: LockGuard): Boolean = when (this) {
    Need.Notifications -> Push.canShow(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    Need.AppTimes -> UsageAccess.granted(context)
    Need.StayOnTop -> guard.canOverlay()
    Need.DeviceAdmin -> guard.isAdmin() || guard.isOwner()
    Need.KeepRunning -> guard.batteryUnrestricted()
    Need.ExactTimes -> com.godbless.lockin.push.Alarms.exactAllowed(context)
    Need.Guard -> com.godbless.lockin.lock.Protection.guardEnabled(context)
}

/** What is still switched off. Settings screens are separate activities, so this is read again on every return. */
internal class PermissionWatch(private val context: Context, private val guard: LockGuard) {
    var missing by mutableStateOf(compute())
        private set

    fun refresh() {
        missing = compute()
    }

    private fun compute(): List<Need> = Need.entries.filter { !it.isOn(context, guard) }
}

/**
 * Shown when someone first signs in, and again each time the app is opened while something is off. One button
 * walks through the switches one by one; Skip leaves the rest for later (the Home page keeps a reminder).
 */
@Composable
internal fun PermissionsScreen(watch: PermissionWatch, guard: LockGuard, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) deniedOnce = true
        watch.refresh()
    }

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "This phone can't open that screen.", Toast.LENGTH_SHORT).show() }
    }

    fun turnOn(need: Need) {
        when (need) {
            Need.Notifications ->
                if (Build.VERSION.SDK_INT >= 33 && !deniedOnce) {
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            Need.AppTimes -> UsageAccess.open(context)
            Need.StayOnTop -> open(guard.overlayIntent())
            Need.DeviceAdmin -> open(guard.adminIntent())
            Need.KeepRunning -> open(guard.batteryIntent())
            Need.Guard -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Need.ExactTimes ->
                if (Build.VERSION.SDK_INT >= 31) {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName)))
                }
        }
    }

    val missing = watch.missing
    val total = Need.entries.size
    val on = total - missing.size

    Column(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        val entrance = rememberEntrance()
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .readable(Widths.Page)
                .padding(horizontal = PageGutter),
        ) {
            Spacer(Modifier.height(28.dp))
            Column(Modifier.rise(entrance, 0).padding(horizontal = 4.dp)) {
                // Someone who already has most of it on only needs to hear about what is left.
                val partway = on > 0 && missing.isNotEmpty()
                Text(
                    when {
                        missing.isEmpty() -> "You're all set"
                        partway && missing.size == 1 -> "One more switch"
                        partway -> "A few switches left"
                        else -> "Let LockIn do its job"
                    },
                    style = LockInText.Headline.copy(fontSize = 34.sp, lineHeight = 40.sp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        missing.isEmpty() -> "Everything LockIn needs is switched on."
                        partway -> "The rest is already on. This one makes locks, limits and reminders work properly."
                        else -> "A few switches make locks, limits and reminders work."
                    },
                    style = LockInText.Sub.copy(color = LockInColors.Body),
                )
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.rise(entrance, 1).padding(horizontal = 4.dp)) {
                StepBar(total = total, current = on - 1)
                Spacer(Modifier.height(8.dp))
                Text("$on of $total on", style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 13.sp))
            }
            Spacer(Modifier.height(18.dp))
            // What is still off is listed. What is already on folds into one line that opens when tapped.
            var showOn by rememberSaveable { mutableStateOf(false) }
            val done = Need.entries.filter { it !in missing }
            val fold = done.isNotEmpty() && missing.isNotEmpty()
            val turn by androidx.compose.animation.core.animateFloatAsState(if (showOn) 270f else 90f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f), label = "fold-chevron")
            val spring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.85f, stiffness = 380f)
            Column(
                Modifier
                    .fillMaxWidth()
                    .rise(entrance, 2)
                    .clip(CardShape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, CardShape)
                    .animateContentSize(spring),
            ) {
                val shown = if (fold) missing else Need.entries.toList()
                shown.forEachIndexed { i, need ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LockInColors.Graphite))
                    NeedRow(need, granted = need !in missing, onTurnOn = { turnOn(need) })
                }
                if (fold) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LockInColors.Graphite))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = if (showOn) "Hide the ones that are on" else "Show the ones that are on") { showOn = !showOn }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LockInColors.Lime))
                        Text(
                            "${done.size} already on",
                            style = LockInText.Label.copy(color = LockInColors.Body, fontSize = 14.sp),
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f, modifier = Modifier.graphicsLayer { rotationZ = turn })
                    }
                    if (showOn) {
                        done.forEach { need ->
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LockInColors.Graphite))
                            NeedRow(need, granted = true, onTurnOn = {})
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PageGutter).padding(top = 8.dp, bottom = 16.dp)) {
            if (missing.isEmpty()) {
                LockInButton("Continue", onClick = onDone)
            } else {
                LockInButton("Allow ${missing.first().title}", onClick = { turnOn(missing.first()) })
                Spacer(Modifier.height(10.dp))
                LockInButton("Skip for now", onClick = onDone, kind = ButtonKind.Outline)
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can turn these on later: Home shows a reminder, and Protection lists them all.",
                    style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 12.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NeedRow(need: Need, granted: Boolean, onTurnOn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, role = Role.Button, onClick = onTurnOn)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(need.title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            // Bigger and lighter than before, so the reason is easy to read.
            Text(need.why, style = LockInText.Small.copy(color = LockInColors.Body, fontSize = 13.sp, lineHeight = 18.sp))
        }
        if (granted) OnChip() else SmallPill("Allow", onTurnOn)
    }
}

@Composable
private fun OnChip() {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(LockInColors.LimeWash).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(LockInColors.Lime))
        Text("On", style = LockInText.Label.copy(color = LockInColors.Frost, fontSize = 13.sp), modifier = Modifier.padding(start = 7.dp))
    }
}

/** A quiet reminder on Home while something is still off. */
@Composable
internal fun PermissionBanner(missing: List<Need>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Says which switch is off, in amber, so it reads as something to fix rather than as good news.
    val first = missing.firstOrNull() ?: return
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFFFFB534)))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                if (missing.size == 1) "${first.title} is off" else "${first.title} and ${missing.size - 1} more are off",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp),
            )
            Text(
                if (missing.size == 1) first.why else "Finish setup so locks and limits work.",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                maxLines = 2,
            )
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
    }
}
