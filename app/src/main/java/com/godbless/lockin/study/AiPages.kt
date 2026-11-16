package com.godbless.lockin.study

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.godbless.lockin.data.AiPage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns textbook pages into small JPEGs for the AI: wide enough to read (about 900 px), small enough to send
 * a dozen quickly (usually 80 to 200 KB each). [pages] are (book id, page index from 0).
 *
 * Pages already prepared are kept (the last few MB of them), so a retry, "Make flashcards" after "Explain", or a
 * quiz after the reader sheet has been open costs nothing here. The rest are drawn one after another (Android's PDF
 * renderer lets only one draw at a time), while the JPEG packing of each runs on the other cores meanwhile.
 */
internal suspend fun pagesForAi(store: StudyStore, pages: List<Pair<String, Int>>): List<AiPage> = withContext(Dispatchers.IO) {
    val out = ArrayList<AiPage>()
    for ((bookId, group) in pages.groupBy({ it.first }, { it.second })) {
        val book = store.book(bookId) ?: continue
        val file = store.pdfFile(bookId)
        // A changed file (a re-imported book) is a different stamp, so old pictures are never reused.
        val stamp = "$bookId:${file.length()}:${file.lastModified()}"
        val wanted = group.distinct()
        val found = HashMap<Int, String>()
        val missing = ArrayList<Int>()
        for (index in wanted) {
            val hit = AiPageCache.get("$stamp:$index")
            if (hit != null) found[index] = hit else missing.add(index)
        }
        if (missing.isNotEmpty()) {
            val fd = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
            if (fd != null) {
                coroutineScope {
                    val packing = ArrayList<Pair<Int, Deferred<String?>>>()
                    try {
                        PdfRenderer(fd).use { renderer ->
                            for (index in missing.filter { it in 0 until renderer.pageCount }) {
                                // 900 px is plenty: the AI (Groq first) reads a page at about that size anyway, and smaller
                                // pages upload faster on a phone connection.
                                val bitmap = runCatching { renderPage(renderer, index, 900) }.getOrNull() ?: continue
                                packing.add(index to async(Dispatchers.Default) { runCatching { jpeg(bitmap) }.getOrNull() })
                            }
                        }
                    } finally {
                        runCatching { fd.close() }
                    }
                    for ((index, job) in packing) {
                        val image = job.await() ?: continue
                        found[index] = image
                        AiPageCache.put("$stamp:$index", image)
                    }
                }
            }
        }
        for (index in wanted) {
            val image = found[index] ?: continue
            out.add(AiPage(book.title, index + 1, image))
        }
    }
    out
}

/** The prepared pages, newest used last; the oldest go first once they add up to about 5 MB. */
private object AiPageCache {
    private const val MAX_CHARS = 5_000_000
    private val map = LinkedHashMap<String, String>(16, 0.75f, true)
    private var chars = 0

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(key: String, image: String) {
        map.put(key, image)?.let { chars -= it.length }
        chars += image.length
        val oldest = map.entries.iterator()
        while (chars > MAX_CHARS && map.size > 1 && oldest.hasNext()) {
            val e = oldest.next()
            if (e.key == key) continue
            chars -= e.value.length
            oldest.remove()
        }
    }
}

/** JPEG, base64. Shrinks once more if the page is unusually heavy. */
private fun jpeg(bitmap: Bitmap): String {
    fun encode(b: Bitmap, q: Int): String {
        val bytes = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
    var s = encode(bitmap, 60)
    if (s.length > 600_000) {
        val small = Bitmap.createScaledBitmap(bitmap, 800, (bitmap.height * 800f / bitmap.width).toInt().coerceAtLeast(1), true)
        s = encode(small, 50)
        small.recycle()
    }
    bitmap.recycle()
    return s
}

/**
 * Cuts the selected part out of a page drawing for the AI: [l], [t], [r], [b] are fractions of the page. A little
 * margin is kept round it (a selection drawn by finger is never exact), tiny selections are enlarged so the letters
 * are big enough to read, big ones shrunk to at most 1100 px wide. [page] is not changed or recycled (it is the
 * reader's cached drawing). Returns the JPEG in base64 and the cut-out picture itself (for the sheet to show).
 */
internal suspend fun snipForAi(page: Bitmap, l: Float, t: Float, r: Float, b: Float): Pair<String, Bitmap>? = withContext(Dispatchers.Default) {
    runCatching {
        val w = page.width
        val h = page.height
        val padX = 0.012f * w
        val padY = 0.006f * h
        var x0 = (minOf(l, r) * w - padX).toInt().coerceIn(0, w - 1)
        var x1 = (maxOf(l, r) * w + padX).toInt().coerceIn(x0 + 1, w)
        var y0 = (minOf(t, b) * h - padY).toInt().coerceIn(0, h - 1)
        var y1 = (maxOf(t, b) * h + padY).toInt().coerceIn(y0 + 1, h)
        // Never thinner than a line of text.
        val minH = (0.03f * h).toInt()
        if (y1 - y0 < minH) {
            val c = (y0 + y1) / 2
            y0 = (c - minH / 2).coerceAtLeast(0)
            y1 = (y0 + minH).coerceAtMost(h)
        }
        if (x1 - x0 < 24) { x0 = (x0 - 12).coerceAtLeast(0); x1 = (x1 + 12).coerceAtMost(w) }
        // createBitmap hands back the page itself when the cut is the whole of it; never let that be recycled below.
        val cut = Bitmap.createBitmap(page, x0, y0, x1 - x0, y1 - y0).let { if (it === page) page.copy(Bitmap.Config.ARGB_8888, false) else it }
        val scale = when {
            cut.width < 520 -> 520f / cut.width
            cut.width > 1100 -> 1100f / cut.width
            else -> 1f
        }.coerceAtMost(3f)
        val out = if (scale != 1f) {
            Bitmap.createScaledBitmap(cut, (cut.width * scale).toInt().coerceAtLeast(1), (cut.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== cut) cut.recycle() }
        } else {
            cut
        }
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP) to out
    }.getOrNull()
}
