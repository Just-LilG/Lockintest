package com.godbless.lockin.study

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/*
 * The text of a book, read from the PDF once and kept beside it, so the AI can be given the page's own words and the
 * other places in the book that match a question (BookIndex.kt, AiContext.kt).
 *
 * Where the text comes from: the system PDF engine. Android 15 (and phones with PDF extension 13) can return the text
 * of a page; this asks for it through reflection on purpose, so a different method name or an older phone can never
 * break the build or the app: it just means no text, and the AI works from the page pictures exactly as before. A
 * scanned book has no text layer either, and is treated the same way (and remembered, so it is not tried again).
 */

internal object PdfPageText {
    /** The text of [page], or null when this phone cannot give it (or the page has none). */
    fun of(page: PdfRenderer.Page): String? {
        return try {
            val list = page.javaClass.getMethod("getTextContents").invoke(page) as? List<*> ?: return null
            val sb = StringBuilder()
            for (item in list) {
                val t = item?.javaClass?.getMethod("getText")?.invoke(item) as? String
                if (!t.isNullOrBlank()) sb.append(t.trim()).append('\n')
            }
            sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) {
            null
        }
    }
}

internal object BookTexts {
    private const val MAX_PAGES = 900
    private val memo = HashMap<String, BookIndex>()
    private val none = HashSet<String>()

    @Synchronized
    fun cached(bookId: String): BookIndex? = memo[bookId]

    /**
     * The index for a book: from memory, else from the saved copy, else read from the PDF (a few seconds for a big book,
     * once). Null when the book has no readable text. Safe to call again and again.
     */
    suspend fun ensure(store: StudyStore, bookId: String): BookIndex? = withContext(Dispatchers.IO) {
        synchronized(this@BookTexts) { memo[bookId] }?.let { return@withContext it }
        if (synchronized(this@BookTexts) { bookId in none }) return@withContext null
        val pdf = store.pdfFile(bookId)
        if (!pdf.exists()) return@withContext null
        val stamp = "${pdf.length()}:${pdf.lastModified()}"
        val side = File(pdf.parentFile, "$bookId.txt")

        val saved = readSaved(side, stamp)
        val pages: Map<Int, String>? = when {
            saved != null -> saved
            else -> extract(pdf)?.also { writeSaved(side, stamp, it) } ?: run { writeSaved(side, stamp, emptyMap()); null }
        }
        if (pages.isNullOrEmpty()) {
            synchronized(this@BookTexts) { none.add(bookId) }
            return@withContext null
        }
        val ix = BookIndex()
        for ((p, t) in pages) ix.add(p, t)
        synchronized(this@BookTexts) { memo[bookId] = ix }
        ix
    }

    private fun readSaved(side: File, stamp: String): Map<Int, String>? {
        if (!side.exists()) return null
        return runCatching {
            val text = side.readText()
            val head = text.substringBefore('\n')
            if (head != "#v1\t$stamp") null else BookTextCodec.decode(text.substringAfter('\n'))
        }.getOrNull()
    }

    private fun writeSaved(side: File, stamp: String, pages: Map<Int, String>) {
        runCatching { side.writeText("#v1\t$stamp\n" + BookTextCodec.encode(pages)) }
    }

    /** Page text for the first [MAX_PAGES] pages, or null when the first few pages give none (no text layer, old phone). */
    private suspend fun extract(pdf: File): Map<Int, String>? {
        val fd = runCatching { ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() ?: return null
        val renderer = runCatching { PdfRenderer(fd) }.getOrNull()
        if (renderer == null) { runCatching { fd.close() }; return null }
        try {
            val out = LinkedHashMap<Int, String>()
            val n = minOf(renderer.pageCount, MAX_PAGES)
            for (i in 0 until n) {
                coroutineContext.ensureActive()
                val page = runCatching { renderer.openPage(i) }.getOrNull() ?: continue
                try {
                    PdfPageText.of(page)?.let { out[i] = it }
                } finally {
                    runCatching { page.close() }
                }
                // Three pages in and nothing: there is no text layer to read, so stop early.
                if (i == 2 && out.isEmpty()) return null
            }
            return out
        } finally {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }
}

/** Short notes about a whole book (a summary and its key terms), written once by the AI and kept beside the PDF. */
internal object BookNotes {
    private fun file(store: StudyStore, bookId: String) = File(store.pdfFile(bookId).parentFile, "$bookId.notes")

    fun get(store: StudyStore, bookId: String): String? = runCatching {
        val f = file(store, bookId)
        if (!f.exists()) return null
        val pdf = store.pdfFile(bookId)
        val text = f.readText()
        if (text.substringBefore('\n') != "#v1\t${pdf.length()}:${pdf.lastModified()}") null else text.substringAfter('\n').ifBlank { null }
    }.getOrNull()

    fun save(store: StudyStore, bookId: String, notes: String) {
        runCatching {
            val pdf = store.pdfFile(bookId)
            file(store, bookId).writeText("#v1\t${pdf.length()}:${pdf.lastModified()}\n$notes")
        }
    }
}
