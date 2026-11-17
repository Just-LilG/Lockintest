package com.godbless.lockin.data

import org.json.JSONObject

/** Who you are on the server: your name, your sign-in email and when your account began (milliseconds). */
class Profile(val name: String, val email: String, val since: Long, val sound: String = "message", val avatar: AvatarSpec? = null)

/** The You tab's profile calls, over the same plain HTTPS as everything else. */
class ProfileRepository(internal val partners: PartnerRepository) {
    suspend fun me(): Result<Profile> = partners.attempt {
        val j = JSONObject(partners.call("POST", "/rest/v1/rpc/profile_me", "{}").body)
        Profile(
            j.optString("name"), j.optString("email"), j.optLong("created_at") * 1000L, j.optString("sound", "message"),
            AvatarSpec.fromJson(j.optJSONObject("avatar")),
        )
    }

    /** Picks the sound your partner hears when you message them. */
    suspend fun setSound(key: String): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/set_message_sound", JSONObject().put("p_sound", key).toString())
        Unit
    }

    /** Saves your avatar on your profile, so your partner and your crew see the one you picked. */
    suspend fun setAvatar(spec: AvatarSpec): Result<Unit> = partners.attempt {
        val body = JSONObject()
            .put("p_style", spec.style ?: JSONObject.NULL)
            .put("p_seed", spec.seed)
            .put("p_color", spec.color)
            .put("p_glyph", spec.glyph)
        partners.call("POST", "/rest/v1/rpc/set_avatar", body.toString())
        Unit
    }

    /** Deletes your account and everything of yours on the server (the `delete_my_account` function). */
    suspend fun deleteAccount(): Result<Unit> = partners.attempt {
        partners.call("POST", "/rest/v1/rpc/delete_my_account", "{}")
        Unit
    }

    /** Renames you. Returns the name as the server saved it (trimmed). */
    suspend fun rename(name: String): Result<String> = partners.attempt {
        val body = JSONObject().put("p_name", name).toString()
        JSONObject(partners.call("POST", "/rest/v1/rpc/set_display_name", body).body).optString("name", name.trim())
    }
}
