package com.godbless.lockin.home

import android.content.Intent
import com.godbless.lockin.ui.pushSwap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.MediaDir
import com.godbless.lockin.data.PreparedPhoto
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.calmFloat
import com.godbless.lockin.ui.graphemes
import com.godbless.lockin.ui.readable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val QuickReplies = listOf("Nudge", "Nice work", "Running late", "Unlock me please")
private val Hearts = listOf("❤️", "👍", "😂", "😮", "🙏", "🔥")
private val Starters = listOf("Hey! 👋", "Ready to lock in?", "Study together today?")
private const val EditWindowMs = 15 * 60 * 1000L
private const val NudgeCooldownMs = 30_000L

private val ChipShape = ControlShape

// =============================================================================================
// What the list is made of
// =============================================================================================

private sealed interface Item { val key: String }
private class DayItem(val label: String, override val key: String) : Item
private class UnreadItem : Item { override val key = "unread" }
private class OlderItem : Item { override val key = "older" }
private class TypingItem : Item { override val key = "typing" }

/** [first] and [last] are this bubble's place in a run from the same person; [active] is a card that still has buttons. */
private class MsgItem(val m: Message, val first: Boolean, val last: Boolean, val active: Boolean) : Item {
    override val key: String get() = m.key
}

private val Settled = setOf("unlock_denied", "lock_lifted", "emergency_release", "lock_canceled")

private fun dayOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dayLabel(at: Long): String {
    val d = dayOf(at)
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        d.year == today.year -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
        else -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.year}"
    }
}

/** The same run: one person, close together in time, on the same day. */
private fun sameRun(a: Message, b: Message): Boolean =
    a.who == b.who && a.who != Who.System && a.at > 0 && b.at - a.at in 0..120_000 && dayOf(a.at) == dayOf(b.at)

private val AllowRx = Regex("^(.+?) allowed (.+?) during locks on (.+)$")

/**
 * Runs of "You allowed A during locks on X's phone", "...allowed B...", close together, become one line: "You allowed
 * A and B during locks on X's phone". The merged line keeps the first one's id, so it simply grows in place.
 */
private fun mergeAllows(msgs: List<Message>): List<Message> {
    val out = ArrayList<Message>(msgs.size)
    for (m in msgs) {
        val last = out.lastOrNull()
        val a = if (m.who == Who.System) AllowRx.find(m.text) else null
        val b = if (last != null && last.who == Who.System && last.theirs == m.theirs && m.at - last.at in 0..300_000L) AllowRx.find(last.text) else null
        if (a != null && b != null && a.groupValues[1] == b.groupValues[1] && a.groupValues[3] == b.groupValues[3]) {
            val names = (b.groupValues[2].replace(" and ", ", ").split(", ") + a.groupValues[2]).filter { it.isNotBlank() }.distinct()
            val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            out[out.lastIndex] = last!!.copy(text = "${a.groupValues[1]} allowed $joined during locks on ${a.groupValues[3]}")
        } else out.add(m)
    }
    return out
}

private fun buildItems(raw: List<Message>, unreadFrom: Long, typing: Boolean, loadingOlder: Boolean, unlockAsked: Boolean): List<Item> {
    val msgs = mergeAllows(raw)
    val out = ArrayList<Item>(msgs.size + 8)
    if (loadingOlder) out.add(OlderItem())
    var askedAt = -1
    var protectAt = -1
    var inviteAt = -1
    var quizAt = -1
    msgs.forEachIndexed { i, m ->
        if (m.who == Who.System && m.theirs) {
            if (m.code == "asked_unlock") askedAt = i
            if (m.code == "protect_asked") protectAt = i
            if (m.code == "game_invite") inviteAt = i
            if (m.code == "quiz_challenge") quizAt = i
        }
    }
    // Your partner's newest quiz keeps its Play button for a week, until you send back a score.
    val quizLive = quizAt >= 0 && System.currentTimeMillis() - msgs[quizAt].at < 7 * 86_400_000L &&
        msgs.drop(quizAt + 1).none { it.who == Who.System && !it.theirs && it.code == "quiz_score" }
    val now = System.currentTimeMillis()
    val askedLive = unlockAsked && askedAt >= 0 && msgs.drop(askedAt + 1).none { it.who == Who.System && it.code in Settled }
    // A request to switch something off stays answerable for 30 minutes, until someone answers it.
    val protectLive = protectAt >= 0 && now - msgs[protectAt].at < 30 * 60_000L &&
        msgs.drop(protectAt + 1).none { it.who == Who.System && (it.code == "protect_allowed" || it.code == "protect_declined") }
    val inviteLive = inviteAt >= 0 && now - msgs[inviteAt].at < 20 * 60_000L
    var prev: Message? = null
    var lastDay: LocalDate? = null
    var unreadPlaced = false
    for ((i, m) in msgs.withIndex()) {
        if (m.at > 0) {
            val d = dayOf(m.at)
            if (d != lastDay) { out.add(DayItem(dayLabel(m.at), "d$d")); lastDay = d; prev = null }
        }
        if (!unreadPlaced && unreadFrom > 0 && m.who == Who.Partner && m.id >= unreadFrom) {
            out.add(UnreadItem()); unreadPlaced = true; prev = null
        }
        val next = msgs.getOrNull(i + 1)
        val breaksBefore = next != null && !unreadPlaced && unreadFrom > 0 && next.who == Who.Partner && next.id >= unreadFrom
        out.add(
            MsgItem(
                m,
                first = prev == null || !sameRun(prev, m),
                last = next == null || breaksBefore || !sameRun(m, next),
                active = (i == askedAt && askedLive) || (i == protectAt && protectLive) || (i == inviteAt && inviteLive) || (i == quizAt && quizLive),
            ),
        )
        prev = m
    }
    if (typing) out.add(TypingItem())
    return out
}

private fun isEmojiOnly(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    val g = graphemes(t)
    if (g.size > 3) return false
    return g.all { gr ->
        val cp = gr.codePointAt(0)
        (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF) && !Character.isLetterOrDigit(cp)
    }
}

private val LinkRegex = Regex("""(https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)

private fun openLink(context: android.content.Context, raw: String) {
    val url = raw.trimEnd('.', ',', ')', '!', '?', ';', ':').let { if (it.startsWith("www.", true)) "https://$it" else it }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun noteGlyph(code: String): Glyph = when {
    code == "nudge" -> Glyph.Bell
    code == "summit" -> Glyph.Peak
    code.startsWith("quiz_") -> Glyph.Cards
    code.startsWith("plan_") -> Glyph.Calendar
    code.startsWith("limit_") || code.startsWith("allow_") -> Glyph.Target
    code == "joined" || code.startsWith("unpair") -> Glyph.People
    else -> Glyph.Lock
}

private fun readableSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

// =============================================================================================
// The screen
// =============================================================================================

/**
 * Your chat with your partner. Your messages show at once and say if they could not be sent. Photos are shrunk, relayed through
 * the server and kept on each phone. Hold a message for reactions, reply, copy, edit and delete; double tap to heart it.
 */
@Composable
internal fun ChatScreen(
    chat: ChatState,
    onBack: () -> Unit,
    onOpenPartner: () -> Unit = {},
    /** Your partner has asked you to unlock, and it is still waiting for an answer. */
    unlockAsked: Boolean = false,
    onAnswerUnlock: (Boolean) -> Unit = {},
    onAnswerProtect: (Boolean) -> Unit = {},
    onOpenGame: () -> Unit = {},
    onOpenQuiz: (String) -> Unit = {},
    /** What their phone is doing ("Phone active", "Last seen 12 min ago"), shown under their name. */
    status: String = "Roped together",
    /** In a side panel: whether it is widened, and the button that widens or narrows it. Null on a phone. */
    widened: Boolean? = null,
    onToggleWidth: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val partner = LocalPartnerName.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val mic = remember { MicState() }
    var draft by rememberSaveable { mutableStateOf("") }

    // Sheets and full-screen layers.
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var storageOpen by remember { mutableStateOf(false) }
    // While any sheet is up, what sits around it (quick replies, the jump-to-newest button) steps out of the way.
    var gamePicker by remember { mutableStateOf(false) }
    val sheetOpen = menuFor != null || attachOpen || headerMenu || storageOpen || gamePicker
    var viewerFile by remember { mutableStateOf<File?>(null) }
    var viewerCaption by remember { mutableStateOf("") }
    var composeOpen by remember { mutableStateOf(false) }
    // The game, full screen over the chat.
    var gameOpen by remember { mutableStateOf(false) }
    var gameKind by remember { mutableStateOf("ttt") }
    var picked by remember { mutableStateOf<PreparedPhoto?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var timeShownFor by remember { mutableStateOf<String?>(null) }
    var nudgeUntil by remember { mutableLongStateOf(0L) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun closeSheets() { menuFor = null; attachOpen = false; headerMenu = false; storageOpen = false; gamePicker = false }
    BackHandler(enabled = menuFor != null || attachOpen || headerMenu || storageOpen || gamePicker) { closeSheets() }
    BackHandler(enabled = mic.recording) { mic.cancel() }
    BackHandler(enabled = gameOpen) { gameOpen = false }
    BackHandler(enabled = composeOpen) { composeOpen = false; picked?.file?.delete(); picked = null }
    BackHandler(enabled = viewerFile != null) { viewerFile = null }

    // ---- Where you had read up to, and the unread line ----
    val readUpTo = remember { chat.lastReadId() }
    var unreadFrom by remember { mutableLongStateOf(0L) }
    var ready by remember { mutableStateOf(false) }
    var unseen by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose {
            chat.markRead()
            VoicePlayer.stop()
        }
    }
    // Your partner's ticks turn to Seen only while you are looking at the chat with the app in front.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        chat.viewing = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        chat.reportSeen()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { chat.viewing = true; chat.reportSeen() }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) chat.viewing = false
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            chat.viewing = false
        }
    }

    val lastMineKey by remember {
        derivedStateOf { chat.messages.lastOrNull { it.who == Who.Me && !it.deleted && it.delivery == Delivery.Sent }?.key }
    }
    val rows by remember(unreadFrom, unlockAsked) {
        derivedStateOf { buildItems(chat.messages, unreadFrom, chat.partnerTyping, chat.loadingOlder, unlockAsked) }
    }

    // First time the messages are there: jump (no scrolling through the whole history) to the first unread, or the end.
    LaunchedEffect(chat.loaded) {
        if (!chat.loaded || ready) return@LaunchedEffect
        if (readUpTo > 0) {
            unreadFrom = chat.messages.firstOrNull { it.who == Who.Partner && it.id > readUpTo }?.id ?: 0L
            withFrameNanos { }
        }
        val at = if (unreadFrom > 0) rows.indexOfFirst { it is UnreadItem } else rows.lastIndex
        list.scrollToItem(at.coerceAtLeast(0))
        ready = true
    }

    // New messages: follow them if you are at the bottom (or sent it); otherwise count them on the arrow.
    val newestKey = chat.messages.lastOrNull()?.key
    LaunchedEffect(newestKey) {
        if (!ready) return@LaunchedEffect
        val n = chat.messages.lastOrNull() ?: return@LaunchedEffect
        if (!list.canScrollForward || n.who == Who.Me) list.animateScrollToItem(rows.lastIndex)
        else if (n.who == Who.Partner) unseen++
    }
    LaunchedEffect(chat.partnerTyping) {
        if (ready && chat.partnerTyping && !list.canScrollForward) list.animateScrollToItem(rows.lastIndex)
    }
    val canScrollForward = list.canScrollForward
    // The jump button only matters when the newest message is really out of reach. While the keyboard moves the list
    // for a moment, the last bubble is only a few pixels short, and the button would sit on top of it.
    val farFromNewest by remember(rows.size) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index < rows.lastIndex || (last.offset + last.size - info.viewportEndOffset) > 120
        }
    }
    LaunchedEffect(canScrollForward) {
        if (!canScrollForward) { unseen = 0; chat.markRead() }
    }

    // Older messages load when you scroll near the top.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex }.collect { if (it <= 2) chat.loadOlder() }
    }

    // The keyboard coming up should not hide the newest message.
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeOpen) {
        if (ready && imeOpen && !list.canScrollForward) list.animateScrollToItem(rows.lastIndex)
    }

    // A failed edit/delete/voice for a moment, then it goes away.
    val problem = chat.actionError ?: chat.voiceError ?: VoicePlayer.error
    LaunchedEffect(problem) {
        if (problem != null) {
            delay(4500)
            chat.actionError = null
            chat.voiceError = null
            VoicePlayer.clearError()
        }
    }
    LaunchedEffect(nudgeUntil) {
        while (System.currentTimeMillis() < nudgeUntil) { nowTick = System.currentTimeMillis(); delay(1000) }
        nowTick = System.currentTimeMillis()
    }

    // Editing puts the old words in the box.
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A recording or a held message takes over the screen, so the keyboard steps aside.
    LaunchedEffect(mic.recording) { if (mic.recording) keyboard?.hide() }
    LaunchedEffect(menuFor != null) { if (menuFor != null) keyboard?.hide() }
    LaunchedEffect(chat.editing) {
        chat.editing?.let { draft = it.text; composerFocus.requestFocus(); keyboard?.show() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            picked = null
            preparing = true
            composeOpen = true
            scope.launch {
                com.godbless.lockin.home.PhotoPrep.prepare(context, uri)
                    .onSuccess { picked = it }
                    .onFailure { composeOpen = false; chat.voiceError = "Couldn't read that photo." }
                preparing = false
            }
        }
    }

    // The camera: a photo is taken into the app's cache, read like a picked one, then the original is deleted.
    var shotPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shotPath?.let { File(it) }
        shotPath = null
        if (ok && f != null && f.length() > 0) {
            picked = null
            preparing = true
            composeOpen = true
            scope.launch {
                com.godbless.lockin.home.PhotoPrep.prepare(context, android.net.Uri.fromFile(f))
                    .onSuccess { picked = it }
                    .onFailure { composeOpen = false; chat.voiceError = "Couldn't read that photo." }
                preparing = false
                f.delete()
            }
        } else f?.delete()
    }
    fun openCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        shotPath = f.absolutePath
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", f)
        runCatching { camera.launch(uri) }.onFailure { shotPath = null; chat.voiceError = "No camera app found." }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else chat.voiceError = "Allow the camera to take a photo."
    }
    fun takePhoto() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) openCamera() else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    fun quoteOf(id: Long?): Pair<String, String>? {
        val m = chat.messages.firstOrNull { it.id == id && id != null && it.who != Who.System } ?: return null
        if (m.deleted) return (if (m.who == Who.Me) "You" else partner) to "Message deleted"
        return (if (m.who == Who.Me) "You" else partner) to
            if (m.media != null) (m.text.takeIf { it != "Photo" } ?: "Photo") else quoteText(m.text, m.voiceMs, m.voice != null)
    }

    fun submit() {
        val t = draft.trim()
        if (t.isEmpty()) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (chat.editing != null) chat.saveEdit(t) else chat.send(t)
        draft = ""
    }

    fun nudge() {
        val now = System.currentTimeMillis()
        if (now < nudgeUntil) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        chat.event("nudge")
        nudgeUntil = now + NudgeCooldownMs
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .readable(Widths.Chat),
        ) {
            // ---- Header ----
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = topPad, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Row(
                    Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button, onClick = onOpenPartner)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box { Avatar(partner, boxSize = 46.dp, filled = true, ring = LockInColors.Ink) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(partner, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 1)
                        // The line changes by sliding: the old one leaves upward as the new one rises into its place.
                        AnimatedContent(
                            targetState = if (chat.offline) "No connection" else if (chat.partnerTyping) "typing…" else status,
                            transitionSpec = {
                                (slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it } togetherWith
                                    slideOutVertically(spring(dampingRatio = 1f, stiffness = 600f)) { -it }).using(null)
                            },
                            modifier = Modifier.clipToBounds(),
                            label = "chat-status",
                        ) { line ->
                            Text(
                                line,
                                style = LockInText.Small.copy(color = if (line == "typing…" || line == "Phone active") LockInColors.Lime else LockInColors.Mist),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (widened != null) {
                    // Two chevrons that point apart when the panel can grow and together when it can shrink.
                    val flip by animateFloatAsState(if (widened) 180f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "widen")
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onToggleWidth)
                            .semantics { contentDescription = if (widened) "Make the chat narrower" else "Make the chat wider" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = flip }) {
                            val w = 2.2.dp.toPx()
                            val c = LockInColors.Silver
                            val round = androidx.compose.ui.graphics.StrokeCap.Round
                            drawLine(c, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.28f, size.height * 0.5f), w, round)
                            drawLine(c, Offset(size.width * 0.28f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), w, round)
                        }
                    }
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { headerMenu = true }
                        .semantics { contentDescription = "More" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        val r = 2.1.dp.toPx()
                        for (i in 0..2) drawCircle(LockInColors.Silver, r, Offset(size.width / 2f, size.height * (0.17f + 0.33f * i)))
                    }
                }
            }
            // A line under the header only once the messages have scrolled beneath it.
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (list.canScrollBackward) LockInColors.Graphite else Color.Transparent))

            // ---- Messages ----
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val maxBubble = (maxWidth * 0.78f).coerceAtMost(440.dp)
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (chat.messages.isEmpty()) {
                        item(key = "empty") {
                            EmptyChat(partner, loaded = chat.loaded, offline = chat.offline, onStart = { chat.send(it) })
                        }
                    }
                    items(rows, key = { it.key }) { item ->
                        when (item) {
                            is DayItem -> Text(
                                item.label,
                                style = LockInText.Small.copy(color = LockInColors.Mist),
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                                textAlign = TextAlign.Center,
                            )
                            is UnreadItem -> UnreadLine()
                            is OlderItem -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                RingSpinner(LockInColors.Mist, 18.dp, 2.dp)
                            }
                            is TypingItem -> Box(Modifier.padding(top = 8.dp)) { TypingBubble() }
                            is MsgItem -> MessageRow(
                                item = chat.dying[item.m.key]?.let { MsgItem(it, item.first, item.last, item.active) } ?: item,
                                maxBubble = maxBubble,
                                me = chat.me,
                                partner = partner,
                                timeShown = timeShownFor == item.m.key,
                                quote = quoteOf(item.m.replyTo),
                                onReply = { if (item.m.id > 0 && !item.m.deleted) chat.replyingTo = item.m },
                                onQuote = {
                                    val target = chat.messages.firstOrNull { it.id == item.m.replyTo }?.key
                                    val at = rows.indexOfFirst { it.key == target }
                                    if (at >= 0) scope.launch { list.animateScrollToItem(at) }
                                },
                                onMenu = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!item.m.deleted && item.m.who != Who.System) menuFor = item.m
                                },
                                onHeart = { if (item.m.id > 0 && !item.m.deleted && chat.canRich) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); chat.react(item.m, "❤️") } },
                                onToggleTime = { timeShownFor = if (timeShownFor == item.m.key) null else item.m.key },
                                onRetry = { chat.retry(item.m) },
                                onOpenPhoto = { f -> viewerCaption = item.m.text; viewerFile = f },
                                voiceFetch = { path -> chat.voiceFile(path) },
                                photoFetch = { path -> chat.photoFile(path) },
                                onAnswerUnlock = onAnswerUnlock,
                                onAnswerProtect = onAnswerProtect,
                                onOpenGame = { kind -> gameKind = if (kind.contains("|wd")) "wd" else if (kind.contains("|ludo")) "ludo" else "ttt"; gameOpen = true },
                                onOpenQuiz = onOpenQuiz,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                dying = chat.dying.containsKey(item.m.key),
                                onDied = { chat.finishDelete(item.m) },
                                latestMine = item.m.key == lastMineKey,
                            )
                        }
                    }
                }

                // Back to the newest, with a count of what you have not seen.
                SlideBubble(
                    visible = ready && canScrollForward && farFromNewest && !sheetOpen,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (unseen > 0) LockInColors.Lime else LockInColors.Slate)
                            .border(1.5.dp, if (unseen > 0) LockInColors.Lime else LockInColors.Graphite, CircleShape)
                            .clickable(role = Role.Button) { scope.launch { unseen = 0; list.animateScrollToItem(rows.lastIndex) } }
                            .semantics { contentDescription = if (unseen > 0) "$unseen new messages. Go to the newest" else "Go to the newest message" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (unseen > 0) Text(if (unseen > 9) "9+" else "$unseen", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 15.sp))
                        else Canvas(Modifier.size(16.dp)) {
                            val c = LockInColors.Silver
                            val w = 2.2.dp.toPx()
                            drawLine(c, Offset(size.width / 2f, 1.dp.toPx()), Offset(size.width / 2f, size.height - 2.dp.toPx()), w, androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(c, Offset(size.width * 0.18f, size.height * 0.58f), Offset(size.width / 2f, size.height - 1.dp.toPx()), w, androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(c, Offset(size.width * 0.82f, size.height * 0.58f), Offset(size.width / 2f, size.height - 1.dp.toPx()), w, androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
            }

            // ---- Quick replies: only while the box is empty and the keyboard is down ----
            AnimatedVisibility(
                visible = draft.isBlank() && !imeOpen && chat.editing == null && !mic.recording && !sheetOpen,
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickReplies.forEach { q ->
                        val cooling = q == "Nudge" && nowTick < nudgeUntil
                        Box(
                            Modifier
                                .height(36.dp)
                                .clip(ChipShape)
                                .border(1.5.dp, if (cooling) LockInColors.Graphite else LockInColors.Rim, ChipShape)
                                .clickable(enabled = !cooling, role = Role.Button) { if (q == "Nudge") nudge() else chat.send(q) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (cooling) "Nudged" else q,
                                style = LockInText.Label.copy(color = if (cooling) LockInColors.Mist else LockInColors.Snow, fontSize = 13.sp),
                            )
                        }
                    }
                }
            }

            // ---- The game you have going with your partner ----
            ChatGameCard(partner, refreshKey = chat.messages.size to gameOpen, onOpen = { keyboard?.hide(); gameKind = "ttt"; gameOpen = true })
            ChatWordCard(partner, refreshKey = chat.messages.size to gameOpen, onOpen = { keyboard?.hide(); gameKind = "wd"; gameOpen = true })
            ChatLudoCard(partner, refreshKey = chat.messages.size to gameOpen, onOpen = { keyboard?.hide(); gameKind = "ludo"; gameOpen = true })

            // ---- What you are answering, or fixing ----
            AnimatedVisibility(
                visible = chat.replyingTo != null,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
            ) {
                val r = chat.replyingTo
                if (r != null) ReplyBar(
                    if (r.who == Who.Me) "yourself" else partner,
                    if (r.media != null) (r.text.takeIf { it != "Photo" } ?: "Photo") else quoteText(r.text, r.voiceMs, r.voice != null),
                ) { chat.replyingTo = null }
            }
            AnimatedVisibility(
                visible = chat.editing != null,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
            ) {
                val e = chat.editing
                if (e != null) EditBar(e.text) { chat.editing = null; draft = "" }
            }
            problem?.let {
                Text(it, style = LockInText.Error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (chat.sendingVoice) {
                Text("Sending voice message…", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            // ---- Message box ----
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val fieldShape = RoundedCornerShape(26.dp)
                // The plus slides away as the recorder opens, instead of vanishing and making the box jump.
                AnimatedVisibility(
                    visible = !mic.recording && chat.editing == null,
                    enter = expandHorizontally(spring(dampingRatio = 1f, stiffness = 600f)),
                    exit = shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 700f)),
                ) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(LockInColors.Slate)
                            .border(1.5.dp, LockInColors.Graphite, CircleShape)
                            .clickable(role = Role.Button) { keyboard?.hide(); attachOpen = true }
                            .semantics { contentDescription = "Add a photo or something else" },
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Plus, LockInColors.Silver, boxSize = 22.dp, strokeWidth = 2.2f) }
                }
                if (mic.recording) {
                    RecordingStrip(mic, Modifier.weight(1f))
                } else Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(fieldShape)
                        .background(LockInColors.Slate)
                        .border(1.5.dp, if (chat.editing != null) LockInColors.Lime else LockInColors.Graphite, fieldShape)
                        // A tap anywhere in the box puts the cursor in the field; the field alone is only as big as its text.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            composerFocus.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        modifier = Modifier.fillMaxWidth().focusRequester(composerFocus),
                        value = draft,
                        onValueChange = {
                            draft = it.take(1000)
                            if (it.isNotEmpty()) chat.typed()
                        },
                        textStyle = LockInText.Input,
                        cursorBrush = SolidColor(LockInColors.Lime),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text("Message $partner", style = LockInText.Input.copy(color = LockInColors.Mist))
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.size(10.dp))
                val ready2 = draft.isNotBlank()
                // An empty box offers the mic (hold to record); typing turns it into the send button, with a little pop.
                Box {
                    AnimatedContent(
                        targetState = ready2,
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.6f, stiffness = 520f), initialScale = 0.4f) togetherWith
                                scaleOut(spring(stiffness = 600f), targetScale = 0.4f)).using(SizeTransform(clip = false))
                        },
                        label = "send",
                    ) { typing ->
                        if (typing) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(LockInColors.Lime)
                                    .clickable(role = Role.Button) { submit() }
                                    .semantics { contentDescription = if (chat.editing != null) "Save" else "Send" },
                                contentAlignment = Alignment.Center,
                            ) {
                                GlyphIcon(Glyph.ArrowUp, LockInColors.OnLime, boxSize = 24.dp, strokeWidth = 2.2f)
                            }
                        } else {
                            MicButton(
                                mic,
                                onRecorded = { chat.sendVoice(it) },
                                onTooShort = { chat.voiceError = "That was too short. Hold the mic or tap it, then say something." },
                            )
                        }
                    }
                    LockHint(mic)
                }
            }
        }

        // ================= Sheets =================
        // What a held message offers.
        var lastHeld by remember { mutableStateOf<Message?>(null) }
        if (menuFor != null) lastHeld = menuFor
        BottomSheet(visible = menuFor != null, onDismiss = { menuFor = null }) {
            val m = menuFor ?: lastHeld ?: return@BottomSheet
            var confirmDelete by remember(m.key) { mutableStateOf(false) }
            val mine = m.who == Who.Me
            val file = m.localFile ?: m.media?.let { ChatMedia.cached(it) }
            Text(
                (if (mine) "You" else partner) + ", ${m.time}" + if (m.edited) " · edited" else "",
                style = LockInText.Small.copy(color = LockInColors.Mist),
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
            if (chat.canRich && m.id > 0 && m.delivery == Delivery.Sent) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val mineReaction = chat.me?.let { m.reactions[it] }
                    Hearts.forEach { e ->
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (mineReaction == e) LockInColors.Lime.copy(alpha = 0.22f) else LockInColors.Graphite)
                                .clickable(role = Role.Button) { chat.react(m, e); menuFor = null }
                                .semantics { contentDescription = "React $e" },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (m.delivery == Delivery.Failed) {
                SheetRow("Try sending again") { chat.retry(m); menuFor = null }
                SheetRow("Discard", danger = true) { chat.discard(m); menuFor = null }
            } else {
                if (m.id > 0) SheetRow("Reply") { chat.replyingTo = m; menuFor = null }
                val copyText = m.text.takeIf { it.isNotBlank() && !(m.media != null && it == "Photo") && m.voice == null }
                if (copyText != null) SheetRow("Copy") {
                    clipboard.setText(AnnotatedString(copyText))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    menuFor = null
                }
                if (file != null && android.os.Build.VERSION.SDK_INT >= 29) SheetRow("Save to gallery") {
                    Toast.makeText(context, if (savePhotoToGallery(context, file)) "Saved to Pictures/LockIn" else "Couldn't save it", Toast.LENGTH_SHORT).show()
                    menuFor = null
                }
                val canEdit = mine && chat.canRich && m.id > 0 && m.media == null && m.voice == null && System.currentTimeMillis() - m.at < EditWindowMs
                if (canEdit) SheetRow("Edit") { chat.startEdit(m); menuFor = null }
                if (mine && chat.canRich && m.id > 0) SheetRow(
                    if (confirmDelete) "Delete for both of you. Tap again" else "Delete",
                    danger = true,
                    armed = confirmDelete,
                ) {
                    if (confirmDelete) { chat.delete(m); menuFor = null } else confirmDelete = true
                }
            }
        }

        // Which game.
        BottomSheet(visible = gamePicker, onDismiss = { gamePicker = false }) {
            GamePicker(
                onTicTacToe = { gamePicker = false; keyboard?.hide(); gameKind = "ttt"; gameOpen = true },
                onWordDuel = { gamePicker = false; keyboard?.hide(); gameKind = "wd"; gameOpen = true },
                onLudo = { gamePicker = false; keyboard?.hide(); gameKind = "ludo"; gameOpen = true },
            )
        }

        // The + button.
        BottomSheet(visible = attachOpen, onDismiss = { attachOpen = false }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachTile("Camera", enabled = chat.canRich, onClick = {
                    attachOpen = false
                    takePhoto()
                }) { CameraIcon(LockInColors.Lime) }
                AttachTile("Photo", enabled = chat.canRich, onClick = {
                    attachOpen = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { PhotoIcon(LockInColors.Lime) }
                AttachTile("Nudge", enabled = nowTick >= nudgeUntil, onClick = { attachOpen = false; nudge() }) {
                    GlyphIcon(Glyph.Bell, LockInColors.Lime, boxSize = 26.dp, strokeWidth = 2f)
                }
                AttachTile("Game", enabled = true, onClick = { attachOpen = false; gamePicker = true }) {
                    GlyphIcon(Glyph.Target, LockInColors.Lime, boxSize = 26.dp, strokeWidth = 2f)
                }
            }
            if (!chat.canRich) {
                Text(
                    "Photos need a LockIn server update. Try again soon.",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp),
                )
            }
        }

        // The three dots.
        BottomSheet(visible = headerMenu, onDismiss = { headerMenu = false }) {
            SheetRow("$partner's page") { headerMenu = false; onOpenPartner() }
            SheetRow("Photos and voice notes on this phone") { headerMenu = false; storageOpen = true }
        }

        BottomSheet(visible = storageOpen, onDismiss = { storageOpen = false }) {
            var bytes by remember { mutableLongStateOf(-1L) }
            var confirm by remember { mutableStateOf(false) }
            LaunchedEffect(storageOpen, ChatMedia.epoch) {
                if (storageOpen) bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaDir.bytes() }
            }
            Text("On this phone", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 17.sp), modifier = Modifier.padding(start = 6.dp))
            Text(
                if (bytes < 0) "Counting…" else readableSize(bytes),
                style = LockInText.Title.copy(fontSize = 30.sp, lineHeight = 36.sp, color = LockInColors.Lime),
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
            Text(
                "A photo or voice note passes through LockIn's server and is saved here. A few hours after your partner opens it, " +
                    "the server's copy is deleted, and one nobody opens is deleted after 7 days. So this phone holds the only copy: " +
                    "clearing it removes photos and voice notes that are gone from the server for good.",
                style = LockInText.Small.copy(color = LockInColors.Body, lineHeight = 19.sp),
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            )
            SheetRow(if (confirm) "Delete them from this phone. Tap again" else "Clear photos and voice notes", danger = true) {
                if (confirm) {
                    ChatMedia.clearAll()
                    VoicePlayer.stop()
                    confirm = false
                    storageOpen = false
                } else confirm = true
            }
        }

        // ================= Full screen =================
        AnimatedVisibility(
            visible = gameOpen,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { it },
            exit = slideOutVertically(spring(stiffness = 500f)) { it },
        ) {
            ChatGameScreen(partner, gameKind, onClose = { gameOpen = false })
        }
        AnimatedVisibility(
            visible = composeOpen,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { it },
            exit = slideOutVertically(spring(stiffness = 500f)) { it },
        ) {
            PhotoCompose(
                photo = picked,
                preparing = preparing,
                partner = partner,
                onSend = { caption ->
                    picked?.let { chat.sendPhoto(it, caption) }
                    picked = null
                    composeOpen = false
                },
                onCancel = { composeOpen = false; picked?.file?.delete(); picked = null },
            )
        }
        AnimatedVisibility(
            visible = viewerFile != null,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { it },
            exit = slideOutVertically(spring(stiffness = 500f)) { it },
        ) {
            val f = viewerFile
            if (f != null) PhotoViewer(
                file = f,
                caption = viewerCaption,
                onClose = { viewerFile = null },
                onSave = if (android.os.Build.VERSION.SDK_INT >= 29) ({
                    Toast.makeText(context, if (savePhotoToGallery(context, f)) "Saved to Pictures/LockIn" else "Couldn't save it", Toast.LENGTH_SHORT).show()
                }) else null,
            )
        }
    }
}

// =============================================================================================
// Pieces
// =============================================================================================

@Composable
private fun EmptyChat(partner: String, loaded: Boolean, offline: Boolean, onStart: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            loaded -> {
                Avatar(partner, boxSize = 72.dp, filled = true, ring = LockInColors.Ink)
                Text("Say hi to $partner", style = LockInText.Title.copy(fontSize = 22.sp), modifier = Modifier.padding(top = 14.dp))
                Text("Start with one of these, or write your own.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Starters.forEach { s ->
                        Box(
                            Modifier.clip(ChipShape).border(1.5.dp, LockInColors.Rim, ChipShape).clickable(role = Role.Button) { onStart(s) }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text(s, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp)) }
                    }
                }
            }
            offline -> Text("Can't reach the server.", style = LockInText.Small.copy(color = LockInColors.Mist))
            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun UnreadLine() {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(LockInColors.Lime.copy(alpha = 0.5f)))
        Text("New messages", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 12.sp), modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(LockInColors.Lime.copy(alpha = 0.5f)))
    }
}

@Composable
private fun EditBar(old: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(LockInColors.Slate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(48.dp).background(LockInColors.Lime))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Editing message", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 12.sp), maxLines = 1)
            Text(old.replace('\n', ' '), style = LockInText.Small.copy(color = LockInColors.Silver), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box(
            Modifier.padding(end = 8.dp).size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Stop editing" },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 12.dp, strokeWidth = 2.2f) }
    }
}

/** A card that slides up from the bottom. Taps outside it close it. */
@Composable
private fun BoxScope.BottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (visible) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it },
        exit = slideOutVertically(spring(stiffness = 520f)) { it },
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .readable(Widths.Chat)
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.5.dp, LockInColors.Graphite, shape)
                // Swallows taps on the card itself, so only the space around it closes it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetRow(label: String, danger: Boolean = false, armed: Boolean = false, onClick: () -> Unit) {
    // A row that now needs a second tap springs and turns red, so it is clear that the next tap is the real one.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed) {
            pop.snapTo(0.94f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 450f))
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(14.dp))
            .then(if (armed) Modifier.background(LockInText.Error.color.copy(alpha = 0.16f)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = LockInText.Label.copy(color = if (danger) LockInText.Error.color else LockInColors.Snow, fontSize = 16.sp))
    }
}

@Composable
private fun AttachTile(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(if (enabled) LockInColors.Graphite else LockInColors.Ink),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(label, style = LockInText.Small.copy(color = if (enabled) LockInColors.Snow else LockInColors.Mist, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** A little camera, drawn. */
@Composable
private fun CameraIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = 2.dp.toPx()
        val body = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.3f)
            lineTo(size.width * 0.3f, size.height * 0.3f)
            lineTo(size.width * 0.38f, size.height * 0.16f)
            lineTo(size.width * 0.62f, size.height * 0.16f)
            lineTo(size.width * 0.7f, size.height * 0.3f)
            lineTo(size.width * 0.92f, size.height * 0.3f)
            lineTo(size.width * 0.92f, size.height * 0.82f)
            lineTo(size.width * 0.08f, size.height * 0.82f)
            close()
        }
        drawPath(path, color, style = body)
        drawCircle(color, radius = size.width * 0.17f, center = Offset(size.width / 2f, size.height * 0.56f), style = body)
    }
}

/** A little picture frame, drawn. */
@Composable
private fun PhotoIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val sw = 2.dp.toPx()
        drawRoundRect(color, Offset(sw, sw * 1.5f), Size(size.width - sw * 2, size.height - sw * 3), CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawCircle(color, 2.2.dp.toPx(), Offset(size.width * 0.34f, size.height * 0.4f))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.8f)
            lineTo(size.width * 0.42f, size.height * 0.55f)
            lineTo(size.width * 0.6f, size.height * 0.7f)
            lineTo(size.width * 0.74f, size.height * 0.5f)
            lineTo(size.width * 0.88f, size.height * 0.8f)
        }
        drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Your partner's hearts and yours, tucked under the corner of a bubble. */
@Composable
private fun ReactionPill(reactions: Map<String, String>, mine: Boolean, onClick: () -> Unit) {
    val counts = reactions.values.groupingBy { it }.eachCount()
    Row(
        Modifier
            .padding(start = if (mine) 0.dp else 10.dp, end = if (mine) 10.dp else 0.dp)
            .offset(y = (-7).dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .border(2.dp, LockInColors.Ink, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        counts.keys.take(3).forEach { Text(it, fontSize = 13.sp) }
        if (reactions.size > 1) Text("${reactions.size}", style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 11.sp), modifier = Modifier.padding(start = 3.dp))
    }
}

/** One line of the chat: a bubble, or a note about something that happened. */
@Composable
private fun MessageRow(
    item: MsgItem,
    maxBubble: Dp,
    me: String?,
    partner: String,
    timeShown: Boolean,
    quote: Pair<String, String>?,
    onReply: () -> Unit,
    onQuote: () -> Unit,
    onMenu: () -> Unit,
    onHeart: () -> Unit,
    onToggleTime: () -> Unit,
    onRetry: () -> Unit,
    onOpenPhoto: (File) -> Unit,
    voiceFetch: suspend (String) -> Result<File>,
    photoFetch: suspend (String) -> Result<File>,
    onAnswerUnlock: (Boolean) -> Unit,
    onAnswerProtect: (Boolean) -> Unit = {},
    onOpenGame: (String) -> Unit,
    onOpenQuiz: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Its delete animation is playing. */
    dying: Boolean = false,
    onDied: () -> Unit = {},
    /** The newest message you sent: it says Sent, then Seen. */
    latestMine: Boolean = false,
) {
    val m = item.m
    // Messages that arrive while the chat is open slide up into place.
    var shown by remember { mutableStateOf(!m.fresh) }
    LaunchedEffect(Unit) { shown = true }
    val dy by animateDpAsState(if (shown) 0.dp else 18.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "message")

    if (m.who == Who.System) {
        Column(modifier.fillMaxWidth().padding(vertical = 6.dp).offset(y = dy), horizontalAlignment = Alignment.CenterHorizontally) {
            if (item.active && m.code == "asked_unlock") {
                ActionCard(
                    title = m.text,
                    detail = "Let them out now, or say not yet.",
                    primary = "Unlock" to { onAnswerUnlock(true) },
                    secondary = "Not yet" to { onAnswerUnlock(false) },
                )
            } else if (item.active && m.code == "protect_asked") {
                ActionCard(
                    title = m.text,
                    detail = "Allow gives them ten minutes for this one thing.",
                    primary = "Allow" to { onAnswerProtect(true) },
                    secondary = "Not yet" to { onAnswerProtect(false) },
                )
            } else if (item.active && m.code == "game_invite") {
                ActionCard(title = m.text, detail = null, primary = "Play now" to { onOpenGame(m.detail) }, secondary = null)
            } else if (item.active && m.code == "quiz_challenge") {
                ActionCard(title = m.text, detail = "Multiple choice. Your score goes back to them.", primary = "Play" to { onOpenQuiz(m.detail) }, secondary = null)
            } else {
                Row(
                    Modifier.clip(CircleShape).background(LockInColors.Slate).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(noteGlyph(m.code), LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f)
                    Text("${m.text}, ${m.time}", style = LockInText.Small.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        return
    }

    val mine = m.who == Who.Me
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val rowScope = rememberCoroutineScope()

    // Delete: the bubble breaks apart, then becomes a "Message deleted" note.
    val death = remember { Animatable(0f) }
    LaunchedEffect(dying) {
        if (dying) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            death.snapTo(0f)
            death.animateTo(1f, tween(950, easing = LinearEasing))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onDied()
        } else {
            death.snapTo(0f)
        }
    }
    // The note that replaces it (or that appears when your partner deletes one) pops in.
    val pop = remember { Animatable(1f) }
    var wasDeleted by remember { mutableStateOf(m.deleted) }
    LaunchedEffect(m.deleted) {
        if (m.deleted && !wasDeleted) {
            pop.snapTo(0.78f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        }
        wasDeleted = m.deleted
    }
    // Double tap: a heart swells over the bubble and drops away.
    val burst = remember { Animatable(0f) }
    val heart: () -> Unit = {
        if (!m.deleted && m.id > 0) rowScope.launch {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(800, easing = LinearEasing))
        }
        onHeart()
    }
    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (item.first) big else small, bottomEnd = if (item.last) 4.dp else small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (item.first) big else small, topEnd = big, bottomEnd = big, bottomStart = if (item.last) 4.dp else small)
    }
    val hasPhoto = m.media != null && !m.deleted
    val caption = if (hasPhoto && m.text == "Photo") "" else m.text
    val emojiOnly = !m.deleted && !hasPhoto && m.voice == null && quote == null && isEmojiOnly(m.text)
    val fillBrush = Brush.verticalGradient(listOf(LockInColors.Lime, lerp(LockInColors.Lime, Color.Black, 0.1f)))
    val textColor = if (mine) LockInColors.OnLime else LockInColors.Snow

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))
            .padding(top = if (item.first) 9.dp else 2.dp)
            .offset(y = dy)
            .swipeToReply(enabled = m.id > 0 && !m.deleted && !dying, onReply = onReply, onLongPress = {}),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        val bubbleModifier = Modifier
            .widthIn(max = maxBubble)
            .clip(shape)
            .then(
                when {
                    emojiOnly -> Modifier
                    m.deleted -> Modifier.border(1.dp, LockInColors.Graphite, shape)
                    mine -> Modifier.background(fillBrush)
                    else -> Modifier.background(LockInColors.Slate).border(1.dp, LockInColors.Graphite, shape)
                },
            )
            // Tap shows the time, double tap hearts it, hold opens the menu. Content with its own taps (a photo, the play
            // button, a link) answers those first.
            .pointerInput(m.key, dying) {
                if (!dying) detectTapGestures(onTap = { if (m.delivery == Delivery.Failed) onRetry() else onToggleTime() }, onDoubleTap = { heart() }, onLongPress = { onMenu() })
            }
        // The bubble and its reactions are one piece for the delete animation, and the heart swells over both.
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 0.5f)
                }
                .disintegrate { death.value },
        ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(bubbleModifier) {
            Column(Modifier.padding(if (emojiOnly) 0.dp else if (hasPhoto) 3.dp else 0.dp)) {
                val padH = if (hasPhoto) 9.dp else 14.dp
                val padV = if (hasPhoto) 5.dp else 10.dp
                val pad = Modifier.padding(horizontal = padH, vertical = padV)
                if (m.deleted) {
                    Text("Message deleted", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 14.sp, fontStyle = FontStyle.Italic), modifier = pad)
                } else {
                    quote?.let { (who, what) -> Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp)) { ReplyQuote(who, what, mine, onQuote) } }
                    if (hasPhoto) {
                        PhotoBubble(
                            m = m, fetch = photoFetch, maxWidth = maxBubble - 6.dp,
                            onOpen = onOpenPhoto, onHeart = heart, onMenu = onMenu, onRetry = onRetry,
                        )
                    }
                    if (m.voice != null) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { VoiceBubble(m.voice, m.voiceMs, m.wave, mine, voiceFetch) }
                    } else if (emojiOnly) {
                        Text(m.text.trim(), fontSize = 40.sp, lineHeight = 46.sp)
                    } else if (caption.isNotBlank()) {
                        BubbleText(caption, textColor, mine, padH, padV, onLink = { openLink(context, it) })
                    }
                }
            }
        }
        if (m.reactions.isNotEmpty() && !m.deleted) ReactionPill(m.reactions, mine, onMenu)
        }
        if (burst.value > 0f && burst.value < 1f) {
            Text(
                "\u2764\uFE0F",
                fontSize = 46.sp,
                modifier = Modifier.align(Alignment.Center).graphicsLayer {
                    val t = burst.value
                    val sc = when {
                        t < 0.28f -> 0.3f + 1.15f * (t / 0.28f)
                        t < 0.5f -> 1.45f - 0.3f * ((t - 0.28f) / 0.22f)
                        else -> 1.15f * (1f - (t - 0.5f) / 0.5f)
                    }
                    scaleX = sc
                    scaleY = sc
                    translationY = -t * 40.dp.toPx()
                },
            )
        }
        }

        // Time under the last of a run, or when tapped; and how it went if it did not go through.
        val status = when (m.delivery) {
            Delivery.Sending -> "Sending…"
            Delivery.Failed -> "Not sent. Tap to try again"
            Delivery.Sent -> null
        }
        if (item.last || timeShown || status != null) {
            Row(Modifier.padding(top = if (m.reactions.isNotEmpty() && !m.deleted) 0.dp else 3.dp, start = 4.dp, end = 4.dp)) {
                if (status != null) {
                    Text(
                        status,
                        style = if (m.delivery == Delivery.Failed) LockInText.Error.copy(fontSize = 12.sp) else LockInText.Small.copy(color = LockInColors.Mist),
                        modifier = if (m.delivery == Delivery.Failed) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier,
                    )
                } else {
                    Text(
                        m.time + if (m.edited && !m.deleted) " · edited" else "",
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                    )
                    // The newest message you sent: Sent, then Seen once your partner has read it.
                    if (mine && latestMine && !m.deleted) {
                        AnimatedContent(
                            targetState = m.seen,
                            transitionSpec = { pushSwap(vertical = true, ms = 240) },
                            modifier = Modifier.clipToBounds(),
                            label = "seen",
                        ) { seenNow ->
                            Text(
                                if (seenNow) " · Seen" else " · Sent",
                                style = LockInText.Small.copy(color = if (seenNow) LockInColors.Lime else LockInColors.Mist),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A message's words, with links you can tap. */
@Composable
private fun BubbleText(text: String, color: Color, mine: Boolean, padH: Dp, padV: Dp, onLink: (String) -> Unit) {
    val link = if (mine) LockInColors.OnLime else LockInColors.Lime
    val annotated = remember(text, color, link) {
        buildAnnotatedString {
            var last = 0
            for (match in LinkRegex.findAll(text)) {
                append(text.substring(last, match.range.first))
                pushStringAnnotation("URL", match.value)
                withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(match.value) }
                pop()
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val hasLinks = annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()
    val tapForLinks = if (!hasLinks) Modifier else Modifier.pointerInput(annotated) {
        // Taps on a link open it; anything else is left to the bubble (time, heart, menu).
        detectTapGestures(onTap = { at ->
            val shift = with(density) { Offset(padH.toPx(), padV.toPx()) }
            val pos = layout?.getOffsetForPosition(at - shift)
            val hit = pos?.let { annotated.getStringAnnotations("URL", it, it).firstOrNull() }
            if (hit != null) onLink(hit.item)
        })
    }
    Text(
        annotated,
        style = LockInText.Sub.copy(color = color, fontSize = 16.sp, lineHeight = 22.sp),
        onTextLayout = { layout = it },
        modifier = tapForLinks.padding(horizontal = padH, vertical = padV),
    )
}

/** "Alex asked to be unlocked", with the answers right here. */
@Composable
private fun ActionCard(title: String, detail: String?, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(shape).border(1.5.dp, LockInColors.Lime, shape).background(LockInColors.Slate).padding(14.dp),
    ) {
        Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
        if (detail != null) Text(detail, style = LockInText.Small.copy(color = LockInColors.Body), modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(ControlShape).background(LockInColors.Lime).clickable(role = Role.Button, onClick = primary.second),
                contentAlignment = Alignment.Center,
            ) { Text(primary.first, style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 15.sp)) }
            if (secondary != null) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(ControlShape).border(1.5.dp, LockInColors.Rim, ControlShape).clickable(role = Role.Button, onClick = secondary.second),
                    contentAlignment = Alignment.Center,
                ) { Text(secondary.first, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp)) }
            }
        }
    }
}

/** Three dots that hop in turn while your partner is typing. */
@Composable
private fun TypingBubble() {
    val phase by rememberInfiniteTransition(label = "typing").calmFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "typing-phase",
    )
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, shape)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (i in 0..2) {
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            val wave = max(0f, sin((phase - i * 0.16f) * 2f * Math.PI.toFloat()))
                            translationY = -wave * 5.dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(LockInColors.Silver),
                )
            }
        }
    }
}


/** A pill that slides up from the bottom and back down. Its own function, so the plain overload is used. */
@Composable
private fun SlideBubble(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { it * 2 },
        exit = slideOutVertically(spring(stiffness = 600f)) { it * 2 },
    ) {
        content()
    }
}
