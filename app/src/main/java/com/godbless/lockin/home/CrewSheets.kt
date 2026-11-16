@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.Crew
import com.godbless.lockin.data.CrewMember
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.safeTake
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The emoji a crew can wear (the empty one means "use the first letter"). */
internal val CrewEmojis = listOf("", "🔥", "🏔️", "📚", "🌙", "⚡", "🦊", "🐼", "🌿", "🎯", "🎧", "☕", "🚀")

/** A member's colour on the rope and board: yours is your own avatar colour, everyone else's is steady per person. */
internal fun memberColor(m: CrewMember, me: String?): Color =
    if (m.id == me) Identity.tint ?: AvatarColors[0]
    else AvatarColors[1 + (m.id.hashCode() and 0x7fffffff) % (AvatarColors.size - 1)]

/** What to call someone: "You", a nickname you gave them (it follows them everywhere), or their name. */
internal fun crewName(m: CrewMember, me: String?): String = if (m.id == me) "You" else Identity.nickname(m.id) ?: m.name

/** The crew's own badge: its colour with its emoji (or first letter). */
@Composable
internal fun CrewBadge(name: String, emoji: String, color: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(AvatarColors.getOrElse(color) { AvatarColors[0] }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            emoji.ifBlank { name.trim().take(1).uppercase().ifEmpty { "C" } },
            style = LockInText.Title.copy(fontSize = (size.value * 0.46f).sp, color = LockInColors.Ink),
        )
    }
}

internal fun shareInvite(context: Context, c: Crew) {
    val text = "Join my LockIn crew \"${c.name}\". Open LockIn, go to Crew, and enter the code ${c.code}."
    runCatching {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to ${c.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Crew code", code))
    Toast.makeText(context, "Code copied.", Toast.LENGTH_SHORT).show()
}

private val JoinedFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun lastText(seconds: Long): String {
    if (seconds <= 0) return "No sessions yet"
    val day = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
    val ago = LocalDate.now().toEpochDay() - day.toEpochDay()
    return when {
        ago <= 0 -> "Held a session today"
        ago == 1L -> "Last held a session yesterday"
        else -> "Last held a session $ago days ago"
    }
}

/**
 * Everything the Crew tab opens over itself: the crew menu, the member sheet and the look editor. Home hosts it in its
 * root box, so the sheets sit above the tab bar and clear of the gesture bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CrewSheets(state: CrewState) {
    val c = state.crew
    val context = LocalContext.current

    // The crew menu.
    OverlaySheet(visible = state.menuOpen && c != null, onDismiss = { state.menuOpen = false }) {
        if (c != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrewBadge(c.name, c.emoji, c.color, 44.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(c.name, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp), maxLines = 1)
                    val owner = c.members.firstOrNull { it.id == c.owner }
                    Text(
                        if (c.mine) "You run this crew" else if (owner != null) "Run by ${owner.name.substringBefore(' ')}" else "${c.members.size} on the rope",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LockInColors.Graphite)) {
                TileRow(Glyph.Share, "Invite friends", c.code, { state.menuOpen = false; shareInvite(context, c) })
                TileDivider()
                TileRow(Glyph.Download, "Copy the code", null, { copyCode(context, c.code) })
                if (c.mine) {
                    TileDivider()
                    TileRow(Glyph.Palette, "Rename and change the look", null, { state.menuOpen = false; state.lookOpen = true })
                    TileDivider()
                    TileRow(Glyph.Lock, "Make a new invite code", null, {
                        state.menuOpen = false
                        state.confirm = ConfirmSpec(
                            "Make a new code?",
                            "The current code (${c.code}) stops working straight away. People already in the crew stay.",
                            "Make a new code",
                            danger = false,
                            onConfirm = { state.newCode() },
                        )
                    })
                }
                TileDivider()
                TileRow(Glyph.Exit, "Leave the crew", null, danger = true, onClick = {
                    state.menuOpen = false
                    state.confirm = ConfirmSpec(
                        "Leave ${c.name}?",
                        "You come off the rope and the board, a session you're in lifts its lock, and standing-session reminders stop. The crew keeps its streak and its chat." +
                            if (c.mine && c.members.size > 1) " Because you run it, the member who has been here longest takes over." else "",
                        "Leave the crew",
                        onConfirm = { state.leave() },
                    )
                })
            }
        }
    }

    // A member.
    var lastMember by remember { mutableStateOf<String?>(null) }
    if (state.memberOpen != null) lastMember = state.memberOpen
    OverlaySheet(visible = state.memberOpen != null && c != null, onDismiss = { state.memberOpen = null }) {
        val m = c?.members?.firstOrNull { it.id == (state.memberOpen ?: lastMember) }
        if (c != null && m != null) {
            val you = m.id == c.me
            val rank = c.members.sortedByDescending { it.week }.indexOfFirst { it.id == m.id } + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, boxSize = 64.dp, filled = true, ring = LockInColors.Slate, tint = memberColor(m, c.me), glyph = if (you) Identity.glyph else null)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(crewName(m, c.me), style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1)
                    Text(
                        (if (m.week > 0) "#$rank this week" else "Not on the board yet") + if (m.id == c.owner) " · Runs the crew" else "",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemberTile("This week", crewHours(m.week), Modifier.weight(1f))
                MemberTile("All time", crewHours(m.total), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemberTile("Sessions", "${m.sessions}", Modifier.weight(1f))
                MemberTile("On the rope since", if (m.joinedAt > 0) Instant.ofEpochSecond(m.joinedAt).atZone(ZoneId.systemDefault()).format(JoinedFmt) else "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                lastText(m.last) + if (m.early > 0) " · left early ${m.early}× this week" else "",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
            if (!you) {
                Spacer(Modifier.height(14.dp))
                var nick by remember(m.id) { mutableStateOf(Identity.nickname(m.id).orEmpty()) }
                LockInField(
                    label = "Nickname (only you see it)",
                    value = nick,
                    onValueChange = { nick = it.safeTake(24); Identity.setNickname(m.id, nick.trim()) },
                    placeholder = m.name,
                    imeAction = ImeAction.Done,
                )
            }
            val nudge = nudgeFor(c, m)
            if (nudge != null) {
                Spacer(Modifier.height(16.dp))
                LockInButton(
                    nudge.label,
                    onClick = {
                        val first = m.name.substringBefore(' ')
                        when (nudge) {
                            CrewNudge.Poke -> state.poke(m.id, first)
                            CrewNudge.Back -> state.cheer(m.id, first, back = true)
                            CrewNudge.Cheer -> state.cheer(m.id, first, back = false)
                        }
                        state.memberOpen = null
                    },
                )
            }
            if (c.mine && !you) {
                Spacer(Modifier.height(10.dp))
                LockInButton(
                    "Remove from the crew",
                    onClick = {
                        state.memberOpen = null
                        state.confirm = ConfirmSpec(
                            "Remove ${m.name.substringBefore(' ')}?",
                            "They come off the rope and lose the crew chat. They can rejoin with the code, so make a new one if you don't want that.",
                            "Remove",
                            onConfirm = { state.kick(m.id, m.name.substringBefore(' ')) },
                        )
                    },
                    kind = ButtonKind.Outline,
                )
            }
        }
    }

    // The look editor (owner).
    OverlaySheet(visible = state.lookOpen && c != null, onDismiss = { state.lookOpen = false }) {
        if (c != null) {
            var name by remember { mutableStateOf(c.name) }
            var emoji by remember { mutableStateOf(c.emoji) }
            var color by remember { mutableIntStateOf(c.color) }
            Text("Crew look", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
            Text("Everyone in the crew sees this.", style = LockInText.Small.copy(color = LockInColors.Mist))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CrewBadge(name, emoji, color, 72.dp) }
            Spacer(Modifier.height(14.dp))
            LockInField(label = "Crew name", value = name, onValueChange = { name = it.safeTake(40) }, placeholder = "Night Owls", imeAction = ImeAction.Done)
            Spacer(Modifier.height(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarColors.forEachIndexed { i, col ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(col)
                            .then(if (color == i) Modifier.border(3.dp, LockInColors.Snow, CircleShape) else Modifier)
                            .clickable(role = Role.RadioButton) { color = i }
                            .semantics { contentDescription = "Colour ${i + 1}"; selected = color == i },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CrewEmojis.forEach { e ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (emoji == e) LockInColors.LimeWash else LockInColors.Graphite)
                            .clickable(role = Role.RadioButton) { emoji = e }
                            .semantics { contentDescription = if (e.isEmpty()) "Use the first letter" else "Symbol $e"; selected = emoji == e },
                        contentAlignment = Alignment.Center,
                    ) { if (e.isEmpty()) Text("Aa", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp)) else Text(e, fontSize = 20.sp) }
                }
            }
            state.error?.let { Text(it, style = LockInText.Error, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(18.dp))
            LockInButton(
                "Save",
                onClick = { state.update(name, emoji, color) { state.lookOpen = false } },
                loading = state.busy,
                enabled = !state.busy && name.isNotBlank(),
            )
        }
    }

    ConfirmSheet(spec = state.confirm, onDismiss = { state.confirm = null })
}

@Composable
private fun MemberTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LockInColors.Graphite)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Title.copy(fontSize = 18.sp, lineHeight = 24.sp), maxLines = 1)
    }
}
