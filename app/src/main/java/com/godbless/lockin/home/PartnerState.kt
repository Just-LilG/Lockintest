package com.godbless.lockin.home

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.ChatLine
import com.godbless.lockin.data.Pairing
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.PreparedPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The partner's name for every screen below Home. "Your partner" until someone is paired. */
internal val LocalPartnerName = compositionLocalOf { "Your partner" }

/** Tic-tac-toe with your partner. Null until someone is paired. */
internal val LocalTtt = compositionLocalOf<com.godbless.lockin.data.TttRepository?> { null }

/** Word Duel with your partner. Null until someone is paired. */
internal val LocalWd = compositionLocalOf<com.godbless.lockin.data.WordDuelRepository?> { null }
internal val LocalLudo = compositionLocalOf<com.godbless.lockin.data.LudoRepository?> { null }

/** The live line to your partner for games (their hand, their typing). Null until someone is paired. */
internal val LocalLive = compositionLocalOf<com.godbless.lockin.live.LiveChannel?> { null }

/**
 * Whether you have a partner and what the invite looks like. It lives above the screens so the tabs,
 * the lock screens and the chat all see the same answer.
 */
internal class PartnerState(private val repo: PartnerRepository, private val scope: CoroutineScope) {
    var pairing by mutableStateOf<Pairing>(Pairing.Loading)
        private set

    /** True while a button's request is in flight. */
    var busy by mutableStateOf(false)
        private set

    /** The last thing that went wrong, in words for the person. Cleared by the next attempt. */
    var error by mutableStateOf<String?>(null)

    val paired: Pairing.Paired? get() = pairing as? Pairing.Paired
    /** What you call them: your nickname for them on this phone, else the name they chose. */
    val name: String get() = Identity.nickname(paired?.id) ?: paired?.partnerName ?: "Your partner"
    val realName: String get() = paired?.partnerName ?: "Your partner"

    suspend fun refresh() {
        repo.pairing()
            .onSuccess { pairing = it; if (it !is Pairing.Loading) error = null }
            .onFailure { if (pairing is Pairing.Loading) error = it.message }
    }

    fun retry() {
        error = null
        scope.launch { refresh() }
    }

    private fun run(block: suspend () -> Result<Pairing>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            block().onSuccess { pairing = it }.onFailure { error = it.message }
            busy = false
        }
    }

    fun makeInvite() = run { repo.createInvite() }

    /** Someone else's invite that has been scanned or tapped, waiting for "Pair with ...?" to be answered. */
    var candidate by mutableStateOf<Candidate?>(null)
        private set

    class Candidate(val code: String, val name: String)

    /** Looks up who made [code], then asks before joining. Used for scanned codes and links. */
    fun preview(code: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.inviteOwner(code)
                .onSuccess { candidate = Candidate(code, it) }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun confirmCandidate() {
        val c = candidate ?: return
        candidate = null
        join(c.code)
    }

    fun dismissCandidate() {
        candidate = null
    }

    fun join(code: String) = run { repo.join(code) }

    /** Cancels an invite, asks to unpair (your partner has to agree), or agrees to their request. */
    fun leave() = afterPairingCall { repo.leave() }

    /** Answers your partner's request to unpair. */
    fun answerUnpair(agree: Boolean) = afterPairingCall { repo.unpairAnswer(agree) }

    /** Takes back your own request. */
    fun cancelUnpair() = afterPairingCall { repo.unpairCancel() }

    /** Runs a call that changes the pairing, then asks the server what the pairing is now. */
    private fun afterPairingCall(block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            block().onFailure { error = it.message }
            repo.pairing().onSuccess { pairing = it }
            busy = false
        }
    }
}

private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun clock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TimeFormat).lowercase(Locale.US)

/** A message you sent that the server hasn't confirmed yet (or couldn't take): what to try again with. */
private class Pending(val text: String, val replyTo: Long?, val photo: PreparedPhoto?)

/**
 * The conversation with your partner. Messages are fetched every few seconds (faster while the chat is
 * open, and at once when a push arrives). What you send shows up straight away as "sending" and is
 * confirmed once the server has saved it, or marked failed with a way to try again.
 */
internal class ChatState(private val repo: PartnerRepository, private val scope: CoroutineScope) {
    val messages = mutableStateListOf<Message>()

    /** True once the first fetch for this partner has finished. */
    var loaded by mutableStateOf(false)
        private set

    /** True while the last fetch could not reach the server. */
    var offline by mutableStateOf(false)
        private set

    /** Messages before this index were there when the chat loaded. */
    var baseline by mutableStateOf(0)
        private set

    /** True while your partner is typing to you. */
    var partnerTyping by mutableStateOf(false)
        private set

    /** There may be older messages on the server than the ones loaded. */
    var hasOlder by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set

    /** Called once when your partner's message lands while the chat is open (the in-chat sound). */
    var onIncoming: () -> Unit = {}

    /** Where "read up to here" is kept for each chat (set by Home). */
    var readGet: (String) -> Long = { 0L }
    var readSet: (String, Long) -> Unit = { _, _ -> }

    /** Something that went wrong with an edit, delete or reaction, to show for a moment. */
    var actionError by mutableStateOf<String?>(null)

    /** Whether the server can carry photos, edits and reactions yet. */
    val canRich: Boolean get() = repo.richer

    /** Your own id, to find your reaction among the others. */
    val me: String? get() = repo.signedInUser()

    private var partnershipId: String? = null
    private var lastKey = 0L
    private var partnerName = "Your partner"
    private var lastId = 0L
    private var oldestId = 0L
    private var since: String? = null
    private val seen = HashSet<Long>()
    private val pending = HashMap<Long, Pending>()
    private val acked = HashSet<Long>()
    private var nextLocal = -1L

    /** Points the chat at the current partnership. A different one starts from empty. */
    fun bind(p: Pairing.Paired?) {
        partnerName = p?.partnerName ?: "Your partner"
        if (p?.id == partnershipId) return
        partnershipId = p?.id
        lastId = 0
        oldestId = 0
        since = null
        seen.clear()
        pending.clear()
        acked.clear()
        messages.clear()
        loaded = false
        baseline = 0
        hasOlder = false
        partnerTyping = false
        seenSent = 0L
    }

    /** How far you had read when the chat was opened (0 when there is no record, so nothing is called new). */
    fun lastReadId(): Long = partnershipId?.let { readGet(it) } ?: 0L

    /** True while the chat is on screen and the app is in front: only then do your partner's ticks turn to Seen. */
    var viewing by mutableStateOf(false)
    private var seenSent = 0L

    /** Tells the server how far you have read, once per new message, while you are looking at the chat. */
    fun reportSeen() {
        if (!viewing || !canRich) return
        val newest = messages.lastOrNull { it.who == Who.Partner && it.id > 0 && !it.deleted }?.id ?: return
        if (newest <= seenSent) return
        seenSent = newest
        scope.launch { repo.markSeen(newest).onFailure { if (seenSent == newest) seenSent = 0L } }
    }

    /** Call when the chat is left: everything up to the newest message counts as read. */
    fun markRead() {
        val pid = partnershipId ?: return
        val newest = messages.lastOrNull { it.id > 0 }?.id ?: return
        readSet(pid, newest)
    }

    suspend fun poll() {
        val id = partnershipId ?: return
        repo.messages(id, lastId, sinceMinusSlack())
            .onSuccess {
                merge(it)
                offline = false
                if (!loaded) {
                    loaded = true
                    baseline = messages.size
                    hasOlder = it.size >= 60
                }
            }
            .onFailure { offline = true }
    }

    /** A few seconds before the newest change seen: a row that was saved just after that stamp is still picked up. */
    private fun sinceMinusSlack(): String? =
        since?.let { runCatching { java.time.OffsetDateTime.parse(it).minusSeconds(5).toString() }.getOrNull() }

    /** Loads the 40 messages before the oldest one shown. */
    suspend fun loadOlder() {
        val id = partnershipId ?: return
        if (loadingOlder || !hasOlder || oldestId <= 0) return
        loadingOlder = true
        repo.older(id, oldestId)
            .onSuccess { lines ->
                val fresh = lines.filter { seen.add(it.id) && !isGamePing(it) }
                if (fresh.isNotEmpty()) {
                    oldestId = minOf(oldestId, fresh.minOf { it.id })
                    messages.addAll(0, fresh.map { toMessage(it) })
                    baseline += fresh.size
                }
                hasOlder = lines.size >= 40
            }
            .onFailure { actionError = it.message }
        loadingOlder = false
    }

    /** Call on every key press in the message box. */
    fun typed() {
        lastKey = System.currentTimeMillis()
    }

    /**
     * Run every second or so while the chat is open: tells your partner you are typing (if you pressed
     * a key in the last 3 seconds) and learns whether they are.
     */
    suspend fun tickTyping() {
        val id = partnershipId ?: return
        val mine = System.currentTimeMillis() - lastKey < 3_000
        repo.typing(id, mine).onSuccess { partnerTyping = it }
    }

    /** The message you're answering, shown above the box until you send or close it. */
    var replyingTo by mutableStateOf<Message?>(null)

    /** The message you're fixing: its words are in the box, and send saves them instead. */
    var editing by mutableStateOf<Message?>(null)

    /** Voice upload in progress, and the last thing that went wrong with voice. */
    var sendingVoice by mutableStateOf(false)
        private set
    var voiceError by mutableStateOf<String?>(null)

    // ---- Sending ------------------------------------------------------------------------------

    /** Sends [text] (as a reply, if you're replying). It shows at once; if it can't be saved it says so and can be retried. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        lastKey = 0
        queue(Pending(t, takeReply(), null))
    }

    /** Sends a prepared photo with a caption. */
    fun sendPhoto(photo: PreparedPhoto, caption: String) {
        queue(Pending(caption.trim().ifBlank { "Photo" }, takeReply(), photo))
    }

    private fun takeReply(): Long? {
        val reply = replyingTo?.id?.takeIf { it > 0 }
        replyingTo = null
        return reply
    }

    /** Sends [text] like [send] and returns a key to follow it by ([deliveryOf]), or null if there's no chat. */
    fun sendTracked(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val local = queue(Pending(t, null, null)) ?: return null
        return "l$local"
    }

    /** Where a message sent with [sendTracked] has got to (its key survives being saved). */
    fun deliveryOf(key: String): Delivery? = messages.firstOrNull { it.key == key }?.delivery

    /** Tries a failed [sendTracked] message again. */
    fun retryKey(key: String) {
        messages.firstOrNull { it.key == key }?.let { retry(it) }
    }

    private fun queue(p: Pending): Long? {
        val pid = partnershipId ?: return null
        val local = nextLocal--
        pending[local] = p
        val now = System.currentTimeMillis()
        messages.add(
            Message(
                p.text, clock(now), Who.Me, local, p.replyTo, at = now,
                media = p.photo?.let { "local" }, mediaW = p.photo?.w ?: 0, mediaH = p.photo?.h ?: 0, thumb = p.photo?.thumb,
                delivery = Delivery.Sending, key = "l$local", fresh = true, localFile = p.photo?.file,
            ),
        )
        deliver(local, pid)
        return local
    }

    private fun deliver(local: Long, pid: String) {
        val p = pending[local] ?: return
        replaceById(local) { it.copy(delivery = Delivery.Sending) }
        scope.launch {
            val r = if (p.photo != null) repo.sendPhoto(pid, p.photo, p.text, p.replyTo) else repo.send(pid, p.text, p.replyTo)
            r.onSuccess { landed(local, it) }
                .onFailure {
                    replaceById(local) { m -> m.copy(delivery = Delivery.Failed) }
                    actionError = it.message
                }
        }
    }

    private fun landed(local: Long, line: ChatLine) {
        pending.remove(local)
        val at = messages.indexOfFirst { it.id == local }
        // A fetch can have brought the saved copy in first; then the "sending" one was already swapped for it.
        if (!seen.add(line.id)) {
            if (at >= 0) messages.removeAt(at)
            return
        }
        if (line.id > lastId) lastId = line.id
        if (oldestId == 0L) oldestId = line.id
        if (line.updatedAt.isNotEmpty() && (since == null || line.updatedAt > since!!)) since = line.updatedAt
        if (at >= 0) messages[at] = toMessage(line, key = messages[at].key, fresh = true) else messages.add(toMessage(line, fresh = true))
    }

    /** Tries a failed message again. */
    fun retry(m: Message) {
        val pid = partnershipId ?: return
        if (m.delivery == Delivery.Failed && pending.containsKey(m.id)) deliver(m.id, pid)
    }

    /** Drops a message that never got sent. */
    fun discard(m: Message) {
        pending.remove(m.id)
        messages.removeAll { it.id == m.id && m.id < 0 }
    }

    /** Uploads and sends a voice message. */
    fun sendVoice(rec: Recording) {
        val id = partnershipId ?: return
        val reply = takeReply()
        sendingVoice = true
        voiceError = null
        scope.launch {
            repo.sendVoice(id, rec.file, rec.ms, rec.wave, reply)
                .onSuccess { merge(listOf(it)) }
                .onFailure { voiceError = it.message ?: "Couldn't send the voice message." }
            rec.file.delete()
            sendingVoice = false
        }
    }

    // ---- Files: downloaded once, kept on the phone, then the server is told it can let go --------

    suspend fun voiceFile(path: String): Result<java.io.File> =
        repo.voiceFile(path).also { if (it.isSuccess) ackFor { m -> m.voice == path } }

    suspend fun photoFile(path: String): Result<java.io.File> =
        repo.photoFile(path).also { if (it.isSuccess) ackFor { m -> m.media == path } }

    private fun ackFor(match: (Message) -> Boolean) {
        val m = messages.firstOrNull(match) ?: return
        // Only your partner's: the sender's copy was never "received".
        if (m.who != Who.Partner || m.id <= 0 || !acked.add(m.id)) return
        scope.launch { repo.ackMedia(m.id).onFailure { acked.remove(m.id) } }
    }

    // ---- Edit, delete, react ----------------------------------------------------------------------

    /** Puts a message of yours back in the box to fix (within 15 minutes, text only). */
    fun startEdit(m: Message) {
        replyingTo = null
        editing = m
    }

    /** Saves the fixed words. Shown at once; put back if the server says no. */
    fun saveEdit(text: String) {
        val m = editing ?: return
        editing = null
        val t = text.trim()
        if (t.isEmpty() || t == m.text) return
        replaceById(m.id) { it.copy(text = t, edited = true) }
        scope.launch {
            repo.edit(m.id, t).onFailure {
                replaceById(m.id) { _ -> m }
                actionError = it.message
            }
        }
    }

    /** Messages whose delete animation is playing: shown just as they were until it ends. Keyed by the row's key. */
    val dying = mutableStateMapOf<String, Message>()

    /**
     * Deletes your message for both of you. The server is asked straight away; the bubble breaks apart on screen
     * meanwhile and turns into "Message deleted" when the animation ends ([finishDelete]). Put back if the server says no.
     */
    fun delete(m: Message) {
        if (m.id < 0) return discard(m)
        if (dying.containsKey(m.key)) return
        dying[m.key] = m
        scope.launch {
            repo.delete(m.id).onFailure {
                dying.remove(m.key)
                replaceById(m.id) { _ -> m }
                actionError = it.message
            }
        }
    }

    /** The delete animation has ended: the bubble becomes a "Message deleted" note. */
    fun finishDelete(m: Message) {
        if (dying.remove(m.key) == null) return
        replaceById(m.id) { it.copy(text = "", deleted = true, media = null, voice = null, thumb = null, reactions = emptyMap()) }
    }

    /** Reacts with [emoji]; the same emoji again takes it back. */
    fun react(m: Message, emoji: String) {
        val mine = me ?: return
        if (m.id <= 0 || m.deleted) return
        val next = if (m.reactions[mine] == emoji) m.reactions - mine else m.reactions + (mine to emoji)
        replaceById(m.id) { it.copy(reactions = next) }
        scope.launch {
            repo.react(m.id, if (next[mine] == null) "" else emoji).onFailure {
                replaceById(m.id) { cur -> cur.copy(reactions = m.reactions) }
                actionError = it.message
            }
        }
    }

    private fun replaceById(id: Long, f: (Message) -> Message) {
        val at = messages.indexOfFirst { it.id == id && it.who != Who.System }
        if (at >= 0) messages[at] = f(messages[at])
    }

    /** Something that happened, such as "nudge", "asked_unlock" or "emergency_release". */
    fun event(code: String) {
        val id = partnershipId ?: return
        scope.launch { repo.event(id, code).onSuccess { merge(listOf(it)) } }
    }

    /** A line that only exists on this phone (used by the design preview of a partner lock). */
    fun addLocal(text: String) {
        messages.add(Message(text, clock(System.currentTimeMillis()), Who.System, key = "x${System.nanoTime()}"))
    }

    // ---- Merging what the server says -------------------------------------------------------------

    private fun merge(lines: List<ChatLine>) {
        var incoming = false
        for (l in lines) {
            if (l.updatedAt.isNotEmpty() && (since == null || l.updatedAt > since!!)) since = l.updatedAt
            if (l.id > lastId) lastId = l.id
            if (oldestId == 0L || l.id < oldestId) oldestId = l.id
            // A game move is only there to send a push; the game card shows the game, so it is not a chat line.
            if (isGamePing(l)) { seen.add(l.id); continue }
            if (!seen.add(l.id)) {
                // Known already: it may have been edited, deleted, reacted to, or its file removed from the server.
                if (l.kind == "text") {
                    val at = messages.indexOfFirst { it.id == l.id && it.who != Who.System }
                    if (at >= 0 && messages[at].updatedAt != l.updatedAt) messages[at] = toMessage(l, key = messages[at].key)
                }
                continue
            }
            // Your own message that is still showing as "sending": swap it for the saved one in the same place.
            if (l.mine && l.kind == "text") {
                val at = messages.indexOfFirst {
                    it.id < 0 && it.delivery == Delivery.Sending && it.who == Who.Me && it.text == l.body && (it.media != null) == (l.media != null)
                }
                if (at >= 0) {
                    pending.remove(messages[at].id)
                    messages[at] = toMessage(l, key = messages[at].key, fresh = true)
                    continue
                }
            }
            messages.add(toMessage(l, fresh = loaded))
            // Their message has landed, so they are done typing it.
            if (!l.mine && l.kind == "text") { partnerTyping = false; incoming = true }
        }
        // Only for new ones: the first fetch (loaded is still false) is the history, not news.
        if (incoming && loaded) onIncoming()
        reportSeen()
    }

    private fun isGamePing(l: ChatLine) = l.kind == "event" && l.body.startsWith("game_move")

    private fun toMessage(l: ChatLine, key: String? = null, fresh: Boolean = false): Message {
        val time = clock(l.at)
        if (l.kind == "text") {
            return Message(
                if (l.deleted) "" else l.body, time, if (l.mine) Who.Me else Who.Partner, l.id, l.replyTo, l.voice, l.voiceMs, l.wave, at = l.at,
                media = l.media, mediaW = l.mediaW, mediaH = l.mediaH, thumb = l.thumb, gone = l.gone,
                edited = l.edited, deleted = l.deleted, reactions = l.reactions, updatedAt = l.updatedAt, seen = l.seen,
                key = key ?: "m${l.id}", fresh = fresh,
            )
        }
        val who = partnerName
        val parts = l.body.split('|')
        val app = parts.getOrNull(1).orEmpty().ifBlank { "an app" }
        val length = (if (parts[0] == "lock_set") parts.getOrNull(1) else parts.getOrNull(2))?.toIntOrNull()?.let { formatMinutes(it) }
            ?: (if (parts[0] == "lock_set") "a while" else "a limit")
        val text = when (parts[0]) {
            "limit_set" -> if (l.mine) "You limited $app to $length a day on $who's phone" else "$who limited $app to $length a day on your phone"
            "limit_removed" -> if (l.mine) "You removed the $app limit on $who's phone" else "$who removed the $app limit on your phone"
            "allow_set" -> if (l.mine) "You allowed $app during locks on $who's phone" else "$who allowed $app during your locks"
            "allow_removed" -> if (l.mine) "You took $app off $who's allowed apps" else "$who took $app off your allowed apps"
            "lock_set" -> if (l.mine) "You locked $who's phone for $length" else "$who locked your phone for $length"
            "lock_lifted" -> if (l.mine) "You unlocked $who" else "$who unlocked you"
            "lock_planned" -> {
                val len = parts.getOrNull(1)?.toIntOrNull()?.let { formatMinutes(it) } ?: "a"
                val at = parts.getOrNull(2)?.toLongOrNull()?.let { planWhen(it * 1000L) }.orEmpty()
                if (l.mine) "You scheduled a $len lock on $who's phone for $at" else "$who scheduled a $len lock on your phone for $at"
            }
            "lock_canceled" -> if (l.mine) "You canceled the lock on $who" else "$who canceled the lock"
            "lock_extended" -> {
                val more = parts.getOrNull(1)?.toIntOrNull()?.let { formatMinutes(it) } ?: "more time"
                if (l.mine) "You added $more to $who's lock" else "$who added $more to your lock"
            }
            "unlock_denied" -> if (l.mine) "You said not yet to $who" else "$who said not yet"
            "sharing_on" -> if (l.mine) "You started sharing your app times" else "$who started sharing their app times"
            "sharing_off" -> if (l.mine) "You stopped sharing your app times" else "$who stopped sharing their app times"
            "nudge" -> if (l.mine) "You nudged $who" else "$who nudged you"
            "summit" -> {
                val climbed = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { " with ${formatMinutes(it)}" }.orEmpty()
                if (l.mine) "You reached today's goal$climbed" else "$who reached today's goal$climbed"
            }
            "game_invite" -> {
                val game = when (parts.getOrNull(1)) { "wd" -> "Word Duel"; "ludo" -> "Ludo"; else -> "tic-tac-toe" }
                if (l.mine) "You sent $who a game of $game" else "$who sent you a game of $game"
            }
            "quiz_challenge" -> {
                val q = com.godbless.lockin.study.decodeChallenge(l.body)
                val what = (if (q != null) "a ${q.cards.size}-card quiz" else "a quiz") + (q?.title?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "")
                if (l.mine) "You sent $who $what" else "$who sent you $what"
            }
            "quiz_score" -> {
                val r = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val n = parts.getOrNull(2)?.toIntOrNull() ?: 0
                if (l.mine) "You scored $r of $n on $who's quiz" else "$who scored $r of $n on your quiz"
            }
            "asked_unlock" -> if (l.mine) "You asked $who to unlock" else "$who asked you to unlock"
            "lock_together_left" -> if (l.mine) "You left the lock together early" else "$who left the lock together early"
            "emergency_release" -> if (l.mine) "You used the emergency release" else "$who used the emergency release"
            "plan_set", "plan_canceled" -> {
                val what = parts.getOrNull(1).orEmpty().ifBlank { "a session" }
                val at = parts.getOrNull(2)?.toLongOrNull()?.let { " for ${planWhen(it * 1000L)}" }.orEmpty()
                val verb = if (parts[0] == "plan_set") "planned" else "canceled"
                if (l.mine) "You $verb $what$at" else "$who $verb $what$at"
            }
            "joined" -> if (l.mine) "You joined $who" else "$who joined"
            "unpair_asked" -> if (l.mine) "You asked $who to unpair" else "$who asked to unpair"
            "lock_repeat", "lock_repeat_off" -> {
                val len = parts.getOrNull(1)?.toIntOrNull()?.let { formatMinutes(it) } ?: "a"
                val days = parts.getOrNull(2)?.toIntOrNull()?.let { daysText(it) } ?: "some days"
                val at = parts.getOrNull(3)?.toIntOrNull()?.let { timeText(it) } ?: ""
                val what = "a $len lock, $days at $at"
                if (parts[0] == "lock_repeat") {
                    if (l.mine) "You set $what on $who" else "$who set $what on you"
                } else {
                    if (l.mine) "You removed $what" else "$who removed $what"
                }
            }
            "protect_asked" -> {
                val what = com.godbless.lockin.lock.Protection.phrase(parts.getOrNull(1).orEmpty())
                if (l.mine) "You asked $who to allow you to $what" else "$who wants to $what"
            }
            "protect_allowed" -> {
                val what = com.godbless.lockin.lock.Protection.phrase(parts.getOrNull(1).orEmpty())
                if (l.mine) "You allowed $who to $what for 10 minutes" else "$who allowed you to $what for 10 minutes"
            }
            "protect_declined" -> {
                val what = com.godbless.lockin.lock.Protection.phrase(parts.getOrNull(1).orEmpty())
                if (l.mine) "You said not yet to $who wanting to $what" else "$who said not yet to you wanting to $what"
            }
            "unpair_declined" -> if (l.mine) "You said not now to unpairing" else "$who said not now to unpairing"
            // Protection switched off on a paired phone with no lock running (the phone tells its partner).
            "protect_tamper" -> {
                val subject = if (l.mine) "You" else who
                val whose = if (l.mine) "your" else "their"
                when (parts.getOrNull(1)) {
                    "admin_off" -> "$subject switched off LockIn's device admin"
                    "overlay_off" -> "$subject turned off \"display over other apps\" for LockIn"
                    "usage_off" -> "$subject turned off LockIn's app-time access"
                    "guard_off" -> "$subject switched off LockIn's protection"
                    "safe_mode" -> "$subject restarted $whose phone in safe mode, where LockIn can't run"
                    else -> "$subject tampered with LockIn's protection"
                }
            }
            // Sent by the locked phone when something got around (or tried to) the lock.
            "lock_tamper" -> {
                val subject = if (l.mine) "You" else who
                val whose = if (l.mine) "your" else "their"
                val voids = parts.getOrNull(1) in setOf("admin_off", "overlay_off", "usage_off", "guard_off", "safe_mode", "time_changed")
                val said = when (parts.getOrNull(1)) {
                    "admin_off" -> "$subject switched off LockIn's device admin during the lock"
                    "overlay_off" -> "$subject turned off \"display over other apps\" during the lock"
                    "stopped" -> "LockIn was stopped on $whose phone for ${parts.getOrNull(2)?.toIntOrNull()?.let { formatMinutes(it) } ?: "a while"} during the lock"
                    "safe_mode" -> "$subject restarted ${if (l.mine) "your" else "their"} phone into safe mode for ${parts.getOrNull(2)?.toIntOrNull()?.let { formatMinutes(it) } ?: "a while"} during the lock"
                    "emergency_dial" -> "$subject opened the emergency dialer during the lock"
                    "time_changed" -> "$subject changed $whose phone's clock during the lock"
                    "usage_off" -> "$subject turned off LockIn's app-time access during the lock"
                    "guard_off" -> "$subject switched off LockIn's protection during the lock"
                    "silent" -> "LockIn on $whose phone stopped checking in ${parts.getOrNull(2)?.toIntOrNull()?.let { formatMinutes(it) } ?: "a while"} ago, during the lock"
                    else -> "$subject tried to get around the lock"
                }
                // A protection switched off for real voids the lock: its hours are not counted (the server does that).
                if (voids) "$said. That session won't count." else said
            }
            else -> "Something happened"
        }
        return Message(
            text, time, Who.System, at = l.at, theirs = !l.mine, code = parts[0], noteId = l.id, key = key ?: "n${l.id}", fresh = fresh,
            detail = if (parts[0] == "quiz_challenge" || parts[0] == "game_invite") l.body else "",
        )
    }
}
