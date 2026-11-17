package com.godbless.lockin.data

import androidx.compose.runtime.mutableStateMapOf
import com.godbless.lockin.home.DiceBear
import org.json.JSONObject

/**
 * How someone's avatar is drawn, as kept on their profile on the server.
 *
 * [style] is null if they never chose one (the app's default illustrated avatar), "" for the classic colour and symbol
 * ([color], [glyph]), or a DiceBear style name drawn from [seed].
 */
data class AvatarSpec(val style: String?, val seed: String, val color: Int, val glyph: String) {
    companion object {
        fun fromJson(j: JSONObject?): AvatarSpec? {
            if (j == null) return null
            return AvatarSpec(
                style = if (j.isNull("style")) null else j.optString("style"),
                seed = j.optString("seed"),
                color = j.optInt("color", -1),
                glyph = j.optString("glyph"),
            )
        }
    }
}

/**
 * The avatars of the people you see (your partner, your crew), by their first name's hash, as the server last told us.
 * The avatar picture reads from here, so a change on someone else's phone shows up here after the next refresh.
 */
object AvatarBook {
    private val specs = mutableStateMapOf<String, AvatarSpec>()

    fun learn(name: String, spec: AvatarSpec?) {
        val key = DiceBear.seedFor(name)
        if (spec == null) specs.remove(key) else if (specs[key] != spec) specs[key] = spec
    }

    operator fun get(name: String): AvatarSpec? = specs[DiceBear.seedFor(name)]
}
