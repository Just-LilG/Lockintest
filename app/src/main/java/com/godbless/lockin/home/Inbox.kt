package com.godbless.lockin.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.CrewLine
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================================
// What counts as news: things your partner and your crew did, newest first
// =============================================================================================

internal enum class InboxKind { Message, Lock, Plan, Limit, Nudge, Game, Crew, Other }

/**
 * One line of news. [id] is the server's id in its own sequence (partner chat or crew chat, told apart by
 * [crew]); it is what "unread" is measured against.
 */
internal class InboxItem(
    val key: String,
    val id: Long,
    val crew: Boolean,
    val kind: InboxKind,
    val title: String,
    /** A second line: what the message said. */
    val detail: String,
    /** Milliseconds. */
    val at: Long,
)

/** 0:07 */
private fun clipTime(ms: Int): String {
    val s = (ms / 1000f).let { Math.round(it) }.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun kindOfCode(code: String): InboxKind = when {
    code == "nudge" -> InboxKind.Nudge
    code == "game_invite" || code == "quiz_challenge" || code == "quiz_score" -> InboxKind.Game
    code.startsWith("plan_") -> InboxKind.Plan
    code.startsWith("limit_") || code.startsWith("allow_") -> InboxKind.Limit
    code.startsWith("lock_") || code.startsWith("protect_") || code in setOf("asked_unlock", "unlock_denied", "emergency_release") -> InboxKind.Lock
    else -> InboxKind.Other
}

/**
 * Builds the activity list from the two chats already on the phone: what your partner did or said, and what the
 * rest of your crew did or said (never your own). A run of messages from one person becomes one line.
 */
internal fun buildInbox(chat: List<Message>, crew: List<CrewLine>, partnerName: String, me: String?): List<InboxItem> {
    val out = ArrayList<InboxItem>()

    // Your partner.
    var group = ArrayList<Message>()
    fun flush() {
        if (group.isEmpty()) return
        val last = group.last()
        val n = group.size
        // A run of one kind says how many; a mixed run lists what it holds; a run of texts shows the last one.
        val voices = group.count { it.voice != null }
        val photos = group.count { it.media != null }
        val texts = n - voices - photos
        val preview = when {
            n == 1 && last.voice != null -> "Voice message \u00b7 ${clipTime(last.voiceMs)}"
            n == 1 && last.media != null -> if (last.text.isNotBlank() && last.text != "Photo") "Photo \u00b7 ${last.text}" else "Photo"
            voices == n -> "$n voice messages \u00b7 ${clipTime(group.sumOf { it.voiceMs })} in all"
            photos == n -> "$n photos"
            texts == n -> last.text
            else -> listOfNotNull(
                texts.takeIf { it > 0 }?.let { "$it text" },
                voices.takeIf { it > 0 }?.let { "$it voice" },
                photos.takeIf { it > 0 }?.let { "$it photo" },
            ).joinToString(" \u00b7 ")
        }
        out.add(
            InboxItem(
                key = "p${last.id}", id = last.id, crew = false, kind = InboxKind.Message,
                title = if (n == 1) "$partnerName sent a message" else "$partnerName sent $n messages",
                detail = preview, at = last.at,
            ),
        )
        group = ArrayList()
    }
    for (m in chat) {
        when {
            m.who == Who.Partner -> if (!m.deleted) group.add(m)
            m.who == Who.System && m.theirs && m.noteId > 0L -> {
                flush()
                out.add(InboxItem("p${m.noteId}", m.noteId, false, kindOfCode(m.code), m.text, "", m.at))
            }
            else -> flush()
        }
    }
    flush()

    // Your crew.
    var cRun = ArrayList<CrewLine>()
    fun flushCrew() {
        if (cRun.isEmpty()) return
        val last = cRun.last()
        val n = cRun.size
        val who = last.name.substringBefore(' ').ifBlank { "Someone" }
        out.add(
            InboxItem(
                key = "c${last.id}", id = last.id, crew = true, kind = InboxKind.Crew,
                title = if (n == 1) "$who wrote in the crew chat" else "$who wrote $n lines in the crew chat",
                detail = when {
                    n > 1 -> "Latest: " + (if (last.voice != null) "voice message" else last.body)
                    last.voice != null -> "Voice message \u00b7 ${clipTime(last.voiceMs)}"
                    else -> last.body
                },
                at = last.at * 1000L,
            ),
        )
        cRun = ArrayList()
    }
    var runSender: String? = null
    for (l in crew) {
        if (l.mine) { flushCrew(); runSender = null; continue }
        if (l.kind == "event") {
            flushCrew(); runSender = null
            out.add(InboxItem("c${l.id}", l.id, true, InboxKind.Crew, eventWords(l, me), "", l.at * 1000L))
        } else {
            if (runSender != null && runSender != l.sender) flushCrew()
            runSender = l.sender
            cRun.add(l)
        }
    }
    flushCrew()

    return out.sortedByDescending { it.at }.take(40)
}

/** What you have already seen, kept on the phone: the newest partner note and crew line you have looked at. */
internal class InboxState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("inbox_seen", Context.MODE_PRIVATE)

    // -1 means never opened: the first time there is something to measure against, everything old counts as seen.
    var seenPartner by mutableLongStateOf(prefs.getLong("p", -1L))
        private set
    var seenCrew by mutableLongStateOf(prefs.getLong("c", -1L))
        private set

    /** What was unread when the sheet last opened, so those lines stay marked while you read them. */
    var shownPartner by mutableLongStateOf(0L)
        private set
    var shownCrew by mutableLongStateOf(0L)
        private set

    fun seed(partnerMax: Long?, crewMax: Long?) {
        if (partnerMax != null && seenPartner < 0L) { seenPartner = partnerMax; prefs.edit().putLong("p", partnerMax).apply() }
        if (crewMax != null && seenCrew < 0L) { seenCrew = crewMax; prefs.edit().putLong("c", crewMax).apply() }
    }

    fun unread(item: InboxItem): Boolean = item.id > (if (item.crew) seenCrew else seenPartner) && item.key !in dismissed

    /** Lines you swiped away (or cleared). Kept on the phone; the newest 300 are remembered. */
    var dismissed by mutableStateOf<Set<String>>(prefs.getStringSet("gone", emptySet())?.toSet() ?: emptySet())
        private set

    fun dismiss(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val next = (dismissed + keys).toList().takeLast(300).toSet()
        dismissed = next
        prefs.edit().putStringSet("gone", next).apply()
    }

    /**
     * Something you looked at somewhere else counts as read: the partner chat while it is open, the crew chat while
     * it is open, and anything that arrives while this list is on screen. Does not change which lines stay marked.
     */
    fun markSeen(items: List<InboxItem>, partner: Boolean, crew: Boolean) {
        var changed = false
        if (partner) {
            val p = items.filter { !it.crew }.maxOfOrNull { it.id } ?: seenPartner
            if (p > seenPartner) { seenPartner = p; changed = true }
        }
        if (crew) {
            val c = items.filter { it.crew }.maxOfOrNull { it.id } ?: seenCrew
            if (c > seenCrew) { seenCrew = c; changed = true }
        }
        if (changed) prefs.edit().putLong("p", seenPartner).putLong("c", seenCrew).apply()
    }

    /** Opening the list reads everything, but the lines that were new stay marked until it closes. */
    fun open(items: List<InboxItem>) {
        shownPartner = seenPartner.coerceAtLeast(0L)
        shownCrew = seenCrew.coerceAtLeast(0L)
        val p = items.filter { !it.crew }.maxOfOrNull { it.id } ?: seenPartner
        val c = items.filter { it.crew }.maxOfOrNull { it.id } ?: seenCrew
        seenPartner = maxOf(seenPartner, p)
        seenCrew = maxOf(seenCrew, c)
        prefs.edit().putLong("p", seenPartner).putLong("c", seenCrew).apply()
    }

    fun wasNew(item: InboxItem): Boolean = item.id > (if (item.crew) shownCrew else shownPartner)
}

/** What the list can be narrowed to. */
internal enum class InboxFilter(val label: String) {
    All("All"), Messages("Messages"), Locks("Locks and plans"), Crew("Crew");

    fun accepts(i: InboxItem): Boolean = when (this) {
        All -> true
        Messages -> !i.crew && (i.kind == InboxKind.Message || i.kind == InboxKind.Nudge || i.kind == InboxKind.Game || i.kind == InboxKind.Other)
        Locks -> !i.crew && (i.kind == InboxKind.Lock || i.kind == InboxKind.Plan || i.kind == InboxKind.Limit)
        Crew -> i.crew
    }
}

private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val ClockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun dayLabel(at: Long): String {
    val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DayFormat)
    }
}

private fun ago(at: Long): String {
    val s = ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m ago"
        else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(ClockFormat).lowercase(Locale.US)
    }
}

// =============================================================================================
// The bell
// =============================================================================================

/**
 * The bell at the top right of Home. It swings when something new arrives, and a lime badge counts what you
 * haven't seen; the badge pops in and bounces whenever the count goes up.
 */
@Composable
internal fun NotificationBell(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val swing = remember { Animatable(0f) }
    val pop = remember { Animatable(if (unread > 0) 1f else 0f) }
    val bounce = remember { Animatable(1f) }
    var last by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(unread) {
        val rose = unread > last
        last = unread
        if (unread > 0) {
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
            if (rose && !com.godbless.lockin.ui.MotionState.reduced) {
                // Swing like a clapper, settling.
                launch { bounce.animateTo(1.3f, tween(110)); bounce.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 420f)) }
                for (a in listOf(16f, -13f, 9f, -5f)) swing.animateTo(a, tween(95, easing = UiGlide))
                swing.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 300f))
            }
        } else {
            pop.animateTo(0f, tween(180, easing = UiGlide))
        }
    }

    Box(
        modifier
            .size(48.dp)
            .springClick(onClick = onClick)
            .semantics { contentDescription = if (unread > 0) "Activity, $unread new" else "Activity" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (unread > 0) LockInColors.LimeWash else LockInColors.Slate)
                .border(1.dp, if (unread > 0) LockInColors.Lime else LockInColors.Graphite, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.graphicsLayer {
                    // Pivot at the top of the bell so it swings from its hook.
                    transformOrigin = TransformOrigin(0.5f, 0.12f)
                    rotationZ = swing.value
                },
            ) {
                GlyphIcon(Glyph.Bell, if (unread > 0) LockInColors.Lime else LockInColors.Silver, boxSize = 22.dp, strokeWidth = 2f)
            }
        }
        if (pop.value > 0.01f) {
            val text = if (unread > 9) "9+" else unread.toString()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .graphicsLayer { scaleX = pop.value * bounce.value; scaleY = pop.value * bounce.value }
                    .heightIn(min = 20.dp)
                    .widthIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(LockInColors.Lime)
                    .border(2.dp, LockInColors.Ink, CircleShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 11.sp, lineHeight = 13.sp))
            }
        }
    }
}

// =============================================================================================
// The sheet
// =============================================================================================

/**
 * What happened while you were away. Slides up over Home with the page dimmed behind it; a tap on the dim, the
 * back button, or pulling the handle down puts it away. Rows slide up one after another, and the new ones carry a
 * lime dot. A tap on a line takes you to where it happened.
 */
@Composable
internal fun InboxSheet(
    visible: Boolean,
    items: List<InboxItem>,
    state: InboxState,
    partnerName: String,
    onClose: () -> Unit,
    onOpen: (InboxItem) -> Unit,
    onSettings: () -> Unit,
) {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(visible) { if (!visible) { delay(360); drag.snapTo(0f) } }

    // Rows slide up only while the sheet is opening. A row that scrolls into view later must not replay it: it
    // used to sit 36dp low for half a second and slide over the day label under it.
    var intro by remember(visible) { mutableStateOf(visible) }
    LaunchedEffect(visible) { if (visible) { delay(1100); intro = false } }
    // Lines that arrive while the list is open are read as they appear, so the bell is clear when it closes.
    LaunchedEffect(visible, items) { if (visible) state.markSeen(items, partner = true, crew = true) }
    // The "N new" badge has done its job once the new lines have been on screen a moment (they stay marked).
    var badge by remember(visible) { mutableStateOf(visible) }
    LaunchedEffect(visible) { if (visible) { delay(3500); badge = false } }
    var filter by remember(visible) { mutableStateOf(InboxFilter.All) }
    val shownItems = remember(items, state.dismissed, filter) {
        items.filter { it.key !in state.dismissed && filter.accepts(it) }
    }
    val live = remember(items, state.dismissed) { items.filter { it.key !in state.dismissed } }

    val dim by animateFloatAsState(if (visible) 1f else 0f, tween(260, easing = UiGlide), label = "inbox-dim")
    if (dim > 0.01f) {
        val pulled = (drag.value / 900f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f * dim * (1f - pulled)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }

    // On a tablet the activity is a panel docked to the right, the full height of the screen and sliding in from the
    // edge (like the chat); on a phone it is a sheet rising from the bottom.
    val wide = com.godbless.lockin.ui.isWide()
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val panelWidth = (config.screenWidthDp * 0.42f).dp.coerceIn(420.dp, 540.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = if (wide) Alignment.CenterEnd else Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = if (wide) slideInHorizontally(tween(340, easing = UiGlide)) { it } else slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = if (wide) slideOutHorizontally(tween(280, easing = UiGlide)) { it } else slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            val shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .offset { IntOffset(0, drag.value.toInt().coerceAtLeast(0)) }
                    .then(
                        if (wide) {
                            Modifier
                                .statusBarsPadding()
                                .padding(top = 12.dp, bottom = 12.dp, end = 12.dp)
                                .width(panelWidth)
                                .fillMaxHeight()
                        } else {
                            Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                // Taller than before, so more rows fit and less of Home is left showing above.
                                .heightIn(max = (config.screenHeightDp * 0.84f).dp.coerceIn(480.dp, 780.dp))
                        },
                    )
                    .clip(shape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, shape)
                    // Swallow taps on the sheet so they don't reach the dim behind it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .navigationBarsPadding(),
            ) {
                // The handle and header: pull down to put the sheet away.
                Column(
                    Modifier
                        .then(
                            // A side panel is put away with its close button or a tap beside it; only the sheet is pulled down.
                            if (wide) Modifier else Modifier.draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                                onDragStopped = { v ->
                                    if (drag.value > with(density) { 120.dp.toPx() } || v > 1400f) onClose()
                                    else drag.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                                },
                            ),
                        )
                        .padding(start = 20.dp, end = 12.dp, top = if (wide) 16.dp else 12.dp),
                ) {
                    if (!wide) Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(LockInColors.Rim),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activity", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
                        val fresh = live.count { state.wasNew(it) }
                        if (fresh > 0 && badge) {
                            Text(
                                "$fresh new",
                                style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 12.sp),
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .clip(CircleShape)
                                    .background(LockInColors.Lime)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (live.isNotEmpty()) {
                            Text(
                                "Clear all",
                                style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .springClick(onClick = { state.dismiss(live.map { it.key }) })
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .semantics { contentDescription = "Clear all activity" },
                            )
                        }
                        Box(
                            Modifier.size(44.dp).springClick(onClick = onSettings).semantics { contentDescription = "Notification settings" },
                            contentAlignment = Alignment.Center,
                        ) { GlyphIcon(Glyph.Gear, LockInColors.Silver, boxSize = 20.dp, strokeWidth = 2f) }
                        Box(
                            Modifier.size(44.dp).springClick(onClick = onClose).semantics { contentDescription = "Close" },
                            contentAlignment = Alignment.Center,
                        ) { GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 14.dp, strokeWidth = 2.2f) }
                    }
                }
                Spacer(Modifier.height(6.dp))

                if (live.isEmpty()) {
                    EmptyInbox(partnerName)
                } else {
                    // Filters, only when there is more than one kind of thing to filter.
                    val kinds = InboxFilter.entries.filter { it == InboxFilter.All || live.any { i -> it.accepts(i) } }
                    if (kinds.size > 2) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            kinds.forEach { f ->
                                val on = f == filter
                                Text(
                                    f.label,
                                    style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Body, fontSize = 13.sp),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (on) LockInColors.Lime else LockInColors.Graphite)
                                        .springClick(onClick = { filter = f })
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    // Day labels sit between the lines.
                    val rows = remember(shownItems) {
                        val list = ArrayList<Any>()
                        var day = ""
                        for (entry in shownItems) {
                            val d = dayLabel(entry.at)
                            if (d != day) { list.add(d); day = d }
                            list.add(entry)
                        }
                        list
                    }
                    if (rows.isEmpty()) {
                        Text(
                            "Nothing in ${filter.label.lowercase(Locale.US)} yet.",
                            style = LockInText.Sub.copy(color = LockInColors.Mist),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(rows, key = { _, r -> if (r is InboxItem) r.key else "day-$r" }) { index, r ->
                            // Rows close the gap when one is swiped away or the filter changes (no fades).
                            val place = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                            if (r is String) {
                                Text(
                                    r,
                                    style = LockInText.Small.copy(color = LockInColors.Mist),
                                    modifier = place.padding(start = 4.dp, top = if (index == 0) 2.dp else 10.dp),
                                )
                            } else if (r is InboxItem) {
                                InboxRow(
                                    r,
                                    isNew = state.wasNew(r),
                                    index = index,
                                    animateIn = intro && index < 9,
                                    modifier = place,
                                    onDismiss = { state.dismiss(listOf(r.key)) },
                                    onClick = { onOpen(r) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: InboxItem,
    isNew: Boolean,
    index: Int,
    animateIn: Boolean,
    modifier: Modifier,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    // While the sheet opens, each row slides up into place a beat after the one above it (no fades). Rows that
    // appear later, while scrolling, are simply there.
    val rise = remember { Animatable(if (animateIn) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!animateIn) return@LaunchedEffect
        delay((index.coerceAtMost(8) * 45 + 120).toLong())
        rise.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 320f))
    }
    // Swipe a line to the left to put it away.
    val swipe = remember { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1f) }
    val shape = RoundedCornerShape(20.dp)
    val (glyph, tint) = when (item.kind) {
        InboxKind.Message -> Glyph.Person to LockInColors.Snow
        InboxKind.Lock -> Glyph.Lock to LockInColors.Lime
        InboxKind.Plan -> Glyph.Calendar to LockInColors.Snow
        InboxKind.Limit -> Glyph.Timer to LockInColors.Snow
        InboxKind.Nudge -> Glyph.Bell to LockInColors.Lime
        InboxKind.Game -> Glyph.Target to LockInColors.Lime
        InboxKind.Crew -> Glyph.Crew to LockInColors.Snow
        InboxKind.Other -> Glyph.People to LockInColors.Silver
    }
    Row(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .graphicsLayer { translationY = rise.value * 36.dp.toPx(); translationX = swipe.value }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        swipeScope.launch {
                            if (swipe.value < -width * 0.35f) {
                                swipe.animateTo(-width, tween(180, easing = UiGlide))
                                onDismiss()
                            } else {
                                swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                            }
                        }
                    },
                    onDragCancel = { swipeScope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f)) } },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        swipeScope.launch { swipe.snapTo((swipe.value + dx).coerceAtMost(0f)) }
                    },
                )
            }
            .fillMaxWidth()
            .clip(shape)
            .background(if (isNew) LockInColors.LimeWash else LockInColors.Graphite)
            .then(if (isNew) Modifier.border(1.dp, LockInColors.Lime.copy(alpha = 0.55f), shape) else Modifier)
            .springClick(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(LockInColors.Slate),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, tint, boxSize = 18.dp, strokeWidth = 2f) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, lineHeight = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.detail.isNotBlank()) {
                // Bigger and lighter than before, and two lines, so a message can be read without opening it.
                Text(item.detail, style = LockInText.Small.copy(color = LockInColors.Body, fontSize = 13.sp, lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                (if (item.crew) "Crew · " else "") + ago(item.at),
                style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 12.sp),
            )
        }
        if (isNew) Box(Modifier.padding(start = 10.dp).size(9.dp).clip(CircleShape).background(LockInColors.Lime))
    }
}

@Composable
private fun EmptyInbox(partnerName: String) {
    // A quiet bell that sways now and then.
    val sway = remember { Animatable(0f) }
    LaunchedEffect(com.godbless.lockin.ui.MotionState.reduced) {
        if (com.godbless.lockin.ui.MotionState.reduced) return@LaunchedEffect
        while (true) {
            for (a in listOf(10f, -8f, 5f, -3f)) sway.animateTo(a, tween(260, easing = UiGlide))
            sway.animateTo(0f, tween(300, easing = UiGlide))
            delay(2600)
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(LockInColors.Graphite),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { transformOrigin = TransformOrigin(0.5f, 0.12f); rotationZ = sway.value }) {
                GlyphIcon(Glyph.Bell, LockInColors.Silver, boxSize = 32.dp, strokeWidth = 2f)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("All quiet", style = LockInText.Title.copy(fontSize = 20.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Locks, nudges, plans and messages from $partnerName and your crew show up here.",
            style = LockInText.Sub.copy(color = LockInColors.Mist),
            textAlign = TextAlign.Center,
        )
    }
}
