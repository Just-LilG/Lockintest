package com.godbless.lockin.lock

import android.content.Context
import com.godbless.lockin.data.AllowedApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The apps your partner allows during their locks, kept on the phone so the lock honours them with no signal.
 * Written whenever the list is fetched (LockIn's Lock page, and the lock service every minute or so).
 */
class AllowedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lock_allowed", Context.MODE_PRIVATE)

    fun save(apps: List<AllowedApp>) {
        val a = JSONArray()
        apps.take(3).forEach { a.put(JSONObject().put("p", it.pkg).put("l", it.label)) }
        prefs.edit().putString("apps", a.toString()).apply()
    }

    fun load(): List<AllowedApp> = runCatching {
        val a = JSONArray(prefs.getString("apps", "[]"))
        List(a.length()) { AllowedApp(a.getJSONObject(it).getString("p"), a.getJSONObject(it).getString("l")) }
    }.getOrDefault(emptyList())

    fun clear() = prefs.edit().remove("apps").apply()
}
