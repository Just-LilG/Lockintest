package com.godbless.lockin.data

import com.godbless.lockin.usage.Limit
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** One app's time for one day, as sent to the server. [day] is "2026-11-12" on this phone. */
class ReportRow(val day: String, val pkg: String, val label: String, val seconds: Int, val limitable: Boolean)

/** An app on my partner's phone, with how long they used it that day. */
class PartnerApp(val pkg: String, val label: String, val seconds: Int, val limitable: Boolean)

class PartnerDay(val day: LocalDate, val apps: List<PartnerApp>) {
    val total: Int = apps.sumOf { it.seconds }
}

/** A limit I set on my partner. */
class PartnerLimit(val pkg: String, val label: String, val seconds: Int)

/**
 * What my partner shares. [status]: "on" (there is data), "waiting" (access is on but nothing has arrived
 * yet), "no_access" (they have not allowed LockIn to read app times), "off" (they stopped sharing), or "none".
 */
class PartnerUsage(val status: String, val reportedAt: Long, val days: List<PartnerDay>, val limits: List<PartnerLimit>) {
    val today: PartnerDay? get() = days.firstOrNull()
}

class MySharing(val sharing: Boolean, val access: Boolean)

/**
 * App times and limits, over the same plain HTTPS calls as the chat. Everything is checked on the server:
 * you only ever see your own partner's data, and only while they share it.
 */
class UsageRepository(private val partners: PartnerRepository) {

    suspend fun report(rows: List<ReportRow>): Result<Boolean> = partners.attempt {
        val list = JSONArray()
        rows.forEach {
            list.put(
                JSONObject().put("day", it.day).put("package", it.pkg).put("label", it.label)
                    .put("seconds", it.seconds).put("limitable", it.limitable),
            )
        }
        partners.call("POST", "/rest/v1/rpc/usage_report", JSONObject().put("p_rows", list).toString()).body.trim() == "true"
    }

    private suspend fun set(sharing: Boolean?, access: Boolean?): Long? {
        val body = JSONObject().put("p_sharing", sharing ?: JSONObject.NULL).put("p_access", access ?: JSONObject.NULL)
        return partners.call("POST", "/rest/v1/rpc/usage_set", body.toString()).body.trim().toLongOrNull()
    }

    /** Turns sharing on or off. Your partner gets a chat note either way. */
    suspend fun setSharing(on: Boolean): Result<Unit> = partners.attempt {
        set(on, null)?.let { partners.pushAsync(it) }
        Unit
    }

    /** Tells the server whether this phone has allowed usage access, so my partner can see why nothing shows. */
    suspend fun setAccess(on: Boolean): Result<Unit> = partners.attempt { set(null, on); Unit }

    suspend fun mine(): Result<MySharing> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/usage_mine", "{}").body)
        MySharing(j.optBoolean("sharing", true), j.optBoolean("access", false))
    }

    suspend fun partner(days: Int = 7): Result<PartnerUsage> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/partner_usage", JSONObject().put("p_days", days).toString()).body)
        val dayList = j.optJSONArray("days") ?: JSONArray()
        val parsedDays = List(dayList.length()) {
            val d = dayList.getJSONObject(it)
            val apps = d.getJSONArray("apps")
            PartnerDay(
                LocalDate.parse(d.getString("day")),
                List(apps.length()) { i ->
                    val a = apps.getJSONObject(i)
                    PartnerApp(a.getString("package"), a.getString("label"), a.getInt("seconds"), a.optBoolean("limitable", true))
                },
            )
        }
        val limitList = j.optJSONArray("limits") ?: JSONArray()
        val limits = List(limitList.length()) {
            val l = limitList.getJSONObject(it)
            PartnerLimit(l.getString("package"), l.getString("label"), l.getInt("seconds"))
        }
        val reported = if (j.isNull("reported_at")) 0L else partners.parseTime(j.optString("reported_at"))
        PartnerUsage(j.optString("status", "none"), reported, parsedDays, limits)
    }

    /** The limits my partner has set on this phone. */
    suspend fun myLimits(): Result<List<Limit>> = partners.attempt {
        val a = JSONArray(partners.call("POST", "/rest/v1/rpc/my_limits", "{}").body)
        List(a.length()) {
            val o = a.getJSONObject(it)
            Limit(o.getString("package"), o.getString("label"), o.getInt("seconds"), o.optString("by", "Your partner"))
        }
    }

    suspend fun setLimit(pkg: String, seconds: Int): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_package", pkg).put("p_seconds", seconds).toString()
        partners.call("POST", "/rest/v1/rpc/set_limit", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }

    suspend fun removeLimit(pkg: String): Result<Unit> = partners.attempt {
        val body = JSONObject().put("p_package", pkg).toString()
        partners.call("POST", "/rest/v1/rpc/remove_limit", body).body.trim().toLongOrNull()?.let { partners.pushAsync(it) }
        Unit
    }
}
