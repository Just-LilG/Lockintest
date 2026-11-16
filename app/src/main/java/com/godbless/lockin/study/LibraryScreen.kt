package com.godbless.lockin.study

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.home.TwoColumns
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.twoPane
import kotlinx.coroutines.launch

private enum class BookSort(val label: String) { Recent("Recent"), Title("A–Z"), Progress("Progress") }

/**
 * Your textbooks: the one you were reading at the top, then all of them as covers on a shelf (two across on a
 * phone, more on a tablet), with search and sorting once there are a few. Each cover's ⋯ (or a long press) opens
 * its actions: start over, rename, remove.
 *
 * Adding a book opens the system file picker, so it is switched off while a lock is on: a locked phone cannot
 * open other apps, and the picker is one.
 */
@Composable
internal fun LibraryScreen(
    store: StudyStore,
    canAdd: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    // While several books are being added: how far along (2 of 7).
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BookSort.Recent) }

    // Pick as many PDFs as you like at once (tap several, or "Select all" in the picker's menu).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        adding = true
        scope.launch {
            var failed = 0
            var lastError: String? = null
            uris.forEachIndexed { i, uri ->
                progress = (i + 1) to uris.size
                store.addBook(uri).onFailure { failed++; lastError = it.message }
            }
            adding = false
            progress = null
            val added = uris.size - failed
            val note = when {
                failed == 0 && added > 1 -> "Added $added books."
                failed == 0 -> null
                added == 0 -> lastError ?: "Couldn't add those files."
                else -> "Added $added. $failed couldn't be added${lastError?.let { ": $it" } ?: "."}"
            }
            note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    val add: () -> Unit = {
        if (canAdd && !adding) picker.launch(arrayOf("application/pdf"))
        else if (!canAdd) Toast.makeText(context, "Add books before you lock in. A locked phone can't open the file picker.", Toast.LENGTH_LONG).show()
    }

    // When each book was last read (for "Recent" and for the book to continue).
    val lastRead = remember(store.reads.size, store.books.size) {
        val m = HashMap<String, Long>()
        store.reads.forEach { r -> if ((m[r.book] ?: 0L) < r.at) m[r.book] = r.at }
        m
    }
    val current = store.books.filter { lastRead.containsKey(it.id) }.maxByOrNull { lastRead[it.id] ?: 0L }
    val shown = store.books
        .filter { query.isBlank() || prettyTitle(it.title).contains(query.trim(), ignoreCase = true) }
        .let { list ->
            when (sort) {
                BookSort.Recent -> list.sortedWith(compareByDescending<Textbook> { lastRead[it.id] ?: 0L }.thenByDescending { it.added })
                BookSort.Title -> list.sortedBy { prettyTitle(it.title).lowercase() }
                BookSort.Progress -> list.sortedByDescending { readFraction(it) }
            }
        }

    // What the AI has made for you (explanations, flashcard sets, quizzes) slides in over the library.
    var showHistory by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showHistory || actionsFor != null) {
        if (actionsFor != null) actionsFor = null else showHistory = false
    }
    Box(Modifier.fillInk()) {
        StudyScaffold(
            title = "Textbooks",
            subtitle = "Kept on this phone. Nothing is uploaded.",
            onBack = onBack,
            wide = true,
        ) {
            if (store.books.isEmpty()) {
                EmptyLibrary(canAdd = canAdd, adding = adding, progress = progress, onAdd = add)
                return@StudyScaffold
            }
            val wide = twoPane()
            TwoColumns(leftWeight = 0.72f, gap = 28.dp, left = {
                if (current != null) {
                    ContinueCard(store, current, onOpen = { onOpen(current.id) })
                    Spacer(Modifier.height(14.dp))
                }
                if (wide) {
                    LibraryStats(store)
                    Spacer(Modifier.height(14.dp))
                }
                if (store.ai.entries.isNotEmpty()) {
                    HistoryRow(store.ai.entries.size) { showHistory = true }
                    Spacer(Modifier.height(14.dp))
                }
                if (!canAdd) {
                    LockedNote()
                    Spacer(Modifier.height(14.dp))
                }
                Spacer(Modifier.height(if (wide) 0.dp else 10.dp))
            }, right = {
                // Title row: how many, and Add.
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your books", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
                    Text(
                        "  ${store.books.size}",
                        style = LockInText.Label.copy(color = LockInColors.Mist, fontSize = 16.sp),
                        modifier = Modifier.weight(1f),
                    )
                    AddPill(
                        text = progress?.let { "Adding ${it.first}/${it.second}" } ?: "Add PDFs",
                        enabled = canAdd && !adding,
                        onClick = add,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (store.books.size >= 4) {
                    SearchAndSort(query, { query = it }, sort, { sort = it })
                    Spacer(Modifier.height(14.dp))
                }
                if (shown.isEmpty()) {
                    Text(
                        "No book called “${query.trim()}”.",
                        style = LockInText.Sub.copy(color = LockInColors.Silver),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                    )
                } else {
                    BookGrid(
                        store = store,
                        books = shown,
                        showAddTile = canAdd && query.isBlank(),
                        onOpen = onOpen,
                        onActions = { actionsFor = it },
                        onAdd = add,
                    )
                }
            })
        }

        // A book's actions, rising from the bottom (a centred card on a tablet).
        val dim by animateFloatAsState(if (actionsFor != null) 1f else 0f, tween(240, easing = UiGlide), label = "dim")
        if (dim > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * dim))
                    .pointerInput(Unit) { detectTapGestures { actionsFor = null } },
            )
        }
        AnimatedVisibility(
            visible = actionsFor != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(300, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(240, easing = UiGlide)) { it },
        ) {
            var last by remember { mutableStateOf<String?>(null) }
            if (actionsFor != null) last = actionsFor
            val book = (actionsFor ?: last)?.let { store.book(it) }
            if (book != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    BookActions(
                        store = store,
                        book = book,
                        onOpen = { actionsFor = null; onOpen(book.id) },
                        onStartOver = {
                            actionsFor = null
                            store.setLastPage(book.id, 0)
                            onOpen(book.id)
                        },
                        onRemove = {
                            actionsFor = null
                            store.removeBook(book.id)
                        },
                        onClose = { actionsFor = null },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showHistory,
            modifier = Modifier.fillInk(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            AiHistoryScreen(store, onBack = { showHistory = false }, onOpenPage = { book, page ->
                showHistory = false
                store.setLastPage(book, page)
                onOpen(book)
            })
        }
    }
}

private fun Modifier.fillInk(): Modifier = this.fillMaxSize().background(LockInColors.Ink)

private fun readFraction(b: Textbook): Float = if (b.pages > 1) ((b.lastPage + 1f) / b.pages).coerceIn(0f, 1f) else 0f

@Composable
private fun rememberCover(store: StudyStore, id: String) = remember(id) {
    runCatching { BitmapFactory.decodeFile(store.thumbFile(id).path)?.asImageBitmap() }.getOrNull()
}

// ---------------------------------------------------------------------------------------------
// Top of the page
// ---------------------------------------------------------------------------------------------

/** The book you were last reading, large, with one tap back into it. */
@Composable
private fun ContinueCard(store: StudyStore, book: Textbook, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val cover = rememberCover(store, book.id)
    val frac = readFraction(book)
    val shownFrac by animateFloatAsState(frac, spring(dampingRatio = 0.85f, stiffness = 80f), label = "continue")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed) 0.98f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, Modifier.width(78.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("CONTINUE READING", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text(
                prettyTitle(book.title),
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            ProgressLine(shownFrac, 6.dp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Page ${book.lastPage + 1} of ${book.pages}",
                    style = LockInText.Small.copy(color = LockInColors.Silver),
                    modifier = Modifier.weight(1f),
                )
                Text("${(frac * 100).toInt()}%", style = LockInText.Small.copy(color = LockInColors.Snow, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Resume", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                GlyphIcon(Glyph.Chevron, LockInColors.OnLime, modifier = Modifier.padding(start = 6.dp), boxSize = 12.dp, strokeWidth = 2.4f)
            }
        }
    }
}

/** A tablet's summary: how far through the whole library you are. */
@Composable
private fun LibraryStats(store: StudyStore) {
    val shape = RoundedCornerShape(24.dp)
    val totalPages = store.books.sumOf { it.pages }
    val readPages = store.books.sumOf { (it.lastPage + 1).coerceIn(0, it.pages) }
    val fill by animateFloatAsState(
        if (totalPages > 0) readPages.toFloat() / totalPages else 0f,
        spring(dampingRatio = 0.85f, stiffness = 70f),
        label = "library-read",
    )
    Column(
        Modifier.fillMaxWidth().clip(shape).background(LockInColors.Slate).border(1.dp, LockInColors.Graphite, shape).padding(18.dp),
    ) {
        Text("YOUR LIBRARY", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, letterSpacing = 1.3.sp))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(fill * 100).toInt()}%", style = LockInText.Headline.copy(fontSize = 40.sp, lineHeight = 44.sp))
            Text(
                "read across ${plural(store.books.size, "book")}",
                style = LockInText.Sub.copy(color = LockInColors.Silver),
                modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressLine(fill, 6.dp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Books", store.books.size.toString(), Modifier.weight(1f))
            Stat("Pages read", readPages.toString(), Modifier.weight(1f))
            Stat("Cards", store.cards.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp, color = LockInColors.Snow))
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
    }
}

@Composable
private fun HistoryRow(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, LockInColors.Graphite, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(LockInColors.LimeWash), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.History, LockInColors.Lime, boxSize = 18.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("AI history", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
            Text("$count saved: explanations, selections, cards and quizzes", style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2f)
    }
}

@Composable
private fun LockedNote() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LockInColors.Slate).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Lock, LockInColors.Silver, boxSize = 18.dp)
        Text(
            "Adding books is off while you're locked in: the file picker is another app.",
            style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 13.sp),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun AddPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (enabled) LockInColors.Lime else LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Plus, if (enabled) LockInColors.OnLime else LockInColors.Mist, boxSize = 16.dp, strokeWidth = 2.2f)
        Text(text, style = LockInText.Label.copy(color = if (enabled) LockInColors.OnLime else LockInColors.Mist, fontSize = 14.sp), modifier = Modifier.padding(start = 6.dp))
    }
}

/** A search box and the sort order (Recent, A–Z, Progress) as chips. */
@Composable
private fun SearchAndSort(query: String, onQuery: (String) -> Unit, sort: BookSort, onSort: (BookSort) -> Unit) {
    val field = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(field).background(LockInColors.Slate).border(1.dp, LockInColors.Graphite, field).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(LockInColors.Mist)
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search your books", style = LockInText.Input.copy(color = LockInColors.Mist, fontSize = 15.sp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = LockInText.Input.copy(fontSize = 15.sp),
                cursorBrush = SolidColor(LockInColors.Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) { onQuery("") }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 11.dp, strokeWidth = 2.2f)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookSort.entries.forEach { s ->
            val on = s == sort
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(if (on) LockInColors.Snow else Color.Transparent)
                    .border(1.dp, if (on) LockInColors.Snow else LockInColors.Graphite, CircleShape)
                    .clickable(role = Role.Tab) { onSort(s) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, style = LockInText.Label.copy(color = if (on) LockInColors.Ink else LockInColors.Silver, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val k = size.width / 24f
        drawCircle(color, 7f * k, androidx.compose.ui.geometry.Offset(10.5f * k, 10.5f * k), style = Stroke(2f * k))
        drawLine(color, androidx.compose.ui.geometry.Offset(15.8f * k, 15.8f * k), androidx.compose.ui.geometry.Offset(21f * k, 21f * k), 2.2f * k, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------------------------
// The shelf
// ---------------------------------------------------------------------------------------------

/** The covers in a grid: as many across as fit at about 150 dp each (two on a phone), and an Add tile at the end. */
@Composable
private fun BookGrid(
    store: StudyStore,
    books: List<Textbook>,
    showAddTile: Boolean,
    onOpen: (String) -> Unit,
    onActions: (String) -> Unit,
    onAdd: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 14.dp
        val cols = ((maxWidth + gap) / (150.dp + gap)).toInt().coerceIn(2, 6)
        val items: List<Textbook?> = books + if (showAddTile) listOf(null) else emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                    row.forEach { b ->
                        if (b == null) AddTile(onAdd, Modifier.weight(1f))
                        else BookTile(store, b, onOpen = { onOpen(b.id) }, onActions = { onActions(b.id) }, modifier = Modifier.weight(1f))
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One book: its cover with a progress line along the bottom and a badge (New, Finished, Exam soon), then its title. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(store: StudyStore, book: Textbook, onOpen: () -> Unit, onActions: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val cover = rememberCover(store, book.id)
    val frac = readFraction(book)
    val shown by animateFloatAsState(frac, spring(dampingRatio = 0.85f, stiffness = 90f), label = "tile-progress")
    val cards = store.cards.count { it.book == book.id }
    val opened = book.lastPage > 0 || store.reads.any { it.book == book.id }
    val exam = store.daysToExam(book)
    val badge: Pair<String, Boolean>? = when {
        exam != null && exam <= 14 -> (if (exam == 0) "Exam today" else "Exam in ${exam}d") to true
        book.pages > 1 && book.lastPage + 1 >= book.pages -> "Finished" to false
        !opened -> "New" to false
        else -> null
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tile")
    Column(
        modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions() },
                onClick = onOpen,
            ),
    ) {
        Box {
            Cover(cover, Modifier.fillMaxWidth(), progress = if (opened) shown else null)
            if (badge != null) {
                Text(
                    badge.first,
                    style = LockInText.Small.copy(color = if (badge.second) Color.White else LockInColors.OnLime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (badge.second) LockInColors.Flare else LockInColors.Lime)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = onActions),
                contentAlignment = Alignment.Center,
            ) {
                Text("⋯", style = LockInText.Label.copy(color = Color.White, fontSize = 16.sp, lineHeight = 16.sp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            prettyTitle(book.title),
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            (if (opened) "p. ${book.lastPage + 1} of ${book.pages}" else "${book.pages} pages") + if (cards > 0) " · $cards cards" else "",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** A book cover at 3:4, with a soft frame. [progress] draws a reading line along its bottom edge. */
@Composable
private fun Cover(cover: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier, progress: Float? = null) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(0.75f)
            .clip(shape)
            .background(LockInColors.Graphite)
            .border(1.dp, LockInColors.Graphite, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            GlyphIcon(Glyph.Book, LockInColors.Silver, boxSize = 28.dp)
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.45f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(5.dp).background(LockInColors.Lime))
            }
        }
    }
}

/** The last tile on the shelf: a dashed outline with a plus, to add more PDFs. */
@Composable
private fun AddTile(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val rim = LockInColors.Rim
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onAdd)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    rim,
                    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(LockInColors.LimeWash), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Plus, LockInColors.Lime, boxSize = 20.dp, strokeWidth = 2.2f)
                }
                Spacer(Modifier.height(8.dp))
                Text("Add PDFs", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A book's actions
// ---------------------------------------------------------------------------------------------

@Composable
private fun BookActions(
    store: StudyStore,
    book: Textbook,
    onOpen: () -> Unit,
    onStartOver: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val cover = rememberCover(store, book.id)
    val cards = store.cards.count { it.book == book.id }
    val saved = store.ai.countFor(book.id)
    var confirming by remember(book.id) { mutableStateOf(false) }
    var renaming by remember(book.id) { mutableStateOf(false) }
    var name by remember(book.id) { mutableStateOf(prettyTitle(book.title)) }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        Modifier
            .widthIn(max = Widths.Sheet)
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(LockInColors.Rim))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, Modifier.width(52.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(prettyTitle(book.title), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.pages} pages · ${(readFraction(book) * 100).toInt()}% read" + (if (cards > 0) " · $cards cards" else "") + (if (saved > 0) " · $saved AI" else ""),
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            confirming -> {
                Text(
                    if (cards > 0) "Remove this book? Its $cards flashcards and what the AI made from it go too." else "Remove this book from your phone?",
                    style = LockInText.Sub.copy(color = LockInColors.Snow),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LockInButton("Keep it", { confirming = false }, Modifier.weight(1f), kind = ButtonKind.Outline)
                    LockInButton("Remove", onRemove, Modifier.weight(1f))
                }
            }
            renaming -> {
                val field = RoundedCornerShape(18.dp)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(field).background(LockInColors.Ink).border(1.dp, LockInColors.Lime, field).padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        singleLine = true,
                        textStyle = LockInText.Input,
                        cursorBrush = SolidColor(LockInColors.Lime),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LockInButton("Cancel", { renaming = false; name = prettyTitle(book.title) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                    LockInButton("Save", {
                        if (name.isNotBlank()) store.renameBook(book.id, name.trim())
                        renaming = false
                    }, Modifier.weight(1f), enabled = name.isNotBlank())
                }
            }
            else -> {
                ActionRow(if (book.lastPage > 0) "Continue at page ${book.lastPage + 1}" else "Open", Glyph.Book, onClick = onOpen)
                if (book.lastPage > 0) ActionRow("Start from page 1", Glyph.History, onClick = onStartOver)
                ActionRow("Rename", Glyph.Gear) { renaming = true }
                ActionRow("Remove from this phone", Glyph.Close, danger = true) { confirming = true }
                Spacer(Modifier.height(4.dp))
                LockInButton("Close", onClose, kind = ButtonKind.Outline)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, glyph: Glyph, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (danger) LockInColors.Flare else LockInColors.Lime, boxSize = 18.dp, strokeWidth = 2f)
        Text(label, style = LockInText.Label.copy(color = if (danger) LockInColors.Flare else LockInColors.Snow, fontSize = 15.sp), modifier = Modifier.padding(start = 14.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// No books yet
// ---------------------------------------------------------------------------------------------

@Composable
private fun EmptyLibrary(canAdd: Boolean, adding: Boolean, progress: Pair<Int, Int>?, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Three covers fanned out.
        Box(Modifier.height(120.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            listOf(-14f to -34.dp, 0f to 0.dp, 14f to 34.dp).forEachIndexed { i, (rot, dx) ->
                Box(
                    Modifier
                        .graphicsLayer { rotationZ = rot; translationX = dx.toPx(); translationY = if (i == 1) -8.dp.toPx() else 0f }
                        .size(width = 66.dp, height = 88.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (i == 1) LockInColors.Lime else LockInColors.Graphite)
                        .border(1.dp, LockInColors.Rim, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Book, if (i == 1) LockInColors.OnLime else LockInColors.Silver, boxSize = 24.dp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Build your shelf", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            "Add your textbooks as PDFs. They're copied into LockIn, so you can read them, make flashcards and ask the AI about any line, even while you're locked in.",
            style = LockInText.Sub.copy(color = LockInColors.Silver, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        Spacer(Modifier.height(20.dp))
        LockInButton(
            text = progress?.let { p -> "Adding ${p.first} of ${p.second}…" } ?: "Add PDFs",
            onClick = onAdd,
            loading = adding && progress == null,
            enabled = canAdd && !adding,
        )
        if (!canAdd) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Add books before you lock in. A locked phone can't open the file picker.",
                style = LockInText.Small.copy(color = LockInColors.Mist, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}
