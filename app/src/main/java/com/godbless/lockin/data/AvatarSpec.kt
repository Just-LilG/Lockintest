package com.godbless.lockin.data

import androidx.compose.runtime.mutableStateMapOf
import com.godbless.lockin.home.DiceBear
import org.json.JSONObject

/**
 * How someone's avatar is drawn, as kept on their profile on the server.
 *
 * [style] is null if they never chose one (the app's default illustrated avatar), "" for the classic colour and symbol
 * ([color], [glyph]), or a DiceBear style name drawn from [seed]. [look] narrows an illustrated style: "" for any, "m" or "f".
 */
data class AvatarSpec(val style: String?, val seed: String, val color: Int, val glyph: String, val look: String = "") {
    companion object {
        fun fromJson(j: JSONObject?): AvatarSpec? {
            if (j == null) return null
            return AvatarSpec(
                style = if (j.isNull("style")) null else j.optString("style"),
                seed = j.optString("seed"),
                color = j.optInt("color", -1),
                glyph = j.optString("glyph"),
                look = j.optString("look"),
            )
        }
    }
}

/**
 * The avatars of the people you see (your partner, your crew), by their first name's hash, as the server last told us.
 * The avatar picture reads from here, so a change on someone else's phone shows up here after the next refresh.
 */
object AvatarBook {
    // A person is found by their full name. If someone is only known by a first name, that first name works only when
    // exactly one person we know has it, so two people called "Lil" can never be mixed up.
    private val specs = mutableStateMapOf<String, AvatarSpec>()
    private val firstNameOwner = HashMap<String, String>()

    private fun norm(s: String) = s.trim().lowercase()

    fun learn(name: String, spec: AvatarSpec?) {
        val full = norm(name)
        if (full.isEmpty()) return
        if (spec == null) { specs.remove(full); return }
        if (specs[full] != spec) specs[full] = spec
        val first = full.substringBefore(' ')
        if (first != full) {
            val owner = firstNameOwner[first]
            when {
                owner == null || owner == full -> { firstNameOwner[first] = full; if (specs["~$first"] != spec) specs["~$first"] = spec }
                else -> { firstNameOwner[first] = ""; specs.remove("~$first") }
            }
        }
    }

    operator fun get(name: String): AvatarSpec? {
        val full = norm(name)
        return specs[full] ?: specs["~" + full.substringBefore(' ')]
    }
}
