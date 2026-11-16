package com.godbless.lockin.data

import org.json.JSONObject

/**
 * Asking my partner to allow something that would take the protection off, and answering when they ask me.
 * The server (protect_ask, protect_status, protect_answer) keeps the requests; see 20261123_protection.sql.
 */
class ProtectRepository(private val partners: PartnerRepository) {

    /** [state]: none (nobody to ask) | open | allowed | declined | expired. [left] is seconds (allowed, or until I may ask again). */
    class Reply(val state: String, val id: Long, val left: Int)

    private fun parse(body: String): Reply {
        val j = JSONObject(body.trim().ifEmpty { "{}" })
        return Reply(j.optString("state", "expired"), j.optLong("id", 0L), j.optInt("left", 0))
    }

    /** I ask for [kind]. Pushes my partner when a new request was made. */
    suspend fun ask(kind: String): Result<Reply> = partners.attempt {
        val r = partners.call("POST", "/rest/v1/rpc/protect_ask", JSONObject().put("p_kind", kind).toString())
        val j = JSONObject(r.body.trim().ifEmpty { "{}" })
        j.optLong("note", 0L).takeIf { it > 0L }?.let { partners.pushAsync(it) }
        Reply(j.optString("state", "expired"), j.optLong("id", 0L), j.optInt("left", 0))
    }

    /** Where my request stands. */
    suspend fun status(id: Long): Result<Reply> = partners.attempt {
        parse(partners.call("POST", "/rest/v1/rpc/protect_status", JSONObject().put("p_id", id).toString()).body)
    }

    /** I answer my partner's newest request. */
    suspend fun answer(allow: Boolean): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_allow", allow).toString()
        partners.call("POST", "/rest/v1/rpc/protect_answer", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }
}
