package com.godbless.lockin.data

import org.json.JSONObject

/**
 * Tic-tac-toe with your partner, over the server's ttt_ functions. Only the two of you can ever see or touch
 * your game. [status] is none, waiting (an invite nobody has answered), playing, won or draw.
 */
class TttGame(
    val status: String,
    /** Nine characters, cell 0 top-left to 8 bottom-right: '.', 'X' or 'O'. */
    val board: String = ".........",
    /** Which mark is mine: "X" or "O". X goes first. */
    val iAm: String = "X",
    val myTurn: Boolean = false,
    /** "me", "them" or "" while nobody has won. */
    val winner: String = "",
    /** The three winning cells, or empty. */
    val line: List<Int> = emptyList(),
    val invitedByMe: Boolean = false,
    /** Whether my partner has the game open right now. */
    val otherHere: Boolean = false,
    val winsMe: Int = 0,
    val winsThem: Int = 0,
    /** The chat note announcing a new invite, to push to my partner; 0 when none. */
    val note: Long = 0L,
    /** When the game last changed (server clock, milliseconds): a late answer never overwrites a newer one. */
    val rev: Long = 0L,
    /** My mark that disappears on my next move (only when I have three), or -1. Old servers never send it. */
    val fadeMe: Int = -1,
    /** My partner's mark that is about to disappear, or -1. */
    val fadeThem: Int = -1,
    /** "slide" (three marks each, then you move one of your own), "vanish" or "classic" (older servers). */
    val rule: String = "classic",
    /** It is my turn and I have three marks out, so my move is to slide one of them. */
    val mustSlide: Boolean = false,
    /** The squares of the latest slide (-1 when the last move was a placement), and where the latest mark landed. */
    val lastFrom: Int = -1,
    val lastTo: Int = -1,
) {
    val over get() = status == "won" || status == "draw"
    val none get() = status == "none"

    /** My marks: the cells holding [iAm]. */
    val mine: List<Int> get() = board.indices.filter { board[it].toString() == iAm }

    /** This game as it will look once I place a mark on [cell], for showing it before the server answers. */
    fun afterMyMove(cell: Int): TttGame {
        val b = board.toCharArray()
        if (cell !in b.indices) return this
        b[cell] = iAm.firstOrNull() ?: 'X'
        return copyWith(b.concatToString(), -1, cell)
    }

    /** This game as it will look once I slide my mark from [from] to [to]. Who wins is left to the server. */
    fun afterMySlide(from: Int, to: Int): TttGame {
        val b = board.toCharArray()
        if (from !in b.indices || to !in b.indices) return this
        b[from] = '.'
        b[to] = iAm.firstOrNull() ?: 'X'
        return copyWith(b.concatToString(), from, to)
    }

    /**
     * This game once my partner has placed a mark on [to] ([from] is -1) or slid one from [from] to [to]. It is shown the moment
     * their phone says so, before the server has confirmed it, so my turn only starts when the real answer arrives.
     */
    fun afterTheirMove(from: Int, to: Int): TttGame {
        val theirs = if (iAm == "X") 'O' else 'X'
        val b = board.toCharArray()
        if (to !in b.indices || b[to] != '.') return this
        if (from in b.indices) {
            if (b[from] != theirs) return this
            b[from] = '.'
        }
        b[to] = theirs
        return copyWith(b.concatToString(), from, to)
    }

    private fun copyWith(newBoard: String, from: Int, to: Int) = TttGame(
        status = status, board = newBoard, iAm = iAm, myTurn = false, winner = winner, line = line,
        invitedByMe = invitedByMe, otherHere = otherHere, winsMe = winsMe, winsThem = winsThem, note = note,
        rev = rev, fadeMe = -1, fadeThem = -1, rule = rule, mustSlide = false, lastFrom = from, lastTo = to,
    )
}

/** Plain words for whatever went wrong, whether it came as a server code or as a sentence already. */
fun tttMessage(e: Throwable): String {
    val m = e.message.orEmpty().trim()
    return when {
        m.contains("not_your_turn") -> "It's their turn."
        m.contains("bad_cell") -> "That square is taken."
        m.contains("must_slide") -> "Move one of your marks instead."
        m.contains("must_place") -> "Place a mark first."
        m.contains("no_game") -> "That game has ended."
        m.contains("unpair_pending") -> "They've asked to unpair, so games are paused."
        m.contains("not_paired") -> "You're not paired with anyone right now."
        m.contains("not_signed_in") -> "You're signed out. Sign in again."
        m.isEmpty() || m.matches(Regex("[A-Za-z0-9_]+")) -> "Something went wrong. Try again."
        else -> m
    }
}

/** Whether the failure just means there is no game any more (a refresh will show that). */
fun tttNoGame(e: Throwable): Boolean {
    val m = e.message.orEmpty()
    return m.contains("no_game") || m == "That game has ended."
}

class TttRepository(private val partners: PartnerRepository) {

    private fun cellOrNone(j: JSONObject, key: String): Int {
        if (j.isNull(key)) return -1
        val v = j.optInt(key, -1)
        return if (v in 0..8) v else -1
    }

    private fun parse(text: String): TttGame {
        val j = JSONObject(text)
        val status = j.optString("status", "none")
        if (status == "none") return TttGame("none")
        val line = j.optJSONArray("line")
        return TttGame(
            status = status,
            board = j.optString("board", ".........").padEnd(9, '.'),
            iAm = j.optString("i_am", "X"),
            myTurn = j.optBoolean("my_turn", false),
            winner = if (j.isNull("winner")) "" else j.optString("winner", ""),
            line = if (line == null) emptyList() else List(line.length()) { line.getInt(it) },
            invitedByMe = j.optBoolean("invited_by_me", false),
            otherHere = j.optBoolean("other_here", false),
            winsMe = j.optInt("wins_me", 0),
            winsThem = j.optInt("wins_them", 0),
            note = j.optLong("note", 0L),
            rev = j.optLong("rev", 0L),
            fadeMe = cellOrNone(j, "fade_me"),
            fadeThem = cellOrNone(j, "fade_them"),
            rule = if (j.isNull("rule")) "classic" else j.optString("rule", "classic"),
            mustSlide = j.optBoolean("must_slide", false),
            lastFrom = cellOrNone(j, "last_from"),
            lastTo = cellOrNone(j, "last_to"),
        )
    }

    private suspend fun rpc(name: String, body: String = "{}") = parse(partners.call("POST", "/rest/v1/rpc/$name", body).body)

    /** The game as it stands. Asking also tells my partner's phone that I have the game open. */
    suspend fun state(): Result<TttGame> = partners.attempt { rpc("ttt_state") }

    /** A look at the game that does not tell my partner I have it open (used by the chat's game card). */
    suspend fun peek(): Result<TttGame> = partners.attempt { rpc("ttt_peek") }

    /** Joins the game my partner opened, or opens one (they get a note in the chat). */
    suspend fun play(): Result<TttGame> = partners.attempt {
        rpc("ttt_play").also { g -> if (g.note > 0L) partners.pushAsync(g.note) }
    }

    /** Places a mark. A note id comes back when my partner was away, and gets them a push ("played their turn"). */
    suspend fun move(cell: Int): Result<TttGame> = partners.attempt {
        rpc("ttt_move", JSONObject().put("p_cell", cell).toString()).also { g -> if (g.note > 0L) partners.pushAsync(g.note) }
    }

    /** Picks up one of my marks and puts it on another, empty square (once I have three out). */
    suspend fun slide(from: Int, to: Int): Result<TttGame> = partners.attempt {
        rpc("ttt_slide", JSONObject().put("p_from", from).put("p_to", to).toString()).also { g -> if (g.note > 0L) partners.pushAsync(g.note) }
    }

    /** A new board after a game; the one who went second goes first. */
    suspend fun again(): Result<TttGame> = partners.attempt {
        rpc("ttt_again").also { g -> if (g.note > 0L) partners.pushAsync(g.note) }
    }

    /** Ends the game for both of you. */
    suspend fun quit(): Result<Unit> = partners.attempt { partners.call("POST", "/rest/v1/rpc/ttt_quit", "{}"); Unit }
}
