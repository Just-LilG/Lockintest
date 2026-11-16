package com.godbless.lockin.data

import com.godbless.lockin.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where the person stands with a partner. */
sealed interface Pairing {
    data object Loading : Pairing
    data object None : Pairing

    /** An invite code exists and is waiting for the partner to enter it. [expires] is epoch millis. */
    data class Pending(val code: String, val expires: Long) : Pairing

    data class Paired(
        val id: String,
        val partnerId: String,
        val partnerName: String,
        val since: Long,
        /** Who asked to unpair: "" nobody, "me", or "them". Unpairing needs the other person to agree. */
        val unpairBy: String = "",
        /** When an unanswered request goes through on its own (milliseconds), or 0. */
        val unpairDue: Long = 0L,
    ) : Pairing
}

/** One row of the chat. [kind] is "text" or "event"; for an event, [body] is a short code like "nudge". */
/** Where voice recordings are kept on the phone. Set at app start. */
object VoiceDir {
    @Volatile var dir: java.io.File? = null
    /** Where recordings were kept before they moved to the app's own (permanent) storage. */
    @Volatile var legacy: java.io.File? = null
}

/**
 * Where photos live on the phone. This is the app's own storage, not the cache: the server deletes its copy
 * once the recipient has theirs, so the phone must not be allowed to throw this away on its own.
 */
object MediaDir {
    @Volatile var dir: java.io.File? = null

    /** What photos and voice notes take on this phone, in bytes. */
    fun bytes(): Long = listOfNotNull(dir, VoiceDir.dir).sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Deletes every saved photo and voice note from this phone. */
    fun clear() {
        listOfNotNull(dir, VoiceDir.dir, VoiceDir.legacy).forEach { d -> d.listFiles()?.forEach { runCatching { it.delete() } } }
    }
}

/** A photo ready to send: shrunk, with a tiny base64 preview. [uploaded] remembers the upload, so a retry doesn't repeat it. */
class PreparedPhoto(val file: java.io.File, val w: Int, val h: Int, val bytes: Int, val thumb: String) {
    @Volatile var uploaded: String? = null
}

class ChatLine(
    val id: Long,
    val mine: Boolean,
    val kind: String,
    val body: String,
    val at: Long,
    /** The message this one answers, if any. */
    val replyTo: Long? = null,
    /** A voice recording's path in the "voice" bucket, if this is a voice message. */
    val voice: String? = null,
    val voiceMs: Int = 0,
    /** Loudness along the recording, digits 0-9. */
    val wave: String = "",
    /** A photo's path in the "chat-media" bucket, its size, and a tiny base64 preview. */
    val media: String? = null,
    val mediaW: Int = 0,
    val mediaH: Int = 0,
    val thumb: String? = null,
    /** The server's copy of the photo or voice note has been deleted (the recipient's phone is the keeper now). */
    val gone: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    /** Who reacted with what: user id to emoji. */
    val reactions: Map<String, String> = emptyMap(),
    /** The server's "last changed" stamp, as text, for asking what changed since. */
    val updatedAt: String = "",
    /** Your partner has read it (only ever set on messages you sent). */
    val seen: Boolean = false,
)

/** An error with words fit to show the person. [status] is the HTTP code when there was one. */
class ApiException(message: String, val status: Int = 0) : Exception(message)

/**
 * Pairing and chat, over Supabase's plain HTTPS API. Every call is made as the signed-in person and
 * retried once with a fresh token if the old one had expired. Row-level security on the server is what
 * keeps a chat private; nothing here is trusted to do that.
 */
class PartnerRepository(private val auth: AuthRepository) {

    internal class Resp(val code: Int, val body: String)

    /** For work that should not hold up the screen, like asking the server to push a message. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun signedInUser(): String? = auth.userId()

    private suspend fun raw(method: String, path: String, body: String?, token: String, extra: Map<String, String>, readMs: Int = 20_000): Resp? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 12_000
                conn.readTimeout = readMs
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Resp(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (e: IOException) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    internal suspend fun call(method: String, path: String, body: String? = null, extra: Map<String, String> = emptyMap(), readMs: Int = 20_000): Resp {
        val token = auth.accessToken() ?: throw ApiException("You're signed out. Sign in again.")
        var r = raw(method, path, body, token, extra, readMs) ?: throw ApiException(OFFLINE)
        if (r.code == 401) {
            val fresh = auth.accessToken(forceRefresh = true) ?: throw ApiException("Your session ended. Sign in again.")
            r = raw(method, path, body, fresh, extra, readMs) ?: throw ApiException(OFFLINE)
        }
        if (r.code !in 200..299) throw ApiException(explain(r), r.code)
        return r
    }

    /** Turns the server's error codes (raised by the SQL functions) into plain sentences. */
    private fun explain(r: Resp): String {
        val message = try {
            // SQL functions say "message"; the Edge Functions say "error".
            JSONObject(r.body).let { it.optString("message").ifBlank { it.optString("error") } }
        } catch (e: Exception) {
            ""
        }
        return when {
            message == "locked_now" -> "You can't delete your account while your phone is locked."
            message == "paired" -> "Unpair from your partner first (they have to agree), then you can delete your account."
            message == "no_request" -> "There's nothing waiting for you to answer."
            message == "invalid_code" -> "That code doesn't match anyone, or it has expired. Check it and try again."
            message == "already_in_crew" -> "You're already in a crew. Leave it first to make or join another."
            message == "crew_not_found" -> "No crew has that code. Check the 6 letters and try again."
            message == "crew_full" -> "That crew is full. A crew holds up to 8 people."
            message == "crew_name_needed" -> "Give your crew a name."
            message == "no_crew" -> "You're not in a crew any more."
            message == "bad_goal" -> "Pick a goal between 0 and 100 hours."
            message == "standing_full" -> "A crew can keep 6 standing sessions. Remove one first."
            message == "standing_exists" -> "There's already a standing session at that time."
            message == "no_session" -> "There's no session on right now."
            message == "already_in" -> "They're already on the rope."
            message == "too_soon" -> "You just did that. Give it a few minutes."
            message == "not_in_crew" -> "They're not in your crew."
            message == "session_running" -> "Your crew already has a session on. Join that one."
            message == "session_over" -> "That session has finished."
            message == "already_left" -> "You left this session early, so you can't rejoin it."
            message == "session_not_found" -> "That session isn't there any more."
            message == "bad_start" -> "Pick a start within the next 6 hours."
            message == "too_many_allowed" -> "You can allow up to 3 apps. Remove one first."
            message == "not_paired" -> "You're not paired with anyone right now."
            message == "daily_limit" -> "That's today's AI allowance used up. It's back tomorrow."
            message == "ai_not_configured" -> "The AI helper isn't switched on yet."
            message == "ai_busy" -> "The AI is busy right now. Try again in a minute."
            message == "ai_failed" -> "The AI couldn't answer that time. Try again."
            message == "nothing_found" -> "The AI couldn't find anything to work with on those pages. Try pages with more text."
            message == "no_pages" -> "Read a few pages first, then try again."
            message == "own_code" -> "That's your own code. Send it to your partner instead."
            message == "already_paired" -> "You already have a partner."
            message == "try_again" -> "Couldn't make a code just now. Try again."
            message == "unknown_app" -> "That app isn't on their list yet. Try again once their phone has reported."
            message == "not_limitable" -> "That app can't be limited."
            message == "bad_limit" -> "Pick a limit between 5 minutes and 24 hours."
            message == "not_paired" -> "You need a partner first."
            message == "bad_lock" -> "Pick a time between 5 minutes and 8 hours."
            message == "bad_goal" -> "Pick a goal between 15 minutes and 16 hours."
            message == "bad_title" -> "Give the session a name (up to 60 characters)."
            message == "bad_length" -> "Pick a length between 5 minutes and 8 hours."
            message == "bad_time" -> "Pick a time that hasn't passed yet, within the next 60 days."
            message == "too_many_plans" -> "That's a lot of plans. Cancel one first."
            message == "bad_start" -> "Pick a start within the next 7 days."
            message == "lock_off" -> "They've switched locks off for now. Ask them to turn it back on in Lock rules."
            message == "over_max" -> "That's longer than they accept. Pick a shorter time."
            message == "strict_lock" -> "This lock has unlock requests turned off."
            message == "already_asked" -> "You already asked. Wait for their answer."
            message == "ask_later" -> "They said not yet. You can ask again in a few minutes."
            message == "no_lock" -> "There's no lock running."
            message == "bad_extend" -> "Add between 5 and 60 minutes."
            message == "bad_rule" -> "Pick a limit between 15 minutes and 8 hours."
            message == "already_locked" -> "They're already locked. Wait for it to end, or unlock them first."
            message == "locked_now" -> "You can't ask to unpair while a lock is on you. Ask again once it ends."
            message == "unpair_pending" -> "They've asked to unpair, so you can't lock them now."
            message == "no_request" -> "There's no request to answer any more."
            message == "bad_days" -> "Pick at least one day."
            message == "bad_tz" -> "Your phone's time zone isn't recognised. Check the date and time settings."
            message == "too_many_repeats" -> "That's 8 repeating locks already. Delete one first."
            message == "no_game" -> "That game has ended."
            message == "not_your_turn" -> "It's their turn."
            message == "bad_cell" -> "That square is taken."
            message == "no_schedule" -> "That repeating lock is already gone."
            message == "cannot_edit" -> "That message can't be changed any more."
            message == "bad_message" -> "A message can't be empty."
            // A feature whose server part isn't switched on yet (the function isn't there).
            message.contains("Could not find the function") || message.contains("schema cache") ->
                "This needs a LockIn server update. Try again soon."
            r.code == 401 || r.code == 403 -> "Your session ended. Sign in again."
            r.code == 429 -> "Too many tries. Wait a minute and try again."
            else -> "Something went wrong. Try again."
        }
    }

    internal suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ApiException("Something went wrong. Try again."))
        }

    // ---- Pairing ------------------------------------------------------------------------------

    private fun parsePairing(text: String): Pairing {
        val j = JSONObject(text)
        return when (j.optString("status")) {
            "pending" -> Pairing.Pending(j.optString("code"), parseTime(j.optString("expires")))
            "paired" -> Pairing.Paired(
                j.optString("id"),
                j.optString("partner_id"),
                j.optString("partner_name").ifEmpty { "Your partner" },
                parseTime(j.optString("paired_at")),
                unpairBy = j.optString("unpair_by", ""),
                unpairDue = j.optLong("unpair_due", 0L) * 1000L,
            )
            else -> Pairing.None
        }
    }

    suspend fun pairing(): Result<Pairing> = attempt { parsePairing(call("POST", "/rest/v1/rpc/my_partnership", "{}").body) }

    suspend fun createInvite(): Result<Pairing> = attempt { parsePairing(call("POST", "/rest/v1/rpc/create_invite", "{}").body) }

    suspend fun join(code: String): Result<Pairing> =
        attempt { parsePairing(call("POST", "/rest/v1/rpc/join_invite", JSONObject().put("p_code", code).toString()).body) }

    /** The name of whoever made this invite, so the app can ask "Pair with Kofi?" before joining. */
    suspend fun inviteOwner(code: String): Result<String> =
        attempt { JSONArray("[" + call("POST", "/rest/v1/rpc/invite_owner", JSONObject().put("p_code", code).toString()).body + "]").getString(0) }

    /**
     * Cancels an invite nobody joined, or asks your partner to unpair (they have to agree), or agrees if they
     * asked first. The server refuses while a lock is on you.
     */
    suspend fun leave(): Result<Unit> = attempt {
        val body = call("POST", "/rest/v1/rpc/leave_partnership", "{}").body
        runCatching { JSONObject(body).optLong("note", 0L) }.getOrDefault(0L).takeIf { it > 0 }?.let { pushAsync(it) }
        Unit
    }

    /** Your partner asked to unpair: agree (the pairing ends) or say not now. */
    suspend fun unpairAnswer(agree: Boolean): Result<Unit> = attempt {
        val body = call("POST", "/rest/v1/rpc/unpair_answer", JSONObject().put("p_agree", agree).toString()).body
        runCatching { JSONObject(body).optLong("note", 0L) }.getOrDefault(0L).takeIf { it > 0 }?.let { pushAsync(it) }
        Unit
    }

    /** Takes back your own request to unpair. */
    suspend fun unpairCancel(): Result<Unit> = attempt { call("POST", "/rest/v1/rpc/unpair_cancel", "{}"); Unit }

    /** The locked phone's warning to the locker (stopped, overlay_off, admin_off, time_changed, emergency_dial). */
    suspend fun lockTamper(kind: String, detail: String): Result<Unit> = attempt {
        val body = call("POST", "/rest/v1/rpc/lock_tamper", JSONObject().put("p_kind", kind).put("p_detail", detail).toString()).body
        body.trim().toLongOrNull()?.let { pushAsync(it) }
        Unit
    }

    /**
     * The same warning with no lock running: a protection (device admin, "display over other apps", app-time
     * access, the guard, safe mode) was switched off on this paired phone without the partner's OK.
     */
    suspend fun protectTamper(kind: String): Result<Unit> = attempt {
        val body = call("POST", "/rest/v1/rpc/protect_tamper", JSONObject().put("p_kind", kind).put("p_detail", "").toString()).body
        body.trim().toLongOrNull()?.let { pushAsync(it) }
        Unit
    }

    // ---- Live line (games) ---------------------------------------------------------------------

    /** The name of the private live channel for my pair (live:<id>), or null when I am not paired or it can't be reached. */
    internal suspend fun liveTopic(): String? =
        attempt { call("POST", "/rest/v1/rpc/live_topic", "{}").body.trim().trim('"') }.getOrNull()?.takeIf { it.startsWith("live:") }

    /** The topic of my crew's live line (live:crew-<crew id>), or null when I'm not in a crew. */
    internal suspend fun liveCrewTopic(): String? =
        attempt { call("POST", "/rest/v1/rpc/live_crew_topic", "{}").body.trim().trim('"') }.getOrNull()?.takeIf { it.startsWith("live:crew-") }

    /** A current sign-in token for the live channel (renewed when it has run out). */
    internal suspend fun liveToken(): String? = auth.accessToken()

    // ---- Chat ---------------------------------------------------------------------------------

    private fun parseLines(text: String): List<ChatLine> {
        val me = auth.userId()
        val rows = JSONArray(text)
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
            val reacts = HashMap<String, String>()
            o.optJSONObject("reactions")?.let { r -> r.keys().forEach { k -> r.optString(k).takeIf { v -> v.isNotBlank() }?.let { v -> reacts[k] = v } } }
            ChatLine(
                o.getLong("id"), o.getString("sender") == me, o.getString("kind"), o.getString("body"), parseTime(o.getString("created_at")),
                replyTo = if (o.isNull("reply_to")) null else o.optLong("reply_to").takeIf { it > 0 },
                voice = str("voice"),
                voiceMs = o.optInt("voice_ms", 0),
                wave = if (o.isNull("voice_wave")) "" else o.optString("voice_wave"),
                media = str("media"),
                mediaW = o.optInt("media_w", 0),
                mediaH = o.optInt("media_h", 0),
                thumb = str("media_thumb"),
                gone = o.optBoolean("media_gone", false),
                edited = !o.isNull("edited_at"),
                deleted = !o.isNull("deleted_at"),
                reactions = reacts,
                updatedAt = o.optString("updated_at", ""),
                seen = !o.isNull("seen_at"),
            )
        }
    }

    // What the server can do changes with its migrations. 3 = seen ticks (20261118_seen), 2 = photos, edits, reactions (20261116_chat_media), 1 = replies
    // and voice (20261113_replies_voice), 0 = plain text. An older server answers 400 to columns it doesn't have, and
    // the chat steps down one level and keeps working.
    @Volatile private var chatLevel = 3
    private val plainCols = "id,sender,kind,body,created_at"
    private val richCols = "$plainCols,reply_to,voice,voice_ms,voice_wave"
    private val fullCols = "$richCols,media,media_w,media_h,media_thumb,media_gone,edited_at,deleted_at,reactions,updated_at"
    private val seenCols = "$fullCols,seen_at"

    private suspend fun <T> withCols(block: suspend (String) -> T): T {
        while (true) {
            val cols = when (chatLevel) { 3 -> seenCols; 2 -> fullCols; 1 -> richCols; else -> plainCols }
            try {
                return block(cols)
            } catch (e: ApiException) {
                if (e.message == OFFLINE || e.status != 400 || chatLevel == 0) throw e
                chatLevel--
            }
        }
    }

    /** True when the server can carry photos, edits, deletes and reactions. */
    val richer: Boolean get() = chatLevel >= 2

    /**
     * Messages after [afterId] (0 for the latest 60), oldest first. When the server keeps change stamps, [since] asks for
     * everything new or changed since that moment instead, so edits, deletes and reactions arrive too.
     */
    suspend fun messages(partnershipId: String, afterId: Long, since: String? = null): Result<List<ChatLine>> = attempt {
        withCols { cols ->
            val base = "/rest/v1/messages?partnership_id=eq.$partnershipId&select=$cols"
            when {
                afterId <= 0 -> parseLines(call("GET", "$base&order=id.desc&limit=60").body).reversed()
                chatLevel >= 2 && since != null ->
                    parseLines(call("GET", "$base&updated_at=gte.${java.net.URLEncoder.encode(since, "UTF-8")}&order=updated_at.asc,id.asc&limit=200").body)
                else -> parseLines(call("GET", "$base&id=gt.$afterId&order=id.asc&limit=200").body)
            }
        }
    }

    /** Up to 40 messages from before [beforeId], oldest first. */
    suspend fun older(partnershipId: String, beforeId: Long): Result<List<ChatLine>> = attempt {
        withCols { cols ->
            parseLines(call("GET", "/rest/v1/messages?partnership_id=eq.$partnershipId&select=$cols&id=lt.$beforeId&order=id.desc&limit=40").body).reversed()
        }
    }

    private suspend fun insert(
        partnershipId: String, kind: String, body: String,
        replyTo: Long? = null, voice: String? = null, voiceMs: Int = 0, wave: String = "",
        photo: PreparedPhoto? = null,
    ): ChatLine {
        val me = auth.userId() ?: throw ApiException("You're signed out. Sign in again.")
        val row = JSONObject().put("partnership_id", partnershipId).put("sender", me).put("kind", kind).put("body", body)
        if (replyTo != null && chatLevel >= 1) row.put("reply_to", replyTo)
        if (voice != null) {
            if (chatLevel < 1) throw ApiException("Voice messages need a LockIn server update. Try again soon.")
            row.put("voice", voice).put("voice_ms", voiceMs).put("voice_wave", wave)
        }
        if (photo != null) {
            if (chatLevel < 2) throw ApiException("Photos need a LockIn server update. Try again soon.")
            row.put("media", photo.uploaded).put("media_w", photo.w).put("media_h", photo.h).put("media_thumb", photo.thumb).put("media_bytes", photo.bytes)
        }
        val r = call("POST", "/rest/v1/messages", row.toString(), mapOf("Prefer" to "return=representation"))
        return parseLines(r.body).first()
    }

    suspend fun send(partnershipId: String, text: String, replyTo: Long? = null): Result<ChatLine> =
        attempt { insert(partnershipId, "text", text.trim(), replyTo = replyTo) }.alsoPush()

    /** Uploads a recording and sends it as a voice message. */
    suspend fun sendVoice(partnershipId: String, file: java.io.File, ms: Int, wave: String, replyTo: Long? = null): Result<ChatLine> = attempt {
        val path = "p-$partnershipId/${java.util.UUID.randomUUID()}.m4a"
        uploadVoice(path, file)
        // Kept locally under its path, so playing your own message needs no download.
        runCatching { file.copyTo(voiceCacheFile(path), overwrite = true) }
        insert(partnershipId, "text", "Voice message", replyTo = replyTo, voice = path, voiceMs = ms, wave = wave)
    }.alsoPush()

    /** Uploads a photo (once, even if sending is retried) and sends it with an optional caption. */
    suspend fun sendPhoto(partnershipId: String, photo: PreparedPhoto, caption: String, replyTo: Long? = null): Result<ChatLine> = attempt {
        if (chatLevel < 2) throw ApiException("Photos need a LockIn server update. Try again soon.")
        if (photo.uploaded == null) {
            val path = "p-$partnershipId/${java.util.UUID.randomUUID()}.jpg"
            bytesCall("POST", "/storage/v1/object/chat-media/$path", withContext(Dispatchers.IO) { photo.file.readBytes() }, "image/jpeg", "photo")
            // Your own copy stays on this phone: the server only holds the photo until your partner has theirs.
            runCatching { photo.file.copyTo(mediaCacheFile(path), overwrite = true) }
            photo.uploaded = path
        }
        insert(partnershipId, "text", caption.trim().ifBlank { "Photo" }, replyTo = replyTo, photo = photo)
    }.alsoPush()

    /** Fixes the words of your own message (within 15 minutes). */
    suspend fun edit(id: Long, text: String): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/edit_message", JSONObject().put("p_id", id).put("p_body", text.trim()).toString()); Unit }

    /** Deletes your own message for both of you. */
    suspend fun delete(id: Long): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/delete_message", JSONObject().put("p_id", id).toString()); Unit }

    /** Sets (or, with an empty [emoji], takes back) your reaction to a message. */
    suspend fun react(id: Long, emoji: String): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/react_message", JSONObject().put("p_id", id).put("p_emoji", emoji).toString()); Unit }

    /** "I have read everything up to [upto]": your partner's ticks turn to Seen. Quietly does nothing on an older server. */
    suspend fun markSeen(upto: Long): Result<Unit> =
        if (chatLevel < 3) Result.success(Unit)
        else attempt { call("POST", "/rest/v1/rpc/mark_seen", JSONObject().put("p_upto", upto).toString()); Unit }

    /** "I have it on my phone now": lets the server delete its copy of a photo or voice note a few hours later. */
    suspend fun ackMedia(id: Long): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/ack_media", JSONObject().put("p_id", id).toString()); Unit }

    // ---- Voice recordings (private "voice" bucket) and photos ("chat-media") ----------------------

    fun voiceCacheFile(path: String): java.io.File =
        java.io.File(VoiceDir.dir ?: throw ApiException("Voice isn't ready yet."), path.replace('/', '_'))

    fun mediaCacheFile(path: String): java.io.File =
        java.io.File(MediaDir.dir ?: throw ApiException("Photos aren't ready yet."), path.replace('/', '_'))

    private suspend fun bytes(method: String, path: String, body: ByteArray?, contentType: String?, token: String): Pair<Int, ByteArray>? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 40_000
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.use { it.readBytes() } ?: ByteArray(0))
            } catch (e: IOException) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private suspend fun bytesCall(method: String, path: String, body: ByteArray?, contentType: String?, what: String = "voice message"): ByteArray {
        val token = auth.accessToken() ?: throw ApiException("You're signed out. Sign in again.")
        var r = bytes(method, path, body, contentType, token) ?: throw ApiException(OFFLINE)
        if (r.first == 401 || r.first == 403 && String(r.second).contains("jwt", ignoreCase = true)) {
            val fresh = auth.accessToken(forceRefresh = true) ?: throw ApiException("Your session ended. Sign in again.")
            r = bytes(method, path, body, contentType, fresh) ?: throw ApiException(OFFLINE)
        }
        if (r.first !in 200..299) {
            // The server has deleted its copy (see 20261116_chat_media.sql) and this phone doesn't have one.
            val text = String(r.second)
            if (method == "GET" && (r.first == 404 || text.contains("not_found") || text.contains("Object not found"))) throw ApiException(GONE, 404)
            throw ApiException("Couldn't ${if (method == "GET") "load" else "send"} the $what.", r.first)
        }
        return r.second
    }

    suspend fun uploadVoice(path: String, file: java.io.File) {
        bytesCall("POST", "/storage/v1/object/voice/$path", file.readBytes(), "audio/mp4")
    }

    /** The recording at [path], downloaded once and then played from the phone. */
    suspend fun voiceFile(path: String): Result<java.io.File> = attempt {
        val f = voiceCacheFile(path)
        if (!f.exists() || f.length() == 0L) {
            // One saved before recordings moved out of the cache folder.
            val old = VoiceDir.legacy?.let { java.io.File(it, path.replace('/', '_')) }
            if (old != null && old.exists() && old.length() > 0L) {
                withContext(Dispatchers.IO) { old.copyTo(f, overwrite = true) }
            } else {
                val data = bytesCall("GET", "/storage/v1/object/authenticated/voice/$path", null, null)
                withContext(Dispatchers.IO) { writeWhole(f, data) }
            }
        }
        f
    }

    /** The photo at [path], downloaded once and kept on the phone. */
    suspend fun photoFile(path: String): Result<java.io.File> = attempt {
        val f = mediaCacheFile(path)
        if (!f.exists() || f.length() == 0L) {
            val data = bytesCall("GET", "/storage/v1/object/authenticated/chat-media/$path", null, null, "photo")
            withContext(Dispatchers.IO) { writeWhole(f, data) }
        }
        f
    }

    /** Writes to a temporary file and renames it, so a half-written file is never mistaken for a saved one. */
    private fun writeWhole(f: java.io.File, data: ByteArray) {
        val tmp = java.io.File(f.parentFile, f.name + ".part")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.writeBytes(data); tmp.delete() }
    }

    /** Something that happened, like "nudge" or "asked_unlock". The chat words it for each side. */
    suspend fun event(partnershipId: String, code: String): Result<ChatLine> =
        attempt { insert(partnershipId, "event", code) }.alsoPush()

    /** Once a message is saved, ask the server to push it to my partner's phone. Never blocks or fails the send. */
    private fun Result<ChatLine>.alsoPush(): Result<ChatLine> {
        getOrNull()?.let { pushAsync(it.id) }
        return this
    }

    /** Asks the server to push the chat note with this id to my partner. Fire and forget. */
    internal fun pushAsync(messageId: Long) {
        background.launch { attempt { call("POST", "/functions/v1/push", JSONObject().put("messageId", messageId).toString()) } }
    }

    /** Tells the rest of the crew about a crew message (chat line or session news). */
    internal fun pushCrewAsync(crewMessageId: Long) {
        background.launch { attempt { call("POST", "/functions/v1/push", JSONObject().put("crewMessageId", crewMessageId).toString()) } }
    }

    // ---- Push ---------------------------------------------------------------------------------

    suspend fun registerDevice(token: String): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/register_device", JSONObject().put("p_token", token).toString()); Unit }

    suspend fun unregisterDevice(token: String): Result<Unit> =
        attempt { call("POST", "/rest/v1/rpc/unregister_device", JSONObject().put("p_token", token).toString()); Unit }

    /** Tells the server whether I am typing, and returns whether my partner is. */
    suspend fun typing(partnershipId: String, typing: Boolean): Result<Boolean> = attempt {
        val body = JSONObject().put("p_partnership", partnershipId).put("p_typing", typing).toString()
        call("POST", "/rest/v1/rpc/typing_tick", body).body.trim() == "true"
    }

    internal fun parseTime(text: String): Long = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    private companion object {
        const val OFFLINE = "No connection. Check your internet and try again."
        const val GONE = "No longer available"
    }
}
