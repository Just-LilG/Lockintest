package com.godbless.lockin.study

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private fun q(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

/**
 * Your cards as a CSV (book, question, answer, page) shared through the share sheet: a copy of your own deck, or one
 * for a friend. [bookId]: just that book's cards (null for all).
 */
internal fun exportCards(context: Context, store: StudyStore, bookId: String? = null) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val title = bookId?.let { store.book(it)?.title }?.let { prettyTitle(it) }
        val file = File(dir, if (title != null) "lockin-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) + ".csv" else "lockin-cards.csv")
        file.bufferedWriter().use { w ->
            w.write("book,question,answer,page\n")
            store.cards.filter { bookId == null || it.book == bookId }.forEach { c ->
                w.write(q(store.book(c.book)?.title.orEmpty()) + "," + q(c.question) + "," + q(c.answer) + "," + c.page + "\n")
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, if (title != null) "My LockIn flashcards: $title" else "My LockIn flashcards")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share my cards").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "Couldn't export just now.", Toast.LENGTH_SHORT).show() }
}

/** Splits CSV text into rows of fields (quotes, doubled quotes and line breaks inside quotes all work). */
internal fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    fun endField() { row.add(field.toString()); field.setLength(0) }
    fun endRow() { endField(); if (row.any { it.isNotBlank() }) rows.add(row); row = ArrayList() }
    while (i < text.length) {
        val ch = text[i]
        when {
            inQuotes && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            ch == '"' -> inQuotes = !inQuotes
            !inQuotes && ch == ',' -> endField()
            !inQuotes && ch == '\n' -> endRow()
            !inQuotes && ch == '\r' -> {}
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}

/**
 * Reads cards from a CSV file: question and answer in the first two columns, or the columns of an export from here
 * (book, question, answer, page). A first row of headings is skipped. Cards already in the deck are not added twice.
 * Returns how many were added, or -1 if the file couldn't be read.
 */
internal fun importCards(context: Context, store: StudyStore, uri: Uri, intoBook: String): Int {
    val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: return -1
    var rows = parseCsv(text.removePrefix("\uFEFF"))
    if (rows.isEmpty()) return 0
    val head = rows.first().map { it.trim().lowercase() }
    val ours = head.size >= 3 && head[0] == "book" && head[1] == "question"
    if (ours || (head.size >= 2 && (head[0] == "question" || head[0] == "front"))) rows = rows.drop(1)
    val have = store.cards.map { it.question.trim().lowercase() + "\u0001" + it.answer.trim().lowercase() }.toHashSet()
    var added = 0
    for (r in rows.take(2000)) {
        val (qs, a, bookTitle, page) = if (ours) listOf(r.getOrElse(1) { "" }, r.getOrElse(2) { "" }, r.getOrElse(0) { "" }, r.getOrElse(3) { "0" })
        else listOf(r.getOrElse(0) { "" }, r.getOrElse(1) { "" }, "", "0")
        if (qs.isBlank() || a.isBlank()) continue
        val key = qs.trim().lowercase() + "\u0001" + a.trim().lowercase()
        if (!have.add(key)) continue
        val book = store.books.firstOrNull { it.title.equals(bookTitle, ignoreCase = true) }?.id ?: intoBook
        store.addCard(book, qs.take(500), a.take(500), page.trim().toIntOrNull() ?: 0)
        added++
    }
    return added
}

/** One row of a CSV about to be imported. [bookTitle]: the book named in the file (LockIn's own exports), or "". */
internal class ImportRow(val question: String, val answer: String, val bookTitle: String, val page: Int)

/** What a CSV would add: the new rows, and how many it has that are already in the deck. */
internal class ImportPreview(val rows: List<ImportRow>, val dupes: Int)

/** Reads a CSV and works out what it would add, without adding anything. Null if the file couldn't be read. */
internal fun previewImport(context: Context, store: StudyStore, uri: Uri): ImportPreview? {
    val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: return null
    var rows = parseCsv(text.removePrefix("\uFEFF"))
    if (rows.isEmpty()) return ImportPreview(emptyList(), 0)
    val head = rows.first().map { it.trim().lowercase() }
    val ours = head.size >= 3 && head[0] == "book" && head[1] == "question"
    if (ours || (head.size >= 2 && (head[0] == "question" || head[0] == "front" || head[0] == "q"))) rows = rows.drop(1)
    val have = store.cards.map { it.question.trim().lowercase() + "\u0001" + it.answer.trim().lowercase() }.toHashSet()
    val out = ArrayList<ImportRow>()
    var dupes = 0
    for (r in rows.take(2000)) {
        val qs = (if (ours) r.getOrElse(1) { "" } else r.getOrElse(0) { "" }).trim()
        val a = (if (ours) r.getOrElse(2) { "" } else r.getOrElse(1) { "" }).trim()
        if (qs.isBlank() || a.isBlank()) continue
        if (!have.add(qs.lowercase() + "\u0001" + a.lowercase())) { dupes++; continue }
        out.add(ImportRow(qs.take(500), a.take(500), if (ours) r.getOrElse(0) { "" }.trim() else "", if (ours) r.getOrElse(3) { "0" }.trim().toIntOrNull() ?: 0 else 0))
    }
    return ImportPreview(out, dupes)
}

/** Adds what [previewImport] found. Rows naming a book you have go there; the rest go to [intoBook]. */
internal fun commitImport(store: StudyStore, preview: ImportPreview, intoBook: String): Int {
    for (r in preview.rows) {
        val book = store.books.firstOrNull { it.title.equals(r.bookTitle, true) || prettyTitle(it.title).equals(r.bookTitle, true) }?.id ?: intoBook
        store.addCard(book, r.question, r.answer, r.page)
    }
    return preview.rows.size
}
