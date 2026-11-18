package com.godbless.lockin.study

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.AiPassage
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.roundToInt

// =============================================================================================
// Selecting part of a page
// =============================================================================================

/**
 * Part of a page you picked out with your finger: [page] counts from 0; [l], [t], [r], [b] are fractions of the
 * page's width and height (0 to 1), in any order while you drag (see [norm]).
 */
@Immutable
internal data class PageSel(val page: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
    fun norm() = PageSel(page, minOf(l, r).coerceIn(0f, 1f), minOf(t, b).coerceIn(0f, 1f), maxOf(l, r).coerceIn(0f, 1f), maxOf(t, b).coerceIn(0f, 1f))
    fun list() = listOf(l, t, r, b)
}

/** Half the width and height of the box a plain long-press picks: about one word. */
internal const val WordHalfW = 0.075f
internal const val WordHalfH = 0.014f

/** A word-sized box round a point. */
internal fun wordAt(page: Int, x: Float, y: Float) = PageSel(page, x - WordHalfW, y - WordHalfH, x + WordHalfW, y + WordHalfH).norm()

/** From the word you pressed to wherever your finger is now. */
internal fun spanTo(anchor: PageSel, x: Float, y: Float): PageSel =
    PageSel(anchor.page, minOf(anchor.l, x), minOf(anchor.t, y), maxOf(anchor.r, x), maxOf(anchor.b, y)).norm()

/**
 * Where a selection is on screen right now, in the reader's pixels, or null if its page is scrolled out of view.
 * [left] is where the page column starts (pages are centred on a wide screen) and [scrollX] the sideways scroll.
 */
internal fun selRect(sel: PageSel, list: LazyListState, scrollX: Int, left: Float, pageWidthPx: Float): Rect? {
    val info = list.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == sel.page } ?: return null
    val top = (item.offset - info.viewportStartOffset).toFloat()
    val x0 = left - scrollX + sel.l * pageWidthPx
    val x1 = left - scrollX + sel.r * pageWidthPx
    return Rect(x0, top + sel.t * item.size, x1, top + sel.b * item.size)
}

/** The highlight: a soft accent band over the words, a crisp edge, and a round handle at each end to adjust it. */
@Composable
internal fun SelectionOverlay(rect: () -> Rect?, showHandles: Boolean, modifier: Modifier = Modifier) {
    val accent = LockInColors.Lime
    val ink = LockInColors.Ink
    Canvas(modifier.fillMaxSize()) {
        val r = rect() ?: return@Canvas
        val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(accent.copy(alpha = 0.26f), topLeft = r.topLeft, size = r.size, cornerRadius = radius)
        drawRoundRect(accent, topLeft = r.topLeft, size = r.size, cornerRadius = radius, style = Stroke(1.5.dp.toPx()))
        if (showHandles) {
            val hr = 9.dp.toPx()
            for (c in listOf(r.topLeft, r.bottomRight)) {
                drawCircle(ink, hr + 2.dp.toPx(), c)
                drawCircle(accent, hr, c)
            }
        }
    }
}

/**
 * The little bar that floats over a selection: Explain (the main thing), Ask, and a cross to let go. It sits
 * above the selection, or below it when there is no room above, and stays on screen at the sides.
 */
@Composable
internal fun SelectionBar(
    rect: () -> Rect?,
    preparing: Boolean,
    onExplain: () -> Unit,
    onAsk: () -> Unit,
    onClear: () -> Unit,
    topLimit: Float,
) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { 14.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val shape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .onSizeChanged { size = it }
                .offset {
                    val r = rect() ?: return@offset IntOffset(-10_000, -10_000)
                    val x = (r.center.x - size.width / 2f).coerceIn(margin, (maxW - size.width - margin).coerceAtLeast(margin))
                    val above = r.top - gap - size.height
                    val y = if (above > topLimit) above else (r.bottom + gap).coerceAtMost(maxH - size.height - margin)
                    IntOffset(x.roundToInt(), y.roundToInt())
                }
                .height(48.dp)
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Rim, shape)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(LockInColors.Lime)
                    .clickable(enabled = !preparing, role = Role.Button, onClick = onExplain)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (preparing) "…" else "✦", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp))
                Text("Explain", style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp))
            }
            Box(
                Modifier.fillMaxHeight().clip(CircleShape).clickable(enabled = !preparing, role = Role.Button, onClick = onAsk).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Ask", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp)) }
            Box(Modifier.width(1.dp).height(20.dp).background(LockInColors.Graphite))
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 12.dp, strokeWidth = 2.2f) }
        }
    }
}

/** A pill at the top while select mode is on: what to do, and a way out. */
@Composable
internal fun SelectHint(text: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(LockInColors.Lime)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Select, LockInColors.OnLime, boxSize = 18.dp)
        Text(text, style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp), modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(LockInColors.OnLime.copy(alpha = 0.14f)).clickable(role = Role.Button, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Close, LockInColors.OnLime, boxSize = 11.dp, strokeWidth = 2.4f) }
    }
}

// =============================================================================================
// The explanation
// =============================================================================================

/** What the reader hands the sheet: the selection, its picture for the AI, and the same picture to show. */
internal class PassageArgs(val sel: PageSel, val snip: String, val picture: Bitmap, val ask: Boolean)

private class Turn(val ask: String, answer: String? = null, error: String? = null, val mode: String, val question: String) {
    var answer by mutableStateOf(answer)
    var error by mutableStateOf(error)
}

private val FollowUps = listOf(
    Triple("Simpler", "simpler", ""),
    Triple("Another example", "example", ""),
    Triple("Why does it matter here?", "ask", "Why does this matter on this page? How does it connect to what the page is teaching?"),
    Triple("Test me", "ask", "Ask me one short question to check I understood this, then give the answer on a new line starting with 'Answer:'."),
)

/**
 * Explains what you selected, in the terms of the book you're reading, and lets you keep asking about it.
 * On a phone it rises from the bottom; on a wide screen ([wide]) it is a panel down the right, so the page
 * stays readable beside it. Everything it says is saved to your AI history.
 */
@Composable
internal fun PassageSheet(
    store: StudyStore,
    bookId: String,
    args: PassageArgs,
    wide: Boolean,
    onClose: () -> Unit,
) {
    val ai = LocalAi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val book = store.book(bookId)
    val page = args.sel.page
    val picture: ImageBitmap = remember(args) { args.picture.asImageBitmap() }

    var first by remember(args) { mutableStateOf<AiPassage?>(null) }
    var loading by remember(args) { mutableStateOf(false) }
    var error by remember(args) { mutableStateOf<String?>(null) }
    val turns = remember(args) { mutableStateListOf<Turn>() }
    var historyId by remember(args) { mutableStateOf<String?>(null) }
    var question by remember(args) { mutableStateOf("") }
    var carded by remember(args) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val busy = loading || turns.any { it.answer == null && it.error == null }

    // What has been said so far, so a follow-up builds on it.
    fun prior(): String = buildString {
        first?.let { f ->
            if (f.meaning.isNotBlank()) append("Meaning: ").append(f.meaning).append(' ')
            if (f.context.isNotBlank()) append("In the book: ").append(f.context).append(' ')
            if (f.example.isNotBlank()) append("Example: ").append(f.example).append(' ')
        }
        turns.filter { it.answer != null }.takeLast(3).forEach { append("Q: ").append(it.ask).append(" A: ").append(it.answer).append(' ') }
    }

    suspend fun call(mode: String, q: String): Result<AiPassage> {
        val repo = ai ?: return Result.failure(IllegalStateException("The AI isn't available right now."))
        val pg = runCatching { pagesForAi(store, listOf(bookId to page)) }.getOrNull()?.firstOrNull()
            ?: return Result.failure(IllegalStateException("Couldn't read this page."))
        return repo.passage(pg, args.snip, mode, q, prior())
    }

    // Keep the picture beside the history entry, so it can be shown again later.
    suspend fun saveFirst(p: AiPassage) {
        val name = "s" + System.currentTimeMillis().toString(36) + ".jpg"
        withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(store.ai.snipFile(name)).use { args.picture.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        }
        historyId = store.ai.logPassage(bookId, book?.title ?: "", page + 1, p, args.sel.norm().list(), name)
    }

    fun explain() {
        loading = true
        error = null
        scope.launch {
            call("explain", "").onSuccess { p ->
                first = p
                saveFirst(p)
            }.onFailure { error = it.message ?: "Couldn't explain that. Try again." }
            loading = false
        }
    }

    fun follow(label: String, mode: String, q: String) {
        if (busy) return
        val t = Turn(label, mode = mode, question = q)
        turns.add(t)
        scope.launch {
            scroll.animateScrollTo(scroll.maxValue)
            call(mode, q).onSuccess { p ->
                val text = when (mode) {
                    "simpler" -> listOf(p.meaning, p.example).filter { it.isNotBlank() }.joinToString("\n\n")
                    else -> p.answer.ifBlank { p.meaning }
                }
                t.answer = text
                if (first == null) {
                    // Asked straight away, without an explanation first: the reading of the selection is still worth keeping.
                    first = AiPassage(p.text, "", "", "", emptyList(), "")
                    saveFirst(first!!)
                }
                historyId?.let { store.ai.addPassageTurn(it, label, text) }
            }.onFailure { t.error = it.message ?: "Couldn't answer that. Try again." }
            kotlinx.coroutines.delay(60)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    LaunchedEffect(args) {
        ai?.warm()
        if (args.ask) runCatching { focus.requestFocus() } else explain()
    }

    val shape = if (wide) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        (if (wide) Modifier.width(440.dp).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(max = (context.resources.configuration.screenHeightDp * 0.86f).dp))
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            // Touches on the panel stay on the panel (the page underneath must not start a selection).
            .pointerInput(Unit) { detectTapGestures { } }
            .then(if (wide) Modifier.statusBarsPadding() else Modifier)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp)) {
            if (!wide) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(LockInColors.Rim))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiBadge()
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Explain this", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                    Text(
                        "Page ${page + 1}" + (book?.let { " · " + prettyTitle(it.title) } ?: ""),
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 13.dp, strokeWidth = 2.2f) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Body
        Column(
            Modifier
                .weight(1f, fill = wide)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
        ) {
            SelectionCard(picture, first?.text)
            Spacer(Modifier.height(16.dp))
            when {
                loading -> {
                    Thinking("Reading your selection…", 1, compact = true)
                    SkeletonLines()
                }
                error != null -> ErrorBox(error!!) { explain() }
                first != null && first!!.meaning.isNotBlank() -> {
                    PassageResult(first!!)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction(if (carded) "Card added ✓" else "Make a flashcard", Glyph.Cards, enabled = !carded) {
                            val f = first ?: return@SmallAction
                            val q = if (f.text.isNotBlank() && f.text.length <= 70) "What does “${f.text}” mean here?" else "Explain the highlighted part."
                            val image = store.saveImage(args.picture) ?: ""
                            store.addCard(bookId, q, f.meaning, page + 1, image)
                            carded = true
                        }
                        SmallAction("Copy", Glyph.Share) {
                            val f = first ?: return@SmallAction
                            clipboard.setText(AnnotatedString(listOf(f.text, f.meaning, f.context, f.example).filter { it.isNotBlank() }.joinToString("\n\n")))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                args.ask && turns.isEmpty() -> Text(
                    "What would you like to know about this? Ask below.",
                    style = LockInText.Sub.copy(color = LockInColors.Silver),
                )
            }
            turns.forEach { t ->
                Spacer(Modifier.height(18.dp))
                when {
                    t.error != null -> {
                        PassageTurnView(t.ask, null)
                        Spacer(Modifier.height(6.dp))
                        ErrorBox(t.error!!) {
                            t.error = null
                            turns.remove(t)
                            follow(t.ask, t.mode, t.question)
                        }
                    }
                    else -> PassageTurnView(t.ask, t.answer)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Follow-ups and your own question
        Column(Modifier.fillMaxWidth().background(LockInColors.Slate).padding(top = 8.dp, bottom = 12.dp)) {
            if (first != null || turns.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FollowUps.forEach { (label, mode, q) ->
                        Box(
                            Modifier
                                .height(36.dp)
                                .clip(CircleShape)
                                .border(1.dp, LockInColors.Rim, CircleShape)
                                .clickable(enabled = !busy, role = Role.Button) { follow(label, mode, q) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = LockInText.Label.copy(color = if (busy) LockInColors.Mist else LockInColors.Snow, fontSize = 13.sp), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            val field = RoundedCornerShape(24.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(field)
                        .background(LockInColors.Ink)
                        .border(1.dp, LockInColors.Graphite, field)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (question.isEmpty()) {
                        Text("Ask about this…", style = LockInText.Input.copy(color = LockInColors.Mist, fontSize = 15.sp))
                    }
                    BasicTextField(
                        value = question,
                        onValueChange = { question = it.take(300) },
                        textStyle = LockInText.Input.copy(fontSize = 15.sp),
                        cursorBrush = SolidColor(LockInColors.Lime),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val q = question.trim()
                            if (q.isNotEmpty() && !busy) { question = ""; follow(q, "ask", q) }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val ready = question.isNotBlank() && !busy
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (ready) LockInColors.Lime else LockInColors.Graphite)
                        .clickable(enabled = ready, role = Role.Button) {
                            val q = question.trim()
                            question = ""
                            follow(q, "ask", q)
                        },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.ArrowUp, if (ready) LockInColors.OnLime else LockInColors.Mist, boxSize = 20.dp, strokeWidth = 2.2f) }
            }
        }
    }
}

/** The part you selected, as it looks in the book, and (once the AI has read it) the words it found there. */
@Composable
private fun SelectionCard(picture: ImageBitmap, text: String?) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(LockInColors.Ink).border(1.dp, LockInColors.Graphite, shape).padding(10.dp)) {
        Text("YOU SELECTED", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, letterSpacing = 1.2.sp))
        Spacer(Modifier.height(8.dp))
        Image(
            picture,
            contentDescription = "What you selected",
            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
            contentScale = ContentScale.Fit,
        )
        if (!text.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Row {
                Box(Modifier.width(3.dp).height(20.dp).clip(CircleShape).background(LockInColors.Lime))
                if (text.contains('$') || RichSplit.looksMath(text)) {
                    // maths read off the page: drawn as maths, not as a quoted string
                    MathText(
                        text,
                        style = LockInText.Sub.copy(color = LockInColors.Snow, lineHeight = 24.sp),
                        modifier = Modifier.padding(start = 10.dp),
                        equation = true,
                    )
                } else {
                    Text(
                        "“$text”",
                        style = LockInText.Sub.copy(color = LockInColors.Snow, fontStyle = FontStyle.Italic, lineHeight = 21.sp),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/** The first explanation of a selection: meaning, its place in the book, an example and the harder words. */
@Composable
internal fun PassageResult(p: AiPassage) {
    if (p.meaning.isNotBlank()) {
        SectionLabel("What it means")
        MathText(p.meaning, style = LockInText.Sub.copy(color = LockInColors.Snow, fontSize = 16.sp, lineHeight = 24.sp))
    }
    if (p.context.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        SectionLabel("Here in the book")
        MathText(p.context, style = LockInText.Sub.copy(color = LockInColors.Body, lineHeight = 22.sp))
    }
    if (p.example.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LockInColors.Graphite).padding(14.dp)) {
            SectionLabel("For example")
            MathText(p.example, style = LockInText.Sub.copy(color = LockInColors.Snow, lineHeight = 22.sp))
        }
    }
    if (p.terms.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SectionLabel("Key words")
        p.terms.forEach { (term, meaning) ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    term,
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.width(110.dp),
                )
                MathText(meaning, style = LockInText.Sub.copy(color = LockInColors.Body, fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** One follow-up: what you asked, on the right like a message you sent, and the answer under it. Null answer: still thinking. */
@Composable
internal fun PassageTurnView(ask: String, answer: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            ask,
            style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 14.sp),
            modifier = Modifier
                .padding(start = 48.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp))
                .background(LockInColors.Lime)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    if (answer == null) {
        Dots()
    } else {
        MathText(answer, style = LockInText.Sub.copy(color = LockInColors.Snow, lineHeight = 23.sp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Three dots that hop in turn: the AI is writing. */
@Composable
private fun Dots() {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "hop")
    Row(Modifier.height(24.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val phase = ((t - i) % 3f + 3f) % 3f
            val lift = if (phase < 1f) kotlin.math.sin(phase * Math.PI).toFloat() else 0f
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(8.dp)
                    .graphicsLayer { translationY = -lift * 6.dp.toPx() }
                    .clip(CircleShape)
                    .background(if (lift > 0.2f) LockInColors.Lime else LockInColors.Rim),
            )
        }
    }
}

/** Grey bars where the explanation will be, with a highlight sweeping across them. */
@Composable
private fun ColumnScope.SkeletonLines() {
    val t by rememberInfiniteTransition(label = "skeleton").animateFloat(0f, 1f, infiniteRepeatable(tween(1300)), label = "sweep")
    val base = LockInColors.Graphite
    val shine = LockInColors.Rim
    listOf(1f, 0.92f, 0.7f, 0f, 0.85f, 0.6f).forEach { w ->
        if (w == 0f) { Spacer(Modifier.height(14.dp)); return@forEach }
        Canvas(Modifier.fillMaxWidth(w).height(12.dp).padding(bottom = 0.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(base, cornerRadius = r)
            val x = (t * 1.6f - 0.3f) * size.width
            drawRoundRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(base, shine, base), startX = x - 80f, endX = x + 80f),
                cornerRadius = r,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LockInColors.Flare.copy(alpha = 0.6f), shape).padding(14.dp)) {
        Text(message, style = LockInText.Sub.copy(color = LockInColors.Snow))
        Spacer(Modifier.height(10.dp))
        Text(
            "Try again",
            style = LockInText.Label.copy(color = LockInColors.Ink, fontSize = 14.sp),
            modifier = Modifier.clip(CircleShape).background(LockInColors.Snow).clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SmallAction(label: String, glyph: Glyph, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(LockInColors.Graphite)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (enabled) LockInColors.Lime else LockInColors.Mist, boxSize = 16.dp)
        Text(label, style = LockInText.Label.copy(color = if (enabled) LockInColors.Snow else LockInColors.Mist, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
    }
}

/** The first time the reader opens after this update: a tip that long-pressing a word explains it. */
internal object ReaderTips {
    private const val PREFS = "reader_tips"
    fun seen(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("select", false)
    fun markSeen(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("select", true).apply()
    }
}
