package com.godbless.lockin.push

import android.content.Context
import com.godbless.lockin.data.PartnerRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Tells the server which phone belongs to the signed-in person, so their partner's messages can reach it.
 * The server keeps one row per phone token (`register_device` / `unregister_device`).
 */
object PushRegistrar {
    private const val Prefs = "push"
    private const val KeyToken = "token"
    private const val KeyRegistered = "registered"
    private const val KeyAsked = "asked_notifications"
    private const val KeyMuted = "muted"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    /** Firebase hands out a new token now and then. Kept so a sign-out can remove it without asking Firebase. */
    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KeyToken, token).apply()
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
            }
        } catch (e: Exception) {
            // Firebase isn't usable (no Play services, say). Pushes just won't arrive.
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Registers this phone for the signed-in person. Skips the call when nothing changed since the last success. */
    suspend fun sync(context: Context, partners: PartnerRepository) {
        val me = partners.signedInUser() ?: return
        syncMuted(context, partners)
        val token = currentToken() ?: prefs(context).getString(KeyToken, null) ?: return
        saveToken(context, token)
        val stamp = "$me:$token"
        if (prefs(context).getString(KeyRegistered, null) == stamp) return
        partners.registerDevice(token).onSuccess {
            prefs(context).edit().putString(KeyRegistered, stamp).apply()
        }
    }

    /** Tells the server which kinds you switched off. Skips the call when nothing changed since the last success. */
    suspend fun syncMuted(context: Context, partners: PartnerRepository) {
        val me = partners.signedInUser() ?: return
        val off = NotifyPrefs.offKeys(context)
        val stamp = "$me:${off.joinToString(",")}"
        if (prefs(context).getString(KeyMuted, null) == stamp) return
        runCatching {
            partners.call(
                "POST", "/rest/v1/rpc/set_notify_off",
                org.json.JSONObject().put("p_kinds", org.json.JSONArray(off)).toString(),
            )
        }.onSuccess { prefs(context).edit().putString(KeyMuted, stamp).apply() }
    }

    /** Before signing out: this phone stops receiving the signed-out person's pushes. Best effort. */
    suspend fun forget(context: Context, partners: PartnerRepository) {
        val token = prefs(context).getString(KeyToken, null) ?: return
        partners.unregisterDevice(token)
        prefs(context).edit().remove(KeyRegistered).apply()
    }

    /** The notification permission is asked for once, when you first have a partner. */
    fun shouldAsk(context: Context): Boolean = !prefs(context).getBoolean(KeyAsked, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KeyAsked, true).apply()
    }
}
