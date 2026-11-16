package com.godbless.lockin.data

import org.json.JSONObject

/** One guess and how it scored: [marks] has a letter per tile, g (right place), y (in the word, elsewhere) or b (not in it). */
class WdGuess(val word: String, val marks: String)

/**
 * Word Duel with your partner, over the server's wd_ functions. [status] is none, setting (the setter has yet to choose a
 * word), guessing, solved or failed. The secret [word] is only here when I set it or the round is over.
 */
data class WdGame(
    val status: String,
    val round: Int = 1,
    /** I am the one who sets the word this round (the other guesses). */
    val iSet: Boolean = false,
    /** It is my move: the setter has a word to choose, or the guesser has a guess to make. */
    val myTurn: Boolean = false,
    val word: String = "",
    val clue: String = "",
    val guesses: List<WdGuess> = emptyList(),
    val tries: Int = 6,
    val invitedByMe: Boolean = false,
    val otherHere: Boolean = false,
    val scoreMe: Int = 0,
    val scoreThem: Int = 0,
    /** Points the round was worth to whoever won it (a solve: 7 minus the tries; a fail: 2 for the setter). */
    val points: Int = 0,
    val note: Long = 0L,
    val rev: Long = 0L,
) {
    val none get() = status == "none"
    val over get() = status == "solved" || status == "failed"
    val triesLeft get() = (tries - guesses.size).coerceAtLeast(0)
}

/** Plain words for whatever went wrong, whether it came as a server code or as a sentence already. */
fun wdMessage(e: Throwable): String {
    val m = e.message.orEmpty().trim()
    return when {
        m.contains("not_your_turn") -> "It's not your move."
        m.contains("bad_word") -> "That needs to be a 5-letter word."
        m.contains("dup") -> "You already tried that word."
        m.contains("no_game") -> "That game has ended."
        m.contains("unpair_pending") -> "They've asked to unpair, so games are paused."
        m.contains("not_paired") -> "You're not paired with anyone right now."
        m.contains("not_signed_in") -> "You're signed out. Sign in again."
        m.isEmpty() || m.matches(Regex("[A-Za-z0-9_]+")) -> "Something went wrong. Try again."
        else -> m
    }
}

fun wdNoGame(e: Throwable): Boolean {
    val m = e.message.orEmpty()
    return m.contains("no_game") || m == "That game has ended."
}

class WordDuelRepository(private val partners: PartnerRepository) {

    private fun parse(text: String): WdGame {
        val j = JSONObject(text)
        val status = j.optString("status", "none")
        if (status == "none") return WdGame("none")
        val gs = j.optJSONArray("guesses")
        return WdGame(
            status = status,
            round = j.optInt("round", 1),
            iSet = j.optBoolean("i_set", false),
            myTurn = j.optBoolean("my_turn", false),
            word = if (j.isNull("word")) "" else j.optString("word", ""),
            clue = if (j.isNull("clue")) "" else j.optString("clue", ""),
            guesses = if (gs == null) emptyList() else List(gs.length()) {
                val o = gs.getJSONObject(it)
                WdGuess(o.optString("w", ""), o.optString("m", ""))
            },
            tries = j.optInt("tries", 6),
            invitedByMe = j.optBoolean("invited_by_me", false),
            otherHere = j.optBoolean("other_here", false),
            scoreMe = j.optInt("score_me", 0),
            scoreThem = j.optInt("score_them", 0),
            points = j.optInt("points", 0),
            note = j.optLong("note", 0L),
            rev = j.optLong("rev", 0L),
        )
    }

    private suspend fun rpc(name: String, body: String = "{}") = parse(partners.call("POST", "/rest/v1/rpc/$name", body).body)

    private fun WdGame.pushed(): WdGame = also { if (it.note > 0L) partners.pushAsync(it.note) }

    /** The game as it stands. Asking also tells my partner's phone that I have it open. */
    suspend fun state(): Result<WdGame> = partners.attempt { rpc("wd_state") }

    /** A look that does not tell my partner I have it open (for the chat's game card). */
    suspend fun peek(): Result<WdGame> = partners.attempt { rpc("wd_peek") }

    /** Opens the game (I set the first word, and my partner gets a note in the chat), or returns the one that exists. */
    suspend fun play(): Result<WdGame> = partners.attempt { rpc("wd_play").pushed() }

    /** The setter picks the secret word, with an optional short clue. */
    suspend fun set(word: String, clue: String): Result<WdGame> = partners.attempt {
        rpc("wd_set", JSONObject().put("p_word", word).put("p_clue", clue).toString()).pushed()
    }

    /** The guesser tries a word. */
    suspend fun guess(word: String): Result<WdGame> = partners.attempt {
        rpc("wd_guess", JSONObject().put("p_word", word).toString()).pushed()
    }

    /** After a round: swap roles and start the next. */
    suspend fun next(): Result<WdGame> = partners.attempt { rpc("wd_next").pushed() }

    /** Ends the game for both of you. */
    suspend fun quit(): Result<Unit> = partners.attempt { partners.call("POST", "/rest/v1/rpc/wd_quit", "{}"); Unit }
}
