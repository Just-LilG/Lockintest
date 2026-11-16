package com.godbless.lockin.study

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.push.QuizReminder
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

private enum class Phase { Hub, Running, Result }

/**
 * Flashcards from the textbooks. Cards you miss come back sooner (see [StudyStore.grade]). A quiz in progress is
 * kept (as card ids) so it survives turning the phone, opening a page and coming back, or pausing.
 *
 * [partnerName] and [onSendEvent]: with a partner, a few cards can be sent to them as a quiz (a chat line), and
 * [challenge] is one of theirs to play (the chat line's code), opened from the chat.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun QuizScreen(
    store: StudyStore,
    onBack: () -> Unit,
    onOpenPage: (bookId: String, page: Int) -> Unit,
    partnerName: String? = null,
    onSendEvent: ((String) -> Unit)? = null,
    challenge: String? = null,
    onChallengeClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    var phase by rememberSaveable { mutableStateOf(Phase.Hub) }
    var aiQuiz by rememberSaveable { mutableStateOf(false) }
    var aiCards by rememberSaveable { mutableStateOf(false) }
    val pairs = remember { listSaver<List<Pair<String, Int>>, String>(save = { l -> l.map { it.first + "#" + it.second } }, restore = { l -> l.map { it.substringBeforeLast('#') to (it.substringAfterLast('#').toIntOrNull() ?: 0) } }) }
    var aiSrc by rememberSaveable(stateSaver = pairs) { mutableStateOf(emptyList<Pair<String, Int>>()) }
    var styleIx by rememberSaveable { mutableIntStateOf(0) }
    var bookFilter by rememberSaveable { mutableStateOf("") }
    var pageFrom by rememberSaveable { mutableStateOf("") }
    var pageTo by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableIntStateOf(10) } // 0 = only what is due
    var weakFirst by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf("") }
    var simplifyId by rememberSaveable { mutableStateOf("") }
    val ids = remember { listSaver<List<String>, String>(save = { it }, restore = { it }) }
    var queueIds by rememberSaveable(stateSaver = ids) { mutableStateOf(emptyList<String>()) }
    var missedIds by rememberSaveable(stateSaver = ids) { mutableStateOf(emptyList<String>()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var retryRound by rememberSaveable { mutableStateOf(false) }

    // A filter on a book that has since been removed falls back to all books.
    LaunchedEffect(bookFilter, store.books.size) { if (bookFilter.isNotEmpty() && store.book(bookFilter) == null) { bookFilter = ""; pageFrom = ""; pageTo = "" } }

    val from = pageFrom.toIntOrNull()
    val to = pageTo.toIntOrNull()
    val pool = store.cards.filter { c ->
        (bookFilter.isEmpty() || c.book == bookFilter) &&
            (from == null || (c.page > 0 && c.page >= from)) &&
            (to == null || (c.page > 0 && c.page <= to))
    }
    // Multiple choice takes its wrong options from the cards being quizzed, so it needs four different answers there.
    val canChoose = pool.map { it.answer.trim().lowercase() }.distinct().size >= 4
    val style = QuizStyle.entries[styleIx.coerceIn(0, QuizStyle.entries.size - 1)]
    val useStyle = if (style == QuizStyle.Choice && !canChoose) QuizStyle.Flash else style
    // The quiz in progress, looked up from the saved ids (a card deleted meanwhile just drops out).
    val queue = queueIds.mapNotNull { id -> store.cards.firstOrNull { it.id == id } }
    val missed = missedIds.mapNotNull { id -> store.cards.firstOrNull { it.id == id } }
    val paused = phase == Phase.Hub && queue.isNotEmpty() && index < queue.size
    // The deck the session's multiple choice borrows from: what was quizzed, or the whole book if that's too small.
    val choicePool = queue.ifEmpty { pool }.let { q -> if (q.map { it.answer }.distinct().size >= 4) q else pool }

    fun begin(cards: List<Card>, retry: Boolean) {
        queueIds = cards.map { it.id }
        missedIds = emptyList()
        index = 0
        startedAt = System.currentTimeMillis()
        retryRound = retry
        phase = Phase.Running
    }

    /** How many cards a new quiz would hold, and how many of those are due. */
    fun plan(): Pair<Int, Int> {
        val due = pool.count { store.isDue(it) }
        val n = when {
            size == 0 -> if (due > 0) due else minOf(20, pool.size)
            else -> minOf(size, pool.size)
        }
        return n to minOf(due, n)
    }

    fun start() {
        val due = pool.filter { store.isDue(it) }
        val weakest = compareBy<Card>({ it.box }, { -it.wrong })
        val limit = if (size == 0) Int.MAX_VALUE else size
        var chosen = (if (weakFirst) due.sortedWith(weakest) else due.shuffled()).take(limit)
        // Short of cards that are due? Top up with extra practice, the weakest and longest-unseen first.
        if (size != 0 && chosen.size < limit) {
            val extra = pool.filter { !store.isDue(it) }.sortedWith(compareBy<Card>({ it.box }, { it.last }))
            chosen = chosen + extra.take(limit - chosen.size)
        }
        if (chosen.isEmpty()) chosen = (if (weakFirst) pool.sortedWith(weakest) else pool.shuffled()).take(20)
        if (!weakFirst) chosen = chosen.shuffled()
        if (chosen.isNotEmpty()) begin(chosen, false)
    }

    fun reset() {
        queueIds = emptyList()
        missedIds = emptyList()
        index = 0
        phase = Phase.Hub
    }

    // A quiz that can't be shown (every card in it was deleted) goes back to the hub instead of a blank page.
    LaunchedEffect(phase, queue.isEmpty()) { if (phase != Phase.Hub && queue.isEmpty()) reset() }

    val played = remember(challenge) { challenge?.let { decodeChallenge(it) } }

    Box(Modifier.fillMaxSize().background(LockInColors.Ink).clipToBounds()) {
        if (aiQuiz) {
            AiQuizScreen(store, aiSrc, "On " + describeSource(store, aiSrc), onBack = { aiQuiz = false }, onOpenPage = onOpenPage)
        } else when (phase) {
            Phase.Hub -> QuizHub(
                store = store,
                pool = pool,
                bookFilter = bookFilter,
                onFilter = { bookFilter = it; pageFrom = ""; pageTo = "" },
                pageFrom = pageFrom,
                pageTo = pageTo,
                onPages = { f, t -> pageFrom = f; pageTo = t },
                style = style,
                canChoose = canChoose,
                onStyle = { styleIx = it.ordinal },
                size = size,
                onSize = { size = it },
                weakFirst = weakFirst,
                onWeak = { weakFirst = it },
                plan = plan(),
                paused = if (paused) queue.size - index else 0,
                onResume = { phase = Phase.Running },
                onStart = ::start,
                onPractise = { cards -> if (cards.isNotEmpty()) begin(cards, false) },
                onMake = { editId = ""; editing = true },
                onEdit = { editId = it; editing = true },
                onSimplify = { simplifyId = it },
                onAi = { src -> aiSrc = src; aiQuiz = true },
                onAiCards = { src -> aiSrc = src; aiCards = true },
                onBack = onBack,
                onOpenPage = onOpenPage,
                partnerName = partnerName,
                onChallenge = onSendEvent,
            )
            Phase.Running -> Session(
                store = store,
                queue = queue,
                index = index,
                onIndex = { index = it },
                style = useStyle,
                choicePool = choicePool,
                onMissed = { missedIds = missedIds + it },
                onUnmissed = { id -> missedIds = missedIds - id },
                onFinish = {
                    store.logRun(queue.size, queue.size - missedIds.size, ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(1))
                    phase = Phase.Result
                },
                onPause = { phase = Phase.Hub },
                onOpenPage = onOpenPage,
            )
            Phase.Result -> QuizResult(
                store = store,
                total = queue.size,
                missed = missed,
                seconds = store.runs.lastOrNull()?.seconds ?: 0,
                onDone = ::reset,
                onAgain = ::start,
                onRetry = { begin(missed, true) },
                onOpenPage = onOpenPage,
            )
        }
        AnimatedVisibility(
            visible = editing,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            CardEditor(
                store = store,
                startBook = bookFilter,
                startPage = 0,
                edit = store.cards.firstOrNull { it.id == editId },
                onClose = { editing = false },
            )
        }
        AnimatedVisibility(
            visible = aiCards,
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AiCardsSheet(store, aiSrc, onClose = { aiCards = false })
        }
        HubSheet(simplifyId.isNotEmpty(), { simplifyId = "" }) {
            SimplifySheet(store, simplifyId, onEdit = { editId = simplifyId; simplifyId = ""; editing = true }, onClose = { simplifyId = "" })
        }
        // Your partner's quiz, from the chat, over everything.
        AnimatedVisibility(
            visible = played != null,
            enter = slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it },
            exit = slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { it },
        ) {
            val c = played ?: Challenge("", emptyList())
            ChallengeScreen(
                challenge = c,
                from = partnerName ?: "Your partner",
                store = store,
                onScore = onSendEvent?.let { send -> { r: Int, n: Int -> send("quiz_score|$r|$n") } },
                onClose = onChallengeClosed,
            )
        }
        if (challenge != null && played == null) {
            LaunchedEffect(challenge) {
                android.widget.Toast.makeText(context, "That quiz couldn't be opened.", android.widget.Toast.LENGTH_SHORT).show()
                onChallengeClosed()
            }
        }
    }
}

/** The AI's flashcards for the pages you read, in a sheet over the hub. */
@Composable
private fun AiCardsSheet(store: StudyStore, source: List<Pair<String, Int>>, onClose: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiBadge()
            Text("Flashcards from your reading", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp), modifier = Modifier.weight(1f).padding(start = 12.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (source.isEmpty()) {
            Text("Read a few textbook pages first. Pages count once they've been on screen for a few seconds.", style = LockInText.Sub.copy(color = LockInColors.Silver))
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CardsPanel(store, source.first().first, source, onClose)
            }
        }
    }
}

// ---------------------------------------------------------------- session

private class Undo(val prev: Card, val index: Int, val wasMissed: Boolean)

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun Session(
    store: StudyStore,
    queue: List<Card>,
    index: Int,
    onIndex: (Int) -> Unit,
    style: QuizStyle,
    choicePool: List<Card>,
    onMissed: (String) -> Unit,
    onUnmissed: (String) -> Unit,
    onFinish: () -> Unit,
    onPause: () -> Unit,
    onOpenPage: (String, Int) -> Unit,
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    val haptic = LocalHapticFeedback.current
    val size = queue.size.coerceAtLeast(1)
    val shownIndex = index.coerceAtMost(size - 1)
    val progress by animateFloatAsState((shownIndex + 1f) / size, spring(dampingRatio = 0.9f, stiffness = 200f), label = "quiz progress")
    // The card already answered: a second tap on the same card (during its slide away) does nothing.
    var graded by remember { mutableIntStateOf(-1) }
    var undo by remember { mutableStateOf<Undo?>(null) }

    val rate: (Int, Card, Rating) -> Unit = { i, card, rating ->
        if (graded != i) {
            graded = i
            val before = store.grade(card.id, rating)
            if (rating == Rating.Missed) onMissed(card.id)
            undo = before?.let { Undo(it, i, rating == Rating.Missed) }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (i + 1 >= queue.size) onFinish() else onIndex(i + 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = topPad).navigationBarsPadding().readable(Widths.Page).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pause",
                style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Pause. Your place is kept.", onClick = onPause)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LockInColors.Graphite)
                    .semantics { contentDescription = "Card ${shownIndex + 1} of ${queue.size}" },
            ) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(LockInColors.Lime))
            }
            Text("${shownIndex + 1}/${queue.size}", style = LockInText.Small.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 12.dp))
            val u = undo
            if (u != null) {
                Text(
                    "Undo",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Undo the last answer") {
                            store.restore(u.prev)
                            if (u.wasMissed) onUnmissed(u.prev.id)
                            graded = -1
                            undo = null
                            onIndex(u.index)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = shownIndex,
            transitionSpec = {
                val back = targetState < initialState
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { if (back) -it else it } togetherWith
                    slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { if (back) it else -it }).using(SizeTransform(clip = false))
            },
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            label = "quiz card",
        ) { i ->
            val card = queue.getOrNull(i)
            if (card != null) {
                val remaining = queue.size - i - 1
                when (style) {
                    QuizStyle.Choice -> ChoiceCard(card, store, choicePool, remaining, { right -> rate(i, card, if (right) Rating.Good else Rating.Missed) }, onOpenPage)
                    QuizStyle.Type -> TypeCard(card, store, remaining, { r -> rate(i, card, r) }, onOpenPage)
                    QuizStyle.Flash -> FlipCard(card, store, remaining, { r -> rate(i, card, r) }, onOpenPage)
                }
            }
        }
    }
}

@Composable
private fun FlipCard(card: Card, store: StudyStore, remaining: Int, onRate: (Rating) -> Unit, onOpenPage: (String, Int) -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (flipped) 180f else 0f, tween(420), label = "flip")
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    val shape = RoundedCornerShape(28.dp)
    val edge = when {
        flipped && dragX.value > 40f -> LockInColors.Lime
        flipped && dragX.value < -40f -> LockInColors.Flare
        else -> LockInColors.Rim
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 20.dp)) {
            // The rest of the deck, peeking out under the card.
            if (remaining >= 2) DeckLayer(18, 0.86f, shape)
            if (remaining >= 1) DeckLayer(9, 0.93f, shape)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = turn
                        translationX = dragX.value
                        rotationZ = (dragX.value / threshold) * 6f
                        cameraDistance = 14f * density.density
                    }
                    .clip(shape)
                    .background(if (turn < 90f) LockInColors.Slate else LockInColors.Graphite)
                    .border(1.5.dp, edge, shape)
                    .clickable(enabled = !flipped, role = Role.Button, onClickLabel = "Show the answer") { flipped = true }
                    .pointerInput(flipped) {
                        if (flipped) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val x = dragX.value
                                    if (x > threshold) onRate(Rating.Good) else if (x < -threshold) onRate(Rating.Missed)
                                    scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
                                },
                                onDragCancel = { scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 300f)) } },
                            ) { change, amount ->
                                change.consume()
                                scope.launch { dragX.snapTo(dragX.value + amount) }
                            }
                        }
                    }
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (turn < 90f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("QUESTION", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, letterSpacing = 1.5.sp))
                        Spacer(Modifier.height(12.dp))
                        if (card.image.isNotEmpty()) {
                            CardImage(store, card.image, Modifier.fillMaxWidth().heightIn(max = 240.dp))
                            Spacer(Modifier.height(14.dp))
                        }
                        Text(card.question, style = LockInText.Title.copy(textAlign = TextAlign.Center, fontSize = 24.sp, lineHeight = 31.sp))
                        Spacer(Modifier.height(18.dp))
                        Text("Tap to see the answer", style = LockInText.Small.copy(color = LockInColors.Mist))
                    }
                } else {
                    // Mirrored back so the text reads the right way round once the card has turned.
                    Column(Modifier.graphicsLayer { rotationY = 180f }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ANSWER", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 11.sp, letterSpacing = 1.5.sp))
                        Spacer(Modifier.height(12.dp))
                        Text(card.answer, style = LockInText.Title.copy(color = LockInColors.Lime, textAlign = TextAlign.Center, fontSize = 24.sp, lineHeight = 31.sp))
                    }
                }
                if (card.page > 0) {
                    Text(
                        "p. ${card.page}",
                        style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp),
                        modifier = Modifier
                            .align(if (turn < 90f) Alignment.TopEnd else Alignment.TopStart)
                            .graphicsLayer { rotationY = if (turn < 90f) 0f else 180f },
                    )
                }
            }
        }
        PageLink(card, onOpenPage)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.heightIn(min = 56.dp)) {
            if (flipped) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LockInButton("Missed", { onRate(Rating.Missed) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                        LockInButton("Good", { onRate(Rating.Good) }, Modifier.weight(1f))
                        LockInButton("Easy", { onRate(Rating.Easy) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Or swipe the card: right for good, left for missed.", style = LockInText.Small.copy(color = LockInColors.Mist))
                }
            }
        }
    }
}

@Composable
private fun DeckLayer(offsetDp: Int, scaleX: Float, shape: RoundedCornerShape) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offsetDp.dp.toPx(); this.scaleX = scaleX }
            .clip(shape)
            .background(LockInColors.Graphite)
            .border(1.dp, LockInColors.Rim, shape),
    )
}

@Composable
private fun ChoiceCard(card: Card, store: StudyStore, deck: List<Card>, remaining: Int, onDone: (Boolean) -> Unit, onOpenPage: (String, Int) -> Unit) {
    // Wrong answers that look like the right one: from the cards being quizzed (the same subject), about as long.
    // Only when that deck is too small do other cards help out, the same book first.
    val options = remember(card.id) {
        val norm = { a: String -> a.trim().lowercase() }
        val near = deck.filter { it.id != card.id && norm(it.answer) != norm(card.answer) }.distinctBy { norm(it.answer) }
        val others = if (near.size >= 3) near else (near + store.cards.filter { it.id != card.id && norm(it.answer) != norm(card.answer) && it.book == card.book }).distinctBy { norm(it.answer) }
        fun score(c: Card) = (if (c.book == card.book) 0 else 1000) + abs(c.answer.length - card.answer.length) + Random.nextInt(0, 30)
        val wrong = others.sortedBy { score(it) }.take(3).map { it.answer }
        (wrong + card.answer).shuffled()
    }
    var picked by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(20.dp)
    val scroll = rememberScrollState()
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(picked) {
        val p = picked ?: return@LaunchedEffect
        if (p != card.answer) {
            shake.snapTo(16f)
            shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 700f))
        }
        delay(120)
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        if (card.image.isNotEmpty()) {
            CardImage(store, card.image, Modifier.fillMaxWidth().heightIn(max = 220.dp))
            Spacer(Modifier.height(14.dp))
        }
        Text(card.question, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 31.sp), modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { o ->
                val reveal = picked != null
                val isRight = o == card.answer
                val isPicked = picked == o
                val border = when {
                    reveal && isRight -> LockInColors.Lime
                    reveal && isPicked -> LockInColors.Flare
                    else -> LockInColors.Graphite
                }
                val pulse by animateFloatAsState(if (reveal && isRight) 1.03f else 1f, spring(dampingRatio = 0.4f, stiffness = 500f), label = "pulse")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            translationX = if (isPicked && !isRight) shake.value * density.density else 0f
                        }
                        .clip(shape)
                        .background(if (reveal && isRight) LockInColors.LimeWash else LockInColors.Slate)
                        .border(1.5.dp, border, shape)
                        .clickable(enabled = !reveal, role = Role.Button) { picked = o }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o, style = LockInText.Sub.copy(color = LockInColors.Snow), modifier = Modifier.weight(1f))
                    // A mark as well as a colour, so right and wrong don't rest on colour alone.
                    if (reveal && isRight) Text("✓", style = LockInText.Title.copy(color = LockInColors.Lime, fontSize = 20.sp), modifier = Modifier.padding(start = 10.dp))
                    else if (reveal && isPicked) Text("✗", style = LockInText.Title.copy(color = LockInColors.Flare, fontSize = 20.sp), modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PageLink(card, onOpenPage)
        Spacer(Modifier.height(10.dp))
        if (picked != null) {
            if (picked != card.answer) {
                Text("The answer is: ${card.answer}", style = LockInText.Small.copy(color = LockInColors.Lime), modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
            }
            LockInButton(if (remaining == 0) "Finish" else "Next", { onDone(picked == card.answer) })
        }
    }
}

@Composable
private fun PageLink(card: Card, onOpenPage: (String, Int) -> Unit) {
    if (card.book.isEmpty() || card.page <= 0) return
    Text(
        "Open page ${card.page}",
        style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(role = Role.Button) { onOpenPage(card.book, card.page) }
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center,
    )
}

// ---------------------------------------------------------------- result

private fun clock(seconds: Int): String = if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

@Composable
private fun QuizResult(
    store: StudyStore,
    total: Int,
    missed: List<Card>,
    seconds: Int,
    onDone: () -> Unit,
    onAgain: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: (String, Int) -> Unit,
) {
    val right = total - missed.size
    val pct = if (total == 0) 0 else right * 100 / total
    // How this compares with the quiz before it.
    val runs = store.runs
    val before = if (runs.size >= 2) runs[runs.size - 2].let { if (it.total == 0) null else it.right * 100 / it.total } else null
    val streak = store.quizStreak()
    StudyScaffold(
        title = "$right of $total",
        subtitle = when {
            missed.isEmpty() -> "Every one right."
            right * 2 >= total -> "Good run. The misses come back sooner."
            else -> "Tough set. The misses come back sooner."
        },
        onBack = onDone,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Accuracy", "$pct%", before?.let { d -> (pct - d).let { if (it > 0) "+$it vs last time" else if (it < 0) "$it vs last time" else "Same as last time" } }, Modifier.weight(1f))
            StatTile("Time", clock(seconds), if (total > 0) "${clock((seconds / total).coerceAtLeast(1))} a card" else null, Modifier.weight(1f))
            StatTile("Streak", if (streak == 1) "1 day" else "$streak days", null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        if (missed.isNotEmpty()) {
            Text("Missed", style = LockInText.Label.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                missed.forEach { c ->
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(LockInColors.Slate).border(1.dp, LockInColors.Graphite, shape).padding(14.dp),
                    ) {
                        Text(c.question, style = LockInText.Sub.copy(color = LockInColors.Snow))
                        Spacer(Modifier.height(4.dp))
                        Text(c.answer, style = LockInText.Sub.copy(color = LockInColors.Lime))
                        if (c.book.isNotEmpty() && c.page > 0) {
                            Text(
                                "Open page ${c.page}",
                                style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp),
                                modifier = Modifier.clickable(role = Role.Button) { onOpenPage(c.book, c.page) }.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            LockInButton(if (missed.size == 1) "Retry the one you missed" else "Retry the ${missed.size} you missed", onRetry)
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Done", onDone, Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton("Go again", onAgain, Modifier.weight(1f), kind = if (missed.isNotEmpty()) ButtonKind.Outline else ButtonKind.Primary)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        if (note != null) Text(note, style = LockInText.Small.copy(color = LockInColors.Body, fontSize = 11.sp, lineHeight = 14.sp))
    }
}
