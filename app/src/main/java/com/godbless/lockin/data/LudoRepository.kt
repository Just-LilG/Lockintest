package com.godbless.lockin.data

import com.godbless.lockin.live.LiveChannel
import org.json.JSONArray
import org.json.JSONObject

/** One of the four seats. [id] is null for an empty seat. [place] is 1 for the winner, 2 next and so on (0 = still playing). */
data class LudoSeat(
    val seat: Int,
    val id: String?,
    val name: String?,
    val here: Boolean,
    val left: Boolean,
    val place: Int,
    val wins: Int,
) {
    val taken get() = id != null
    val first get() = name?.trim()?.substringBefore(' ')?.ifBlank { null } ?: "Someone"
}

/** A token knocked back to its yard by a move: token [t] (0..15) was at progress [from]. */
data class LudoCap(val t: Int, val from: Int)

/**
 * What happened last, so every phone can play the same animation. [kind] is start, roll, move, sit, stand, again or left.
 * A roll that moved straight away arrives as a move with [rolled] set.
 */
data class LudoLast(
    val kind: String,
    val seat: Int = -1,
    val token: Int = -1,
    val from: Int = 0,
    val to: Int = 0,
    val roll: Int = 0,
    val rolled: Boolean = false,
    val caps: List<LudoCap> = emptyList(),
    val home: Boolean = false,
    val done: Boolean = false,
    val bonus: Boolean = false,
    val auto: Boolean = false,
    val over: Boolean = false,
    val none: Boolean = false,
    val three: Boolean = false,
    val first: Int = -1,
    val seq: Long = 0,
)

/**
 * A game of Ludo. [scope] is pair or crew; [status] none, lobby (a crew table filling up), playing or over. [tokens] has four
 * entries per seat (see LudoGeo for what the numbers mean). [json] is the server's answer as it came, so it can be passed to
 * the other phones over the live channel.
 */
data class LudoGame(
    val status: String,
    val scope: String = "pair",
    val id: String = "",
    val host: String = "",
    val seats: List<LudoSeat> = emptyList(),
    val tokens: List<Int> = List(16) { -1 },
    val turn: Int = 0,
    val phase: String = "roll",
    val dice: Int = 0,
    val sixes: Int = 0,
    val last: LudoLast? = null,
    val seq: Long = 0,
    /** How long the player on turn has left, as of [at]. */
    val leftMs: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val meSeat: Int = -1,
    val rev: Long = 0,
    val note: Long = 0,
    val crewNote: Long = 0,
    val json: String = "",
) {
    val none get() = status == "none"
    val lobby get() = status == "lobby"
    val playing get() = status == "playing"
    val over get() = status == "over"
    val seated get() = seats.filter { it.taken }
    val myTurn get() = playing && meSeat >= 0 && turn == meSeat
    val iHost get() = meSeat >= 0 && seats.getOrNull(meSeat)?.id == host
    fun seat(s: Int) = seats.getOrNull(s)
    fun msLeftNow(now: Long = System.currentTimeMillis()) = (leftMs - (now - at)).coerceAtLeast(0)
}

/** Plain words for whatever went wrong. */
fun ludoMessage(e: Throwable): String {
    val m = e.message.orEmpty().trim()
    return when {
        m.contains("not_your_turn") -> "It's not your turn."
        m.contains("not_now") -> "That's already happened. Have a look at the board."
        m.contains("seat_taken") -> "Someone just took that seat."
        m.contains("need_two") -> "You need at least two players to start."
        m.contains("not_host") -> "Only the person who set up the table can start it."
        m.contains("bad_token") -> "That token can't move with this roll."
        m.contains("no_game") -> "That game has ended."
        m.contains("no_crew") -> "You're not in a crew right now."
        m.contains("unpair_pending") -> "They've asked to unpair, so games are paused."
        m.contains("not_paired") -> "You're not paired with anyone right now."
        m.contains("not_signed_in") -> "You're signed out. Sign in again."
        m.isEmpty() || m.matches(Regex("[A-Za-z0-9_]+")) -> "Something went wrong. Try again."
        else -> m
    }
}

/**
 * Ludo over the server's ludo_ functions. The dice are rolled on the server, never here. [scope] is "pair" (you and your
 * partner) or "crew" (a table for up to four people in your crew).
 */
class LudoRepository(internal val partners: PartnerRepository) {

    /** My user id, to find my seat in a game that came from another phone. */
    fun me(): String? = partners.signedInUser()

    /** The crew's own live line, made the first time a crew table is opened. */
    internal val crewLive: LiveChannel by lazy { LiveChannel.forCrew(partners) }

    fun parse(text: String, at: Long = System.currentTimeMillis()): LudoGame {
        val j = JSONObject(text)
        val status = j.optString("status", "none")
        if (status == "none") return LudoGame("none", json = text)
        val me = me()
        val sa = j.optJSONArray("seats") ?: JSONArray()
        val seats = List(4) { i ->
            val o = sa.optJSONObject(i) ?: JSONObject()
            LudoSeat(
                seat = i,
                id = o.optString("id").takeIf { !o.isNull("id") && it.isNotBlank() },
                name = o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
                here = o.optBoolean("here", false),
                left = o.optBoolean("left", false),
                place = o.optInt("place", 0),
                wins = o.optInt("wins", 0),
            )
        }
        val ta = j.optJSONArray("tokens") ?: JSONArray()
        val tokens = List(16) { ta.optInt(it, -1) }
        val l = j.optJSONObject("last")
        val last = l?.let { o ->
            val ca = o.optJSONArray("caps") ?: JSONArray()
            LudoLast(
                kind = o.optString("kind"),
                seat = o.optInt("seat", -1),
                token = o.optInt("token", -1),
                from = o.optInt("from", 0),
                to = o.optInt("to", 0),
                roll = o.optInt("roll", 0),
                rolled = o.optBoolean("rolled", false),
                caps = List(ca.length()) { ca.getJSONObject(it).let { c -> LudoCap(c.optInt("t"), c.optInt("from")) } },
                home = o.optBoolean("home", false),
                done = o.optBoolean("done", false),
                bonus = o.optBoolean("bonus", false),
                auto = o.optBoolean("auto", false),
                over = o.optBoolean("over", false),
                none = o.optBoolean("none", false),
                three = o.optBoolean("three", false),
                first = o.optInt("first", -1),
                seq = o.optLong("seq", 0),
            )
        }
        return LudoGame(
            status = status,
            scope = j.optString("scope", "pair"),
            id = j.optString("id"),
            host = j.optString("host"),
            seats = seats,
            tokens = tokens,
            turn = j.optInt("turn", 0),
            phase = j.optString("phase", "roll"),
            dice = j.optInt("dice", 0),
            sixes = j.optInt("sixes", 0),
            last = last,
            seq = j.optLong("seq", 0),
            leftMs = j.optLong("left_ms", 0),
            at = at,
            // Worked out here rather than taken from the server, so a game passed on by another phone is right for me.
            meSeat = seats.indexOfFirst { it.id != null && it.id == me },
            rev = j.optLong("rev", 0),
            note = j.optLong("note", 0),
            crewNote = j.optLong("crew_note", 0),
            json = text,
        )
    }

    private suspend fun rpc(name: String, scope: String, extra: JSONObject.() -> Unit = {}): LudoGame {
        val body = JSONObject().put("p_scope", scope).apply(extra).toString()
        val g = parse(partners.call("POST", "/rest/v1/rpc/$name", body).body)
        if (g.note > 0L) partners.pushAsync(g.note)
        if (g.crewNote > 0L) partners.pushCrewAsync(g.crewNote)
        return g
    }

    /** The game as it stands; asking also marks me as at the table. */
    suspend fun state(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_state", scope) }

    /** A look that does not mark me as here (the chat's card). */
    suspend fun peek(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_peek", scope) }

    /** Opens a game (a pair game starts at once; a crew table waits for people to sit), or returns the one there is. */
    suspend fun open(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_open", scope) }

    suspend fun sit(scope: String, seat: Int): Result<LudoGame> = partners.attempt { rpc("ludo_sit", scope) { put("p_seat", seat) } }
    suspend fun stand(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_stand", scope) }
    suspend fun start(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_start", scope) }
    suspend fun roll(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_roll", scope) }
    suspend fun move(scope: String, token: Int): Result<LudoGame> = partners.attempt { rpc("ludo_move", scope) { put("p_token", token) } }

    /** Plays the turn of whoever let their time run out (the server checks it really has). */
    suspend fun tick(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_tick", scope) }

    suspend fun again(scope: String): Result<LudoGame> = partners.attempt { rpc("ludo_again", scope) }

    /** Leaves (a pair game ends for both; at a crew table the others play on). [end] lets the host close the table. */
    suspend fun leave(scope: String, end: Boolean = false): Result<LudoGame> =
        partners.attempt { rpc("ludo_leave", scope) { put("p_end", end) } }
}
