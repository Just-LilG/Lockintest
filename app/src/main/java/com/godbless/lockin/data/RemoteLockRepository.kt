package com.godbless.lockin.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A lock my partner set on my phone. [remaining] is what the server says is left. When [active] is false,
 * [lastId] and [lastEndedBy] ("time", "locker", "emergency" or "unpaired") say how the latest one ended.
 */
class MyLock(
    val active: Boolean,
    val id: Long,
    val seconds: Int,
    val remaining: Int,
    val by: String,
    val lastId: Long,
    val lastEndedBy: String,
    /** What my partner wrote when they locked me. */
    val message: String = "",
    /** My partner turned off unlock requests for this lock. */
    val strict: Boolean = false,
    /** My partner started their own session at the same time. */
    val together: Boolean = false,
    /** I asked to be unlocked and am waiting for an answer. */
    val asked: Boolean = false,
    /** "denied" after my partner said not yet, otherwise empty. */
    val answer: String = "",
    /** Seconds until I may ask again after a no. */
    val retryIn: Int = 0,
    /** My partner scheduled a lock that has not begun; [startsIn] is the wait in seconds. */
    val upcoming: Boolean = false,
    val startsIn: Int = 0,
)

/** A lock I set on my partner, with what their phone has reported about it. */
class TheirLock(
    val id: Long,
    val seconds: Int,
    val remaining: Int,
    val message: String = "",
    val strict: Boolean = false,
    val together: Boolean = false,
    /** Times they picked their phone up (tried to leave) during this lock. */
    val pickups: Int = 0,
    /** Seconds I added after the start. */
    val extended: Int = 0,
    /** They asked me to unlock them and I have not answered. */
    val asked: Boolean = false,
    val reason: String = "",
    /** "denied" once I said not yet. */
    val answer: String = "",
    /** Seconds until it begins, when I scheduled it for later. 0 once it runs. */
    val startsIn: Int = 0,
    /** Seconds since their phone last checked in during this lock (-1 if it never has). */
    val beatAgo: Int = -1,
    /** Times LockIn had to cover another app they opened during this lock. */
    val escapes: Int = 0,
)

/** One finished lock, from either side. [held] is how long it really ran, in seconds. */
class LockRecord(
    val id: Long,
    val mine: Boolean,
    val seconds: Int,
    val held: Int,
    val endedBy: String,
    val pickups: Int,
    val message: String,
    val together: Boolean,
    val asked: Boolean,
    val startedAt: Long,
    /** Set up for later and cancelled before it began. */
    val canceled: Boolean = false,
)

/**
 * A repeating lock: on the [days] set (bit 0 Monday ... bit 6 Sunday), at [startMinute] past midnight in [tz],
 * for [seconds]. [mine] is true for one I set on my partner, false for one set on me.
 */
class LockRepeat(
    val id: Long,
    val mine: Boolean,
    val days: Int,
    val startMinute: Int,
    val seconds: Int,
    val tz: String,
    val message: String,
    val strict: Boolean,
)

/** Whether someone accepts locks, and the longest one they accept (seconds). */
class LockRule(val allowed: Boolean, val max: Int)

class LockRules(val mine: LockRule, val theirs: LockRule)

/** An app that stays usable during a lock. */
data class AllowedApp(val pkg: String, val label: String)

/** [mine]: apps you may use during your partner's locks. [theirs]: apps you allowed on your partner's phone. */
class AllowedApps(val mine: List<AllowedApp>, val theirs: List<AllowedApp>)

/** Your partner can allow up to this many apps during your locks. */
const val MaxAllowedApps = 3

/**
 * Locking your partner's phone, over the same plain HTTPS calls as the chat. The server holds the clock:
 * a lock ends at a fixed time, even if the locked phone was offline when it began.
 */
class RemoteLockRepository(private val partners: PartnerRepository) {

    /** Locks my partner's phone for [seconds] (5 minutes to 8 hours). They get a push. */
    suspend fun lock(seconds: Int, message: String = "", strict: Boolean = false, together: Boolean = false, startInSeconds: Int = 0): Result<TheirLock> = partners.attempt {
        val body = JSONObject()
            .put("p_seconds", seconds)
            .put("p_message", message)
            .put("p_strict", strict)
            .put("p_together", together)
            .put("p_start_in", startInSeconds)
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/lock_partner", body.toString()).body)
        j.optLong("note", 0L).takeIf { it > 0 }?.let { partners.pushAsync(it) }
        TheirLock(j.getLong("id"), j.getInt("seconds"), j.getInt("remaining"), message.trim(), strict, together, startsIn = j.optInt("starts_in", 0))
    }

    /** I (the locked one) ask my partner to unlock me, with a short reason. */
    suspend fun ask(reason: String): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_reason", reason).toString()
        partners.call("POST", "/rest/v1/rpc/lock_ask", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    /** I (the locked one) withdraw my request before my partner answers. */
    suspend fun askCancel(): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/lock_ask_cancel", "{}")
        Unit
    }

    /** I (the locker) answer a request: unlock them, or say not yet. */
    suspend fun answer(approve: Boolean): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_approve", approve).toString()
        partners.call("POST", "/rest/v1/rpc/lock_answer", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    /** I (the locked one) tell the server how many times I picked up my phone, so my partner can see it. */
    suspend fun report(pickups: Int): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/lock_report", JSONObject().put("p_pickups", pickups).toString())
        Unit
    }

    /** I (the locker) add time to the lock that is running. */
    suspend fun extend(seconds: Int): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/lock_extend", JSONObject().put("p_seconds", seconds).toString()).body)
        j.optLong("note", 0L).takeIf { it > 0 }?.let { partners.pushAsync(it) }
        Unit
    }

    /** The latest finished locks between us, newest first. */
    suspend fun history(): Result<List<LockRecord>> = partners.attempt {
        val rows = JSONArray(partners.call("POST", "/rest/v1/rpc/lock_history", JSONObject().put("p_limit", 30).toString()).body)
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            LockRecord(
                id = o.getLong("id"),
                mine = o.getBoolean("mine"),
                seconds = o.getInt("seconds"),
                held = o.getInt("held"),
                endedBy = o.optString("ended_by"),
                pickups = o.optInt("pickups", 0),
                message = o.optString("message"),
                together = o.optBoolean("together", false),
                asked = o.optBoolean("asked", false),
                startedAt = o.optLong("started", 0L) * 1000L,
                canceled = o.optBoolean("canceled", false),
            )
        }
    }

    suspend fun allowed(): Result<AllowedApps> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/allowed_apps", "{}").body)
        fun list(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) {
            val o = a.getJSONObject(it)
            AllowedApp(o.optString("package"), o.optString("label"))
        }
        AllowedApps(list(j.optJSONArray("mine")), list(j.optJSONArray("theirs")))
    }

    /** Lets your partner keep using [app] during your locks of them. */
    suspend fun allow(app: AllowedApp): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_package", app.pkg).put("p_label", app.label).toString()
        partners.call("POST", "/rest/v1/rpc/allow_app", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    suspend fun disallow(pkg: String): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/disallow_app", JSONObject().put("p_package", pkg).toString())
            .body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    suspend fun rules(): Result<LockRules> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/lock_rules", "{}").body)
        fun rule(k: String) = j.getJSONObject(k).let { LockRule(it.optBoolean("allowed", true), it.optInt("max", 28800)) }
        LockRules(rule("mine"), rule("theirs"))
    }

    suspend fun setRules(allowed: Boolean, maxSeconds: Int): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_allowed", allowed).put("p_max", maxSeconds).toString()
        partners.call("POST", "/rest/v1/rpc/lock_rules_set", body)
        Unit
    }

    /** Ends the lock I set early. */
    suspend fun unlock(): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/unlock_partner", "{}").body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    /** The locked person's emergency release. Ends the lock and tells my partner. */
    suspend fun emergency(): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/lock_emergency", "{}").body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    suspend fun mine(): Result<MyLock> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/my_lock", "{}").body)
        val last = j.optJSONObject("last")
        MyLock(
            active = j.optString("state") == "active",
            id = j.optLong("id", 0L),
            seconds = j.optInt("seconds", 0),
            remaining = j.optInt("remaining", 0),
            by = j.optString("by", "Your partner"),
            lastId = last?.optLong("id", 0L) ?: 0L,
            lastEndedBy = last?.optString("ended_by", "") ?: "",
            message = j.optString("message", ""),
            strict = j.optBoolean("strict", false),
            together = j.optBoolean("together", false),
            asked = j.optBoolean("asked", false),
            answer = j.optString("answer", ""),
            retryIn = j.optInt("retry_in", 0),
            upcoming = j.optString("state") == "scheduled",
            startsIn = j.optInt("starts_in", 0),
        )
    }

    suspend fun theirs(): Result<TheirLock?> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/their_lock", "{}").body)
        if (j.optString("state") == "active") {
            TheirLock(
                id = j.getLong("id"),
                seconds = j.getInt("seconds"),
                remaining = j.getInt("remaining"),
                message = j.optString("message", ""),
                strict = j.optBoolean("strict", false),
                together = j.optBoolean("together", false),
                pickups = j.optInt("pickups", 0),
                extended = j.optInt("extended", 0),
                asked = j.optBoolean("asked", false),
                reason = j.optString("reason", ""),
                answer = j.optString("answer", ""),
                startsIn = j.optInt("starts_in", 0),
                beatAgo = j.optInt("beat_ago", -1),
                escapes = j.optInt("escapes", 0),
            )
        } else {
            null
        }
    }

    /** Repeating locks, both the ones I set and the ones set on me. */
    suspend fun repeats(): Result<List<LockRepeat>> = partners.attempt {
        val rows = JSONArray(partners.call("POST", "/rest/v1/rpc/lock_schedules", "{}").body)
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            LockRepeat(
                id = o.getLong("id"),
                mine = o.optBoolean("mine", false),
                days = o.optInt("days", 0),
                startMinute = o.optInt("start_minute", 0),
                seconds = o.optInt("seconds", 0),
                tz = o.optString("tz", java.time.ZoneId.systemDefault().id),
                message = o.optString("message", ""),
                strict = o.optBoolean("strict", false),
            )
        }
    }

    /** Sets a repeating lock on my partner, in this phone's time zone. */
    suspend fun addRepeat(days: Int, startMinute: Int, seconds: Int, message: String, strict: Boolean): Result<Unit> = partners.attempt {
        val body = JSONObject()
            .put("p_days", days)
            .put("p_start_minute", startMinute)
            .put("p_seconds", seconds)
            .put("p_tz", java.time.ZoneId.systemDefault().id)
            .put("p_message", message)
            .put("p_strict", strict)
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/schedule_add", body.toString()).body)
        j.optLong("note", 0L).takeIf { it > 0 }?.let { partners.pushAsync(it) }
        Unit
    }

    /** Deletes one of my repeating locks. */
    suspend fun removeRepeat(id: Long): Result<Unit> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/schedule_remove", JSONObject().put("p_id", id).toString()).body)
        j.optLong("note", 0L).takeIf { it > 0 }?.let { partners.pushAsync(it) }
        Unit
    }

    /**
     * The locked phone checks in: it reports pickups and escapes, and hears back whether the lock still runs and
     * how long is left (the server's clock decides). Null remaining means the lock is over.
     */
    suspend fun beat(pickups: Int, escapes: Int, deviceId: String? = null): Result<Int?> = partners.attempt {
        val body = JSONObject().put("p_pickups", pickups).put("p_escapes", escapes)
            .apply { if (deviceId != null) put("p_device", deviceId) }.toString()
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/lock_beat", body).body)
        if (j.optString("state") == "active") j.optInt("remaining", 0) else null
    }
}
