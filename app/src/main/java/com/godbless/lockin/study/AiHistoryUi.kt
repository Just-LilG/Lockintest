package com.godbless.lockin.study

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.home.rememberSlideIn
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or the date. */
internal fun agoLabel(at: Long): String {
    val min = ((System.currentTimeMillis() - at) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        min < 48 * 60 -> "yesterday"
        min < 14 * 24 * 60 -> "${min / (24 * 60)} days ago"
        else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" }
    }
}

private fun dayHeading(at: Long): String {
    val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }
}

private fun pagesWord(pages: List<Int>): String = when {
    pages.isEmpty() -> ""
    pages.size == 1 -> "Page ${pages[0]}"
    pages.size <= 4 -> "Pages " + pages.joinToString(", ")
    else -> "${pages.size} pages"
}

private fun kindWord(k: AiKind) = when (k) {
    AiKind.Explain -> "Explained"
    AiKind.Cards -> "Flashcards"
    AiKind.Quiz -> "Quiz"
    AiKind.Passage -> "Selections"
}

/** Full-screen: everything the AI has made for you, across your books. */
@Composable
internal fun AiHistoryScreen(store: StudyStore, onBack: () -> Unit, onOpenPage: (bookId: String, page: Int) -> Unit) {
    StudyScaffold(title = "AI history", subtitle = "Saved on this phone, so you can come back to it.", onBack = onBack) {
        AiHistoryList(store, bookId = null, currentPage = null, onOpenPage = onOpenPage)
    }
}

/**
 * The saved explanations, flashcard sets and quizzes, newest first and grouped by day. [bookId] limits it to one
 * book (the reader's sheet); [currentPage] marks the entries about the page you are on. Tap one to open it.
 */
@Composable
internal fun AiHistoryList(
    store: StudyStore,
    bookId: String?,
    currentPage: Int?,
    onOpenPage: ((bookId: String, page: Int) -> Unit)?,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf("") }
    val all = store.ai.entries.filter { bookId == null || it.book == bookId }
    val shown = all.filter { filter.isEmpty() || it.kind.name == filter }

    if (all.isEmpty()) {
        Text(
            "Nothing here yet. Explain a page, make flashcards or take an AI quiz and it is saved here.",
            style = LockInText.Sub.copy(color = LockInColors.Silver),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }

    // Filter chips: the picked one fills with lime, springing between colours.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "All", AiKind.Explain.name to "Explained", AiKind.Passage.name to "Selections", AiKind.Cards.name to "Flashcards", AiKind.Quiz.name to "Quizzes").forEach { (key, label) ->
            val on = filter == key
            val bg by animateColorAsState(if (on) LockInColors.Lime else LockInColors.Graphite, spring(stiffness = 500f), label = "chip")
            Box(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.Tab) { filter = key }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp))
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    if (shown.isEmpty()) {
        Text("None of those yet.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(horizontal = 4.dp))
        return
    }

    Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.85f, stiffness = 400f))) {
        var lastDay = ""
        shown.forEachIndexed { i, e ->
            val day = dayHeading(e.at)
            if (day != lastDay) {
                lastDay = day
                Text(
                    day,
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    modifier = Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 12.dp, bottom = 8.dp),
                )
            }
            EntryCard(
                store = store,
                e = e,
                index = i,
                expanded = open == e.id,
                here = currentPage != null && e.book == bookId && e.pages.contains(currentPage),
                showBook = bookId == null,
                onToggle = { open = if (open == e.id) "" else e.id },
                onOpenPage = onOpenPage,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun EntryCard(
    store: StudyStore,
    e: AiEntry,
    index: Int,
    expanded: Boolean,
    here: Boolean,
    showBook: Boolean,
    onToggle: () -> Unit,
    onOpenPage: ((String, Int) -> Unit)?,
) {
    // Each card slides in from the right, one after another, with a spring.
    val slide = rememberSlideIn(true, index.coerceAtMost(6))
    val shape = RoundedCornerShape(22.dp)
    val turn by androidx.compose.animation.core.animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "turn")
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = slide.value * size.width * 0.3f }
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, if (here) LockInColors.Lime else LockInColors.Graphite, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(LockInColors.LimeWash), contentAlignment = Alignment.Center) {
                GlyphIcon(
                    when (e.kind) { AiKind.Explain -> Glyph.Book; AiKind.Cards -> Glyph.Cards; AiKind.Quiz -> Glyph.Target; AiKind.Passage -> Glyph.Select },
                    LockInColors.Lime, boxSize = 20.dp,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    pagesWord(e.pages) + if (showBook) " · " + prettyTitle(e.bookTitle) else "",
                    style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val detail = when (e.kind) {
                    AiKind.Explain -> if (e.simpler) "Explained simply" else "Explained"
                    AiKind.Cards -> "${e.cards().size} flashcards" + if (e.cardsAdded > 0) " · ${e.cardsAdded} added" else ""
                    AiKind.Quiz -> e.quizScore?.let { "Quiz · ${it.first} of ${it.second} right" } ?: "Quiz · ${e.questions().size} questions"
                    AiKind.Passage -> e.data.optString("text").let { t -> if (t.isNotBlank()) "\u201C" + t.take(40) + (if (t.length > 40) "…\u201D" else "\u201D") else "A selection" }
                }
                Text(
                    detail + " · " + agoLabel(e.at) + if (here) " · this page" else "",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            GlyphIcon(Glyph.Chevron, LockInColors.Mist, modifier = Modifier.graphicsLayer { rotationZ = turn }, boxSize = 16.dp, strokeWidth = 2f)
        }
        Collapse(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                when (e.kind) {
                    AiKind.Explain -> ExplainBody(e.explanation())
                    AiKind.Cards -> CardsBody(store, e)
                    AiKind.Quiz -> QuizBody(e.questions())
                    AiKind.Passage -> PassageBody(store, e)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onOpenPage != null) {
                        Box(Modifier.weight(1f)) {
                            LockInButton("Open page ${e.page}", onClick = { onOpenPage(e.book, e.page - 1) }, kind = ButtonKind.Outline)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        LockInButton("Delete", onClick = { store.ai.remove(e.id) }, kind = ButtonKind.Outline)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExplainBody(r: com.godbless.lockin.data.AiExplanation) {
    MathText(r.summary, style = LockInText.Sub.copy(color = LockInColors.Snow, fontSize = 16.sp, lineHeight = 23.sp))
    Spacer(Modifier.height(10.dp))
    r.points.forEachIndexed { i, p ->
        Row(Modifier.padding(bottom = 6.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(LockInColors.LimeWash), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 11.sp))
            }
            Text(p, style = LockInText.Sub.copy(color = LockInColors.Body), modifier = Modifier.padding(start = 10.dp))
        }
    }
    if (r.example.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LockInColors.Graphite).padding(12.dp)) {
            Text("For example", style = LockInText.Small.copy(color = LockInColors.Lime))
            MathText(r.example, style = LockInText.Sub.copy(color = LockInColors.Snow))
        }
    }
    if (r.check.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        MathText("Check yourself: ${r.check}", style = LockInText.Small.copy(color = LockInColors.Mist))
    }
}

@Composable
private fun CardsBody(store: StudyStore, e: AiEntry) {
    val cards = e.cards()
    cards.forEach { c ->
        Column(Modifier.padding(bottom = 8.dp)) {
            MathText(c.question, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
            MathText(c.answer, style = LockInText.Small.copy(color = LockInColors.Body))
        }
    }
    // A set you never added (or only some of) can still be added later.
    if (e.cardsAdded == 0 && cards.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        LockInButton("Add all ${cards.size} flashcards", onClick = {
            cards.forEach { store.addCard(e.book, it.question, it.answer, it.page) }
            store.ai.markCardsAdded(e.id, cards.size)
        })
    }
}

@Composable
private fun QuizBody(qs: List<com.godbless.lockin.data.AiQuestion>) {
    qs.forEachIndexed { i, q ->
        Column(Modifier.padding(bottom = 10.dp)) {
            MathText("${i + 1}. ${q.question}", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
            MathText(q.options.getOrNull(q.answer).orEmpty(), style = LockInText.Small.copy(color = LockInColors.Lime))
            if (q.why.isNotBlank()) MathText(q.why, style = LockInText.Small.copy(color = LockInColors.Body))
        }
    }
}

/** A saved selection: its picture, what it says, the explanation and any follow-ups. */
@Composable
private fun PassageBody(store: StudyStore, e: AiEntry) {
    val saved = remember(e.id, e.data.toString()) { e.passage() }
    val picture = remember(saved.snip) {
        if (saved.snip.isBlank()) null
        else runCatching { android.graphics.BitmapFactory.decodeFile(store.ai.snipFile(saved.snip).path)?.asImageBitmap() }.getOrNull()
    }
    if (picture != null) {
        androidx.compose.foundation.Image(
            picture, contentDescription = "What you selected",
            modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        Spacer(Modifier.height(10.dp))
    }
    PassageResult(saved.first)
    saved.turns.forEach { t ->
        Spacer(Modifier.height(12.dp))
        PassageTurnView(t.ask, t.answer)
    }
}
