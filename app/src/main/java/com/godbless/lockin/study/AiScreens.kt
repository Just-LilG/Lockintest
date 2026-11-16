package com.godbless.lockin.study

import com.godbless.lockin.ui.calmFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.AiCard
import com.godbless.lockin.data.AiExplanation
import com.godbless.lockin.data.AiQuestion
import com.godbless.lockin.data.AiRepository
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** The AI helper, provided by Home (null where there's no signed-in session). */
internal val LocalAi = staticCompositionLocalOf<AiRepository?> { null }

private val AiCardShape = RoundedCornerShape(24.dp)

// =============================================================================================
// "Reading your pages…": the wait, made pleasant
// =============================================================================================

/** While the AI reads: a little stack of pages that flip one after another, and what it's doing. */
@Composable
internal fun Thinking(line: String, pages: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    val t by rememberInfiniteTransition(label = "ai").calmFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "flip")
    Column(modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 120.dp, height = if (compact) 72.dp else 96.dp).graphicsLayer { if (compact) { scaleX = 0.72f; scaleY = 0.72f } },
            contentAlignment = Alignment.Center,
        ) {
            for (i in 0 until 3) {
                val phase = ((t * 3f - i) % 3f + 3f) % 3f
                val lift = if (phase < 1f) sin(phase * PI.toFloat()) else 0f
                Box(
                    Modifier
                        .size(width = 64.dp, height = 84.dp)
                        .graphicsLayer {
                            translationX = (i - 1) * 18.dp.toPx()
                            translationY = -lift * 14.dp.toPx()
                            rotationZ = (i - 1) * 6f + lift * 8f
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (lift > 0.1f) LockInColors.Lime else LockInColors.Snow)
                        .padding(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(5) { r ->
                            Box(Modifier.fillMaxWidth(if (r == 4) 0.6f else 1f).height(3.dp).clip(CircleShape).background(LockInColors.Ink.copy(alpha = 0.25f)))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 22.dp))
        Text(line, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (pages == 1) "Reading 1 page. This takes a few seconds." else "Reading $pages pages. This takes a few seconds.",
            style = LockInText.Small.copy(color = LockInColors.Mist),
            textAlign = TextAlign.Center,
        )
        if (compact) {
            // The shape of the answer to come: a summary and a few points, shimmering until it lands.
            Spacer(Modifier.height(18.dp))
            val x by rememberInfiniteTransition(label = "shimmer").calmFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
            val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(LockInColors.Graphite, LockInColors.Rim, LockInColors.Graphite),
                start = androidx.compose.ui.geometry.Offset(x * 700f, 0f),
                end = androidx.compose.ui.geometry.Offset(x * 700f + 500f, 0f),
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1f, 0.92f, 0.6f).forEach { f -> Box(Modifier.fillMaxWidth(f).height(14.dp).clip(CircleShape).background(brush)) }
                Spacer(Modifier.height(4.dp))
                listOf(0.85f, 0.7f, 0.78f).forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(brush))
                        Box(Modifier.padding(start = 10.dp).fillMaxWidth(f).height(12.dp).clip(CircleShape).background(brush))
                    }
                }
            }
        }
    }
}

@Composable
internal fun Problem(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = LockInText.Sub.copy(color = LockInColors.Body), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        LockInButton("Try again", onClick = onRetry, kind = ButtonKind.Outline)
    }
}

// =============================================================================================
// AI quiz
// =============================================================================================

/**
 * A quiz the AI writes on the pages you read: one question at a time, four answers. Pick one and it lights up
 * right (lime) or wrong (coral) with a short "why" and the page it comes from. At the end, your score, and the
 * ones you missed can become flashcards.
 *
 * [source] is (book id, page index from 0); [title] says where it came from ("Today's reading").
 */
@Composable
internal fun AiQuizScreen(
    store: StudyStore,
    source: List<Pair<String, Int>>,
    title: String,
    onBack: () -> Unit,
    onOpenPage: (bookId: String, page: Int) -> Unit,
) {
    val ai = LocalAi.current
    val scope = rememberCoroutineScope()
    var questions by remember { mutableStateOf<List<AiQuestion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val missed = remember { mutableStateListOf<AiQuestion>() }
    var right by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var entryId by remember { mutableStateOf("") }

    // Which book a question's page belongs to (the first source page with that number).
    fun bookFor(page: Int): String = source.firstOrNull { it.second == page - 1 }?.first ?: source.firstOrNull()?.first.orEmpty()

    LaunchedEffect(attempt) {
        questions = null
        error = null
        index = 0
        picked = null
        missed.clear()
        right = 0
        saved = false
        if (ai == null) { error = "Sign in to use the AI helper."; return@LaunchedEffect }
        if (source.isEmpty()) { error = "Read a few pages in a textbook first. Pages count once they've been on screen for a few seconds."; return@LaunchedEffect }
        val pages = pagesForAi(store, source)
        ai.quiz(pages, count = if (pages.size >= 6) 8 else 6)
            .onSuccess {
                questions = it
                val first = source.firstOrNull()?.first.orEmpty()
                entryId = store.ai.logQuiz(first, store.book(first)?.title.orEmpty(), source.map { p -> p.second + 1 }, it)
            }
            .onFailure { error = it.message ?: "The AI couldn't make a quiz. Try again." }
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(top = topPad)
            .navigationBarsPadding()
            .readable(Widths.Page)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            AiBadge()
        }
        Spacer(Modifier.height(14.dp))
        Text("AI quiz", style = LockInText.Headline.copy(fontSize = 38.sp))
        Text(title, style = LockInText.Sub.copy(color = LockInColors.Silver))
        Spacer(Modifier.height(18.dp))

        val qs = questions
        when {
            error != null -> Problem(error!!) { attempt++ }
            qs == null -> Thinking("Writing your quiz", source.size)
            index >= qs.size -> {
                // ---- Done ----
                val pct = if (qs.isEmpty()) 0f else right / qs.size.toFloat()
                // Your score goes onto the saved quiz.
                LaunchedEffect(Unit) { if (entryId.isNotEmpty()) store.ai.setQuizScore(entryId, right, qs.size) }
                val ring = remember { Animatable(0f) }
                LaunchedEffect(Unit) { ring.animateTo(pct, spring(dampingRatio = 0.7f, stiffness = 60f)) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sw = 12.dp.toPx()
                            drawArc(LockInColors.Graphite, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                            drawArc(LockInColors.Lime, -90f, 360f * ring.value, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$right/${qs.size}", style = LockInText.Headline.copy(fontSize = 40.sp))
                            Text("right", style = LockInText.Small.copy(color = LockInColors.Mist))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when {
                            pct >= 0.99f -> "Perfect. You really read those pages."
                            pct >= 0.7f -> "Strong. A couple to look at again."
                            pct >= 0.4f -> "Getting there. Go back over the ones you missed."
                            else -> "Worth another read of those pages."
                        },
                        style = LockInText.Sub.copy(color = LockInColors.Body),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    if (missed.isNotEmpty()) {
                        LockInButton(
                            if (saved) "Saved as flashcards" else "Save the ${missed.size} I missed as flashcards",
                            onClick = {
                                if (!saved) {
                                    missed.forEach { q -> store.addCard(bookFor(q.page), q.question, q.options[q.answer], q.page) }
                                    saved = true
                                }
                            },
                            success = saved,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    LockInButton("New quiz", onClick = { attempt++ }, kind = ButtonKind.Outline)
                }
            }
            else -> {
                // ---- A question ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    qs.indices.forEach { i ->
                        val c by animateColorAsState(
                            when {
                                i < index -> LockInColors.Lime
                                i == index -> LockInColors.Snow
                                else -> LockInColors.Graphite
                            },
                            tween(200),
                            label = "dot",
                        )
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(c))
                    }
                }
                Spacer(Modifier.height(18.dp))
                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        (slideInHorizontally(tween(320, easing = UiGlide)) { it } togetherWith slideOutHorizontally(tween(280, easing = UiGlide)) { -it })
                            .using(SizeTransform(clip = false))
                    },
                    label = "question",
                ) { i ->
                    val q = qs[i]
                    Column(Modifier.fillMaxWidth()) {
                        Text("Question ${i + 1} of ${qs.size}", style = LockInText.Small.copy(color = LockInColors.Mist))
                        Spacer(Modifier.height(6.dp))
                        Text(q.question, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 29.sp))
                        Spacer(Modifier.height(16.dp))
                        q.options.forEachIndexed { o, option ->
                            Option(
                                letter = "ABCD"[o],
                                text = option,
                                state = when {
                                    picked == null -> 0
                                    o == q.answer -> 1
                                    o == picked -> 2
                                    else -> 3
                                },
                            ) {
                                if (picked == null) {
                                    picked = o
                                    if (o == q.answer) right++ else missed.add(q)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        // Why, once you've answered.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = picked != null,
                            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it / 2 },
                            exit = slideOutVertically(tween(150)) { it / 2 },
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(AiCardShape)
                                    .background(LockInColors.Slate)
                                    .padding(16.dp),
                            ) {
                                Text(
                                    if (picked == q.answer) "Right" else "Not quite",
                                    style = LockInText.Label.copy(color = if (picked == q.answer) LockInColors.Lime else LockInColors.Flare),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(q.why, style = LockInText.Sub.copy(color = LockInColors.Body))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "See page ${q.page}",
                                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onOpenPage(bookFor(q.page), q.page - 1) }.padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (picked != null) {
                    LockInButton(if (index == qs.size - 1) "See my score" else "Next question", onClick = {
                        index++
                        picked = null
                    })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One answer. [state]: 0 not answered yet, 1 the right one, 2 your wrong pick, 3 another wrong one. */
@Composable
private fun Option(letter: Char, text: String, state: Int, onClick: () -> Unit) {
    val bg by animateColorAsState(
        when (state) {
            1 -> LockInColors.Lime
            2 -> LockInColors.Flare
            else -> LockInColors.Slate
        },
        tween(220),
        label = "option",
    )
    val pop by animateFloatAsState(if (state == 1 || state == 2) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    val fg = if (state == 1 || state == 2) LockInColors.Ink else if (state == 3) LockInColors.Mist else LockInColors.Snow
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .graphicsLayer { val k = 1f + 0.05f * pop * (1f - pop) * 4f; scaleX = k; scaleY = k }
            .clip(shape)
            .background(bg)
            .border(1.dp, if (state == 0) LockInColors.Graphite else bg, shape)
            .clickable(enabled = state == 0, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (state == 1 || state == 2) LockInColors.Ink.copy(alpha = 0.15f) else LockInColors.Graphite),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter.toString(), style = LockInText.Label.copy(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
        Text(text, style = LockInText.Sub.copy(color = fg, fontSize = 16.sp, lineHeight = 21.sp), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun AiBadge() {
    Row(
        Modifier.clip(CircleShape).background(LockInColors.LimeWash).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✦", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp))
        Text("AI", style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp), modifier = Modifier.padding(start = 6.dp))
    }
}

// =============================================================================================
// In the reader: the AI sheet (explain this page / make cards / quiz me)
// =============================================================================================

private enum class AiTask { Menu, Explain, Cards, History }

/**
 * Slides up over the page you're reading. Starts with three choices; Explain and Cards happen right here,
 * Quiz opens the full quiz.
 */
@Composable
internal fun ReaderAiSheet(
    store: StudyStore,
    bookId: String,
    page: Int,
    drag: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    onQuiz: () -> Unit,
    onClose: () -> Unit,
    /** Jumps the reader to a page (from the history), counted from 0. */
    onGoToPage: (Int) -> Unit = {},
    /** Puts the reader in select mode, to pick a word or sentence to explain. */
    onSelect: (() -> Unit)? = null,
) {
    var task by remember { mutableStateOf(AiTask.Menu) }
    // While the sheet is open, get ready: wake the AI up and prepare the pictures of this page first (Explain and
    // Cards use it), then, quietly, the other pages a quiz would use. Whatever is ready makes the answer that much sooner.
    val readyAi = LocalAi.current
    LaunchedEffect(bookId, page) {
        readyAi?.warm()
        runCatching { pagesForAi(store, listOf(bookId to page)) }
        kotlinx.coroutines.delay(500)
        val others = store.reads.filter { it.book == bookId }.map { it.page }.distinct().filter { it != page }.takeLast(11)
        for (p in others) {
            runCatching { pagesForAi(store, listOf(bookId to p)) }
            kotlinx.coroutines.delay(150)
        }
    }
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(0, drag.value.toInt().coerceAtLeast(0)) }
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        // Pull the header down to put the sheet away.
        Column(
            Modifier.draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = androidx.compose.foundation.gestures.rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (drag.value > with(density) { 120.dp.toPx() } || v > 1400f) onClose()
                    else drag.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                },
            ),
        ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(LockInColors.Rim),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiBadge()
            Text(
                when (task) {
                    AiTask.Menu -> "Page ${page + 1}"
                    AiTask.Explain -> "Explaining page ${page + 1}"
                    AiTask.Cards -> "Flashcards from page ${page + 1}"
                    AiTask.History -> "Your AI history"
                },
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f) }
        }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = task,
            transitionSpec = {
                (slideInHorizontally(tween(300, easing = UiGlide)) { it / 2 } togetherWith slideOutHorizontally(tween(240, easing = UiGlide)) { -it / 2 })
                    .using(SizeTransform(clip = true))
            },
            label = "ai-task",
        ) { t ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (t) {
                    AiTask.Menu -> {
                        val savedHere = store.ai.explanation(bookId, page + 1, false) ?: store.ai.explanation(bookId, page + 1, true)
                        MenuRow(
                            "Explain this page",
                            if (savedHere != null) "Saved ${agoLabel(savedHere.at)}. Opens instantly." else "The key ideas in plain words, with an example.",
                        ) { task = AiTask.Explain }
                        if (onSelect != null) {
                            MenuRow("Explain a word or sentence", "Pick it out on the page. Explained the way this book uses it.") { onSelect() }
                        }
                        MenuRow("Quiz me on what I've read", "Questions on the pages you read in this book.") { onQuiz() }
                        MenuRow("Make flashcards", "Cards for the important facts on this page.") { task = AiTask.Cards }
                        val saved = store.ai.countFor(bookId)
                        if (saved > 0) MenuRow("Your AI history", if (saved == 1) "1 saved for this book." else "$saved saved for this book.") { task = AiTask.History }
                    }
                    AiTask.History -> Column {
                        AiHistoryList(store, bookId, page + 1) { _, p -> onGoToPage(p); onClose() }
                        Spacer(Modifier.height(8.dp))
                    }
                    AiTask.Explain -> ExplainPanel(store, bookId, page)
                    AiTask.Cards -> CardsPanel(store, bookId, listOf(bookId to page), onDone = onClose)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
            Text(sub, style = LockInText.Small.copy(color = LockInColors.Mist))
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Lime, boxSize = 14.dp, strokeWidth = 2.2f)
    }
}

@Composable
private fun ExplainPanel(store: StudyStore, bookId: String, page: Int) {
    val ai = LocalAi.current
    var simpler by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var redo by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiExplanation?>(null) }
    // The saved copy being shown, if this explanation came from the history rather than from the AI just now.
    var savedAt by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(simpler, attempt) {
        result = null
        error = null
        savedAt = 0L
        // A page you already had explained opens straight away, without asking the AI again.
        val hit = if (redo) null else store.ai.explanation(bookId, page + 1, simpler)
        if (hit != null) {
            result = hit.explanation()
            savedAt = hit.at
            return@LaunchedEffect
        }
        redo = false
        if (ai == null) { error = "Sign in to use the AI helper."; return@LaunchedEffect }
        val p = pagesForAi(store, listOf(bookId to page)).firstOrNull()
        if (p == null) { error = "Couldn't read that page."; return@LaunchedEffect }
        ai.explain(p, simpler)
            .onSuccess {
                result = it
                store.ai.logExplain(bookId, store.book(bookId)?.title.orEmpty(), page + 1, simpler, it)
            }
            .onFailure { error = it.message }
    }
    val r = result
    when {
        error != null -> Problem(error!!) { attempt++ }
        r == null -> Thinking(if (simpler) "Saying it more simply" else "Reading the page", 1, compact = true)
        else -> Column {
            if (savedAt > 0L) {
                Text("Saved ${agoLabel(savedAt)}", style = LockInText.Small.copy(color = LockInColors.Lime))
                Spacer(Modifier.height(8.dp))
            }
            ExplainBody(r)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    if (!simpler) LockInButton("Explain it more simply", onClick = { simpler = true }, kind = ButtonKind.Outline)
                    else LockInButton("Show the first version", onClick = { simpler = false }, kind = ButtonKind.Outline)
                }
                // Only for a saved one: asking again costs an AI call, so it's a deliberate choice.
                if (savedAt > 0L) Box(Modifier.weight(1f)) {
                    LockInButton("Ask again", onClick = { redo = true; attempt++ }, kind = ButtonKind.Outline)
                }
            }
        }
    }
}

/** Flashcards the AI made: all ticked to start; untick any you don't want, then add them. */
@Composable
internal fun CardsPanel(store: StudyStore, bookId: String, source: List<Pair<String, Int>>, onDone: () -> Unit) {
    val ai = LocalAi.current
    var attempt by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<List<AiCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keep = remember { mutableStateListOf<Boolean>() }
    var added by remember { mutableIntStateOf(0) }
    var entryId by remember { mutableStateOf("") }
    LaunchedEffect(attempt) {
        cards = null
        error = null
        if (ai == null) { error = "Sign in to use the AI helper."; return@LaunchedEffect }
        ai.cards(pagesForAi(store, source)).onSuccess {
            cards = it
            keep.clear()
            keep.addAll(it.map { true })
            // Kept in the history at once: close the sheet without adding and the set is still there.
            entryId = store.ai.logCards(bookId, store.book(bookId)?.title.orEmpty(), source.map { p -> p.second + 1 }, it)
        }.onFailure { error = it.message }
    }
    val cs = cards
    when {
        error != null -> Problem(error!!) { attempt++ }
        cs == null -> Thinking("Making flashcards", source.size, compact = source.size == 1)
        added > 0 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Added $added flashcards", style = LockInText.Title.copy(fontSize = 20.sp))
            Text("They're in Quiz, ready to review.", style = LockInText.Small.copy(color = LockInColors.Mist))
            Spacer(Modifier.height(14.dp))
            LockInButton("Done", onClick = onDone)
        }
        else -> Column {
            cs.forEachIndexed { i, c ->
                val on = keep.getOrElse(i) { true }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) LockInColors.Graphite else LockInColors.Ink)
                        .clickable(role = Role.Checkbox) { if (i < keep.size) keep[i] = !on }
                        .padding(14.dp),
                ) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) LockInColors.Lime else LockInColors.Graphite),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("✓", style = LockInText.Small.copy(color = LockInColors.OnLime, fontSize = 13.sp))
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(c.question, style = LockInText.Label.copy(color = if (on) LockInColors.Snow else LockInColors.Mist, fontSize = 15.sp))
                        Text(c.answer, style = LockInText.Small.copy(color = LockInColors.Body))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val n = keep.count { it }
            LockInButton(if (n == 0) "Pick at least one" else "Add $n flashcards", enabled = n > 0, onClick = {
                cs.forEachIndexed { i, c -> if (keep.getOrElse(i) { false }) store.addCard(bookId, c.question, c.answer, c.page) }
                added = n
                if (entryId.isNotEmpty()) store.ai.markCardsAdded(entryId, n)
            })
        }
    }
}

/** The AI's "working on it", for other study screens. */
@Composable
internal fun AiThinking(line: String) = Thinking(line, 1, compact = true)

/** The AI's "that didn't work" with a retry, for other study screens. */
@Composable
internal fun AiProblem(text: String, onRetry: () -> Unit) = Problem(text, onRetry)
