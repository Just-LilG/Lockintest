package com.godbless.lockin.ludo

/** A point on the board in cell units: (0, 0) is the board's top-left corner, (15, 15) its bottom-right. */
internal data class P(val x: Float, val y: Float) {
    operator fun plus(o: P) = P(x + o.x, y + o.y)
    operator fun minus(o: P) = P(x - o.x, y - o.y)
    operator fun times(f: Float) = P(x * f, y * f)
}

/**
 * The Ludo board, the same as the server's (supabase/migrations/20261127_ludo.sql). A 15 x 15 grid with a cross-shaped
 * track of 52 squares, numbered clockwise from seat 0's start. Seat s starts on square 13*s; a token's progress p is 0..50
 * on the track, 51..55 in its own coloured lane, 56 home, -1 in the yard and -2 gone (its player left).
 *
 * Positions here are "canonical", seat 0 (red) in the top-left corner. Each phone turns the board so its own seat sits
 * bottom-left ([view]).
 */
internal object LudoGeo {
    const val N = 15
    const val HOME = 56

    /** The 52 track squares as (column, row), clockwise from seat 0's start. */
    val TRACK: List<IntArray> = buildList {
        for (c in 1..5) add(intArrayOf(c, 6))
        for (r in 5 downTo 0) add(intArrayOf(6, r))
        add(intArrayOf(7, 0))
        for (r in 0..5) add(intArrayOf(8, r))
        for (c in 9..14) add(intArrayOf(c, 6))
        add(intArrayOf(14, 7))
        for (c in 14 downTo 9) add(intArrayOf(c, 8))
        for (r in 9..14) add(intArrayOf(8, r))
        add(intArrayOf(7, 14))
        for (r in 14 downTo 9) add(intArrayOf(6, r))
        for (c in 5 downTo 0) add(intArrayOf(c, 8))
        add(intArrayOf(0, 7))
        add(intArrayOf(0, 6))
    }

    /** Turns a point a quarter turn clockwise about the board's centre, [times] times. */
    fun rot(p: P, times: Int): P {
        var q = p
        repeat(((times % 4) + 4) % 4) { q = P(N - q.y, q.x) }
        return q
    }

    private fun cell(c: Int, r: Int) = P(c + 0.5f, r + 0.5f)

    fun abs(seat: Int, pos: Int): Int? = if (pos in 0..50) (seat * 13 + pos) % 52 else null
    fun safe(abs: Int?): Boolean = abs != null && (abs % 13 == 0 || abs % 13 == 8)
    fun isStart(abs: Int): Boolean = abs % 13 == 0
    fun isStar(abs: Int): Boolean = abs % 13 == 8

    /** Seat 0's yard sockets (the four round spots inside its corner). */
    private val SOCKETS = listOf(P(2.0f, 2.0f), P(4.0f, 2.0f), P(2.0f, 4.0f), P(4.0f, 4.0f))

    /** Seat 0's home lane: progress 51..55. */
    private fun lane0(pos: Int) = cell(pos - 50, 7)

    /** Where seat 0's finished tokens rest, in its triangle of the centre. */
    private val HOME0 = listOf(P(6.55f, 7.1f), P(6.55f, 7.9f), P(7.05f, 7.5f), P(6.35f, 7.5f))

    /** The canonical centre of token [k] of [seat] at progress [pos], or null when it is not on the board. */
    fun point(seat: Int, pos: Int, k: Int): P? = when {
        pos == -1 -> rot(SOCKETS[k], seat)
        pos in 0..50 -> TRACK[abs(seat, pos)!!].let { cell(it[0], it[1]) }
        pos in 51..55 -> rot(lane0(pos), seat)
        pos == HOME -> rot(HOME0[k], seat)
        else -> null
    }

    /** The centre of a track square. */
    fun square(abs: Int): P = TRACK[abs].let { cell(it[0], it[1]) }

    /** The centre of a yard (the big coloured corner) of [seat]. */
    fun yardCentre(seat: Int): P = rot(P(3f, 3f), seat)

    /** The board point where [seat]'s lane square [pos] (51..55) is. */
    fun laneSquare(seat: Int, pos: Int): P = rot(lane0(pos), seat)

    /** Where a token at [pos] goes with [dice], or null if it can't move. */
    fun dest(pos: Int, dice: Int): Int? = when {
        pos == -1 -> if (dice == 6) 0 else null
        pos in 0..55 && pos + dice <= HOME -> pos + dice
        else -> null
    }

    /** The tokens (0..3) of [seat] that can move with [dice]. */
    fun movable(tokens: List<Int>, seat: Int, dice: Int): List<Int> =
        (0..3).filter { dest(tokens.getOrElse(seat * 4 + it) { -2 }, dice) != null }

    /**
     * The squares a token passes through, one point per hop (the first is where it lands after one step). Leaving the yard
     * is one hop onto the start square.
     */
    fun path(seat: Int, k: Int, from: Int, to: Int): List<P> =
        if (from == -1) listOfNotNull(point(seat, 0, k))
        else (from + 1..to).mapNotNull { point(seat, it, k) }

    /** How far round the board each seat is turned for the person sitting at [mySeat] (their corner bottom-left). */
    fun viewTurns(mySeat: Int): Int = if (mySeat in 0..3) (3 - mySeat + 4) % 4 else 0

    /** The canonical point [p] as the viewer sees it. */
    fun view(p: P, turns: Int): P = rot(p, turns)

    /** And back, for a touch. */
    fun unview(p: P, turns: Int): P = rot(p, 4 - turns)

    /** The corner (0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left) where [seat] shows for the viewer. */
    fun corner(seat: Int, turns: Int): Int = (seat + turns) % 4

    /** The seat shown in [corner] for the viewer. */
    fun seatAt(corner: Int, turns: Int): Int = ((corner - turns) % 4 + 4) % 4
}
