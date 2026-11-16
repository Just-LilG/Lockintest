package com.godbless.lockin.study

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.home.TwoColumns
import com.godbless.lockin.ui.twoPane
import com.godbless.lockin.push.QuizReminder
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// =============================================================================================
// The Quiz page. One smart button up top that does the right thing; everything else (how the quiz is put
// together, which book, the cards themselves) is one tap away and never in the way. Stages have one colour each,
// everywhere: new (grey), learning (amber), learned (the accent).
// =============================================================================================

/** Flashcards, multiple choice, or typing the answer. */
internal enum class QuizStyle(val label: String) { Flash("Flashcards"), Choice("Multiple choice"), Type("Type it") }

internal val Amber = Color(0xFFF2B33D)

internal fun stageColor(s: Stage): Color = when (s) {
    Stage.New -> LockInColors.Mist
    Stage.Learning -> Amber
    Stage.Learned -> LockInColors.Lime
}

private val BookTints = listOf(
    Color(0xFF5AA9FF), Color(0xFFB085FF), Color(0xFF3CC9A0), Color(0xFFFF8A5B),
    Color(0xFFFFC94D), Color(0xFFFF6FA8), Color(0xFF7FD1FF), Color(0xFF9BD45A),
)

/** Each book has its own colour, the same on every chip, row and card. */
internal fun bookTint(id: String): Color = if (id.isEmpty()) LockInColors.Rim else BookTints[(id.hashCode() and 0x7fffffff) % BookTints.size]

internal fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

/** "pages 12 to 17", "page 4", or "pages 3, 9 and 12" for scattered ones (pages count from 0 here). */
internal fun pagesWords(pages: List<Int>): String {
    val ps = pages.distinct().sorted().map { it + 1 }
    if (ps.isEmpty()) return ""
    if (ps.size == 1) return "page ${ps[0]}"
    val run = ps.last() - ps.first() + 1 == ps.size
    return if (run || ps.size > 4) "pages ${ps.first()} to ${ps.last()}" else "pages " + ps.dropLast(1).joinToString(", ") + " and " + ps.last()
}

/** Where AI questions come from: one book's recent pages, or all your recent reading ([book] empty). */
internal fun aiSource(store: StudyStore, book: String, max: Int): List<Pair<String, Int>> {
    val reads = store.reads.filter { store.book(it.book) != null }
    val picked = if (book.isEmpty()) store.recentReading(max) else reads.filter { it.book == book }.takeLast(max)
    return picked.map { it.book to it.page }
}

internal fun describeSource(store: StudyStore, src: List<Pair<String, Int>>): String {
    if (src.isEmpty()) return "Read a few textbook pages first."
    val byBook = src.groupBy({ it.first }, { it.second })
    return byBook.entries.joinToString(" · ") { (b, ps) -> prettyTitle(store.book(b)?.title ?: "Book") + ", " + pagesWords(ps) }
}

/** What the big button does now. */
private sealed interface Smart {
    data class Resume(val left: Int) : Smart
    data class Review(val cards: List<Card>) : Smart
    data class WarmUp(val cards: List<Card>) : Smart
    data object Empty : Smart
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuizHub(
    store: StudyStore,
    pool: List<Card>,
    bookFilter: String,
    onFilter: (String) -> Unit,
    pageFrom: String,
    pageTo: String,
    onPages: (String, String) -> Unit,
    style: QuizStyle,
    canChoose: Boolean,
    onStyle: (QuizStyle) -> Unit,
    size: Int,
    onSize: (Int) -> Unit,
    weakFirst: Boolean,
    onWeak: (Boolean) -> Unit,
    plan: Pair<Int, Int>,
    paused: Int,
    onResume: () -> Unit,
    onStart: () -> Unit,
    onPractise: (List<Card>) -> Unit,
    onMake: () -> Unit,
    onEdit: (String) -> Unit,
    onSimplify: (String) -> Unit,
    onAi: (List<Pair<String, Int>>) -> Unit,
    onAiCards: (List<Pair<String, Int>>) -> Unit,
    onBack: () -> Unit,
    onOpenPage: (String, Int) -> Unit,
    partnerName: String?,
    onChallenge: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val prefs = remember { context.getSharedPreferences("quiz_hub", Context.MODE_PRIVATE) }

    // Sheets over the page.
    var sheet by rememberSaveable { mutableStateOf("") } // "", "settings", "books", "menu", "import", "share", "source"
    var sourceFor by rememberSaveable { mutableStateOf("quiz") }
    var aiBook by rememberSaveable { mutableStateOf("") }
    var cardsBook by rememberSaveable { mutableStateOf(store.recentReading().lastOrNull()?.book ?: "") }

    // The card list.
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") } // all, due, new, learning, learned, tricky
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) } // 0 newest, 1 weakest, 2 due first
    var shown by rememberSaveable { mutableIntStateOf(40) }
    var openCard by rememberSaveable { mutableStateOf("") }
    var hiddenId by rememberSaveable { mutableStateOf("") }
    var listY by remember { mutableIntStateOf(0) }

    // A deleted card waits five seconds so it can be brought back; it is gone for good after that (or on leaving).
    LaunchedEffect(hiddenId) {
        if (hiddenId.isNotEmpty()) {
            delay(5000)
            store.deleteCard(hiddenId)
            hiddenId = ""
        }
    }
    DisposableEffect(Unit) { onDispose { if (hiddenId.isNotEmpty()) store.deleteCard(hiddenId) } }

    fun showList(f: String) {
        filter = f
        listOpen = true
        shown = 40
        scope.launch {
            delay(60)
            scroll.animateScrollTo(listY.coerceAtLeast(0), spring(dampingRatio = 0.9f, stiffness = 260f))
        }
    }

    val dueCards = pool.filter { store.isDue(it) }
    val dueCount = dueCards.size
    val done = store.doneToday(pool)
    val streak = store.quizStreak()
    val picked = store.book(bookFilter)
    val tricky = pool.filter { it.tricky }

    // Worked out once per change in the deck (not on every redraw), so "Up next" stays put.
    val review = remember(dueCards.map { it.id }, weakFirst) {
        (if (weakFirst) dueCards.sortedWith(compareBy<Card>({ it.box }, { -it.wrong })) else dueCards.shuffled()).take(30)
    }
    val warm = remember(pool.map { it.id to it.box }) {
        pool.sortedWith(compareBy<Card>({ it.box }, { it.last })).take(minOf(5, pool.size)).shuffled()
    }
    val smart: Smart = when {
        paused > 0 -> Smart.Resume(paused)
        pool.isEmpty() -> Smart.Empty
        dueCount > 0 -> Smart.Review(review)
        else -> Smart.WarmUp(warm)
    }

    HubScaffold(scroll, onBack, onMenu = { sheet = "menu" }) {
        // On a tablet: the title, today's card and the AI rows on the left; how well the cards are known, the cards
        // themselves and the reminder on the right. On a phone, one column, in the same order as ever.
        TwoColumns(leftWeight = 1f, gap = 24.dp, left = leftPane@{
        // ---- Title, and which book
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("Quiz", style = LockInText.Headline.copy(fontSize = 38.sp))
            Text(
                when {
                    store.cards.isEmpty() -> "Make cards from your textbooks."
                    pool.isEmpty() -> "No cards in this book yet."
                    dueCount > 0 -> plural(dueCount, "card") + " to review today"
                    else -> "Nothing due. You're ahead."
                },
                style = LockInText.Sub.copy(color = LockInColors.Silver),
            )
        }
        Spacer(Modifier.height(14.dp))
        if (store.books.isNotEmpty() || bookFilter.isNotEmpty()) {
            BookPill(store, picked, pageFrom, pageTo) { sheet = "books" }
            Spacer(Modifier.height(14.dp))
        }

        if (store.cards.isEmpty()) {
            EmptyDeck(onImport = { sheet = "import" })
            if (!twoPane()) {
                Spacer(Modifier.height(16.dp))
                AiRows(store, aiBook, cardsBook, onAi, onAiCards, onMake) { which -> sourceFor = which; sheet = "source" }
            }
            return@leftPane
        }

        // ---- Today
        HeroCard(
            store = store,
            pool = pool,
            dueCount = dueCount,
            done = done,
            streak = streak,
            smart = smart,
            book = picked,
            style = style,
            size = size,
            weakFirst = weakFirst,
            prefs = prefs,
            onSmart = {
                when (val s = smart) {
                    is Smart.Resume -> onResume()
                    is Smart.Review -> onPractise(s.cards)
                    is Smart.WarmUp -> onPractise(s.cards)
                    Smart.Empty -> onMake()
                }
            },
            onNewQuiz = onStart,
            onCustomise = { sheet = "settings" },
        )

        // ---- Cards that keep being missed
        if (tricky.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HubRow(
                icon = { GlyphIcon(Glyph.Flame, Amber, boxSize = 18.dp, strokeWidth = 2f) },
                iconBg = Amber.copy(alpha = 0.16f),
                title = plural(tricky.size, "tricky card"),
                sub = "Missed again and again. Rewrite them, or practise just these.",
                onClick = { showList("tricky") },
            )
        }

        Spacer(Modifier.height(12.dp))
        AiRows(store, aiBook, cardsBook, onAi, onAiCards, onMake) { which -> sourceFor = which; sheet = "source" }
        }, right = rightPane@{
        // Nothing to the right of an empty deck but the ways to fill it.
        if (store.cards.isEmpty()) {
            if (twoPane()) AiRows(store, aiBook, cardsBook, onAi, onAiCards, onMake) { which -> sourceFor = which; sheet = "source" }
            return@rightPane
        }
        // ---- How well they're known (each part opens those cards)
        if (!twoPane()) Spacer(Modifier.height(24.dp))
        Mastery(pool) { stage -> showList(stage.name.lowercase()) }

        // ---- The cards
        Spacer(Modifier.height(24.dp))
        Column(Modifier.onGloballyPositioned { listY = it.positionInParent().y.toInt() }) {
            CardList(
                store = store,
                pool = pool,
                open = listOpen,
                onOpen = { listOpen = it; if (!it) { filter = "all"; searching = false; query = "" } },
                filter = filter,
                onFilter = { filter = it; shown = 40 },
                searching = searching,
                onSearching = { searching = it; if (!it) query = "" },
                query = query,
                onQuery = { query = it },
                sort = sort,
                onSort = { sort = it },
                shown = shown,
                onMore = { shown += 40 },
                openCard = openCard,
                onOpenCard = { openCard = it },
                hiddenId = hiddenId,
                onDelete = { id ->
                    if (hiddenId.isNotEmpty()) store.deleteCard(hiddenId)
                    hiddenId = id
                    if (openCard == id) openCard = ""
                },
                onUndo = { hiddenId = "" },
                onPractise = onPractise,
                onEdit = onEdit,
                onSimplify = onSimplify,
                onOpenPage = onOpenPage,
            )
        }

        // ---- A daily nudge
        Spacer(Modifier.height(24.dp))
        ReminderRow()
        })
    }

    // ---- The sheets
    HubSheet(sheet == "settings", { sheet = "" }) {
        SettingsSheet(pool, style, canChoose, onStyle, size, onSize, weakFirst, onWeak, plan, onStart = { sheet = ""; onStart() })
    }
    HubSheet(sheet == "books", { sheet = "" }) {
        BookSheet(store, bookFilter, onFilter, pageFrom, pageTo, onPages, onDone = { sheet = "" })
    }
    HubSheet(sheet == "source", { sheet = "" }) {
        SourceSheet(
            store = store,
            forCards = sourceFor == "cards",
            current = if (sourceFor == "cards") cardsBook else aiBook,
            onPick = { b -> if (sourceFor == "cards") cardsBook = b else aiBook = b; sheet = "" },
        )
    }
    HubSheet(sheet == "menu", { sheet = "" }) {
        MenuSheet(
            partnerName = partnerName,
            canChallenge = onChallenge != null && pool.size >= 4,
            onMake = { sheet = ""; onMake() },
            onImport = { sheet = "import" },
            onShare = { sheet = "share" },
            onChallenge = {
                sheet = ""
                val code = encodeChallenge(store, pool)
                if (code == null) Toast.makeText(context, "Need 4 cards with different answers to send a quiz.", Toast.LENGTH_SHORT).show()
                else { onChallenge?.invoke(code); Toast.makeText(context, "Quiz sent to ${partnerName ?: "your partner"}.", Toast.LENGTH_SHORT).show() }
            },
        )
    }
    HubSheet(sheet == "import", { sheet = "" }) {
        ImportSheet(store, bookFilter, onDone = { sheet = "" })
    }
    HubSheet(sheet == "share", { sheet = "" }) {
        ShareSheet(store, picked, onDone = { sheet = "" })
    }
}

// ---------------------------------------------------------------- page frame

/**
 * The page with a bar that stays on top: a solid band under the status bar (nothing scrolls under the clock),
 * the back button, the title once the big one has scrolled away, and the menu.
 */
@Composable
private fun HubScaffold(scroll: ScrollState, onBack: () -> Unit, onMenu: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(24.dp)
    val density = LocalDensity.current
    val collapsed = scroll.value > with(density) { 64.dp.toPx() }
    // Two columns on a big screen get the wide width; the bar above them lines up with it.
    val width = if (twoPane()) Widths.Wide else Widths.Page
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = topPad + 60.dp)
                .navigationBarsPadding()
                .readable(width)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            content()
            Spacer(Modifier.height(40.dp))
        }
        Column(Modifier.fillMaxWidth().background(LockInColors.Ink)) {
            Spacer(Modifier.height(topPad))
            Row(
                Modifier.fillMaxWidth().height(60.dp).readable(width).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Box(Modifier.weight(1f).height(60.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    BarTitle(collapsed)
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .clickable(role = Role.Button, onClickLabel = "More: import, share, challenge", onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋯", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 22.sp, color = LockInColors.Snow))
                }
            }
            // A hairline once the page has moved under the bar.
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.value > 0) LockInColors.Graphite else LockInColors.Ink))
        }
    }
}

/** The small title in the bar, rising in once the big one has scrolled away. */
@Composable
private fun BarTitle(shown: Boolean) {
    AnimatedVisibility(
        shown,
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { it },
        exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 600f)) { it },
    ) {
        Text("Quiz", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
    }
}

/** A sheet that slides up over a dark backdrop (tap the backdrop to close). */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun HubSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .animateEnterExit(
                        enter = slideInVertically(spring(dampingRatio = 0.88f, stiffness = 420f)) { it },
                        exit = slideOutVertically(spring(dampingRatio = 0.95f, stiffness = 520f)) { it },
                    )
                    .fillMaxWidth()
                    .readable(Widths.Page)
                    .heightIn(max = 680.dp)
                    .clip(shape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(LockInColors.Rim))
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, sub: String? = null) {
    Text(text, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
    if (sub != null) Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist))
    Spacer(Modifier.height(14.dp))
}

// ---------------------------------------------------------------- book switcher

@Composable
private fun BookPill(store: StudyStore, picked: Textbook?, pageFrom: String, pageTo: String, onClick: () -> Unit) {
    val range = when {
        pageFrom.isNotEmpty() && pageTo.isNotEmpty() -> " · pages $pageFrom to $pageTo"
        pageFrom.isNotEmpty() -> " · from page $pageFrom"
        pageTo.isNotEmpty() -> " · up to page $pageTo"
        else -> ""
    }
    val exam = store.daysToExam(picked)
    Row(
        Modifier
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Choose a book", onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (picked == null) LockInColors.Lime else bookTint(picked.id)))
        Text(
            (picked?.let { prettyTitle(it.title) } ?: "All books") + range,
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 230.dp).padding(start = 8.dp),
        )
        if (exam != null) {
            Text(
                if (exam == 0) "Exam today" else "Exam in ${plural(exam, "day")}",
                style = LockInText.Small.copy(color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text("▾", style = LockInText.Label.copy(color = LockInColors.Mist, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun BookSheet(
    store: StudyStore,
    current: String,
    onFilter: (String) -> Unit,
    pageFrom: String,
    pageTo: String,
    onPages: (String, String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    SheetTitle("Which cards", "Everything on the Quiz page follows the book you pick.")
    val counts = store.books.associate { b -> b.id to store.cards.count { it.book == b.id } }
    val loose = store.cards.count { c -> c.book.isEmpty() || store.book(c.book) == null }
    BookChoice("All books", store.cards.size, current.isEmpty(), LockInColors.Lime, null) { onFilter(""); onDone() }
    val withCards = store.books.filter { (counts[it.id] ?: 0) > 0 }
    val empty = store.books.filter { (counts[it.id] ?: 0) == 0 }
    withCards.forEach { b ->
        BookChoice(prettyTitle(b.title), counts[b.id] ?: 0, current == b.id, bookTint(b.id), store.daysToExam(b)) { onFilter(b.id) }
    }
    if (loose > 0) Text("${plural(loose, "card")} not linked to a book show under All books.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    if (empty.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("No cards yet", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        empty.forEach { b -> BookChoice(prettyTitle(b.title), 0, current == b.id, bookTint(b.id), null) { onFilter(b.id) } }
    }

    val picked = store.book(current) ?: return
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(LockInColors.Graphite))
    Spacer(Modifier.height(14.dp))
    Text(prettyTitle(picked.title), style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
    Spacer(Modifier.height(10.dp))

    // Pages
    val from = pageFrom.toIntOrNull()
    val to = pageTo.toIntOrNull()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StudyField(pageFrom, { onPages(it.filter(Char::isDigit).take(4), pageTo) }, "From page", Modifier.weight(1f), number = true)
        StudyField(pageTo, { onPages(pageFrom, it.filter(Char::isDigit).take(4)) }, "To page", Modifier.weight(1f), number = true)
    }
    val problem = when {
        from != null && to != null && from > to -> "\"From\" is after \"To\". Swap them?"
        from != null && from > picked.pages -> "This book has ${picked.pages} pages."
        to != null && to > picked.pages -> "This book has ${picked.pages} pages."
        else -> null
    }
    if (problem != null) {
        Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(problem, style = LockInText.Small.copy(color = LockInColors.Flare), modifier = Modifier.weight(1f))
            if (from != null && to != null && from > to) {
                Text(
                    "Swap",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onPages(pageTo, pageFrom) }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }

    // Exam date
    Spacer(Modifier.height(16.dp))
    Text("Exam date", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    val days = store.daysToExam(picked)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val day = 86_400_000L
        Chip("None", days == null, true) { store.setExam(picked.id, 0L) }
        Chip("In 1 week", days == 7, true) { store.setExam(picked.id, System.currentTimeMillis() + 7 * day) }
        Chip("In 2 weeks", days == 14, true) { store.setExam(picked.id, System.currentTimeMillis() + 14 * day) }
        Chip("In a month", days == 30, true) { store.setExam(picked.id, System.currentTimeMillis() + 30 * day) }
        Chip(if (days != null && days !in setOf(7, 14, 30)) examDate(picked.exam) else "Pick a date…", days != null && days !in setOf(7, 14, 30), true) {
            val c = java.util.Calendar.getInstance()
            if (picked.exam > 0) c.timeInMillis = picked.exam
            android.app.DatePickerDialog(context, { _, y, m, d ->
                val at = java.util.Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
                store.setExam(picked.id, at)
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis()
            }.show()
        }
    }
    if (days != null) {
        Text(
            "Cards from this book come back often enough to all be seen again before the exam.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }

    // Name
    Spacer(Modifier.height(16.dp))
    var renaming by rememberSaveable(picked.id) { mutableStateOf(false) }
    var newTitle by rememberSaveable(picked.id) { mutableStateOf(prettyTitle(picked.title)) }
    if (!renaming) {
        Text(
            "Rename this book",
            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { renaming = true }.padding(horizontal = 6.dp, vertical = 10.dp),
        )
    } else {
        StudyField(newTitle, { newTitle = it.take(60) }, "Book name")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Cancel", { renaming = false }, Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton("Save name", { store.renameBook(picked.id, newTitle); renaming = false }, Modifier.weight(1f), enabled = newTitle.isNotBlank())
        }
    }
    Spacer(Modifier.height(14.dp))
    LockInButton("Done", onDone)
}

private fun examDate(ms: Long): String = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun BookChoice(title: String, count: Int, on: Boolean, tint: Color, examDays: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(if (on) LockInColors.Graphite else LockInColors.Slate)
            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Graphite, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The book's spine.
        Box(Modifier.width(5.dp).height(26.dp).clip(CircleShape).background(tint))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(plural(count, "card"), examDays?.let { if (it == 0) "exam today" else "exam in ${plural(it, "day")}" }).joinToString(" · "),
                style = LockInText.Small.copy(color = if (examDays != null) Amber else LockInColors.Mist),
            )
        }
        if (on) Text("✓", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 16.sp))
    }
}

// ---------------------------------------------------------------- today

@Composable
private fun HeroCard(
    store: StudyStore,
    pool: List<Card>,
    dueCount: Int,
    done: Int,
    streak: Int,
    smart: Smart,
    book: Textbook?,
    style: QuizStyle,
    size: Int,
    weakFirst: Boolean,
    prefs: android.content.SharedPreferences,
    onSmart: () -> Unit,
    onNewQuiz: () -> Unit,
    onCustomise: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val caughtUp = dueCount == 0 && pool.isNotEmpty()

    // Once a day, finishing everything that was due gets a little moment.
    val today = store.dayStart()
    var party by remember { mutableStateOf(false) }
    LaunchedEffect(caughtUp, done) {
        if (caughtUp && done > 0 && prefs.getLong("celebrated", 0L) != today) {
            prefs.edit().putLong("celebrated", today).apply()
            party = true
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(if (dueCount > 0) 1.5.dp else 1.dp, if (dueCount > 0) LockInColors.Lime else LockInColors.Graphite, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TodayRing(dueCount, done, party)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    when {
                        pool.isEmpty() -> "No cards here"
                        dueCount > 0 -> plural(dueCount, "card") + " due"
                        else -> "All caught up"
                    },
                    style = LockInText.Title.copy(fontSize = 21.sp, lineHeight = 27.sp),
                )
                val next = store.nextDue(pool)
                Text(
                    buildString {
                        append(if (done == 0) "None done yet today" else "$done done today")
                        if (dueCount == 0 && next != null) {
                            val n = pool.count { !store.isDue(it) && store.dayStart(it.due) == store.dayStart(next) }
                            append(" · next ${plural(n, "card")} ${dueWords(store, next)}")
                        }
                    },
                    style = LockInText.Small.copy(color = LockInColors.Body),
                )
                if (streak > 0) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val breathe = remember { Animatable(1f) }
                        LaunchedEffect(streak) {
                            // A lit streak flickers once when you arrive.
                            breathe.snapTo(0.7f)
                            breathe.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f))
                        }
                        GlyphIcon(Glyph.Flame, LockInColors.Lime, Modifier.graphicsLayer { scaleX = breathe.value; scaleY = breathe.value }, boxSize = 15.dp, strokeWidth = 2f)
                        Text(
                            "$streak-day streak",
                            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp),
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
        }

        // An exam coming: how far there is to go.
        val exam = store.daysToExam(book)
        if (exam != null && book != null) {
            val toLearn = pool.count { it.stage != Stage.Learned }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Amber.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Calendar, Amber, boxSize = 16.dp, strokeWidth = 2f)
                Text(
                    when {
                        toLearn == 0 -> "Exam ${if (exam == 0) "today" else "in ${plural(exam, "day")}"}. Every card learned."
                        exam == 0 -> "Exam today. ${plural(toLearn, "card")} still learning: a last run-through?"
                        else -> "Exam in ${plural(exam, "day")} · ${plural(toLearn, "card")} to learn · about ${(toLearn + exam - 1) / exam} a day"
                    },
                    style = LockInText.Small.copy(color = LockInColors.Snow),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        // The week, as seven small bars.
        Spacer(Modifier.height(16.dp))
        WeekBars(store)

        // Up next: a peek at the deck.
        val upNext = when (smart) {
            is Smart.Review -> smart.cards.firstOrNull()
            is Smart.WarmUp -> smart.cards.firstOrNull()
            else -> null
        }
        if (upNext != null) {
            Spacer(Modifier.height(16.dp))
            DeckPeek(upNext, store, layers = minOf(2, (if (smart is Smart.Review) smart.cards.size else (smart as Smart.WarmUp).cards.size) - 1))
        }

        Spacer(Modifier.height(16.dp))
        when (smart) {
            is Smart.Resume -> {
                LockInButton(if (smart.left == 1) "Resume · 1 card left" else "Resume · ${smart.left} cards left", onSmart)
                Spacer(Modifier.height(8.dp))
                LockInButton("Start a new quiz", onNewQuiz, kind = ButtonKind.Outline)
            }
            is Smart.Review -> LockInButton("Review ${plural(smart.cards.size, "card")}", onSmart)
            is Smart.WarmUp -> {
                LockInButton("Warm up · ${plural(smart.cards.size, "weakest card", "weakest cards")}", onSmart)
                Text(
                    "Nothing's due, so these are the ones you know least.",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Smart.Empty -> LockInButton("Make a card", onSmart, kind = ButtonKind.Outline)
        }
        if (pool.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Choose how the quiz is put together", onClick = onCustomise)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Gear, LockInColors.Mist, boxSize = 15.dp, strokeWidth = 2f)
                Text(
                    listOf(style.label, if (size == 0) "due only" else "$size cards", if (weakFirst) "weakest first" else "random order").joinToString(" · "),
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Customise", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp))
            }
        }
    }
}

internal fun dueWords(store: StudyStore, ms: Long): String {
    val days = ((store.dayStart(ms) - store.dayStart()) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "later today"
        days == 1 -> "tomorrow"
        else -> "in $days days"
    }
}

/** How much of today's reviewing is done, with a burst of sparks the first time each day it's all done. */
@Composable
private fun TodayRing(dueCount: Int, done: Int, party: Boolean) {
    val frac = if (dueCount + done == 0) 0f else done / (dueCount + done).toFloat()
    val sweep by animateFloatAsState(if (dueCount == 0 && done > 0) 1f else frac, spring(dampingRatio = 0.85f, stiffness = 70f), label = "today ring")
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(party) {
        if (party) {
            launch { pop.snapTo(0.7f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 260f)) }
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1100))
        }
    }
    val accent = LockInColors.Lime
    val track = LockInColors.Graphite
    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(76.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            val sw = 8.dp.toPx()
            val tl = Offset(sw / 2f, sw / 2f)
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            if (sweep > 0.001f) drawArc(accent, -90f, 360f * sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(
            if (dueCount == 0) "✓" else "$dueCount",
            style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp, color = if (dueCount == 0) accent else LockInColors.Snow),
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
        // Sparks fly out and shrink to nothing (no fading).
        if (burst.value in 0.001f..0.999f) {
            Canvas(Modifier.size(150.dp)) {
                val k = burst.value
                val c = Offset(size.width / 2f, size.height / 2f)
                for (i in 0 until 14) {
                    val a = (i / 14f) * 2f * PI.toFloat() + 0.3f
                    val r = (38f + 34f * k) * density
                    val dot = (4.5f * (1f - k)) * density
                    val col = if (i % 3 == 0) Amber else accent
                    drawCircle(col, dot, Offset(c.x + cos(a) * r, c.y + sin(a) * r))
                }
            }
        }
    }
}

/** Cards answered on each of the last seven days, and the week's share right. */
@Composable
private fun WeekBars(store: StudyStore) {
    val counts = store.reviewsByDay(7)
    val top = max(1, counts.maxOrNull() ?: 0)
    val acc = store.weekAccuracy()
    val letters = remember {
        val c = java.util.Calendar.getInstance()
        List(7) { i ->
            val d = (c.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, i - 6) }
            java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault()).format(d.time)
        }
    }
    val accent = LockInColors.Lime
    val empty = LockInColors.Graphite
    Row(verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            counts.forEachIndexed { i, n ->
                val h by animateFloatAsState(n / top.toFloat(), spring(dampingRatio = 0.8f, stiffness = 120f), label = "bar$i")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.width(14.dp).height(34.dp)) {
                        val bh = if (n == 0) 3.dp.toPx() else (size.height * h).coerceAtLeast(4.dp.toPx())
                        drawRoundRect(if (n == 0) empty else accent.copy(alpha = if (i == 6) 1f else 0.55f), Offset(0f, size.height - bh), Size(size.width, bh), CornerRadius(4.dp.toPx()))
                    }
                    Text(letters[i], style = LockInText.Small.copy(color = if (i == 6) LockInColors.Snow else LockInColors.Mist, fontSize = 10.sp))
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            Text(acc?.let { "$it%" } ?: "–", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 24.sp))
            Text(if (acc == null) "No quizzes this week" else "right this week", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp))
        }
    }
}

/** The first card of what the button would start, with the rest of the deck under it. */
@Composable
private fun DeckPeek(card: Card, store: StudyStore, layers: Int) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxWidth().padding(bottom = (layers * 6).dp)) {
        for (l in layers downTo 1) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { translationY = (l * 6).dp.toPx(); scaleX = 1f - 0.05f * l }
                    .clip(shape)
                    .background(LockInColors.Graphite)
                    .border(1.dp, LockInColors.Rim, shape),
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(LockInColors.Ink).border(1.dp, LockInColors.Rim, shape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(34.dp).clip(CircleShape).background(bookTint(card.book)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("UP NEXT", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 10.sp, letterSpacing = 1.4.sp))
                Text(card.question, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StrengthDots(card.box, Modifier.padding(start = 10.dp))
        }
    }
}

/** A card's strength: five dots, filled up to its box. */
@Composable
internal fun StrengthDots(box: Int, modifier: Modifier = Modifier) {
    val on = when {
        box >= StudyStore.MasteredBox -> LockInColors.Lime
        box > 0 -> Amber
        else -> LockInColors.Rim
    }
    Row(modifier.semantics { contentDescription = "Strength ${box.coerceIn(0, 5)} of 5" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..5) Box(Modifier.size(6.dp).clip(CircleShape).background(if (i <= box) on else LockInColors.Graphite))
    }
}

// ---------------------------------------------------------------- rows

@Composable
private fun HubRow(icon: @Composable () -> Unit, iconBg: Color, title: String, sub: String, onClick: () -> Unit, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = LockInText.Label.copy(color = if (enabled) LockInColors.Snow else LockInColors.Mist, fontSize = 16.sp))
            Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2f)
    }
}

/** The AI rows say exactly which pages they'll use, with a way to pick others. */
@Composable
private fun AiRows(
    store: StudyStore,
    aiBook: String,
    cardsBook: String,
    onAi: (List<Pair<String, Int>>) -> Unit,
    onAiCards: (List<Pair<String, Int>>) -> Unit,
    onMake: () -> Unit,
    onChange: (String) -> Unit,
) {
    val quizSrc = aiSource(store, aiBook, 12)
    val cardSrc = aiSource(store, cardsBook, 6)
    val sparkle: @Composable () -> Unit = { Text("✦", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 16.sp)) }
    val change: @Composable (String) -> Unit = { which ->
        Text(
            "Change",
            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Choose which pages") { onChange(which) }.padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
    val hasReads = store.reads.isNotEmpty()
    val quizTrail: @Composable () -> Unit = { if (hasReads) change("quiz") else GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2f) }
    val cardsTrail: @Composable () -> Unit = { if (hasReads) change("cards") else GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 14.dp, strokeWidth = 2f) }
    HubRow(sparkle, LockInColors.LimeWash, "Quiz me on what I read", describeSource(store, quizSrc), { onAi(quizSrc) }, enabled = quizSrc.isNotEmpty(), trailing = quizTrail)
    Spacer(Modifier.height(8.dp))
    HubRow(sparkle, LockInColors.LimeWash, "Make cards with AI", if (cardSrc.isEmpty()) "Read a few textbook pages first." else "Up to 6 cards from " + describeSource(store, cardSrc), { onAiCards(cardSrc) }, enabled = cardSrc.isNotEmpty(), trailing = cardsTrail)
    Spacer(Modifier.height(8.dp))
    HubRow({ GlyphIcon(Glyph.Plus, LockInColors.Snow, boxSize = 16.dp, strokeWidth = 2.2f) }, LockInColors.Graphite, "Make a card", "Your own question and answer, with a picture from the page if you like.", onMake)
}

@Composable
private fun SourceSheet(store: StudyStore, forCards: Boolean, current: String, onPick: (String) -> Unit) {
    SheetTitle(if (forCards) "Make cards from" else "Quiz me on", "Pages count once they've been on screen for a few seconds.")
    val max = if (forCards) 6 else 12
    if (!forCards) {
        val all = aiSource(store, "", max)
        BookChoiceText("All recent reading", describeSource(store, all), current.isEmpty(), LockInColors.Lime) { onPick("") }
    }
    val books = store.reads.map { it.book }.distinct().reversed().mapNotNull { store.book(it) }
    books.forEach { b ->
        val src = aiSource(store, b.id, max)
        BookChoiceText(prettyTitle(b.title), pagesWords(src.map { it.second }).replaceFirstChar { it.uppercase() } + " (your last ${plural(src.size, "page")} read)", current == b.id, bookTint(b.id)) { onPick(b.id) }
    }
}

@Composable
private fun BookChoiceText(title: String, sub: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(if (on) LockInColors.Graphite else LockInColors.Slate)
            .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Graphite, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(26.dp).clip(CircleShape).background(tint))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (on) Text("✓", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 16.sp))
    }
}

@Composable
private fun EmptyDeck(onImport: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LockInColors.Graphite, shape).padding(22.dp)) {
        Text("No cards yet", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
        Spacer(Modifier.height(4.dp))
        Text(
            "A card is a question and its answer. Make one from any page while you read, here, or let the AI write them from what you read.",
            style = LockInText.Sub.copy(color = LockInColors.Silver),
        )
        Text(
            "Have cards in a spreadsheet? Import them",
            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
            modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onImport).padding(vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsSheet(
    pool: List<Card>,
    style: QuizStyle,
    canChoose: Boolean,
    onStyle: (QuizStyle) -> Unit,
    size: Int,
    onSize: (Int) -> Unit,
    weakFirst: Boolean,
    onWeak: (Boolean) -> Unit,
    plan: Pair<Int, Int>,
    onStart: () -> Unit,
) {
    SheetTitle("Your quiz", "These stay as you leave them.")
    SheetLabel("Style")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuizStyle.entries.forEach { s -> Chip(s.label, style == s && (s != QuizStyle.Choice || canChoose), s != QuizStyle.Choice || canChoose) { onStyle(s) } }
    }
    Text(
        when {
            style == QuizStyle.Type -> "Type the answer. Small typos and word order don't count against you."
            !canChoose -> "Multiple choice needs 4 cards with different answers in this book."
            style == QuizStyle.Choice -> "The wrong options come from this book's other cards."
            else -> "Turn the card, then say how it went."
        },
        style = LockInText.Small.copy(color = LockInColors.Mist),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
    Spacer(Modifier.height(16.dp))
    SheetLabel("How many")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val n = pool.size
        // Counts bigger than the deck are the same as "all", so they show as "All N" once and the rest step aside.
        listOf(5, 10, 20).forEach { v ->
            if (v < n) Chip("$v", size == v, true) { onSize(v) }
        }
        Chip(if (n <= 20) "All $n" else "20+", size != 0 && (size >= n || (n > 20 && size > 20)), true) { onSize(if (n <= 20) maxOf(n, 1) else 50) }
        Chip("Due only", size == 0, true) { onSize(0) }
    }
    Spacer(Modifier.height(16.dp))
    SheetLabel("Order")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Random", !weakFirst, true) { onWeak(false) }
        Chip("Weakest first", weakFirst, true) { onWeak(true) }
    }
    Spacer(Modifier.height(20.dp))
    val (n, due) = plan
    LockInButton(if (pool.isEmpty()) "No cards here" else "Start · ${plural(n, "card")}", onStart, enabled = pool.isNotEmpty())
    Text(
        when {
            pool.isEmpty() -> ""
            due == 0 -> "Nothing is due, so this is extra practice."
            due < n -> "$due due, plus ${n - due} extra practice."
            else -> "All of them are due."
        },
        style = LockInText.Small.copy(color = LockInColors.Mist),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = LockInText.Label.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
internal fun Chip(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (on) LockInColors.Lime else LockInColors.Slate)
            .border(1.5.dp, if (on) LockInColors.Lime else if (enabled) LockInColors.Rim else LockInColors.Graphite, CircleShape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
            style = LockInText.Label.copy(
                fontSize = 13.sp,
                color = when {
                    on -> LockInColors.OnLime
                    enabled -> LockInColors.Snow
                    else -> LockInColors.Mist
                },
            ),
        )
    }
}

// ---------------------------------------------------------------- progress

/** New, learning and learned as one bar; each part opens its cards. */
@Composable
private fun Mastery(pool: List<Card>, onStage: (Stage) -> Unit) {
    if (pool.isEmpty()) return
    val fresh = pool.count { it.stage == Stage.New }
    val learned = pool.count { it.stage == Stage.Learned }
    val learning = pool.size - fresh - learned
    val total = pool.size.toFloat()
    val a by animateFloatAsState(learned / total, spring(dampingRatio = 0.85f, stiffness = 80f), label = "learned")
    val b by animateFloatAsState(learning / total, spring(dampingRatio = 0.85f, stiffness = 80f), label = "learning")
    SheetLabel("Progress")
    val learnedC = LockInColors.Lime
    val track = LockInColors.Graphite
    val newC = LockInColors.Rim
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(learned * 100f / total).toInt()}%", style = LockInText.Title.copy(fontSize = 26.sp, lineHeight = 30.sp))
            Text(" learned", style = LockInText.Sub.copy(color = LockInColors.Silver), modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Three parts with small gaps, so "learning" never reads as the empty track.
        Canvas(Modifier.fillMaxWidth().height(12.dp).semantics { contentDescription = "$learned learned, $learning learning, $fresh new" }) {
            val r = CornerRadius(size.height / 2f)
            val gap = 3.dp.toPx()
            val w = size.width
            drawRoundRect(track, size = size, cornerRadius = r)
            var x = 0f
            fun part(f: Float, c: Color) {
                if (f <= 0.0005f) return
                val pw = (w * f - gap).coerceAtLeast(size.height)
                drawRoundRect(c, Offset(x, 0f), Size(pw.coerceAtMost(w - x), size.height), r)
                x += w * f
            }
            part(a, learnedC)
            part(b, Amber)
            part(1f - a - b, newC)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageChip("Learned", learned, learnedC, Modifier.weight(1f)) { onStage(Stage.Learned) }
            StageChip("Learning", learning, Amber, Modifier.weight(1f)) { onStage(Stage.Learning) }
            StageChip("New", fresh, LockInColors.Mist, Modifier.weight(1f)) { onStage(Stage.New) }
        }
    }
}

@Composable
private fun StageChip(label: String, n: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LockInColors.Ink)
            .clickable(enabled = n > 0, role = Role.Button, onClickLabel = "Show the $label cards", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = LockInText.Small.copy(color = LockInColors.Body, fontSize = 12.sp), modifier = Modifier.padding(start = 6.dp))
        }
        Text("$n", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- the cards

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardList(
    store: StudyStore,
    pool: List<Card>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    sort: Int,
    onSort: (Int) -> Unit,
    shown: Int,
    onMore: () -> Unit,
    openCard: String,
    onOpenCard: (String) -> Unit,
    hiddenId: String,
    onDelete: (String) -> Unit,
    onUndo: () -> Unit,
    onPractise: (List<Card>) -> Unit,
    onEdit: (String) -> Unit,
    onSimplify: (String) -> Unit,
    onOpenPage: (String, Int) -> Unit,
) {
    val visible = pool.filter { it.id != hiddenId }
    fun inFilter(c: Card, f: String) = when (f) {
        "due" -> store.isDue(c)
        "new" -> c.stage == Stage.New
        "learning" -> c.stage == Stage.Learning
        "learned" -> c.stage == Stage.Learned
        "tricky" -> c.tricky
        else -> true
    }
    val filtered = visible.filter { inFilter(it, filter) }
    val q = query.trim()
    val matches = if (q.isEmpty()) filtered else filtered.filter { it.question.contains(q, true) || it.answer.contains(q, true) }
    val ordered = when (sort) {
        1 -> matches.sortedWith(compareBy<Card>({ it.box }, { -it.wrong }))
        2 -> matches.sortedBy { it.due }
        else -> matches
    }

    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Your cards", style = LockInText.Label.copy(color = LockInColors.Silver), modifier = Modifier.weight(1f))
        if (open) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (searching) LockInColors.Graphite else LockInColors.Slate)
                    .clickable(role = Role.Button, onClickLabel = if (searching) "Close search" else "Search cards") { onSearching(!searching) },
                contentAlignment = Alignment.Center,
            ) { SearchGlass(if (searching) LockInColors.Lime else LockInColors.Silver) }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (open) "Hide" else "Show all ${visible.size}",
            style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp),
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onOpen(!open) }.padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
    AnimatedVisibility(hiddenId.isNotEmpty(), enter = expandVertically(), exit = shrinkVertically()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(LockInColors.Graphite).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Card deleted.", style = LockInText.Small.copy(color = LockInColors.Body), modifier = Modifier.weight(1f))
            Text(
                "Undo",
                style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (open) {
            AnimatedVisibility(searching, enter = expandVertically(), exit = shrinkVertically()) {
                StudyField(query, { onQuery(it.take(60)) }, "Search questions and answers")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "due" to "Due", "new" to "New", "learning" to "Learning", "learned" to "Learned", "tricky" to "Tricky").forEach { (k, l) ->
                    val n = visible.count { inFilter(it, k) }
                    if (k == "all" || n > 0 || filter == k) Chip("$l $n", filter == k, true) { onFilter(k) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sortName = listOf("Newest", "Weakest", "Due first")[sort]
                Text(
                    "Sort: $sortName ▾",
                    style = LockInText.Small.copy(color = LockInColors.Silver, fontSize = 13.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Change the order") { onSort((sort + 1) % 3) }.padding(horizontal = 6.dp, vertical = 10.dp),
                )
                Spacer(Modifier.weight(1f))
                if (filter != "all" && ordered.isNotEmpty()) {
                    Text(
                        "Practise these ${ordered.size}",
                        style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 13.sp),
                        modifier = Modifier.clip(CircleShape).background(LockInColors.Lime).clickable(role = Role.Button) { onPractise(ordered.take(50).shuffled()) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val rows = if (open) ordered.take(shown) else ordered.take(3)
        rows.forEach { c ->
            CardRow(
                card = c,
                store = store,
                open = openCard == c.id,
                onToggle = { onOpenCard(if (openCard == c.id) "" else c.id) },
                onOpenPage = { onOpenPage(c.book, c.page) },
                onEdit = { onEdit(c.id) },
                onSimplify = { onSimplify(c.id) },
                onDelete = { onDelete(c.id) },
            )
        }
        if (open && ordered.size > shown) {
            Text(
                "Show ${minOf(40, ordered.size - shown)} more",
                style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = onMore).padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
        if (open && matches.isEmpty()) {
            Text(if (q.isNotEmpty()) "No cards match \"$q\"." else "No cards here.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        if (open) Text("Tip: swipe a card left to delete it, or hold it for more.", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 12.sp), modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

@Composable
private fun SearchGlass(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val k = size.width / 24f
        drawCircle(color, 6.5f * k, Offset(10.5f * k, 10.5f * k), style = Stroke(2.2f * k))
        drawLine(color, Offset(15.5f * k, 15.5f * k), Offset(20.5f * k, 20.5f * k), strokeWidth = 2.2f * k, cap = StrokeCap.Round)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    card: Card,
    store: StudyStore,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenPage: () -> Unit,
    onEdit: () -> Unit,
    onSimplify: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 120.dp.toPx() }
    Box(Modifier.fillMaxWidth()) {
        // Under the card as it slides: what letting go will do.
        Row(
            Modifier.matchParentSize().clip(shape).background(if (-drag.value > limit) LockInColors.Flare else LockInColors.Graphite).padding(end = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (drag.value < -4f) Text("Delete", style = LockInText.Label.copy(color = if (-drag.value > limit) androidx.compose.ui.graphics.Color.White else LockInColors.Silver, fontSize = 13.sp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .graphicsLayer { translationX = drag.value }
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.dp, if (card.tricky) Amber.copy(alpha = 0.6f) else LockInColors.Graphite, shape)
                .pointerInput(card.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (-drag.value > limit) {
                                scope.launch { drag.animateTo(-size.width.toFloat(), spring(dampingRatio = 1f, stiffness = 600f)); onDelete(); drag.snapTo(0f) }
                            } else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) } },
                    ) { change, amount ->
                        val next = (drag.value + amount).coerceIn(-size.width.toFloat(), 0f)
                        if (next != drag.value) change.consume()
                        scope.launch { drag.snapTo(next) }
                    }
                }
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (open) "Close this card" else "Show the answer",
                    onLongClickLabel = "More for this card",
                    onLongClick = { if (!open) onToggle() },
                    onClick = onToggle,
                ),
        ) {
            // The book's spine.
            Box(Modifier.width(5.dp).fillMaxHeight().background(bookTint(card.book)))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(card.question, style = LockInText.Sub.copy(color = LockInColors.Snow), maxLines = if (open) 10 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (card.image.isNotEmpty()) Text("🖼", style = LockInText.Small.copy(fontSize = 13.sp), modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StrengthDots(card.box)
                    Text(
                        listOfNotNull(
                            when {
                                card.stage == Stage.New -> "new"
                                store.isDue(card) -> "due now"
                                card.stage == Stage.Learned -> "learned · back ${dueWords(store, card.due)}"
                                else -> "back ${dueWords(store, card.due)}"
                            },
                            if (card.page > 0) "p. ${card.page}" else null,
                        ).joinToString(" · "),
                        style = LockInText.Small.copy(color = if (store.isDue(card) && card.stage != Stage.New) LockInColors.Lime else LockInColors.Mist, fontSize = 12.sp),
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (card.tricky) {
                        Text("tricky", style = LockInText.Small.copy(color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.clip(CircleShape).background(Amber.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        if (card.image.isNotEmpty()) {
                            CardImage(store, card.image, Modifier.fillMaxWidth().heightIn(max = 180.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(card.answer, style = LockInText.Sub.copy(color = LockInColors.Lime))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(store.book(card.book)?.title?.let { prettyTitle(it) }, "${card.right} right, ${card.wrong} missed").joinToString(" · "),
                            style = LockInText.Small.copy(color = LockInColors.Mist),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (card.book.isNotEmpty() && card.page > 0) RowAction("Open page", LockInColors.Snow, onOpenPage)
                            RowAction("Edit", LockInColors.Snow, onEdit)
                            RowAction("✦ Simplify", LockInColors.Lime, onSimplify)
                            RowAction("Delete", LockInColors.Flare, onDelete)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = LockInText.Label.copy(color = color, fontSize = 13.sp),
        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

// ---------------------------------------------------------------- reminder

@Composable
private fun ReminderRow() {
    val context = LocalContext.current
    var minute by remember { mutableIntStateOf(QuizReminder.minute(context)) }
    var blocked by remember { mutableStateOf(false) }
    val times = listOf(8 * 60 to "8 am", 12 * 60 to "12 pm", 18 * 60 to "6 pm", 21 * 60 to "9 pm")
    val label = times.firstOrNull { it.first == minute }?.second ?: "6 pm"
    val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        blocked = !ok
        if (ok) { minute = 18 * 60; QuizReminder.set(context, minute) }
    }
    fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(minute) { if (minute >= 0) blocked = !allowed() }

    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(LockInColors.Slate).border(1.dp, LockInColors.Graphite, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Bell, if (minute >= 0) LockInColors.Lime else LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Daily reminder", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                Text(if (minute >= 0) "At $label, only on days with cards due" else "Off", style = LockInText.Small.copy(color = LockInColors.Mist))
            }
            if (minute >= 0) {
                Text(
                    "$label ▾",
                    style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(LockInColors.Graphite)
                        .clickable(role = Role.Button, onClickLabel = "Change the time") {
                            val i = times.indexOfFirst { it.first == minute }
                            minute = times[(i + 1).mod(times.size)].first
                            QuizReminder.set(context, minute)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            MiniSwitch(minute >= 0) { on ->
                if (on) {
                    if (allowed()) { minute = 18 * 60; QuizReminder.set(context, minute) }
                    else asker.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else { minute = -1; QuizReminder.set(context, -1); blocked = false }
            }
        }
        AnimatedVisibility(blocked, enter = expandVertically(), exit = shrinkVertically()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notifications are off for LockIn, so the reminder can't show.", style = LockInText.Small.copy(color = LockInColors.Flare), modifier = Modifier.weight(1f))
                Text(
                    "Turn on",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "switch")
    val track = if (on) LockInColors.Lime else LockInColors.Rim
    val thumb = if (on) LockInColors.OnLime else LockInColors.Silver
    Box(
        Modifier
            .width(52.dp)
            .height(32.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(role = Role.Switch, onClickLabel = if (on) "Turn off" else "Turn on") { onChange(!on) }
            .semantics { selected = on }
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = (20 * x).dp).size(24.dp).clip(CircleShape).background(thumb))
    }
}

// ---------------------------------------------------------------- menu, import, share

@Composable
private fun MenuSheet(partnerName: String?, canChallenge: Boolean, onMake: () -> Unit, onImport: () -> Unit, onShare: () -> Unit, onChallenge: () -> Unit) {
    SheetTitle("More")
    MenuItem(Glyph.Plus, "Make a card", "A question and its answer.", onMake)
    MenuItem(Glyph.Download, "Import cards", "From a CSV file (a spreadsheet).", onImport)
    MenuItem(Glyph.Share, "Share cards", "Send all of them, or one book's, as a file.", onShare)
    if (partnerName != null) {
        MenuItem(
            Glyph.People,
            "Challenge $partnerName",
            if (canChallenge) "Send them 5 of your cards as a quick quiz. You'll see their score in the chat." else "Needs at least 4 cards here.",
            onChallenge,
            enabled = canChallenge,
        )
    }
}

@Composable
private fun MenuItem(glyph: Glyph, title: String, sub: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(LockInColors.Graphite), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, if (enabled) LockInColors.Snow else LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = LockInText.Label.copy(color = if (enabled) LockInColors.Snow else LockInColors.Mist, fontSize = 16.sp))
            Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist))
        }
    }
}

@Composable
private fun ImportSheet(store: StudyStore, intoBook: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var failed by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(intoBook) }
    var added by remember { mutableIntStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val p = previewImport(context, store, uri)
            failed = p == null
            preview = p
            added = -1
        }
    }
    val p = preview
    when {
        added >= 0 -> {
            SheetTitle(if (added == 0) "Nothing new to add" else "Added ${plural(added, "card")}", "They're in your deck, ready to review.")
            LockInButton("Done", onDone)
        }
        p == null -> {
            SheetTitle("Import cards", "From a spreadsheet saved as CSV.")
            Text("Two columns: the question, then the answer. A first row of headings is fine. Files shared from LockIn work too, books and pages included.", style = LockInText.Sub.copy(color = LockInColors.Body))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LockInColors.Ink).padding(14.dp)) {
                Text("question,answer", style = LockInText.Small.copy(color = LockInColors.Mist, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                Text("What does RAM stand for?,Random access memory", style = LockInText.Small.copy(color = LockInColors.Snow, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                Text("Unit of force?,The newton", style = LockInText.Small.copy(color = LockInColors.Snow, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            }
            if (failed) Text("Couldn't read that file. Is it a CSV?", style = LockInText.Small.copy(color = LockInColors.Flare), modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            Spacer(Modifier.height(16.dp))
            LockInButton("Choose a CSV file", { picker.launch("*/*") })
        }
        else -> {
            SheetTitle(
                if (p.rows.isEmpty()) "No new cards in that file" else "${plural(p.rows.size, "card")} to add",
                if (p.dupes > 0) "${plural(p.dupes, "card")} you already have will be skipped." else null,
            )
            p.rows.take(3).forEach { r ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(LockInColors.Graphite).padding(12.dp)) {
                    Text(r.question, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.answer, style = LockInText.Small.copy(color = LockInColors.Lime), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (p.rows.size > 3) Text("and ${p.rows.size - 3} more", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            if (p.rows.isNotEmpty() && p.rows.any { it.bookTitle.isEmpty() } && store.books.isNotEmpty()) {
                SheetLabel("Put them in")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("No book", target.isEmpty(), true) { target = "" }
                    store.books.forEach { b -> Chip(prettyTitle(b.title), target == b.id, true) { target = b.id } }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Another file", { picker.launch("*/*") }, Modifier.weight(1f), kind = ButtonKind.Outline)
                LockInButton(if (p.rows.isEmpty()) "Done" else "Add ${p.rows.size}", {
                    if (p.rows.isEmpty()) onDone() else added = commitImport(store, p, target)
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShareSheet(store: StudyStore, picked: Textbook?, onDone: () -> Unit) {
    val context = LocalContext.current
    SheetTitle("Share cards", "As a CSV file anyone can open, or import into LockIn.")
    MenuItem(Glyph.Share, "All ${plural(store.cards.size, "card")}", "Every book.", { exportCards(context, store, null); onDone() })
    if (picked != null) {
        val n = store.cards.count { it.book == picked.id }
        MenuItem(Glyph.Book, prettyTitle(picked.title), plural(n, "card") + " from this book.", { exportCards(context, store, picked.id); onDone() }, enabled = n > 0)
    }
}
