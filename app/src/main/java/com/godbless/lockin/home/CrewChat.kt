package com.godbless.lockin.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.CrewLine
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/** Names in the crew chat each get their own colour, the same every time. */
private val NameColors = listOf(
    Color(0xFFC6F432), Color(0xFF7FD4FF), Color(0xFFFFB86B), Color(0xFFD7A6FF),
    Color(0xFF6BE3B4), Color(0xFFFF8F85), Color(0xFFFFE066), Color(0xFF9FB4FF),
)

private fun colorFor(id: String?): Color {
    val c = NameColors[((id ?: "").hashCode() and 0x7fffffff) % NameColors.size]
    // These are bright for the dark theme; on the light one they're deepened so the names stay readable.
    return if (LockInColors.isDark) c else androidx.compose.ui.graphics.lerp(c, Color.Black, 0.48f)
}

private val CrewQuick = listOf("Who's in?", "Starting soon", "Back at it", "Nice session")

/** The crew's chat: everyone's lines, each name in its own colour, and session news in the middle. */
@Composable
internal fun CrewChat(crew: CrewState, onBack: () -> Unit) {
    val c = crew.crew
    DisposableEffect(Unit) {
        crew.openChat()
        onDispose { crew.closeChat() }
    }
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val mic = remember { MicState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun quoteOf(id: Long?): Pair<String, String>? {
        val l = crew.lines.firstOrNull { it.id == id && id != null } ?: return null
        return (if (l.mine) "You" else l.name) to quoteText(l.body, l.voiceMs, l.voice != null)
    }
    LaunchedEffect(crew.lines.size) { if (crew.lines.isNotEmpty()) list.animateScrollToItem(crew.lines.size) }
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    var ludoOpen by rememberSaveable { mutableStateOf(false) }
    val ludo = LocalLudo.current
    androidx.activity.compose.BackHandler(enabled = ludoOpen) { ludoOpen = false }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .navigationBarsPadding()
            .imePadding()
            .readable(Widths.Chat),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPad, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.godbless.lockin.ui.BackButton(onBack)
            Box(Modifier.padding(start = 12.dp).size(46.dp).clip(CircleShape).background(LockInColors.Graphite), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Crew, LockInColors.Lime, boxSize = 22.dp, strokeWidth = 2f)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(c?.name ?: "Crew", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
                Text(
                    c?.members?.joinToString(", ") { if (it.id == c.me) "You" else it.name.substringBefore(' ') } ?: "",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    maxLines = 1,
                )
            }
            if (ludo != null) {
                // A Ludo table for the crew: up to four can sit down.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Graphite)
                        .clickable(role = Role.Button) { ludoOpen = true }
                        .semantics { contentDescription = "Play Ludo with the crew" },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                        val h = size.width / 2f
                        val cols = com.godbless.lockin.ludo.LudoPalette.base
                        for (i in 0..3) {
                            val x = if (i == 1 || i == 2) h else 0f
                            val y = if (i >= 2) h else 0f
                            drawRoundRect(cols[i], androidx.compose.ui.geometry.Offset(x + 1f, y + 1f), androidx.compose.ui.geometry.Size(h - 2f, h - 2f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
        CrewLudoCard(refreshKey = crew.lines.size to ludoOpen, onOpen = { ludoOpen = true })

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            item {
                Text(
                    if (crew.lines.isEmpty()) "Say hi to the crew." else "Your crew chat",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            itemsIndexed(crew.lines, key = { _, l -> l.id }) { i, line ->
                // A name shows above the first of someone's lines in a row.
                val prev = crew.lines.getOrNull(i - 1)
                val showName = !line.mine && line.kind == "text" && (prev == null || prev.sender != line.sender || prev.kind != "text")
                CrewLineItem(
                    line, c?.me, showName, animate = i >= crew.baseline,
                    quote = quoteOf(line.replyTo),
                    onReply = { crew.replyingTo = line },
                    onQuote = {
                        val at = crew.lines.indexOfFirst { it.id == line.replyTo }
                        if (at >= 0) scope.launch { list.animateScrollToItem(at + 1) }
                    },
                    fetch = { path -> crew.voiceFile(path) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrewQuick.forEach { q ->
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(com.godbless.lockin.ui.ControlShape)
                        .border(1.5.dp, LockInColors.Rim, com.godbless.lockin.ui.ControlShape)
                        .clickable(role = Role.Button) { crew.send(q) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(q, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp))
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = crew.replyingTo != null,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
        ) {
            val r = crew.replyingTo
            if (r != null) ReplyBar(if (r.mine) "yourself" else r.name, quoteText(r.body, r.voiceMs, r.voice != null)) { crew.replyingTo = null }
        }
        (crew.voiceError ?: VoicePlayer.error)?.let {
            Text(it, style = LockInText.Error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (crew.sendingVoice) {
            Text("Sending voice message…", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val fieldShape = RoundedCornerShape(26.dp)
            val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val composerKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            if (mic.recording) RecordingStrip(mic, Modifier.weight(1f)) else Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(fieldShape)
                    .background(LockInColors.Slate)
                    .border(1.5.dp, LockInColors.Graphite, fieldShape)
                    // A tap anywhere in the box (its padding, the empty part to the right) puts the cursor in the
                    // field; the text field alone is only as big as its text.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        composerFocus.requestFocus()
                        composerKeyboard?.show()
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    modifier = Modifier.fillMaxWidth().focusRequester(composerFocus),
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = LockInText.Input,
                    cursorBrush = SolidColor(LockInColors.Lime),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) Text("Message the crew", style = LockInText.Input.copy(color = LockInColors.Mist))
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.size(10.dp))
            val ready = draft.isNotBlank()
            if (ready) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Lime)
                        .clickable(role = Role.Button) {
                            crew.send(draft)
                            draft = ""
                        }
                        .semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.ArrowUp, LockInColors.OnLime, boxSize = 24.dp, strokeWidth = 2.2f)
                }
            } else {
                Box {
                    MicButton(mic, onRecorded = { crew.sendVoice(it) }, onTooShort = { crew.voiceError = "That was too short. Hold the mic or tap it, then say something." })
                    LockHint(mic)
                }
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = ludoOpen && ludo != null,
        enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 0.86f, stiffness = 320f)) { it },
        exit = androidx.compose.animation.slideOutVertically(spring(dampingRatio = 1f, stiffness = 420f)) { it },
    ) {
        if (ludo != null) {
            val live = remember { ludo.crewLive }
            com.godbless.lockin.ludo.LudoGameScreen(ludo, "crew", live, c?.name ?: "Crew", onBack = { ludoOpen = false })
        }
    }
    }
}

private val TimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

/** How a crew event reads in the chat. */
internal fun eventWords(line: CrewLine, me: String?): String {
    val who = if (line.sender == me) "You" else line.name.substringBefore(' ')
    val parts = line.body.split('|')
    return when (parts[0]) {
        "crew_made" -> "$who started the crew"
        "crew_joined" -> "$who joined the crew"
        "crew_left" -> "$who left the crew"
        "session_start" -> {
            val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val at = (parts.getOrNull(2)?.toLongOrNull() ?: 0L) * 1000L
            if (at - line.at * 1000L > 60_000L) "$who set a ${formatMinutes(mins)} session for ${TimeFormat.format(Date(at))}"
            else "$who started a ${formatMinutes(mins)} session"
        }
        "session_join" -> "$who is in"
        "session_left" -> "$who left early, ${formatMinutes(parts.getOrNull(1)?.toIntOrNull() ?: 1)} before the end"
        "session_canceled" -> "$who called off the session"
        "goal_set" -> {
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (m <= 0) "$who cleared the crew goal" else "$who set the crew goal to ${formatMinutes(m)} a week"
        }
        "goal_hit" -> "The crew hit its weekly goal"
        "crew_renamed" -> "$who renamed the crew to ${parts.drop(1).joinToString("|").ifBlank { "something new" }}"
        "crew_kicked" -> {
            val target = parts.getOrNull(2).orEmpty().ifBlank { "someone" }
            if (parts.getOrNull(1) == me) "$who removed you from the crew" else "$who removed $target from the crew"
        }
        "crew_code_new" -> "$who made a new invite code"
        "crew_owner" -> {
            val next = parts.getOrNull(1).orEmpty().ifBlank { "someone" }
            "$next now runs the crew"
        }
        "standing_add" -> "$who added a standing session"
        "ludo_open" -> "$who opened a Ludo table"
        "poke" -> if (parts.getOrNull(1) == me) "$who poked you to join" else "$who poked someone to join"
        "cheer" -> {
            val back = parts.getOrNull(2) == "back"
            if (parts.getOrNull(1) == me) (if (back) "$who says get back on the rope" else "$who is cheering you on")
            else (if (back) "$who told someone to get back on" else "$who cheered someone on")
        }
        else -> "$who did something"
    }
}

@Composable
private fun CrewLineItem(
    line: CrewLine,
    me: String?,
    showName: Boolean,
    animate: Boolean,
    quote: Pair<String, String>? = null,
    onReply: () -> Unit = {},
    onQuote: () -> Unit = {},
    fetch: suspend (String) -> Result<java.io.File> = { Result.failure(Exception("Unavailable")) },
) {
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { shown = true }
    val dy by animateDpAsState(if (shown) 0.dp else 18.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "line")
    val time = TimeFormat.format(Date(line.at * 1000L))

    if (line.kind == "event") {
        val early = line.body.startsWith("session_left")
        Row(Modifier.fillMaxWidth().offset(y = dy), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(CircleShape).background(LockInColors.Slate).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(if (line.body.startsWith("session")) Glyph.Lock else if (line.body.startsWith("ludo")) Glyph.Star else Glyph.Crew, if (early) LockInColors.Flare else LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f)
                Text(
                    "${eventWords(line, me)}, $time",
                    style = LockInText.Small.copy(color = if (early) LockInColors.Flare else LockInColors.Silver),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        return
    }

    val mine = line.mine
    Row(
        Modifier.fillMaxWidth().offset(y = dy).swipeToReply(enabled = true, onReply = onReply),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showName) {
                Text(
                    line.name,
                    style = LockInText.Small.copy(color = colorFor(line.sender), fontSize = 12.sp),
                    modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                )
            }
            val shape = if (mine) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
            } else {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
            }
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(if (mine) LockInColors.Lime else LockInColors.Slate)
                    .then(if (mine) Modifier else Modifier.border(1.dp, LockInColors.Graphite, shape))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    quote?.let { (who, what) -> ReplyQuote(who, what, mine, onQuote) }
                    if (line.voice != null) {
                        VoiceBubble(line.voice, line.voiceMs, line.wave, mine, fetch)
                    } else {
                        Text(
                            line.body,
                            style = LockInText.Sub.copy(color = if (mine) LockInColors.OnLime else LockInColors.Snow, fontSize = 16.sp, lineHeight = 22.sp),
                        )
                    }
                }
            }
            Text(time, style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp))
        }
    }
}
