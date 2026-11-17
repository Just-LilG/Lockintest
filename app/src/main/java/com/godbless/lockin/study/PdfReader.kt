package com.godbless.lockin.study

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * An open PDF. Android's renderer draws one page at a time and is not thread safe, so every draw goes
 * through one lock, and finished pages are kept in a cache sized in bytes.
 */
internal class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    // height / width of each page. Everything starts as the first page's shape and is corrected as pages draw.
    private val ratios = FloatArray(pageCount) { 1.414f }

    init {
        synchronized(lock) {
            val first = renderer.openPage(0)
            try {
                val r = first.height.toFloat() / first.width
                ratios.fill(r)
            } finally {
                first.close()
            }
        }
    }

    // A sixth of the app's memory, so a sharp zoomed page doesn't push out all the others.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(32 shl 20, 128 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun ratio(index: Int): Float = ratios[index]

    fun peek(index: Int, widthPx: Int): Bitmap? = cache.get("$index@$widthPx")

    suspend fun bitmap(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        val bmp = synchronized(lock) {
            if (closed) null else renderPage(renderer, index, widthPx)
        } ?: return@withContext null
        ratios[index] = bmp.height.toFloat() / bmp.width
        cache.put(key, bmp)
        bmp
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.close()
            fd.close()
        }
        cache.evictAll()
    }
}

/**
 * Dark pages: black on white becomes white on black, and the colours in diagrams and photos keep their hue
 * (an invert followed by a half turn round the colour wheel), so a red line stays red-ish instead of going cyan.
 */
private val DarkFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Sepia pages: white paper turns warm cream, easier on the eyes at night. */
private val SepiaFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.98f, 0f, 0f, 0f, 0f,
            0f, 0.93f, 0f, 0f, 0f,
            0f, 0f, 0.78f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private val ToneNames = listOf("Light", "Dark", "Sepia")

/**
 * Reads a textbook. Pages scroll down the screen, pinch with two fingers to zoom (then slide sideways
 * to pan), and the bar at the bottom jumps to a page, flips the pages dark, or makes a flashcard from
 * the page you are on. Where you stopped is remembered.
 */
@Composable
internal fun ReaderScreen(
    store: StudyStore,
    bookId: String,
    startPage: Int,
    onBack: () -> Unit,
) {
    val book = store.book(bookId)
    var doc by remember(bookId) { mutableStateOf<PdfDoc?>(null) }
    var failed by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(bookId) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfDoc(store.pdfFile(bookId)) }.getOrNull() }
        if (opened == null) failed = true else doc = opened
    }
    DisposableEffect(bookId) { onDispose { doc?.close() } }

    var zoom by remember { mutableFloatStateOf(1f) }
    // 0 light, 1 dark, 2 sepia.
    var tone by rememberSaveable { mutableIntStateOf(0) }
    val dark = tone == 1
    // Bars hide while you read down the page and come back when you read up, or tap.
    var chrome by remember { mutableStateOf(true) }
    var chip by remember { mutableStateOf(false) }
    var scrollPos by remember { mutableIntStateOf(0) }
    val aiDrag = remember { Animatable(0f) }
    var jump by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var aiQuiz by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Explaining a word or sentence: what's selected on the page, whether a finger is still dragging it, select mode
    // (from the Select button: one finger draws a selection instead of scrolling), and the open explanation.
    var sel by remember(bookId) { mutableStateOf<PageSel?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var passage by remember(bookId) { mutableStateOf<PassageArgs?>(null) }
    var showTip by remember { mutableStateOf(!ReaderTips.seen(context)) }

    val last = ((book?.pages ?: 1) - 1).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (if (startPage >= 0) startPage else (book?.lastPage ?: 0)).coerceIn(0, last))
    // The page you're looking at: the one under the upper-middle of the screen, not the sliver still showing at the top.
    val page by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val at = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * 2 / 5
            info.visibleItemsInfo.firstOrNull { at >= it.offset && at < it.offset + it.size }?.index ?: list.firstVisibleItemIndex
        }
    }
    LaunchedEffect(list) {
        var prev = list.firstVisibleItemIndex * 100_000 + list.firstVisibleItemScrollOffset
        snapshotFlow { list.firstVisibleItemIndex * 100_000 + list.firstVisibleItemScrollOffset }.collect { pos ->
            val d = pos - prev
            prev = pos
            if (d > 24) chrome = false else if (d < -24) chrome = true
            scrollPos = pos
        }
    }
    // A small "12 / 240" shows while you scroll with the bars away.
    LaunchedEffect(scrollPos) { chip = true; delay(900); chip = false }
    LaunchedEffect(aiOpen) { if (!aiOpen) { delay(380); aiDrag.snapTo(0f) } }
    // The AI sheet explains the page you opened it on, even if you scroll behind it.
    var aiPage by remember { mutableStateOf(0) }
    // Back closes whatever is on top of the page before it leaves the book.
    androidx.activity.compose.BackHandler(enabled = aiQuiz || aiOpen || making || jump || passage != null || sel != null || armed) {
        when {
            passage != null -> passage = null
            sel != null -> sel = null
            armed -> armed = false
            aiQuiz -> aiQuiz = false
            aiOpen -> aiOpen = false
            making -> making = false
            else -> jump = false
        }
    }

    // Remember where you stopped, a moment after you stop moving. A page that stays up 8 seconds counts as read
    // (it's what the AI quiz asks about).
    LaunchedEffect(page) {
        delay(1500)
        store.setLastPage(bookId, page)
        delay(6500)
        store.markRead(bookId, page)
    }
    DisposableEffect(bookId) { onDispose { store.setLastPage(bookId, page) } }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    val readerDensity = LocalDensity.current
    // Cut the selection out of a sharp drawing of its page and open the explanation.
    fun openPassage(ask: Boolean) {
        val s = sel?.norm() ?: return
        val d = doc ?: return
        if (preparing) return
        preparing = true
        scope.launch {
            val cut = d.bitmap(s.page, 1600)?.let { snipForAi(it, s.l, s.t, s.r, s.b) }
            preparing = false
            if (cut == null) {
                android.widget.Toast.makeText(context, "Couldn't read that part. Try selecting it again.", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            sel = s
            passage = PassageArgs(s, cut.first, cut.second, ask)
            chrome = false
            if (showTip) { showTip = false; ReaderTips.markSeen(context) }
            // On a phone the explanation rises over the lower part of the screen: bring the selection up near the top.
            if (context.resources.configuration.screenWidthDp < 600) {
                val info = list.layoutInfo
                info.visibleItemsInfo.firstOrNull { it.index == s.page }?.let { item ->
                    val y = item.offset - info.viewportStartOffset + s.t * item.size
                    val target = with(readerDensity) { (topPad + 20.dp).toPx() }
                    runCatching { list.animateScrollBy(y - target, tween(380, easing = UiGlide)) }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(if (dark) Color.Black else if (tone == 2) Color(0xFF3A3328) else LockInColors.Graphite)) {
        val d = doc
        when {
            failed || book == null -> Message("Couldn't open this book.", onBack)
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LockInColors.Lime, strokeWidth = 2.dp)
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                // On a wide screen pages are read at a comfortable width (not stretched across a tablet), centred, and
                // the explanation panel takes the right side instead of covering the page.
                val wideScreen = maxWidth >= 600.dp
                val panelW = if (wideScreen && passage != null) 440.dp else 0.dp
                val avail = maxWidth - panelW
                val screenW = if (wideScreen) minOf(avail, 820.dp) else avail
                val pageW = screenW * zoom
                val leftPad = ((avail - pageW) / 2).coerceAtLeast(0.dp)
                // Pages are drawn in steps of 128 px so zooming does not redraw them on every frame.
                val renderW = (with(density) { pageW.toPx() }.toInt() / 128 * 128).coerceIn(256, 2300)
                val hs = rememberScrollState()
                var pendX by remember { mutableFloatStateOf(0f) }
                var pendY by remember { mutableFloatStateOf(0f) }
                // Zooms about a point on the screen: the bit of page under it stays under it.
                fun zoomTo(target: Float, cx: Float, cy: Float) {
                    val z0 = zoom
                    val z1 = target.coerceIn(1f, 3.5f)
                    if (z1 == z0) return
                    val r = z1 / z0
                    pendX += (hs.value + cx) * (r - 1f)
                    pendY += (list.firstVisibleItemScrollOffset + cy) * (r - 1f)
                    zoom = z1
                }
                // Where the pages are, for turning a touch into a spot on a page (and back, to draw the selection).
                val pageWpx by androidx.compose.runtime.rememberUpdatedState(with(density) { pageW.toPx() })
                val leftPx by androidx.compose.runtime.rememberUpdatedState(with(density) { leftPad.toPx() })
                val grabPx = with(density) { 30.dp.toPx() }
                fun pageAt(p: androidx.compose.ui.geometry.Offset): Int? {
                    val info = list.layoutInfo
                    return info.visibleItemsInfo.firstOrNull {
                        val top = it.offset - info.viewportStartOffset
                        p.y >= top && p.y < top + it.size
                    }?.index
                }
                fun fractions(page: Int, p: androidx.compose.ui.geometry.Offset): Pair<Float, Float>? {
                    val info = list.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == page } ?: return null
                    val top = item.offset - info.viewportStartOffset
                    return ((p.x + hs.value - leftPx) / pageWpx).coerceIn(0f, 1f) to ((p.y - top) / item.size.toFloat()).coerceIn(0f, 1f)
                }
                fun shownRect(): androidx.compose.ui.geometry.Rect? = sel?.let { selRect(it.norm(), list, hs.value, leftPx, pageWpx) }
                // 1 = the handle at the start of the selection, 2 = the one at its end, 0 = neither.
                fun handleAt(p: androidx.compose.ui.geometry.Offset): Int {
                    if (passage != null) return 0
                    val r = shownRect() ?: return 0
                    return when {
                        (p - r.topLeft).getDistance() < grabPx -> 1
                        (p - r.bottomRight).getDistance() < grabPx -> 2
                        else -> 0
                    }
                }
                fun pinch(ev: androidx.compose.ui.input.pointer.PointerEvent) {
                    if (ev.changes.size > 1) {
                        val z = ev.calculateZoom()
                        if (z != 1f) {
                            val c = ev.calculateCentroid()
                            zoomTo(zoom * z, c.x, c.y)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
                // A finger that picks out words: from a word-sized box where it went down to wherever it goes.
                suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragSelect(start: androidx.compose.ui.geometry.Offset) {
                    val page = pageAt(start) ?: return
                    val (ax, ay) = fractions(page, start) ?: return
                    val anchor = wordAt(page, ax, ay)
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    selecting = true
                    armed = false
                    chrome = false
                    jump = false
                    sel = anchor
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                        val c = ev.changes.first()
                        if (c.pressed && (c.position - start).getDistance() > viewConfiguration.touchSlop) {
                            fractions(page, c.position)?.let { (fx, fy) -> sel = spanTo(anchor, fx, fy) }
                        }
                    } while (ev.changes.any { it.pressed })
                    selecting = false
                }
                // The pages only have their new size a frame after the zoom changes; then slide to keep the point put.
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos { }
                        val dx = pendX
                        val dy = pendY
                        if (dx != 0f || dy != 0f) {
                            pendX -= dx
                            pendY -= dy
                            withFrameNanos { }
                            if (dx != 0f) hs.scrollTo((hs.value + dx).roundToInt().coerceAtLeast(0))
                            if (dy != 0f) list.scrollBy(dy)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // Watch first, and only take the touch when it is ours: two fingers (zoom), a finger held still
                            // (select words), a selection handle, or select mode. Ordinary one-finger scrolling still works.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val grab = handleAt(down.position)
                                if (grab != 0) {
                                    // Drag a handle to grow or shrink the selection.
                                    down.consume()
                                    selecting = true
                                    val page = sel?.page ?: return@awaitEachGesture
                                    do {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        ev.changes.forEach { it.consume() }
                                        val c = ev.changes.first()
                                        val cur = sel
                                        if (cur != null) {
                                            fractions(page, c.position)?.let { (fx, fy) ->
                                                sel = if (grab == 1) PageSel(page, fx, fy, cur.r, cur.b) else PageSel(page, cur.l, cur.t, fx, fy)
                                            }
                                        }
                                    } while (ev.changes.any { it.pressed })
                                    sel = sel?.norm()
                                    selecting = false
                                    return@awaitEachGesture
                                }
                                if (armed && passage == null) {
                                    down.consume()
                                    dragSelect(down.position)
                                    return@awaitEachGesture
                                }
                                // 0 still watching, 1 it moved or a second finger came (scroll or zoom), 2 it lifted (a tap).
                                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var o = 0
                                    while (o == 0) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        o = if (ev.changes.size > 1) {
                                            1
                                        } else {
                                            val c = ev.changes.first()
                                            if (!c.pressed) 2 else if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) 1 else 0
                                        }
                                    }
                                    o
                                }
                                when (outcome) {
                                    // Held still: pick out the word under the finger, then follow it.
                                    null -> if (passage == null && !aiOpen && !making && !aiQuiz) dragSelect(down.position)
                                    1 -> do {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        pinch(ev)
                                    } while (ev.changes.any { it.pressed })
                                    else -> Unit
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                // Double tap zooms in on that spot, or back out.
                                onDoubleTap = { p ->
                                    scope.launch {
                                        val from = zoom
                                        val to = if (from > 1.2f) 1f else 2.5f
                                        animate(from, to, animationSpec = tween(280, easing = UiGlide)) { v, _ -> zoomTo(v, p.x, p.y) }
                                    }
                                },
                                onTap = {
                                    when {
                                        passage != null -> passage = null
                                        sel != null -> sel = null
                                        else -> chrome = !chrome
                                    }
                                },
                            )
                        }
                        .horizontalScroll(hs),
                ) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.padding(start = leftPad).width(pageW),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = topPad + 64.dp, bottom = 140.dp),
                    ) {
                        items(d.pageCount) { index ->
                            PageView(d, index, renderW, tone)
                        }
                    }
                }
                // The selection, drawn over the page it is on (it follows the page as you scroll), and its bar.
                if (sel != null) {
                    SelectionOverlay(rect = { shownRect() }, showHandles = !selecting && passage == null)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = sel != null && !selecting && passage == null,
                    enter = androidx.compose.animation.scaleIn(spring(dampingRatio = 0.7f, stiffness = 500f), initialScale = 0.6f),
                    exit = androidx.compose.animation.scaleOut(tween(140, easing = UiGlide), targetScale = 0.6f),
                ) {
                    SelectionBar(
                        rect = { shownRect() },
                        preparing = preparing,
                        onExplain = { openPassage(false) },
                        onAsk = { openPassage(true) },
                        onClear = { sel = null },
                        topLimit = with(density) { (topPad + 8.dp).toPx() },
                    )
                }
            }
        }

        // Top bar. Its fade takes the paper colour, so it blends into the page instead of a black smear.
        val fade = when (tone) { 1 -> Color.Black; 2 -> Color(0xFFF4EBD6); else -> Color.White }
        val titleColor = if (tone == 1) Color(0xFFF2F2F2) else Color(0xFF1C1A16)
        AnimatedVisibility(
            visible = chrome || doc == null,
            enter = slideInVertically(tween(240, easing = UiGlide)) { -it },
            exit = slideOutVertically(tween(220, easing = UiGlide)) { -it },
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to fade.copy(alpha = 0.96f), 0.62f to fade.copy(alpha = 0.88f), 1f to fade.copy(alpha = 0f)))
                .padding(start = 16.dp, end = 16.dp, top = topPad, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Text(
                book?.title ?: "",
                style = LockInText.Label.copy(color = titleColor, fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            // Paper colour: light, dark or sepia, in turn.
            Row(
                Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, CircleShape)
                    .clickable(role = Role.Button) { tone = (tone + 1) % 3 }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(when (tone) { 1 -> Color.Black; 2 -> Color(0xFFF4EBD6); else -> Color.White })
                        .border(1.dp, LockInColors.Rim, CircleShape),
                )
                Text(ToneNames[tone], style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
            }
        }
        }

        // The page number, while you scroll and the bars are away.
        AnimatedVisibility(
            visible = chip && !chrome && !jump && doc != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topPad),
            enter = slideInVertically(tween(200, easing = UiGlide)) { -it * 2 },
            exit = slideOutVertically(tween(200, easing = UiGlide)) { -it * 2 },
        ) {
            Text(
                "${page + 1} / ${book?.pages ?: 0}",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        // Bottom bar
        if (doc != null && book != null) {
            AnimatedVisibility(
                visible = (chrome || jump) && sel == null && !armed,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(240, easing = UiGlide)) { it },
                exit = slideOutVertically(tween(220, easing = UiGlide)) { it },
            ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                AnimatedVisibility(
                    visible = jump,
                    enter = slideInVertically(tween(260, easing = UiGlide)) { it },
                    exit = slideOutVertically(tween(220, easing = UiGlide)) { it },
                ) {
                    val shape = RoundedCornerShape(24.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(shape)
                            .background(LockInColors.Slate)
                            .border(1.dp, LockInColors.Graphite, shape)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text("Page ${page + 1} of ${book.pages}", style = LockInText.Small.copy(color = LockInColors.Silver))
                        Slider(
                            value = page.toFloat(),
                            onValueChange = { v ->
                                // Jump by moving the list; the slider follows because it reads the page back.
                                val target = v.toInt().coerceIn(0, last)
                                if (target != list.firstVisibleItemIndex) scope.launch { list.scrollToItem(target) }
                            },
                            valueRange = 0f..last.coerceAtLeast(1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = LockInColors.Lime,
                                activeTrackColor = LockInColors.Lime,
                                inactiveTrackColor = LockInColors.Graphite,
                            ),
                        )
                    }
                }
                val bar = RoundedCornerShape(32.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(bar)
                        .background(LockInColors.Slate)
                        .border(1.dp, LockInColors.Graphite, bar)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { jump = !jump }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${page + 1} / ${book.pages}", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                    }
                    Spacer(Modifier.weight(1f))
                    BarButton("Select", Glyph.Select, onClick = { sel = null; armed = true; jump = false; chrome = false })
                    BarButton("AI", Glyph.Target, onClick = { aiPage = page; aiOpen = true })
                    BarButton("Card", Glyph.Cards, onClick = { making = true }, accent = true)
                }
            }
            }
        }

        // Select mode: what to do now, and a way out. And, once, a tip that long-pressing explains.
        AnimatedVisibility(
            visible = armed,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topPad + 8.dp),
            enter = slideInVertically(tween(240, easing = UiGlide)) { -it * 2 },
            exit = slideOutVertically(tween(200, easing = UiGlide)) { -it * 2 },
        ) {
            SelectHint("Drag over a word or sentence", onCancel = { armed = false })
        }
        AnimatedVisibility(
            visible = showTip && doc != null && chrome && !armed && sel == null && passage == null && !aiOpen && !making,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
            enter = slideInVertically(tween(300, easing = UiGlide)) { it * 2 },
            exit = slideOutVertically(tween(220, easing = UiGlide)) { it * 2 },
        ) {
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .widthIn(max = 520.dp)
                    .clip(shape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Lime.copy(alpha = 0.5f), shape)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Select, LockInColors.Lime, boxSize = 22.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("New: explain any part", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp))
                    Text("Press and hold a word, then drag over a sentence. AI explains it as this book uses it.", style = LockInText.Small.copy(color = LockInColors.Mist))
                }
                Text(
                    "Got it",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 14.sp),
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { showTip = false; ReaderTips.markSeen(context) }.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        // The explanation of a selection: up from the bottom on a phone, a panel down the right on a wide screen.
        val wideReader = com.godbless.lockin.ui.isWide()
        AnimatedVisibility(
            visible = passage != null,
            modifier = Modifier.align(if (wideReader) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (wideReader) androidx.compose.animation.slideInHorizontally(tween(320, easing = UiGlide)) { it } else slideInVertically(tween(320, easing = UiGlide)) { it },
            exit = if (wideReader) androidx.compose.animation.slideOutHorizontally(tween(260, easing = UiGlide)) { it } else slideOutVertically(tween(260, easing = UiGlide)) { it },
        ) {
            // Keeps showing the last one while it slides away.
            var shown by remember { mutableStateOf<PassageArgs?>(null) }
            LaunchedEffect(passage) { if (passage != null) shown = passage }
            val a = passage ?: shown
            if (a != null) {
                PassageSheet(store = store, bookId = bookId, args = a, wide = wideReader, onClose = { passage = null; sel = null })
            }
        }

        // The page dims behind the sheets, and a tap on the dim closes them.
        val dim by animateFloatAsState(
            if (aiOpen || making) 1f else 0f,
            tween(260, easing = UiGlide),
            label = "dim",
        )
        if (dim > 0.01f) {
            val pulled = if (aiOpen) (aiDrag.value / 900f).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f * dim * (1f - pulled)))
                    .pointerInput(Unit) { detectTapGestures { if (aiOpen) aiOpen = false else making = false } },
            )
        }

        // The AI sheet slides up over the page: explain it, make cards, or quiz on what you read.
        AnimatedVisibility(
            visible = aiOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            ReaderAiSheet(
                store = store,
                bookId = bookId,
                page = aiPage,
                drag = aiDrag,
                onQuiz = { aiOpen = false; aiQuiz = true },
                onClose = { aiOpen = false },
                onGoToPage = { p -> scope.launch { list.scrollToItem(p.coerceIn(0, last)) } },
                onSelect = { aiOpen = false; sel = null; armed = true; chrome = false },
            )
        }
        // The quiz on what you read in this book (this page included), over the reader.
        AnimatedVisibility(
            visible = aiQuiz,
            enter = slideInVertically(tween(320, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            val source = remember {
                (store.reads.filter { it.book == bookId }.map { it.page } + page).distinct().takeLast(12).map { bookId to it }
            }
            AiQuizScreen(
                store = store,
                source = source,
                title = "On ${source.size} pages you read in ${book?.title ?: "this book"}",
                onBack = { aiQuiz = false },
                onOpenPage = { _, p -> aiQuiz = false; scope.launch { list.scrollToItem(p.coerceIn(0, last)) } },
            )
        }

        // Card editor slides up over the page you are reading.
        AnimatedVisibility(
            visible = making,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            CardEditor(
                store = store,
                startBook = bookId,
                startPage = page + 1,
                onClose = { making = false },
            )
        }
    }
}

@Composable
private fun BarButton(label: String, glyph: Glyph, onClick: () -> Unit, accent: Boolean = false) {
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (accent) LockInColors.Lime else LockInColors.Slate)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (accent) LockInColors.OnLime else LockInColors.Silver, boxSize = 18.dp)
        Text(
            label,
            style = LockInText.Label.copy(color = if (accent) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = LockInText.Sub.copy(color = LockInColors.Snow))
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.height(44.dp).clip(CircleShape).border(1.5.dp, LockInColors.Rim, CircleShape).clickable(role = Role.Button, onClick = onBack).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Back", style = LockInText.Label.copy(color = LockInColors.Snow)) }
    }
}

/** One page. It keeps showing the last drawing (a little soft) while a sharper one is made after a zoom. */
@Composable
private fun PageView(doc: PdfDoc, index: Int, renderW: Int, tone: Int) {
    var bitmap by remember(doc, index) { mutableStateOf(doc.peek(index, renderW)) }
    LaunchedEffect(doc, index, renderW) {
        val b = doc.bitmap(index, renderW)
        if (b != null) bitmap = b
    }
    val b = bitmap
    val ratio = if (b != null) b.height.toFloat() / b.width else doc.ratio(index)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / ratio)
            .background(if (tone == 1) Color.Black else if (tone == 2) Color(0xFFF4EBD6) else Color.White),
    ) {
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = when (tone) { 1 -> DarkFilter; 2 -> SepiaFilter; else -> null },
            )
        }
    }
}
