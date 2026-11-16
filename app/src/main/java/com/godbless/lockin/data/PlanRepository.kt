package com.godbless.lockin.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A session planned by one of you for both. [startsAt] is in epoch milliseconds. */
class Plan(
    val id: String,
    val title: String,
    val startsAt: Long,
    val minutes: Int,
    val mine: Boolean,
    val by: String,
)

/** Daily goals in minutes. [theirs] is null until there is a partner. */
class Goals(val mine: Int, val theirs: Int?)

/**
 * Daily goals and planned sessions, over the same plain HTTPS calls as the chat. The server keeps one
 * goal per person and one shared list of plans per pair, so both phones always see the same plans.
 */
class PlanRepository(private val partners: PartnerRepository) {

    suspend fun goals(): Result<Goals> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/goals", "{}").body)
        Goals(j.optInt("mine", 120), if (j.isNull("theirs")) null else j.optInt("theirs", 120))
    }

    suspend fun setGoal(minutes: Int): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/set_goal", JSONObject().put("p_minutes", minutes).toString())
        Unit
    }

    suspend fun plans(): Result<List<Plan>> = partners.attempt {
        val rows = JSONArray(partners.call("POST", "/rest/v1/rpc/my_plans", "{}").body)
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            Plan(
                id = o.getString("id"),
                title = o.getString("title"),
                startsAt = o.getLong("at") * 1000L,
                minutes = o.getInt("minutes"),
                mine = o.optBoolean("mine"),
                by = o.optString("by"),
            )
        }
    }

    /** Plans a session and tells my partner. */
    suspend fun plan(title: String, startsAtMillis: Long, minutes: Int): Result<Unit> = partners.attempt {
        val body = JSONObject()
            .put("p_title", title)
            .put("p_starts_at", Instant.ofEpochMilli(startsAtMillis).toString())
            .put("p_minutes", minutes)
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/plan_session", body.toString()).body)
        j.optLong("note", 0L).takeIf { it > 0 }?.let { partners.pushAsync(it) }
        Unit
    }

    /** Cancels a planned session for both of us. */
    suspend fun cancel(id: String): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/cancel_session", JSONObject().put("p_id", id).toString()).body.trim()
            .toLongOrNull()?.takeIf { it > 0 }?.let { partners.pushAsync(it) }
        Unit
    }
}
