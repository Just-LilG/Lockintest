package com.godbless.lockin.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.LockRecord
import com.godbless.lockin.data.LockRules
import com.godbless.lockin.data.MyLock
import com.godbless.lockin.data.RemoteLockRepository
import com.godbless.lockin.data.TheirLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The locks between you and your partner: the one on your phone, the one you set on theirs, and their history. */
internal class LockState(private val repo: RemoteLockRepository, private val scope: CoroutineScope) {
    /** The lock your partner set on you, as last fetched. Null until the first answer. */
    var mine by mutableStateOf<MyLock?>(null)
        private set

    /** The lock you set on your partner, while it runs. */
    @set:JvmName("assignTheirs") // keeps this setter from clashing with setTheirs(t) below
    var theirs by mutableStateOf<TheirLock?>(null)
        private set

    /** When the lock you set ends, on this phone's clock (milliseconds). */
    var theirsEndsAt by mutableLongStateOf(0L)
        private set

    /** When the lock you set begins, on this phone's clock (milliseconds). Past or now once it runs. */
    var theirsStartsAt by mutableLongStateOf(0L)
        private set

    /** Finished locks between you, newest first. */
    var history by mutableStateOf<List<LockRecord>>(emptyList())
        private set

    /** Whether each of you accepts locks, and the longest one. Null until fetched. */
    var rules by mutableStateOf<LockRules?>(null)
        private set

    /** Repeating locks between you, both directions. Empty until fetched. */
    var repeats by mutableStateOf<List<com.godbless.lockin.data.LockRepeat>>(emptyList())
        private set
    private var repeatsAt = 0L

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    // The emergency release must work with no connection, so the phone lets go first and tells the server when it can.
    private var emergencyPending = false

    suspend fun refresh(paired: Boolean) {
        if (!paired) {
            mine = null
            theirs = null
            theirsEndsAt = 0L
            theirsStartsAt = 0L
            history = emptyList()
            repeats = emptyList()
            onRepeats(emptyList())
            rules = null
            return
        }
        if (emergencyPending) repo.emergency().onSuccess { emergencyPending = false }
        repo.mine().onSuccess { mine = it }
        // Repeats change rarely: once a minute is plenty.
        if (System.currentTimeMillis() - repeatsAt > 60_000L) loadRepeats()
        repo.theirs()
            .onSuccess { setTheirs(it) }
            .onFailure { Log.w(TAG, "their_lock failed: ${it.message}") }
    }

    private fun setTheirs(t: TheirLock?) {
        theirs = t
        val now = System.currentTimeMillis()
        theirsEndsAt = if (t == null) 0L else now + t.remaining * 1000L
        theirsStartsAt = if (t == null) 0L else now + t.startsIn * 1000L
    }

    /** Locks your partner. [onLocked] runs once the server has accepted it. */
    fun lock(minutes: Int, message: String, strict: Boolean, together: Boolean, startInMinutes: Int = 0, onLocked: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        Log.i(TAG, "lock requested: $minutes min, strict=$strict, together=$together")
        scope.launch {
            repo.lock(minutes * 60, message, strict, together, startInMinutes * 60)
                .onSuccess {
                    Log.i(TAG, "lock set: id=${it.id} remaining=${it.remaining}")
                    setTheirs(it)
                    onLocked()
                }
                .onFailure {
                    Log.w(TAG, "lock failed: ${it.message}")
                    error = it.message ?: "Couldn't lock them. Try again."
                }
            busy = false
            // Ask the server what it now holds, so this page and their phone agree.
            repo.theirs().onSuccess { server -> if (server != null) setTheirs(server) }
            loadHistory()
        }
    }

    fun unlock() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.unlock().onSuccess { setTheirs(null) }.onFailure { error = it.message ?: "Couldn't unlock them. Try again." }
            busy = false
            loadHistory()
        }
    }

    /** Adds time to the lock that is running. */
    fun extend(minutes: Int) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.extend(minutes * 60)
                .onSuccess { repo.theirs().onSuccess { server -> setTheirs(server) } }
                .onFailure { error = it.message ?: "Couldn't add time. Try again." }
            busy = false
        }
    }

    /** Answers a request to unlock: [approve] lets them out, otherwise it is a polite no. */
    fun answer(approve: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.answer(approve)
                .onSuccess { repo.theirs().onSuccess { server -> setTheirs(server) } }
                .onFailure { error = it.message ?: "Couldn't send that. Try again." }
            busy = false
            if (approve) loadHistory()
        }
    }

    /** The locked person asks to be let out. [done] gets null on success, or a sentence saying what went wrong. */
    fun ask(reason: String, done: (String?) -> Unit) {
        scope.launch {
            repo.ask(reason).onSuccess { done(null) }.onFailure { done(it.message ?: "Couldn't send that. Try again.") }
        }
    }

    /** The locked person takes their request back. [done] gets null on success, or a sentence saying what went wrong. */
    fun cancelAsk(done: (String?) -> Unit) {
        scope.launch {
            repo.askCancel().onSuccess { done(null) }.onFailure { done(it.message ?: "Couldn't cancel that. Try again.") }
        }
    }

    /** The locked person reports how many times they picked up their phone. */
    fun report(pickups: Int) {
        scope.launch { repo.report(pickups) }
    }

    fun emergency() {
        scope.launch { repo.emergency().onFailure { emergencyPending = true } }
    }

    suspend fun loadRepeats() {
        repo.repeats().onSuccess {
            repeats = it
            repeatsAt = System.currentTimeMillis()
            onRepeats(it.filter { r -> !r.mine })
        }
    }

    /** Set by Home: the repeats on this phone, so it can wake itself at the next start. */
    var onRepeats: (List<com.godbless.lockin.data.LockRepeat>) -> Unit = {}

    /** Adds a repeating lock on your partner. [done] runs once the server has it. */
    fun addRepeat(days: Int, startMinute: Int, minutes: Int, message: String, strict: Boolean, done: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.addRepeat(days, startMinute, minutes * 60, message, strict)
                .onSuccess { done() }
                .onFailure { error = it.message ?: "Couldn't save that. Try again." }
            busy = false
            loadRepeats()
        }
    }

    fun removeRepeat(id: Long) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.removeRepeat(id).onFailure { error = it.message ?: "Couldn't delete that. Try again." }
            busy = false
            loadRepeats()
        }
    }

    suspend fun loadHistory() {
        repo.history().onSuccess { history = it }
    }

    suspend fun loadRules() {
        repo.rules().onSuccess { rules = it }
        loadAllowed()
    }

    /** Apps allowed during locks, both ways. Null until fetched. */
    var allowed by mutableStateOf<com.godbless.lockin.data.AllowedApps?>(null)
        private set
    var allowError by mutableStateOf<String?>(null)
        private set

    /** Told the apps allowed on this phone, so the lock can keep them open even offline. */
    var onAllowedMine: ((List<com.godbless.lockin.data.AllowedApp>) -> Unit)? = null

    suspend fun loadAllowed() {
        repo.allowed().onSuccess {
            allowed = it
            onAllowedMine?.invoke(it.mine)
        }
    }

    fun allow(app: com.godbless.lockin.data.AllowedApp) {
        allowError = null
        scope.launch {
            repo.allow(app).onSuccess { loadAllowed() }.onFailure { allowError = it.message ?: "Couldn't allow that app. Try again." }
        }
    }

    fun disallow(pkg: String) {
        allowError = null
        // Gone from the list at once; it comes back if the server says no.
        allowed = allowed?.let { com.godbless.lockin.data.AllowedApps(it.mine, it.theirs.filter { a -> a.pkg != pkg }) }
        scope.launch {
            repo.disallow(pkg).onFailure { allowError = it.message ?: "Couldn't remove that app. Try again." }
            loadAllowed()
        }
    }

    fun saveRules(allowed: Boolean, maxSeconds: Int) {
        scope.launch {
            repo.setRules(allowed, maxSeconds)
                .onSuccess { loadRules() }
                .onFailure { error = it.message ?: "Couldn't save that. Try again." }
        }
    }

    private companion object {
        const val TAG = "LockInLock"
    }
}
