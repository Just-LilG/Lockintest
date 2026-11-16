package com.godbless.lockin.usage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A daily limit your partner set on one of your apps. [by] is their name, for the block screen. */
class Limit(val pkg: String, val label: String, val seconds: Int, val by: String)

/**
 * What the guard service needs to know even with no signal and no screen open: the limits, which apps
 * it has suspended, and whether it should be reporting app times. Kept on the phone.
 */
class LimitStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("limits", Context.MODE_PRIVATE)

    fun load(): List<Limit> = runCatching {
        val a = JSONArray(prefs.getString(KeyLimits, "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            Limit(o.getString("package"), o.getString("label"), o.getInt("seconds"), o.optString("by", "Your partner"))
        }
    }.getOrDefault(emptyList())

    fun save(limits: List<Limit>) {
        val a = JSONArray()
        limits.forEach {
            a.put(JSONObject().put("package", it.pkg).put("label", it.label).put("seconds", it.seconds).put("by", it.by))
        }
        prefs.edit().putString(KeyLimits, a.toString()).apply()
    }

    /** Apps this phone has suspended (device owner only), so they can be switched back on. */
    fun suspended(): Set<String> = HashSet(prefs.getStringSet(KeySuspended, emptySet()) ?: emptySet())

    fun setSuspended(set: Set<String>) {
        prefs.edit().putStringSet(KeySuspended, HashSet(set)).apply()
    }

    /** True while the person is paired and sharing, so the service reports app times. */
    var reporting: Boolean
        get() = prefs.getBoolean(KeyReporting, false)
        set(value) = prefs.edit().putBoolean(KeyReporting, value).apply()

    /** Forgets everything but what is suspended, which the service still has to undo. */
    fun clearSettings() {
        prefs.edit().remove(KeyLimits).remove(KeyReporting).apply()
    }

    private companion object {
        const val KeyLimits = "limits"
        const val KeySuspended = "suspended"
        const val KeyReporting = "reporting"
    }
}
