package com.godbless.lockin.lock

import android.content.Context
import java.util.Random

/**
 * The words Word Duel knows: every 5-letter word it accepts as a guess (`assets/words5.txt`), and the everyday ones it picks from
 * when you ask it to choose (`assets/answers5.txt`). Read once, on first use. Until they are read every word is accepted, so a
 * slow phone never blocks a guess; the server only checks the shape (five letters).
 */
internal object WordBank {
    @Volatile private var valid: Set<String>? = null
    @Volatile private var answers: List<String> = emptyList()
    private val random = Random()

    /** Reads the lists (call it off the main thread; it is quick: about 60 KB). */
    fun load(context: Context) {
        if (valid != null) return
        val app = context.applicationContext
        val v = HashSet<String>(12_000)
        val a = ArrayList<String>(2_000)
        runCatching { app.assets.open("words5.txt").bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.length == 5 }.forEach { v.add(it) } } }
        runCatching { app.assets.open("answers5.txt").bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.length == 5 }.forEach { a.add(it) } } }
        v.addAll(a)
        answers = a
        valid = if (v.isEmpty()) null else v
    }

    fun isWord(w: String): Boolean {
        val set = valid ?: return true
        return w in set
    }

    /** An everyday word to use as a secret. */
    fun pick(): String = answers.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] } ?: "crane"
}

/** How [guess] scores against [word]: five letters of g (right place), y (in the word, elsewhere) or b (not in it). Same rule as the server. */
internal fun wdMarks(guess: String, word: String): String {
    if (guess.length != 5 || word.length != 5) return "bbbbb"
    val res = CharArray(5) { 'b' }
    val left = word.toCharArray()
    for (i in 0 until 5) if (guess[i] == word[i]) { res[i] = 'g'; left[i] = ' ' }
    for (i in 0 until 5) {
        if (res[i] == 'g') continue
        val j = left.indexOf(guess[i])
        if (j >= 0) { res[i] = 'y'; left[j] = ' ' }
    }
    return String(res)
}
