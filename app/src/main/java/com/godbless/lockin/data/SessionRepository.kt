package com.godbless.lockin.data

import android.content.Context
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finished sessions. A session is written to a file on the phone first and sent afterwards, because a
 * locked phone is often offline; the file is emptied as the server confirms each one. Each session has
 * its own id, so sending one twice is harmless.
 */
class SessionRepository(context: Context, private val api: PartnerRepository, private val auth: AuthRepository) {
    private val file = File(context.applicationContext.filesDir, "pending_sessions.json")
    private val lock = Mutex()

    /** Waiting to be sent: the row and the account it belongs to. */
    private class Pending(val owner: String, val row: SessionRow)

    private fun load(): MutableList<Pending> = try {
        val a = JSONArray(file.readText())
        MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            Pending(
                o.getString("owner"),
                SessionRow(o.getString("id"), true, o.getLong("at"), o.getInt("seconds"), o.getInt("pickups"), o.getString("title")),
            )
        }
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun save(list: List<Pending>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("owner", it.owner).put("id", it.row.id).put("at", it.row.startedAt)
                    .put("seconds", it.row.seconds).put("pickups", it.row.pickups).put("title", it.row.title),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    /** Sessions of this account that the server has not confirmed yet. */
    suspend fun unsent(): List<SessionRow> = lock.withLock {
        val me = auth.userId() ?: return emptyList()
        withContext(Dispatchers.IO) { load().filter { it.owner == me }.map { it.row } }
    }

    /** Saves a finished session on the phone. Call [flush] afterwards to send it. */
    suspend fun enqueue(row: SessionRow) {
        val me = auth.userId() ?: return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val list = load()
                if (list.none { it.row.id == row.id }) list.add(Pending(me, row))
                save(list)
            }
        }
    }

    /** Sends whatever is waiting. Stops at the first failure and tries again next time. */
    suspend fun flush() {
        val me = auth.userId() ?: return
        lock.withLock {
            val list = withContext(Dispatchers.IO) { load() }
            val mine = list.filter { it.owner == me }
            for (item in mine) {
                val r = row(item.row)
                val sent = api.attempt { api.call("POST", "/rest/v1/sessions?on_conflict=id", r, mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")) }
                if (sent.isFailure) break
                list.remove(item)
                withContext(Dispatchers.IO) { save(list) }
            }
        }
    }

    private fun row(s: SessionRow): String = JSONObject()
        .put("id", s.id)
        .put("started_at", Instant.ofEpochMilli(s.startedAt).toString())
        .put("seconds", s.seconds)
        .put("pickups", s.pickups)
        .put("title", s.title)
        .toString()

    /** The last [days] days of sessions, yours and your partner's, newest first. */
    suspend fun recent(days: Int = 62): Result<List<SessionRow>> = api.attempt {
        val since = Instant.now().minusSeconds(days * 86_400L)
        val path = "/rest/v1/sessions?select=id,user_id,started_at,seconds,pickups,title" +
            "&started_at=gte.$since&order=started_at.desc&limit=1000"
        val me = auth.userId()
        val rows = JSONArray(api.call("GET", path).body)
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            SessionRow(
                o.getString("id"),
                o.getString("user_id") == me,
                api.parseTime(o.getString("started_at")),
                o.getInt("seconds"),
                o.optInt("pickups", 0),
                o.optString("title", "Focus session"),
            )
        }
    }

    suspend fun totals(): Result<Totals> = api.attempt {
        val j = JSONObject(api.call("POST", "/rest/v1/rpc/session_totals", "{}").body)
        Totals(j.optLong("mine_seconds"), j.optInt("mine_count"), j.optLong("partner_seconds"), j.optInt("partner_count"))
    }
}
