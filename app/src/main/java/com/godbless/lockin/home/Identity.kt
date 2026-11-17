@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The colours an avatar can take. The first (index 0) is the app's own lime, whatever the theme. */
internal val AvatarColors: List<Color> = listOf(
    Color(0xFFC8F53B), Color(0xFF5AA9FF), Color(0xFF9B7BFF), Color(0xFFFF7AB6),
    Color(0xFFFF7A59), Color(0xFFFFB534), Color(0xFF4FD6A5), Color(0xFFE6E8EC),
)

/** Symbols you can use instead of your initial. */
internal val AvatarGlyphs = listOf("\uD83C\uDFD4\uFE0F", "\uD83E\uDDD7", "\uD83D\uDD25", "\uD83D\uDCDA", "\uD83C\uDFA7", "\uD83C\uDF19", "\u2615", "\uD83E\uDD8A", "\uD83D\uDC3C", "\uD83C\uDF3F", "\u26A1", "\uD83C\uDFAF")

/**
 * How you look to yourself on this phone (avatar colour and symbol) and what you call your partner here. Both are
 * kept on this phone only: your partner doesn't see your avatar choice, and they don't see the nickname you gave them.
 */
internal object Identity {
    private var prefs: android.content.SharedPreferences? = null

    var avatarColor by mutableIntStateOf(-1)
        private set
    var avatarGlyph by mutableStateOf("")
        private set
    /** A DiceBear style for your illustrated avatar, or "" for the classic colour-and-symbol one. */
    var avatarStyle by mutableStateOf(DiceBear.DefaultStyle)
        private set
    /** Narrows an illustrated style: "" for any, "m" or "f". */
    var avatarLook by mutableStateOf("")
        private set
    /** The shuffle code for your illustrated avatar; blank means the one your account gives ([defaultSeed]). */
    var avatarSeed by mutableStateOf("")
        private set
    /** The code the server derives from your account, so you look the same on every phone and nobody shares it. */
    var defaultSeed by mutableStateOf("")
        private set
    private val nicks = mutableStateMapOf<String, String>()

    /** Set by the Home screen: sends a change to the server so your partner and crew see it. */
    var syncUp: ((com.godbless.lockin.data.AvatarSpec) -> Unit)? = null

    fun spec() = com.godbless.lockin.data.AvatarSpec(avatarStyle, avatarSeed, avatarColor, avatarGlyph, avatarLook)

    val tint: Color? get() = AvatarColors.getOrNull(avatarColor)
    val glyph: String? get() = avatarGlyph.ifBlank { null }

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs = p
        avatarColor = p.getInt("avatar_color", -1)
        avatarGlyph = p.getString("avatar_glyph", "").orEmpty()
        // Someone who already chose a colour or symbol keeps it; everyone else gets the illustrated avatar.
        avatarStyle = p.getString("avatar_style", null) ?: if (avatarColor >= 0 || avatarGlyph.isNotBlank()) "" else DiceBear.DefaultStyle
        avatarSeed = p.getString("avatar_seed", "").orEmpty()
        defaultSeed = p.getString("avatar_default_seed", "").orEmpty()
        avatarLook = p.getString("avatar_look", "").orEmpty()
        p.all.forEach { (k, v) -> if (k.startsWith("nick_") && v is String && v.isNotBlank()) nicks[k.removePrefix("nick_")] = v }
    }

    fun setAvatar(color: Int, glyph: String, style: String = avatarStyle, seed: String = avatarSeed, look: String = avatarLook) {
        // Save the code in use, not "blank", so your partner's phone draws exactly what you see.
        val code = seed.ifBlank { defaultSeed }
        avatarColor = color
        avatarGlyph = glyph
        avatarStyle = style
        avatarSeed = code
        avatarLook = look
        // "dirty" stays set until the server has it, so a later refresh can't overwrite a change that didn't go through.
        prefs?.edit()?.putInt("avatar_color", color)?.putString("avatar_glyph", glyph)
            ?.putString("avatar_style", style)?.putString("avatar_seed", code)?.putString("avatar_look", look)?.putBoolean("avatar_dirty", true)?.apply()
        syncUp?.invoke(spec())
    }

    /** Someone signed out: the next person on this phone starts from their own avatar, not this one's. */
    fun reset() {
        avatarColor = -1
        avatarGlyph = ""
        avatarStyle = DiceBear.DefaultStyle
        avatarSeed = ""
        defaultSeed = ""
        avatarLook = ""
        prefs?.edit()?.remove("avatar_look")?.remove("avatar_default_seed")?.remove("avatar_color")?.remove("avatar_glyph")?.remove("avatar_style")?.remove("avatar_seed")
            ?.remove("avatar_dirty")?.apply()
    }

    /** The server has the avatar this phone sent. */
    fun markPushed() { prefs?.edit()?.putBoolean("avatar_dirty", false)?.apply() }

    /**
     * Your avatar as the server has it. If you picked one (on this phone or another), it follows your account. If
     * this phone has a pick the server hasn't got yet (made offline, or before avatars were saved), it is sent up.
     */
    fun adopt(server: com.godbless.lockin.data.AvatarSpec?) {
        if (server == null) return
        if (server.seed.isNotBlank() && server.seed != defaultSeed && server.style == null) {
            defaultSeed = server.seed
            prefs?.edit()?.putString("avatar_default_seed", defaultSeed)?.apply()
        }
        val dirty = prefs?.getBoolean("avatar_dirty", false) == true
        val localChoice = prefs?.contains("avatar_style") == true || avatarColor >= 0 || avatarGlyph.isNotBlank()
        val serverStyle = server.style
        if (dirty || (serverStyle == null && localChoice)) {
            syncUp?.invoke(spec())
        } else if (serverStyle != null) {
            avatarColor = server.color
            avatarGlyph = server.glyph
            avatarStyle = serverStyle
            avatarSeed = server.seed
            avatarLook = server.look
            prefs?.edit()?.putInt("avatar_color", avatarColor)?.putString("avatar_glyph", avatarGlyph)
                ?.putString("avatar_style", avatarStyle)?.putString("avatar_seed", avatarSeed)
                ?.putString("avatar_look", avatarLook)?.apply()
        }
    }

    /** What you call your partner, or null to use the name they chose. */
    fun nickname(partnerId: String?): String? = partnerId?.let { nicks[it] }?.takeIf { it.isNotBlank() }

    fun setNickname(partnerId: String, nick: String) {
        val clean = nick.trim().take(24)
        if (clean.isEmpty()) nicks.remove(partnerId) else nicks[partnerId] = clean
        prefs?.edit()?.putString("nick_$partnerId", clean)?.apply()
    }
}
