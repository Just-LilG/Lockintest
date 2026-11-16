package com.godbless.lockin.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.Crew
import com.godbless.lockin.data.CrewLine
import com.godbless.lockin.data.CrewRepository
import com.godbless.lockin.lock.LockService
import com.godbless.lockin.push.CrewAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Your crew, as the Crew tab and the crew chat see it. Home keeps it fresh (often while the chat is open or a
 * session is on, now and then otherwise) and a crew push refreshes it at once.
 *
 * It also keeps the phone in step with the session: in a running session you joined, the phone holds the lock
 * (the same lock service as a partner lock); when it ends or you leave, the lock goes; one you joined for later
 * sets an alarm for its start.
 */
internal class CrewState(
    private val repo: CrewRepository,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var crew by mutableStateOf<Crew?>(null)
        private set
    /** The first answer from the server has come in (so "no crew" really means no crew). */
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** The first look for your crew failed (offline, or the server isn't ready). */
    var failed by mutableStateOf(false)
        private set
    /** The server doesn't have crews yet. */
    var notReady by mutableStateOf(false)
        private set

    fun retry() {
        failed = false
        error = null
        scope.launch { refresh() }
    }
    var error by mutableStateOf<String?>(null)

    /** The crew chat, oldest first. */
    val lines = mutableStateListOf<CrewLine>()
    /** Lines before this index were already there when the chat opened, so they don't animate in. */
    var baseline by mutableStateOf(0)
        private set
    var chatOpen by mutableStateOf(false)

    /** Something new in the crew chat since you last had it open. */
    val hasUnread: Boolean get() = !chatOpen && (crew?.last ?: 0L) > seenUpTo
    private var seenUpTo: Long
        get() = context.getSharedPreferences("crew", Context.MODE_PRIVATE).getLong("seen", 0L)
        set(v) { context.getSharedPreferences("crew", Context.MODE_PRIVATE).edit().putLong("seen", v).apply() }

    private var goalSaid: Long
        get() = context.getSharedPreferences("crew", Context.MODE_PRIVATE).getLong("goal_said", 0L)
        set(v) { context.getSharedPreferences("crew", Context.MODE_PRIVATE).edit().putLong("goal_said", v).apply() }

    /** A short confirmation of the last poke or cheer, shown for a moment. */
    var note by mutableStateOf<String?>(null)

    // ---- Sheets on the Crew tab (HomeScreen hosts them, so they clear the gesture bar) ----
    var menuOpen by mutableStateOf(false)
    var lookOpen by mutableStateOf(false)
    /** The member whose sheet is open (a user id), or null. */
    var memberOpen by mutableStateOf<String?>(null)
    var confirm by mutableStateOf<ConfirmSpec?>(null)
    /** You were taken out of the crew by its owner (so the tab can say so instead of just showing "no crew"). */
    var removed by mutableStateOf(false)
    private var leavingByMe = false

    /** Unread messages from other people, from the chat lines we have. */
    val unreadCount: Int get() = if (chatOpen) 0 else lines.count { it.id > seenUpTo && it.kind == "text" && !it.mine }

    val me: String? get() = crew?.me
    val inSession: Boolean get() = crew?.session?.let { s -> me in s.joined } == true
    val leftSession: Boolean get() = crew?.session?.let { s -> me in s.left } == true

    suspend fun refresh() {
        repo.state()
            .onSuccess {
                // Gone without you leaving: the owner removed you.
                if (it == null && crew != null && !leavingByMe) removed = true
                if (it != null) removed = false
                leavingByMe = false
                crew = it
                loaded = true
                failed = false
                notReady = false
                if (it == null) lines.clear()
                if (seenUpTo == 0L && it != null) seenUpTo = it.last
                // A lock or alarm that can't be set right now must never take the app down.
                runCatching { keepLockInStep() }.onFailure { e -> android.util.Log.w("LockInCrew", "lock step failed", e) }
                runCatching { com.godbless.lockin.push.StandingReminders.sync(context, it?.name.orEmpty(), it?.standing.orEmpty()) }
                // The week's goal was just reached: the first phone to notice tells the crew, once.
                if (it != null && it.goalMin > 0 && it.weekTotal >= it.goalMin * 60 && goalSaid != it.weekStart) {
                    goalSaid = it.weekStart
                    scope.launch { repo.checkGoal() }
                }
            }
            .onFailure {
                // Never sit on "Finding your crew…" forever: say what's wrong, and Retry tries again.
                if (!loaded) {
                    val m = it.message.orEmpty()
                    notReady = m.startsWith("This needs a LockIn server update")
                    error = if (notReady) "Crews are almost here. The server part is still being switched on." else m.ifBlank { "Couldn't reach your crew." }
                    failed = true
                }
            }
        loadLines(quiet = !chatOpen)
    }

    /** New chat lines since the last one we have. */
    suspend fun loadLines(quiet: Boolean = false) {
        if (crew == null) return
        val after = lines.lastOrNull()?.id ?: 0L
        repo.messages(after).onSuccess { fresh ->
            if (after == 0L) {
                lines.clear()
                lines.addAll(fresh)
                baseline = lines.size
            } else {
                lines.addAll(fresh.filter { f -> lines.none { it.id == f.id } })
            }
            // A quiet fetch (for the activity list) must not clear the Crew tab's unread dot.
            if (!quiet) lines.lastOrNull()?.let { seenUpTo = maxOf(seenUpTo, it.id) }
        }
    }

    fun openChat() {
        chatOpen = true
        scope.launch { loadLines() }
    }

    fun closeChat() {
        chatOpen = false
        crew?.let { seenUpTo = maxOf(seenUpTo, it.last) }
    }

    private fun run(before: () -> Unit = {}, after: suspend () -> Unit = {}, block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        error = null
        before()
        scope.launch {
            block().onFailure { error = it.message }.onSuccess { after() }
            refresh()
            busy = false
        }
    }

    fun create(name: String) = run { repo.create(name) }
    fun join(code: String) = run { repo.join(code) }
    fun leave() = run(before = { leavingByMe = true }, after = { LockService.endCrew(context); CrewAlarm.cancel(context); com.godbless.lockin.push.StandingReminders.clear(context) }) { repo.leave() }

    fun setGoal(minutes: Int) = run { repo.setGoal(minutes) }

    /** Owner: rename the crew and set its emoji and colour. */
    fun update(name: String, emoji: String, color: Int, done: () -> Unit = {}) = run(after = { done() }) { repo.update(name, emoji, color) }

    /** Owner: take someone off the rope. */
    fun kick(user: String, name: String) = run(after = { note = "Removed $name." }) { repo.kick(user) }

    /** Owner: a new invite code. The old one stops working. */
    fun newCode() = run(after = { note = "New invite code. The old one no longer works." }) { repo.newCode() }

    // ---- Standing sessions: removing one waits a few seconds so it can be undone ----
    val hiddenStanding = androidx.compose.runtime.mutableStateListOf<Long>()
    /** The standing session that is about to be removed (its Undo is showing), or null. */
    var undoStanding by mutableStateOf<Long?>(null)
        private set
    private var removeJob: kotlinx.coroutines.Job? = null

    fun removeStandingSoon(id: Long) {
        val earlier = undoStanding
        removeJob?.cancel()
        if (earlier != null) scope.launch { commitStanding(earlier) }
        hiddenStanding.add(id)
        undoStanding = id
        removeJob = scope.launch {
            kotlinx.coroutines.delay(5000)
            commitStanding(id)
        }
    }

    fun undoStandingRemoval() {
        removeJob?.cancel()
        undoStanding?.let { hiddenStanding.remove(it) }
        undoStanding = null
    }

    private suspend fun commitStanding(id: Long) {
        repo.removeStanding(id)
            .onSuccess { refresh() }
            .onFailure {
                error = if (it.message.orEmpty().contains("not_allowed")) "Only whoever added it, or the crew owner, can remove a standing session." else it.message
            }
        hiddenStanding.remove(id)
        if (undoStanding == id) undoStanding = null
    }
    fun addStanding(wkMinute: Int, minutes: Int) = run { repo.addStanding(wkMinute, minutes) }
    fun removeStanding(id: Long) = run { repo.removeStanding(id) }
    fun poke(user: String, name: String) = run(after = { note = "Poked $name." }) { repo.poke(user) }
    fun cheer(user: String, name: String, back: Boolean) =
        run(after = { note = if (back) "Told $name to get back on." else "Cheered $name on." }) { repo.cheer(user, if (back) "back" else "cheer") }

    /** Starts a session for the crew and puts you in it. */
    fun start(minutes: Int, startIn: Int) = run { repo.start(minutes, startIn) }

    fun joinSession() {
        val s = crew?.session ?: return
        run { repo.joinSession(s.id) }
    }

    /** Before it begins: step out. Once it runs: leave early (the crew sees it), and the lock lifts. */
    fun leaveSession() {
        val s = crew?.session ?: return
        run(after = { LockService.endCrew(context); CrewAlarm.cancel(context) }) { repo.leaveSession(s.id) }
    }

    /** The line you're answering, shown above the box until you send or close it. */
    var replyingTo by mutableStateOf<CrewLine?>(null)
    var sendingVoice by mutableStateOf(false)
        private set
    var voiceError by mutableStateOf<String?>(null)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val reply = replyingTo?.id
        replyingTo = null
        scope.launch {
            repo.send(t, reply).onSuccess { loadLines() }.onFailure { error = it.message }
        }
    }

    fun sendVoice(rec: Recording) {
        val c = crew ?: return
        val reply = replyingTo?.id
        replyingTo = null
        sendingVoice = true
        voiceError = null
        scope.launch {
            repo.sendVoice(c.id, rec.file, rec.ms, rec.wave, reply)
                .onSuccess { loadLines() }
                .onFailure { voiceError = it.message ?: "Couldn't send the voice message." }
            rec.file.delete()
            sendingVoice = false
        }
    }

    suspend fun voiceFile(path: String) = repo.voiceFile(path)

    /** The phone holds the lock exactly while you're in a running session, and wakes for one you joined for later. */
    private fun keepLockInStep() {
        val c = crew
        val s = c?.session
        val mine = s != null && c.me in s.joined
        when {
            s != null && mine && s.startsIn == 0 && s.remaining > 0 -> {
                CrewAlarm.cancel(context)
                LockService.holdCrew(context, s.id, s.remaining, c.name)
            }
            s != null && mine && s.startsIn > 0 -> CrewAlarm.set(context, s.readAt + s.startsIn * 1000L)
            else -> {
                CrewAlarm.cancel(context)
                if (LockService.crewSessionHeld(context) != 0L) LockService.endCrew(context)
            }
        }
    }
}
