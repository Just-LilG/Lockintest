package com.godbless.lockin.study

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.readable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

// =============================================================================================
// Pictures on cards
// =============================================================================================

/** A card's picture, read off the main thread. Nothing (not a broken box) if the file has gone. */
@Composable
internal fun CardImage(store: StudyStore, name: String, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(store.imageFile(name).path) }.getOrNull() }
    }
    val b = bmp ?: return
    Image(
        b.asImageBitmap(),
        contentDescription = "Picture on this card",
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color.White),
    )
}

/**
 * Full screen: a textbook page, where you drag a box around the part you want on the card (a diagram, a table).
 * Arrows step to the pages around it. [page] counts from 1.
 */
@Composable
internal fun PageCropper(store: StudyStore, bookId: String, page: Int, onCrop: (String) -> Unit, onCancel: () -> Unit) {
    val book = store.book(bookId)
    var index by remember { mutableIntStateOf((page - 1).coerceAtLeast(0)) }
    val bmp by produceState<Bitmap?>(null, bookId, index) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(store.pdfFile(bookId), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r -> if (index < r.pageCount) renderPage(r, index, 1400) else null }
                }
            }.getOrNull()
        }
    }
    var box by remember { mutableStateOf<Rect?>(null) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(24.dp)
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .clickable(enabled = false) {}
            .padding(top = topPad)
            .navigationBarsPadding()
            .readable(Widths.Page)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onCancel)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Pick part of the page", style = LockInText.Title.copy(fontSize = 19.sp, lineHeight = 24.sp))
                Text(
                    (book?.let { prettyTitle(it.title) + " · " } ?: "") + "page ${index + 1}",
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
            StepButton("‹", index > 0) { index--; box = null }
            StepButton("›", book == null || index + 1 < book.pages) { index++; box = null }
        }
        Spacer(Modifier.height(10.dp))
        Text("Drag across the part you want.", style = LockInText.Small.copy(color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) {
                Text("Opening the page…", style = LockInText.Small.copy(color = LockInColors.Mist))
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(b.width.toFloat() / b.height, matchHeightConstraintsFirst = b.height > b.width * 1.2f)
                        .onSizeChanged { view = it }
                        .pointerInput(b) {
                            var start = Offset.Zero
                            detectDragGestures(
                                onDragStart = { p -> start = p; box = Rect(p, p) },
                            ) { change, _ ->
                                change.consume()
                                val p = Offset(change.position.x.coerceIn(0f, size.width.toFloat()), change.position.y.coerceIn(0f, size.height.toFloat()))
                                box = Rect(min(start.x, p.x), min(start.y, p.y), max(start.x, p.x), max(start.y, p.y))
                            }
                        },
                ) {
                    Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    val accent = LockInColors.Lime
                    Canvas(Modifier.fillMaxSize()) {
                        val r = box ?: return@Canvas
                        val dim = Color.Black.copy(alpha = 0.5f)
                        drawRect(dim, Offset.Zero, Size(size.width, r.top))
                        drawRect(dim, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
                        drawRect(dim, Offset(0f, r.top), Size(r.left, r.height))
                        drawRect(dim, Offset(r.right, r.top), Size(size.width - r.right, r.height))
                        drawRect(accent, r.topLeft, r.size, style = Stroke(2.5f * density))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val r = box
        val ok = r != null && r.width > 24f && r.height > 24f && bmp != null && view.width > 0
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Whole page", {
                val b = bmp ?: return@LockInButton
                store.saveImage(b)?.let(onCrop)
            }, Modifier.weight(1f), kind = ButtonKind.Outline, enabled = bmp != null)
            LockInButton("Use this part", {
                val b = bmp ?: return@LockInButton
                val rr = box ?: return@LockInButton
                val sx = b.width / view.width.toFloat()
                val sy = b.height / view.height.toFloat()
                val x = (rr.left * sx).toInt().coerceIn(0, b.width - 1)
                val y = (rr.top * sy).toInt().coerceIn(0, b.height - 1)
                val w = (rr.width * sx).toInt().coerceIn(1, b.width - x)
                val h = (rr.height * sy).toInt().coerceIn(1, b.height - y)
                store.saveImage(Bitmap.createBitmap(b, x, y, w, h))?.let(onCrop)
            }, Modifier.weight(1f), enabled = ok)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = LockInText.Title.copy(fontSize = 22.sp, color = if (enabled) LockInColors.Snow else LockInColors.Rim)) }
}

// =============================================================================================
// Type the answer
// =============================================================================================

internal enum class Match { Right, Close, Wrong }

private val Articles = setOf("a", "an", "the")

private fun norm(s: String): List<String> {
    val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return plain.replace(Regex("[^\\p{L}\\p{N}.+\\-]+"), " ").split(' ').map { it.trim('.', '-') }.filter { it.isNotEmpty() && it !in Articles }
}

private fun distance(a: String, b: String): Int {
    if (a == b) return 0
    val prev = IntArray(b.length + 1) { it }
    val cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        cur.copyInto(prev)
    }
    return prev[b.length]
}

/**
 * Forgiving marking: case, accents, punctuation, "a/an/the" and word order don't matter, and a typo or two in a long
 * word is fine. Numbers must be exact. A long answer is right when most of its key words are there.
 */
internal fun checkAnswer(typed: String, answer: String): Match {
    // "Arithmetic logic unit (ALU)": either the words or what's in brackets will do, as will "A or B" / "A; B".
    val inside = Regex("\\(([^)]*)\\)").findAll(answer).map { it.groupValues[1] }.toList()
    val outside = answer.replace(Regex("\\([^)]*\\)"), " ")
    val forms = (listOf(answer, outside) + inside + answer.split(Regex("\\s+or\\s+|;", RegexOption.IGNORE_CASE)))
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return forms.map { matchOne(typed, it) }.minByOrNull { it.ordinal } ?: Match.Wrong
}

private fun matchOne(typed: String, answer: String): Match {
    val t = norm(typed)
    val a = norm(answer)
    if (t.isEmpty()) return Match.Wrong
    if (a.isEmpty()) return if (typed.trim().equals(answer.trim(), true)) Match.Right else Match.Wrong
    // Numbers are the words that start with a digit ("9.81", "1945"; not the "s2" of a unit).
    val isNum = { w: String -> w.trimStart('+', '-').firstOrNull()?.isDigit() == true }
    val numsA = a.filter(isNum).toSet()
    val numsT = t.filter(isNum).toSet()
    if (numsA.isNotEmpty() && !numsT.containsAll(numsA)) return if (numsT.isEmpty()) Match.Wrong else Match.Close
    // The number right and only a unit left out ("9.81" for "9.81 m/s2"): right.
    if (numsA.isNotEmpty() && a.filter { w -> w !in numsA }.all { it.length <= 4 }) return Match.Right
    val tj = t.joinToString(" ")
    val aj = a.joinToString(" ")
    if (tj == aj || t.sorted() == a.sorted()) return Match.Right
    if (a.size <= 3) {
        val d = distance(tj, aj)
        return when {
            d <= max(1, aj.length / 6) -> Match.Right
            d <= max(2, aj.length / 3) -> Match.Close
            tj.contains(aj) -> Match.Right
            else -> Match.Wrong
        }
    }
    // Longer answers: how many of the answer's key words (3+ letters) turn up, allowing a typo in long ones.
    val keys = a.filter { it.length >= 3 }.ifEmpty { a }
    val hit = keys.count { k -> t.any { w -> w == k || (k.length >= 5 && distance(w, k) <= 1) || (k.length >= 8 && distance(w, k) <= 2) } }
    val share = hit / keys.size.toFloat()
    return when {
        share >= 0.8f -> Match.Right
        share >= 0.5f -> Match.Close
        else -> Match.Wrong
    }
}

/** A card answered by typing: check it, see how close it was, and overrule the marking if you know better. */
@Composable
internal fun TypeCard(card: Card, store: StudyStore, remaining: Int, onRate: (Rating) -> Unit, onOpenPage: (String, Int) -> Unit) {
    var typed by rememberSaveable(card.id) { mutableStateOf("") }
    var verdict by remember(card.id) { mutableStateOf<Match?>(null) }
    var gaveUp by remember(card.id) { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(verdict) {
        if (verdict == Match.Wrong && !gaveUp) {
            shake.snapTo(14f)
            shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 700f))
        }
    }
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(LockInColors.Slate).border(1.5.dp, LockInColors.Rim, shape).padding(22.dp),
        ) {
            Text("QUESTION", style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, letterSpacing = 1.5.sp))
            Spacer(Modifier.height(10.dp))
            if (card.image.isNotEmpty()) {
                CardImage(store, card.image, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(card.question, style = LockInText.Title.copy(fontSize = 23.sp, lineHeight = 30.sp))
        }
        Spacer(Modifier.height(16.dp))
        val v = verdict
        Box(Modifier.graphicsLayer { translationX = shake.value * density.density }) {
            StudyField(typed, { if (v == null) typed = it.take(300) }, "Your answer", minHeight = 64.dp)
        }
        Spacer(Modifier.height(12.dp))
        if (v == null) {
            LockInButton("Check", { verdict = checkAnswer(typed, card.answer) }, enabled = typed.isNotBlank())
            Text(
                "I don't know",
                style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button) { gaveUp = true; verdict = Match.Wrong }.padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            val (title, color) = when {
                gaveUp -> "Here it is" to LockInColors.Silver
                v == Match.Right -> "✓ Right" to LockInColors.Lime
                v == Match.Close -> "Almost: check it" to Amber
                else -> "✗ Not quite" to LockInColors.Flare
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LockInColors.Slate).border(1.5.dp, color, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(title, style = LockInText.Label.copy(color = color, fontSize = 16.sp))
                Spacer(Modifier.height(6.dp))
                Text("The answer", style = LockInText.Small.copy(color = LockInColors.Mist))
                Text(card.answer, style = LockInText.Sub.copy(color = LockInColors.Lime, fontSize = 17.sp))
                if (!gaveUp && v != Match.Right) {
                    Spacer(Modifier.height(6.dp))
                    Text("You wrote: $typed", style = LockInText.Small.copy(color = LockInColors.Body))
                }
            }
            Spacer(Modifier.height(12.dp))
            val last = remaining == 0
            when {
                v == Match.Right -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LockInButton(if (last) "Finish" else "Next", { onRate(Rating.Good) }, Modifier.weight(2f))
                    LockInButton("Too easy", { onRate(Rating.Easy) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                }
                v == Match.Close -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LockInButton("Count it wrong", { onRate(Rating.Missed) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                    LockInButton("I was right", { onRate(Rating.Good) }, Modifier.weight(1f))
                }
                else -> {
                    LockInButton(if (last) "Finish" else "Next", { onRate(Rating.Missed) })
                    if (!gaveUp) {
                        Text(
                            "I was right, count it",
                            style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button) { onRate(Rating.Good) }.padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (card.book.isNotEmpty() && card.page > 0) {
            Text(
                "Open page ${card.page}",
                style = LockInText.Label.copy(color = LockInColors.Silver, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button) { onOpenPage(card.book, card.page) }.padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// =============================================================================================
// A quiz for your partner
// =============================================================================================

/** Cards sent to your partner in the chat: the book they're from and up to five questions with their answers. */
internal class Challenge(val title: String, val cards: List<Pair<String, String>>)

/** The chat line's code for 5 of [pool] as a quiz ("quiz_challenge|<base64>"), or null without 4 different answers. */
internal fun encodeChallenge(store: StudyStore, pool: List<Card>): String? {
    // Cards they can be quizzed on fairly: distinct answers, so the options aren't twins.
    val picked = pool.shuffled().distinctBy { it.answer.trim().lowercase() }.take(5)
    if (picked.size < 4) return null
    val books = picked.map { it.book }.distinct()
    val title = if (books.size == 1) store.book(books[0])?.title?.let { prettyTitle(it) }.orEmpty() else ""
    // A chat line holds up to 2000 characters, so long cards are shortened (and, if need be, fewer are sent).
    for (cut in intArrayOf(160, 110, 80, 60)) {
        for (n in picked.size downTo 4) {
            val c = JSONArray()
            picked.take(n).forEach { c.put(JSONArray().put(it.question.take(cut)).put(it.answer.take(cut))) }
            val json = JSONObject().put("t", title.take(40)).put("c", c).toString()
            val b64 = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val code = "quiz_challenge|$b64"
            if (code.length <= 1900) return code
        }
    }
    return null
}

internal fun decodeChallenge(body: String): Challenge? = runCatching {
    val b64 = body.substringAfter('|', "")
    val json = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
    val c = json.getJSONArray("c")
    Challenge(
        json.optString("t"),
        List(c.length()) { i -> c.getJSONArray(i).let { it.getString(0) to it.getString(1) } }.filter { it.first.isNotBlank() && it.second.isNotBlank() },
    )
}.getOrNull()?.takeIf { it.cards.size >= 2 }

/**
 * Your partner's quiz: multiple choice (the wrong options are their other answers). At the end, send them your
 * score, and keep the cards if you like.
 */
@Composable
internal fun ChallengeScreen(
    challenge: Challenge,
    from: String,
    store: StudyStore,
    onScore: ((Int, Int) -> Unit)?,
    onClose: () -> Unit,
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(24.dp)
    var index by rememberSaveable { mutableIntStateOf(0) }
    var right by rememberSaveable { mutableIntStateOf(0) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var kept by rememberSaveable { mutableStateOf(false) }
    val total = challenge.cards.size
    val options = remember(challenge) {
        challenge.cards.map { (_, a) ->
            val wrong = challenge.cards.map { it.second }.filter { it != a }.distinct().shuffled().take(3)
            (wrong + a).shuffled()
        }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .padding(top = topPad)
            .navigationBarsPadding()
            .readable(Widths.Page)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClose)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("$from's quiz", style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
                Text(
                    listOfNotNull(challenge.title.ifBlank { null }, if (index < total) "${index + 1} of $total" else "Done").joinToString(" · "),
                    style = LockInText.Small.copy(color = LockInColors.Mist),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { it } togetherWith
                    slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { -it }
            },
            modifier = Modifier.weight(1f),
            label = "challenge",
        ) { i ->
            if (i < total) {
                val (q, a) = challenge.cards[i]
                var picked by remember(i) { mutableStateOf<String?>(null) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(q, style = LockInText.Title.copy(fontSize = 23.sp, lineHeight = 30.sp), modifier = Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.height(18.dp))
                    options[i].forEach { o ->
                        val reveal = picked != null
                        val isRight = o == a
                        val border = when {
                            reveal && isRight -> LockInColors.Lime
                            reveal && picked == o -> LockInColors.Flare
                            else -> LockInColors.Graphite
                        }
                        val pulse by animateFloatAsState(if (reveal && isRight) 1.03f else 1f, spring(dampingRatio = 0.4f, stiffness = 500f), label = "pulse")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (reveal && isRight) LockInColors.LimeWash else LockInColors.Slate)
                                .border(1.5.dp, border, RoundedCornerShape(20.dp))
                                .clickable(enabled = !reveal, role = Role.Button) { picked = o; if (o == a) right++ }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(o, style = LockInText.Sub.copy(color = LockInColors.Snow), modifier = Modifier.weight(1f))
                            if (reveal && isRight) Text("✓", style = LockInText.Title.copy(color = LockInColors.Lime, fontSize = 20.sp))
                            else if (reveal && picked == o) Text("✗", style = LockInText.Title.copy(color = LockInColors.Flare, fontSize = 20.sp))
                        }
                    }
                    if (picked != null) {
                        Spacer(Modifier.height(6.dp))
                        LockInButton(if (i + 1 == total) "See my score" else "Next", { index = i + 1 })
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(30.dp))
                    Text("$right of $total", style = LockInText.Headline.copy(fontSize = 48.sp))
                    Text(
                        when {
                            right == total -> "Every one. $from will want a rematch."
                            right * 2 >= total -> "Solid. Send it to $from."
                            else -> "Tough set. Keep the cards and try them again."
                        },
                        style = LockInText.Sub.copy(color = LockInColors.Silver),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))
                    if (onScore != null) {
                        LockInButton(if (sent) "Score sent ✓" else "Send my score to $from", { if (!sent) { sent = true; onScore(right, total) } }, enabled = !sent)
                        Spacer(Modifier.height(10.dp))
                    }
                    LockInButton(
                        if (kept) "Added to your cards ✓" else "Keep these cards",
                        {
                            if (!kept) {
                                kept = true
                                val have = store.cards.map { it.question.trim().lowercase() }.toHashSet()
                                challenge.cards.forEach { (q, a) -> if (have.add(q.trim().lowercase())) store.addCard("", q, a, 0) }
                            }
                        },
                        kind = ButtonKind.Outline,
                        enabled = !kept,
                    )
                    Spacer(Modifier.height(10.dp))
                    LockInButton("Done", onClose, kind = ButtonKind.Outline)
                }
            }
        }
    }
}

// =============================================================================================
// A clearer card, from the AI
// =============================================================================================

/** For a card that keeps being missed: the AI suggests a clearer question, a shorter answer and a memory hook. */
@Composable
internal fun SimplifySheet(store: StudyStore, cardId: String, onEdit: () -> Unit, onClose: () -> Unit) {
    val card = store.cards.firstOrNull { it.id == cardId } ?: return
    val ai = LocalAi.current
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<com.godbless.lockin.data.AiSimpler?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        result = null
        error = null
        if (ai == null) { error = "Sign in to use the AI helper."; return@LaunchedEffect }
        val page = if (card.book.isNotEmpty() && card.page > 0) pagesForAi(store, listOf(card.book to card.page - 1)).firstOrNull() else null
        ai.simplify(card.question, card.answer, page).onSuccess { result = it }.onFailure { error = it.message }
    }
    Text("✦ A clearer card", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
    Text(if (card.tricky) "Missed ${card.wrong} times. Same idea, easier to remember." else "Same idea, easier to remember.", style = LockInText.Small.copy(color = LockInColors.Mist))
    Spacer(Modifier.height(14.dp))
    val r = result
    when {
        done -> {
            Text("Card rewritten. It starts fresh, as a new card.", style = LockInText.Sub.copy(color = LockInColors.Body))
            Spacer(Modifier.height(14.dp))
            LockInButton("Done", onClose)
        }
        error != null -> {
            AiProblem(error!!) { attempt++ }
            LockInButton("Rewrite it myself", onEdit, kind = ButtonKind.Outline)
        }
        r == null -> AiThinking("Rewriting the card")
        else -> {
            Text("Now", style = LockInText.Small.copy(color = LockInColors.Mist))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LockInColors.Ink).padding(12.dp)) {
                Text(card.question, style = LockInText.Small.copy(color = LockInColors.Body))
                Text(card.answer, style = LockInText.Small.copy(color = LockInColors.Mist))
            }
            Spacer(Modifier.height(10.dp))
            Text("Suggested", style = LockInText.Small.copy(color = LockInColors.Lime))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LockInColors.Graphite).border(1.dp, LockInColors.Lime, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text(r.question, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                Spacer(Modifier.height(4.dp))
                Text(r.answer, style = LockInText.Sub.copy(color = LockInColors.Lime))
                if (r.hint.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("💡 ${r.hint}", style = LockInText.Small.copy(color = LockInColors.Body))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Try again", { attempt++ }, Modifier.weight(1f), kind = ButtonKind.Outline)
                LockInButton("Use this", {
                    store.rewriteCard(card.id, r.question, if (r.hint.isBlank()) r.answer else r.answer + "\n💡 " + r.hint)
                    done = true
                }, Modifier.weight(1f))
            }
        }
    }
}
