package com.godbless.lockin.data

import org.json.JSONArray
import org.json.JSONObject

/** Someone in your crew, with what they did this week. */
data class CrewMember(
    val id: String,
    val name: String,
    /** Their message sound (what their crew messages ring with). */
    val sound: String,
    /** Seconds held in crew sessions since Monday. */
    val week: Int,
    /** Sessions they left early this week. */
    val early: Int,
    /** Seconds held in the week before, for who moved up or down. */
    val prev: Int = 0,
    /** Seconds held in this crew, ever, and how many sessions that was. */
    val total: Int = 0,
    val sessions: Int = 0,
    /** When their latest session began, in seconds since 1970 (0 if none), and when they joined. */
    val last: Long = 0L,
    val joinedAt: Long = 0L,
    /** Seconds held on each day of this week, Monday first. */
    val days: List<Int> = List(7) { 0 },
    /** How they chose to look, if the server sent it. */
    val avatar: AvatarSpec? = null,
)

/** The crew's session that hasn't finished yet: waiting to start, or running. */
data class CrewSession(
    val id: Long,
    val by: String,
    val byName: String,
    val seconds: Int,
    /** Seconds until it begins; 0 once it runs. */
    val startsIn: Int,
    /** Seconds left (from the start if it hasn't begun). */
    val remaining: Int,
    val joined: Set<String>,
    val left: Set<String>,
    /** When this was read, so the clocks can keep counting between reads. */
    val readAt: Long = System.currentTimeMillis(),
)

data class Crew(
    val id: String,
    val name: String,
    val code: String,
    /** You made it. */
    val mine: Boolean,
    val me: String,
    val members: List<CrewMember>,
    val session: CrewSession?,
    /** The newest chat line, for the preview on the Crew tab. */
    val recent: CrewLine?,
    /** Id of the newest crew message of any kind. */
    val last: Long,
    /** The crew's weekly goal in minutes (0 when none is set) and what everyone has held since Monday, in seconds. */
    val goalMin: Int = 0,
    val weekTotal: Int = 0,
    /** Monday 00:00 UTC this week, in seconds since 1970. */
    val weekStart: Long = 0L,
    /** Days in a row (UTC) with two or more members holding 10+ minutes together. */
    val streak: Int = 0,
    val lastWeek: CrewRecap? = null,
    val standing: List<CrewStanding> = emptyList(),
    /** The crew's look: an emoji (may be empty) and a colour index (0 to 7). */
    val emoji: String = "",
    val color: Int = 0,
    /** Who runs the crew (can rename it, remove people, make a new code). */
    val owner: String = "",
)

/** How last week went for the crew. */
data class CrewRecap(val total: Int, val sessions: Int, val topName: String, val topSeconds: Int)

/** A weekly slot the crew meets at. [wkMinute] counts minutes from Monday 00:00 UTC. */
data class CrewStanding(val id: Long, val wkMinute: Int, val minutes: Int, val byName: String, val by: String = "")

data class CrewLine(
    val id: Long,
    val sender: String?,
    val name: String,
    /** "text" or "event". */
    val kind: String,
    val body: String,
    /** Seconds since 1970. */
    val at: Long,
    val mine: Boolean,
    val replyTo: Long? = null,
    /** A voice recording's path in the "voice" bucket, if this is a voice message. */
    val voice: String? = null,
    val voiceMs: Int = 0,
    val wave: String = "",
)

/** The phone's check-in during a crew lock. */
data class CrewBeat(val state: String, val id: Long, val remaining: Int, val startsIn: Int, val crew: String)

/** Crews, over the same signed-in HTTPS calls as pairing. The server functions are in 20261113_crews.sql. */
class CrewRepository(private val partners: PartnerRepository) {

    /** Your crew, or null when you're not in one. */
    suspend fun state(): Result<Crew?> = partners.attempt {
        // The week starts on this phone's Monday (the server works it out from the offset in minutes).
        val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val body = runCatching { partners.call("POST", "/rest/v1/rpc/crew_state_v2", JSONObject().put("p_tz", offset).toString()).body }
            .getOrElse { partners.call("POST", "/rest/v1/rpc/crew_state", "{}").body }
        val j = JSONObject(body)
        val c = j.optJSONObject("crew") ?: return@attempt null
        val me = j.optString("me")
        val members = j.optJSONArray("members") ?: JSONArray()
        val s = j.optJSONObject("session")
        val r = j.optJSONObject("recent")
        Crew(
            id = c.optString("id"),
            name = c.optString("name"),
            code = c.optString("code"),
            emoji = c.optString("emoji"),
            color = c.optInt("color"),
            owner = c.optString("owner").takeIf { it != "null" }.orEmpty(),
            mine = c.optBoolean("mine"),
            me = me,
            members = List(members.length()) { i ->
                val m = members.getJSONObject(i)
                val avatar = AvatarSpec.fromJson(m.optJSONObject("avatar"))
                // You are drawn from your own saved choice; everyone else is remembered by name.
                if (m.optString("id") != me) AvatarBook.learn(m.optString("name").ifBlank { "Someone" }, avatar)
                CrewMember(
                    id = m.optString("id"),
                    name = m.optString("name").ifBlank { "Someone" },
                    sound = m.optString("sound", "message"),
                    week = m.optInt("week"),
                    early = m.optInt("early"),
                    prev = m.optInt("prev"),
                    total = m.optInt("total"),
                    sessions = m.optInt("sessions"),
                    last = if (m.isNull("last")) 0L else m.optLong("last"),
                    joinedAt = m.optLong("joined_at"),
                    days = m.optJSONArray("days")?.let { d -> List(7) { k -> d.optInt(k) } } ?: List(7) { 0 },
                    avatar = avatar,
                )
            },
            session = s?.let {
                CrewSession(
                    id = it.optLong("id"),
                    by = it.optString("by"),
                    byName = it.optString("by_name"),
                    seconds = it.optInt("seconds"),
                    startsIn = it.optInt("starts_in"),
                    remaining = it.optInt("remaining"),
                    joined = ids(it.optJSONArray("joined")),
                    left = ids(it.optJSONArray("left")),
                )
            },
            recent = r?.let { CrewLine(0, null, it.optString("name"), "text", it.optString("body"), it.optLong("at"), it.optBoolean("mine")) },
            last = j.optLong("last"),
            goalMin = j.optInt("goal_min"),
            weekTotal = j.optInt("week_total"),
            weekStart = j.optLong("week_start"),
            streak = j.optInt("streak"),
            lastWeek = j.optJSONObject("last_week")?.let {
                if (it.optInt("sessions") <= 0) null
                else CrewRecap(it.optInt("total"), it.optInt("sessions"), it.optString("top_name").takeIf { n -> n != "null" }.orEmpty(), it.optInt("top_seconds"))
            },
            standing = j.optJSONArray("standing")?.let { a ->
                List(a.length()) { i ->
                    val s = a.getJSONObject(i)
                    CrewStanding(s.optLong("id"), s.optInt("wk_minute"), s.optInt("minutes"), s.optString("by_name").takeIf { n -> n != "null" }.orEmpty(), s.optString("by").takeIf { n -> n != "null" }.orEmpty())
                }
            }.orEmpty(),
        )
    }

    private fun ids(a: JSONArray?): Set<String> = if (a == null) emptySet() else List(a.length()) { a.optString(it) }.toSet()

    suspend fun create(name: String): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/crew_create", JSONObject().put("p_name", name.trim()).toString())
        Unit
    }

    suspend fun join(code: String): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_join", JSONObject().put("p_code", code.trim().uppercase()).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    suspend fun leave(): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/crew_leave", "{}")
        Unit
    }

    /** Renames the crew and sets its look (owner only). A blank name keeps the old one. */
    suspend fun update(name: String, emoji: String, color: Int): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_update", JSONObject().put("p_name", name.trim()).put("p_emoji", emoji).put("p_color", color).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    /** Removes a member (owner only). */
    suspend fun kick(user: String): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_kick", JSONObject().put("p_user", user).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    /** Makes a new invite code; the old one stops working (owner only). */
    suspend fun newCode(): Result<String> = partners.attempt {
        JSONObject(partners.call("POST", "/rest/v1/rpc/crew_new_code", "{}").body).optString("code")
    }

    suspend fun messages(after: Long): Result<List<CrewLine>> = partners.attempt {
        val a = JSONArray(partners.call("POST", "/rest/v1/rpc/crew_messages", JSONObject().put("p_after", after).put("p_limit", 80).toString()).body)
        List(a.length()) { i ->
            val m = a.getJSONObject(i)
            CrewLine(
                id = m.optLong("id"),
                sender = m.optString("sender").takeIf { it.isNotBlank() && it != "null" },
                name = m.optString("name"),
                kind = m.optString("kind"),
                body = m.optString("body"),
                at = m.optLong("at"),
                mine = m.optBoolean("mine"),
                replyTo = if (m.isNull("reply_to")) null else m.optLong("reply_to").takeIf { it > 0 },
                voice = if (m.isNull("voice")) null else m.optString("voice").takeIf { it.isNotBlank() },
                voiceMs = m.optInt("voice_ms", 0),
                wave = if (m.isNull("voice_wave")) "" else m.optString("voice_wave"),
            )
        }
    }

    suspend fun send(text: String, replyTo: Long? = null): Result<Long> = partners.attempt {
        val body = JSONObject().put("p_body", text)
        if (replyTo != null) body.put("p_reply", replyTo)
        val id = partners.call("POST", "/rest/v1/rpc/crew_send", body.toString()).body.trim().toLong()
        partners.pushCrewAsync(id)
        id
    }

    /** Uploads a recording to the crew's folder and sends it as a voice message. */
    suspend fun sendVoice(crewId: String, file: java.io.File, ms: Int, wave: String, replyTo: Long? = null): Result<Long> = partners.attempt {
        val path = "c-$crewId/${java.util.UUID.randomUUID()}.m4a"
        partners.uploadVoice(path, file)
        runCatching { file.copyTo(partners.voiceCacheFile(path), overwrite = true) }
        val body = JSONObject().put("p_body", "Voice message").put("p_voice", path).put("p_voice_ms", ms).put("p_voice_wave", wave)
        if (replyTo != null) body.put("p_reply", replyTo)
        val id = partners.call("POST", "/rest/v1/rpc/crew_send", body.toString()).body.trim().toLong()
        partners.pushCrewAsync(id)
        id
    }

    suspend fun voiceFile(path: String) = partners.voiceFile(path)

    suspend fun start(minutes: Int, startIn: Int): Result<Long> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_session_start", JSONObject().put("p_minutes", minutes).put("p_start_in", startIn).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        j.optLong("session")
    }

    /** Returns the seconds left, and the seconds until it begins. */
    suspend fun joinSession(id: Long): Result<Pair<Int, Int>> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_session_join", JSONObject().put("p_session", id).toString()).body)
        j.optInt("remaining") to j.optInt("starts_in")
    }

    suspend fun leaveSession(id: Long): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_session_leave", JSONObject().put("p_session", id).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    /** The crew's weekly goal in minutes; 0 clears it. */
    suspend fun setGoal(minutes: Int): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_goal_set", JSONObject().put("p_minutes", minutes).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    /** Says once a week that the crew reached its goal. Quiet when it already has or the goal isn't reached. */
    suspend fun checkGoal(): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_goal_check", "{}").body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    suspend fun addStanding(wkMinute: Int, minutes: Int): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/crew_standing_add", JSONObject().put("p_wk_minute", wkMinute).put("p_minutes", minutes).toString())
        Unit
    }

    suspend fun removeStanding(id: Long): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/crew_standing_remove", JSONObject().put("p_id", id).toString())
        Unit
    }

    /** Taps a member who hasn't joined the running session. */
    suspend fun poke(user: String): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_poke", JSONObject().put("p_user", user).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    /** [kind] is "cheer" or "back" (get back on the rope). */
    suspend fun cheer(user: String, kind: String): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_cheer", JSONObject().put("p_user", user).put("p_kind", kind).toString()).body)
        j.optLong("message").takeIf { it > 0 }?.let { partners.pushCrewAsync(it) }
        Unit
    }

    suspend fun beat(): Result<CrewBeat> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/crew_beat", "{}").body)
        CrewBeat(j.optString("state", "none"), j.optLong("id"), j.optInt("remaining"), j.optInt("starts_in"), j.optString("crew"))
    }
}
