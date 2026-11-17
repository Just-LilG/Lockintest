package com.godbless.lockin.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.SessionRepository
import com.godbless.lockin.data.SessionRow
import com.godbless.lockin.data.Totals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** A session shorter than this is treated as a mis-tap and not saved. */
private const val MinSeconds = 30

/**
 * Your sessions and the numbers worked out from them. A session that has just ended is counted
 * straight away, before the server has confirmed it, so the screens never show a total that drops.
 */
internal class SessionState(private val repo: SessionRepository, private val scope: CoroutineScope) {
    var stats by mutableStateOf(Stats.Empty)
        private set

    /** True once the first refresh has finished, whether or not it reached the server. */
    var loaded by mutableStateOf(false)
        private set

    private var rows = emptyList<SessionRow>()
    private var totals = Totals.Empty
    private var unsent = emptyList<SessionRow>()
    private var partnerName = "Your partner"
    private var pairedAt: Long? = null
    private var restDays: Set<java.time.DayOfWeek> = emptySet()

    /** Days you rest on purpose: they don't break your streak. */
    fun setRestDays(days: Set<java.time.DayOfWeek>) {
        if (days == restDays) return
        restDays = days
        rebuild()
    }

    fun setPartner(name: String, since: Long?) {
        partnerName = name
        pairedAt = since
        rebuild()
    }

    /** Sends anything waiting, then fetches the latest. Safe to call as often as you like. */
    suspend fun refresh() {
        repo.flush()
        unsent = repo.unsent()
        repo.recent().onSuccess { rows = it }
        repo.totals().onSuccess { totals = it }
        rebuild()
        loaded = true
    }

    /**
     * Call when a lock ends. [seconds] is how long it ran. [id] defaults to a fresh one; pass a stable id
     * (see [lockSessionId]) when the same session could be recorded twice, and the server keeps one copy.
     */
    fun record(startedAt: Long, seconds: Int, pickups: Int, title: String = "Focus session", id: String = UUID.randomUUID().toString()) {
        if (seconds < MinSeconds) return
        if (unsent.any { it.id == id } || rows.any { it.id == id }) return
        val row = SessionRow(id, true, startedAt, seconds, pickups, title)
        unsent = unsent + row
        rebuild()
        scope.launch {
            repo.enqueue(row)
            refresh()
        }
    }

    private fun rebuild() {
        val known = rows.map { it.id }.toSet()
        val pending = unsent.filter { it.id !in known }
        stats = buildStats(rows + pending, totals, pending, partnerName, pairedAt, System.currentTimeMillis(), restDays = restDays)
    }
}

/** The same session id for the same partner lock, on every phone and after a restart. */
internal fun lockSessionId(lockId: Long): String = UUID.nameUUIDFromBytes("lockin-partner-lock:$lockId".toByteArray()).toString()
