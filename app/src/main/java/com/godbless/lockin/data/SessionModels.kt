package com.godbless.lockin.data

/** One finished lock. [mine] is false for your partner's sessions, which you are allowed to see. */
class SessionRow(
    val id: String,
    val mine: Boolean,
    val startedAt: Long,
    val seconds: Int,
    val pickups: Int,
    val title: String,
)

/** All-time numbers, kept on the server so the app never has to download every session. */
class Totals(val mineSeconds: Long, val mineCount: Int, val partnerSeconds: Long, val partnerCount: Int) {
    companion object {
        val Empty = Totals(0, 0, 0, 0)
    }
}
