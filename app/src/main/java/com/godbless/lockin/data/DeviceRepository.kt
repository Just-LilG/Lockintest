package com.godbless.lockin.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** This phone: a lasting id of its own (not a hardware id), and the name people know it by. */
object ThisDevice {
    private const val Prefs = "device"
    private const val Key = "id"

    fun id(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        prefs.getString(Key, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(Key, fresh).apply()
        return fresh
    }

    /** The name set in Settings ("Lil's Galaxy"), or the maker and model. */
    fun name(context: Context): String {
        val set = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return set?.takeIf { it.isNotBlank() } ?: model()
    }

    fun model(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    fun os(): String = "Android ${Build.VERSION.RELEASE}"
}

/** One phone with LockIn. During a lock: [beatAgo] seconds since it last held the lock (-1 never), [escapes] on it. */
class Device(
    val id: String,
    val name: String,
    val model: String,
    val os: String,
    val app: String,
    val seenAgo: Int,
    val locked: Boolean = false,
    val beatAgo: Int = -1,
    val escapes: Int = 0,
)

/** Your devices and your partner's, over the same plain HTTPS as everything else. */
class DeviceRepository(private val partners: PartnerRepository) {
    suspend fun checkIn(context: Context, appVersion: String): Result<Unit> = partners.attempt {
        val body = JSONObject()
            .put("p_device", ThisDevice.id(context))
            .put("p_name", ThisDevice.name(context))
            .put("p_model", ThisDevice.model())
            .put("p_os", ThisDevice.os())
            .put("p_app", appVersion)
        partners.call("POST", "/rest/v1/rpc/device_checkin", body.toString())
        Unit
    }

    /** A quiet "this phone is still here", so the partner's "seen ..." stays fresh while LockIn runs in the background. */
    suspend fun ping(context: Context): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/device_ping", JSONObject().put("p_device", ThisDevice.id(context)).toString())
        Unit
    }

    suspend fun forget(deviceId: String): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/device_forget", JSONObject().put("p_device", deviceId).toString())
        Unit
    }

    suspend fun partner(): Result<List<Device>> = partners.attempt { parse(partners.call("POST", "/rest/v1/rpc/partner_devices", "{}").body) }

    suspend fun mine(): Result<List<Device>> = partners.attempt { parse(partners.call("POST", "/rest/v1/rpc/my_devices", "{}").body) }

    private fun parse(text: String): List<Device> {
        val rows = JSONArray(text)
        return List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            Device(
                id = o.getString("id"),
                name = o.optString("name", "Android phone"),
                model = o.optString("model", ""),
                os = o.optString("os", ""),
                app = o.optString("app", ""),
                seenAgo = o.optInt("seen_ago", 0),
                locked = o.optBoolean("locked", false),
                beatAgo = o.optInt("beat_ago", -1),
                escapes = o.optInt("escapes", 0),
            )
        }
    }
}
