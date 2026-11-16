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
    private val nicks = mutableStateMapOf<String, String>()

    val tint: Color? get() = AvatarColors.getOrNull(avatarColor)
    val glyph: String? get() = avatarGlyph.ifBlank { null }

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs = p
        avatarColor = p.getInt("avatar_color", -1)
        avatarGlyph = p.getString("avatar_glyph", "").orEmpty()
        p.all.forEach { (k, v) -> if (k.startsWith("nick_") && v is String && v.isNotBlank()) nicks[k.removePrefix("nick_")] = v }
    }

    fun setAvatar(color: Int, glyph: String) {
        avatarColor = color
        avatarGlyph = glyph
        prefs?.edit()?.putInt("avatar_color", color)?.putString("avatar_glyph", glyph)?.apply()
    }

    /** What you call your partner, or null to use the name they chose. */
    fun nickname(partnerId: String?): String? = partnerId?.let { nicks[it] }?.takeIf { it.isNotBlank() }

    fun setNickname(partnerId: String, nick: String) {
        val clean = nick.trim().take(24)
        if (clean.isEmpty()) nicks.remove(partnerId) else nicks[partnerId] = clean
        prefs?.edit()?.putString("nick_$partnerId", clean)?.apply()
    }
}
