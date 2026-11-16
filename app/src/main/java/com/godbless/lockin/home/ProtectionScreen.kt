package com.godbless.lockin.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.lock.ProtectionLevel
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.StepBar
import com.godbless.lockin.ui.rise

private val CardShape = RoundedCornerShape(24.dp)
private val BigShape = RoundedCornerShape(28.dp)

/**
 * How hard LockIn is to get around, and the switches that make it harder. Each permission opens the
 * Android screen where it is granted; the page re-checks when you come back.
 */
@Composable
internal fun ProtectionScreen(guard: LockGuard, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var level by remember { mutableStateOf(guard.level()) }
    var admin by remember { mutableStateOf(guard.isAdmin()) }
    var overlay by remember { mutableStateOf(guard.canOverlay()) }
    var battery by remember { mutableStateOf(guard.batteryUnrestricted()) }
    var owner by remember { mutableStateOf(guard.isOwner()) }
    var guardOn by remember { mutableStateOf(com.godbless.lockin.lock.Protection.guardEnabled(context)) }
    fun refresh() {
        guardOn = com.godbless.lockin.lock.Protection.guardEnabled(context)
        level = guard.level()
        admin = guard.isAdmin()
        overlay = guard.canOverlay()
        battery = guard.batteryUnrestricted()
        owner = guard.isOwner()
    }

    // Settings screens are separate activities, so check again whenever we come back to the front.
    DisposableEffect(context) {
        val lifecycleOwner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "This phone can't open that screen.", Toast.LENGTH_SHORT).show() }
    }

    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        PageScaffold { entrance ->
            Row(Modifier.fillMaxWidth().rise(entrance, 0)) { BackButton(onBack) }
            Spacer(Modifier.height(14.dp))
            PageTitle("Protection", "How hard LockIn is to get around.", entrance)
            Spacer(Modifier.height(20.dp))

            LevelCard(level, Modifier.rise(entrance, 1))
            Spacer(Modifier.height(26.dp))

            SectionTitle("Turn on", Modifier.rise(entrance, 2))
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .rise(entrance, 3)
                    .clip(CardShape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, CardShape),
            ) {
                PermissionRow(
                    "Device admin",
                    "LockIn can lock the screen, and can't be uninstalled until this is switched off.",
                    admin,
                ) { open(guard.adminIntent()) }
                RowDivider()
                PermissionRow(
                    "Stay on top",
                    "If someone leaves during a session, LockIn jumps straight back and counts a pickup.",
                    overlay,
                ) { open(guard.overlayIntent()) }
                RowDivider()
                PermissionRow(
                    "Protection",
                    "Switching LockIn off or removing it needs your partner's OK. Turn on \"LockIn protection\" in Accessibility.",
                    guardOn,
                ) { open(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                RowDivider()
                PermissionRow(
                    "Keep running",
                    "Stops the phone putting LockIn to sleep while the screen is off.",
                    battery,
                ) { open(guard.batteryIntent()) }
            }
            Spacer(Modifier.height(26.dp))

            SectionTitle("Full lockdown", Modifier.rise(entrance, 4))
            Spacer(Modifier.height(12.dp))
            LockdownCard(
                owner = owner,
                command = guard.ownerCommand,
                onCopy = {
                    clipboard.setText(AnnotatedString(guard.ownerCommand))
                    Toast.makeText(context, "Command copied.", Toast.LENGTH_SHORT).show()
                },
                onTurnOff = {
                    val done = guard.clearOwner()
                    refresh()
                    if (!done) Toast.makeText(context, "End your session first.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.rise(entrance, 5),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Emergency calls and the phone app always work, even in full lockdown.",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                modifier = Modifier.padding(horizontal = 4.dp).rise(entrance, 6),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(LockInColors.Graphite))
}

// ---------------------------------------------------------------------------------------------
// Level
// ---------------------------------------------------------------------------------------------

@Composable
private fun LevelCard(level: ProtectionLevel, modifier: Modifier = Modifier) {
    val about = when (level) {
        ProtectionLevel.Basic ->
            "During a session LockIn pins the screen. Someone can still leave it with a gesture, or uninstall the app."
        ProtectionLevel.Strong ->
            "LockIn is a device admin, so it can't be uninstalled until the admin is switched off in Settings."
        ProtectionLevel.Total ->
            "LockIn is the device owner. A session is a full kiosk, and nobody can leave it or uninstall the app until it ends."
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(BigShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, BigShape)
            .padding(20.dp),
    ) {
        Text("Protection level", style = LockInText.Label.copy(color = LockInColors.Silver))
        Text(level.label, style = LockInText.Headline.copy(fontSize = 40.sp, lineHeight = 46.sp))
        Spacer(Modifier.height(14.dp))
        StepBar(total = 3, current = level.ordinal)
        Spacer(Modifier.height(14.dp))
        Text(about, style = LockInText.Sub.copy(color = LockInColors.Body))
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun PermissionRow(title: String, detail: String, on: Boolean, onTurnOn: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(detail, style = LockInText.Small.copy(color = LockInColors.Mist))
        }
        if (on) OnChip() else SmallPill("Turn on", onTurnOn)
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

@Composable
private fun LockdownCard(
    owner: Boolean,
    command: String,
    onCopy: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CardShape)
            .padding(18.dp),
    ) {
        if (owner) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 14.dp)) {
                    Text("Device owner", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Text("Sessions run as a full kiosk.", style = LockInText.Small.copy(color = LockInColors.Mist))
                }
                OnChip()
            }
            Spacer(Modifier.height(14.dp))
            SmallPill("Turn off lockdown", onTurnOff)
        } else {
            Text("Make LockIn the device owner", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text(
                "This is the only way to make a session impossible to leave. It takes one command from a computer, with USB debugging on and no accounts signed in on the phone (you can add them back after).",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LockInColors.Graphite)
                    .padding(12.dp),
            ) {
                Text(
                    command,
                    style = LockInText.Small.copy(color = LockInColors.Frost, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SmallPill("Copy command", onCopy)
        }
    }
}
