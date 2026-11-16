package com.godbless.lockin.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.Accent
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Palette
import com.godbless.lockin.ui.MotionMode
import com.godbless.lockin.ui.MotionState
import com.godbless.lockin.ui.ThemeMode
import com.godbless.lockin.ui.ThemeState
import com.godbless.lockin.ui.rise

private val SetCard = RoundedCornerShape(22.dp)

/**
 * Settings, from the gear on the You page: the theme (follow the phone, dark or light) and the accent colour,
 * with a live preview, then the other settings pages. A change shows across the whole app at once.
 */
@Composable
internal fun SettingsPage(
    onOpen: (YouSub) -> Unit,
    onBack: () -> Unit,
    you: YouState,
    stats: Stats,
    protection: String,
    onProtection: () -> Unit,
    updateStatus: String,
    onUpdate: () -> Unit,
    onConfirm: (ConfirmSpec) -> Unit,
    onSignOut: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var whatsNew by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    YouSubFrame("Settings", "Make LockIn look like yours", onBack, wide = true) { entrance ->
        // On a tablet: how LockIn looks on the left; account, data, about and sign out on the right.
        TwoColumns(leftWeight = 1f, gap = 24.dp, left = {
        ThemePreview(Modifier.rise(entrance, 1))
        Spacer(Modifier.height(24.dp))

        SectionTitle("Theme", Modifier.rise(entrance, 2))
        Row(Modifier.fillMaxWidth().rise(entrance, 2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (mode in ThemeMode.entries) {
                ModeCard(mode, picked = ThemeState.mode == mode, modifier = Modifier.weight(1f)) {
                    if (ThemeState.mode != mode) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        ThemeState.setMode(mode)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("Colour", Modifier.rise(entrance, 3))
        val rows = Accent.entries.chunked(3)
        Column(Modifier.fillMaxWidth().rise(entrance, 3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (accent in row) {
                        Swatch(accent, picked = ThemeState.accent == accent, modifier = Modifier.weight(1f)) {
                            if (ThemeState.accent != accent) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                ThemeState.setAccent(accent)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("Motion", Modifier.rise(entrance, 4))
        MotionPicker(Modifier.rise(entrance, 4))
        Spacer(Modifier.height(24.dp))

        }, right = {
        SectionTitle("Account", Modifier.rise(entrance, 4))
        SettingsGroup(Modifier.rise(entrance, 4)) {
            TileRow(Glyph.Person, "Profile", you.displayName, onClick = { onOpen(YouSub.Profile) })
            TileDivider()
            TileRow(Glyph.Shield, "Sign-in and security", null, onClick = { onOpen(YouSub.Security) })
            TileDivider()
            TileRow(Glyph.Lock, "Lock protection", protection, onClick = onProtection)
            TileDivider()
            TileRow(Glyph.ArrowUp, "App updates", updateStatus, onClick = onUpdate)
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("Your data", Modifier.rise(entrance, 5))
        SettingsGroup(Modifier.rise(entrance, 5)) {
            TileRow(Glyph.Download, "Export my sessions", "CSV", onClick = {
                if (stats.exportRows.isEmpty()) android.widget.Toast.makeText(context, "No sessions to export yet.", android.widget.Toast.LENGTH_SHORT).show()
                else exportSessions(context, stats.exportRows)
            })
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("About", Modifier.rise(entrance, 5))
        SettingsGroup(Modifier.rise(entrance, 5)) {
            TileRow(Glyph.Star, "What's new", "Version ${com.godbless.lockin.BuildConfig.VERSION_NAME}", onClick = { whatsNew = !whatsNew })
            com.godbless.lockin.ui.Collapse(whatsNew) {
                Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhatsNew.forEach { line -> Text("• $line", style = LockInText.Small.copy(color = LockInColors.Body)) }
                }
            }
            TileDivider()
            TileRow(Glyph.Share, "Send feedback", null, onClick = {
                val body = "LockIn ${com.godbless.lockin.BuildConfig.VERSION_NAME} on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\nWhat happened or what would you like?\n"
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "LockIn feedback")
                    .putExtra(android.content.Intent.EXTRA_TEXT, body)
                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Send feedback").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("Danger zone", Modifier.rise(entrance, 6))
        SettingsGroup(Modifier.rise(entrance, 6)) {
            TileRow(Glyph.Exit, "Sign out", null, danger = true, onClick = {
                onConfirm(ConfirmSpec("Sign out?", "You'll need your email and password to get back in. Anything waiting to sync is sent first.", "Sign out", danger = false, onConfirm = onSignOut))
            })
            TileDivider()
            TileRow(Glyph.Close, "Delete my account", null, danger = true, onClick = {
                onConfirm(
                    ConfirmSpec(
                        "Delete your account?",
                        "This removes your profile, sessions, messages and plans from LockIn for good. Your partner will no longer be paired with you. It can't be undone.",
                        "Delete everything",
                        onConfirm = { you.deleteAccount(onSignOut) },
                    ),
                )
            })
        }
        you.deleteError?.let { Text(it, style = LockInText.Error, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
        if (you.deleting) Text("Deleting your account…", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        Spacer(Modifier.height(12.dp))
        })
    }
}

/**
 * Reduce motion: Auto calms the app while battery saver is on (or animations are off in the phone's settings),
 * Always keeps it calm, Never keeps full motion. The highlight slides to the choice.
 */
@Composable
private fun MotionPicker(modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(SetCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, SetCard)
            .padding(14.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(LockInColors.Graphite),
        ) {
            val third = maxWidth / 3
            val x by androidx.compose.animation.core.animateDpAsState(
                third * MotionMode.entries.indexOf(MotionState.mode),
                spring(dampingRatio = 0.7f, stiffness = 380f),
                label = "motion-pill",
            )
            Box(
                Modifier
                    .offset(x = x)
                    .width(third)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(LockInColors.Lime),
            )
            Row(Modifier.fillMaxSize()) {
                for (m in MotionMode.entries) {
                    val picked = MotionState.mode == m
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(role = Role.RadioButton) {
                                if (!picked) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    MotionState.setMode(m)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.label,
                            style = LockInText.Label.copy(color = if (picked) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (MotionState.mode) {
                MotionMode.Auto -> if (MotionState.batterySaver) "Battery saver is on, so LockIn is keeping still: loops are off and slides finish at once."
                else "LockIn calms itself while battery saver is on, or when animations are off in your phone's settings."
                MotionMode.Always -> "Loops are off and slides finish at once, whatever the battery."
                MotionMode.Never -> "Full motion, even in battery saver."
            },
            style = LockInText.Small.copy(color = LockInColors.Mist),
        )
    }
}

/** A small piece of LockIn drawn in the current theme: a card, a progress bar, a chip and the main button. */
@Composable
private fun ThemePreview(modifier: Modifier = Modifier) {
    val accent = ThemeState.accent
    Column(
        modifier
            .fillMaxWidth()
            .clip(SetCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, SetCard)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(LockInColors.LimeWash),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Palette, LockInColors.Lime, boxSize = 18.dp, strokeWidth = 2f) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${accent.label}, ${if (LockInColors.isDark) "dark" else "light"}", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                Text("This is how LockIn looks now", style = LockInText.Small)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("2h 40m", style = LockInText.Title.copy(fontSize = 30.sp, lineHeight = 34.sp))
            Text("  of 4h today", style = LockInText.Small.copy(fontSize = 13.sp), modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Progress, springing to the same spot whenever the colours change (so the change is easy to see).
        val fill by animateFloatAsState(0.66f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "preview-fill")
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(LockInColors.Graphite, cornerRadius = r)
            drawRoundRect(LockInColors.Lime, size = Size(size.width * fill, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(CircleShape).background(LockInColors.LimeWash).padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("3 day streak", style = LockInText.Small.copy(color = LockInColors.Lime, fontWeight = FontWeight.SemiBold)) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("Lock in", style = LockInText.Button.copy(color = LockInColors.OnLime, fontSize = 15.sp)) }
        }
    }
}

/** One way of choosing the theme, drawn as a tiny screen in that theme. Auto is half dark, half light. */
@Composable
private fun ModeCard(mode: ThemeMode, picked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = ThemeState.accent
    val dark = Palette.build(accent, true)
    val light = Palette.build(accent, false)
    val ring by animateFloatAsState(if (picked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "mode-ring")
    Column(modifier.springClick(role = Role.RadioButton, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .graphicsLayer { scaleX = 0.96f + 0.04f * ring; scaleY = scaleX }
                .clip(RoundedCornerShape(16.dp))
                .border((1f + 1.5f * ring).dp, if (picked) LockInColors.Lime else LockInColors.Graphite, RoundedCornerShape(16.dp)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                when (mode) {
                    ThemeMode.Dark -> miniScreen(dark)
                    ThemeMode.Light -> miniScreen(light)
                    ThemeMode.System -> {
                        miniScreen(light)
                        // The dark half, cut on a diagonal.
                        val cut = Path().apply {
                            moveTo(size.width * 0.62f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(size.width * 0.38f, size.height)
                            close()
                        }
                        clipPath(cut) { miniScreen(dark) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            mode.label,
            style = LockInText.Label.copy(color = if (picked) LockInColors.Snow else LockInColors.Mist, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium),
        )
    }
}

/** A tiny app screen in [p]: a title line, a card with a progress bar, and the accent button. */
private fun DrawScope.miniScreen(p: Palette) {
    val w = size.width
    val h = size.height
    drawRect(p.ink)
    val pad = w * 0.12f
    drawRoundRect(p.snow, topLeft = Offset(pad, h * 0.12f), size = Size(w * 0.45f, h * 0.06f), cornerRadius = CornerRadius(h * 0.03f))
    drawRoundRect(p.mist, topLeft = Offset(pad, h * 0.21f), size = Size(w * 0.3f, h * 0.035f), cornerRadius = CornerRadius(h * 0.02f))
    drawRoundRect(p.slate, topLeft = Offset(pad, h * 0.32f), size = Size(w - pad * 2, h * 0.3f), cornerRadius = CornerRadius(w * 0.08f))
    drawRoundRect(p.graphite, topLeft = Offset(pad, h * 0.32f), size = Size(w - pad * 2, h * 0.3f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(1.dp.toPx()))
    val barY = h * 0.5f
    drawLine(p.graphite, Offset(pad * 1.8f, barY), Offset(w - pad * 1.8f, barY), strokeWidth = h * 0.035f, cap = StrokeCap.Round)
    drawLine(p.lime, Offset(pad * 1.8f, barY), Offset(pad * 1.8f + (w - pad * 3.6f) * 0.62f, barY), strokeWidth = h * 0.035f, cap = StrokeCap.Round)
    drawRoundRect(p.lime, topLeft = Offset(pad, h * 0.72f), size = Size(w - pad * 2, h * 0.13f), cornerRadius = CornerRadius(h * 0.065f))
}

/** One accent colour: a circle in the shade it will have in the current theme, with its name. */
@Composable
private fun Swatch(accent: Accent, picked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = Palette.build(accent, LockInColors.isDark)
    val ring by animateFloatAsState(if (picked) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 520f), label = "swatch")
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (picked) LockInColors.Slate else LockInColors.Ink)
            .border(1.dp, if (picked) LockInColors.Graphite else LockInColors.Ink, RoundedCornerShape(18.dp))
            .springClick(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                // The ring springs out around the picked colour.
                if (ring > 0.01f) drawCircle(p.lime, radius = size.minDimension / 2f - 1.dp.toPx(), center = c, style = Stroke(2.dp.toPx() * ring))
                drawCircle(p.lime, radius = size.minDimension / 2f - (6f + 2f * ring).dp.toPx(), center = c)
                if (ring > 0.01f) {
                    // A check mark in the colour's own text colour.
                    val k = size.minDimension / 48f * ring
                    val path = Path().apply {
                        moveTo(c.x - 7f * k, c.y + 0.5f * k)
                        lineTo(c.x - 2f * k, c.y + 5.5f * k)
                        lineTo(c.x + 7.5f * k, c.y - 5f * k)
                    }
                    drawPath(path, p.onLime, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            accent.label,
            style = LockInText.Small.copy(
                color = if (picked) LockInColors.Snow else LockInColors.Mist,
                fontSize = 13.sp,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SetCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, SetCard)
            .padding(vertical = 6.dp),
        content = content,
    )
}

/** What changed in this version, for the "What's new" row. */
private val WhatsNew = listOf(
    "Allowed apps during a lock open cleanly: no cover flashing over them, no false escape, share sheets and pickers inside them work, and closing one takes you back to the lock page.",
    "A lock your partner sets no longer has an emergency release: only they or the timer end it. Emergency calls still work, and your own locks keep hold-to-end.",
    "Ludo: play your partner, or open a table for up to four in your crew. Real dice, captures, sounds, and every roll live.",
    "Faster AI: quizzes and flashcards are written in parts at the same time, and pages are prepared before you ask.",
    "Tablets: Textbooks, Quiz, Settings and Notifications use the whole width in two columns, and Activity opens as a panel on the right.",
    "Livelier widgets: a night mountain with a rope between you and your partner, a dial ring and a glowing week chart, with twinkling stars, drifting cloud and a shimmer on Lock in (they follow Motion above).",
    "Tap your avatar to pick a colour and symbol, and give your partner a private nickname.",
    "A goal sheet with presets, rest days that never break your streak, and a streak freeze.",
    "Achievements, personal records and a weekly recap you can share.",
    "Tap this month on You to see your whole year.",
    "Quiet hours for notifications, and export or delete your data from Settings.",
)
