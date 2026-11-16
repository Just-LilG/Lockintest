package com.godbless.lockin.widget

import android.content.Context
import com.godbless.lockin.ui.Accent
import com.godbless.lockin.ui.ThemeState

/**
 * The widgets' colours, as plain ARGB ints. They follow the look picked in LockIn (dark, light or the phone's,
 * and the accent), with the greys nudged towards the accent the same way the app does it.
 */
internal class WidgetLook(
    val dark: Boolean,
    val accent: Int,
    val onAccent: Int,
    /** The card. */
    val surface: Int,
    /** The hairline round the card. */
    val rim: Int,
    /** Chips and the empty part of rings and bars. */
    val raised: Int,
    val track: Int,
    /** Past days' bars. */
    val bar: Int,
    val snow: Int,
    val frost: Int,
    val body: Int,
    val mist: Int,
    /** The mountain: top of its fill, and the snow cap. */
    val hill: Int,
    val snowcap: Int,
    /** How strong the accent glow behind the main figure is (0..255). */
    val glowAlpha: Int,
) {
    companion object {
        fun of(context: Context): WidgetLook {
            val accent = runCatching { ThemeState.accent }.getOrDefault(Accent.Lime)
            val dark = runCatching { ThemeState.isDark(context) }.getOrDefault(true)
            return build(accent, dark)
        }

        fun build(accent: Accent, dark: Boolean): WidgetLook {
            val a = (if (dark) accent.dark else accent.light).toInt()
            fun n(hex: Long, k: Float) = mix(hex.toInt(), a, k * accent.tint)
            return if (dark) WidgetLook(
                dark = true, accent = a, onAccent = accent.onDark.toInt(),
                surface = n(0xFF141416, 0.05f), rim = 0x17FFFFFF, raised = n(0xFF242427, 0.06f),
                track = n(0xFF242427, 0.06f), bar = n(0xFF3A3A40, 0.06f),
                snow = 0xFFFFFFFF.toInt(), frost = n(0xFFE2E2E6, 0.015f), body = n(0xFFBDBDC4, 0.02f),
                mist = n(0xFF8B8B93, 0.04f), hill = n(0xFF3A3A41, 0.08f), snowcap = 0xFFE9E9EE.toInt(),
                glowAlpha = 46,
            ) else WidgetLook(
                dark = false, accent = a, onAccent = accent.onLight.toInt(),
                surface = 0xFFFFFFFF.toInt(), rim = 0x14000000, raised = n(0xFFEEEEF2, 0.05f),
                track = n(0xFFECECF0, 0.05f), bar = n(0xFFCFCFD6, 0.05f),
                snow = 0xFF0B0B0C.toInt(), frost = n(0xFF25252A, 0.015f), body = n(0xFF3B3B42, 0.02f),
                mist = n(0xFF6B6B74, 0.04f), hill = n(0xFFD9D9E1, 0.08f), snowcap = 0xFFFFFFFF.toInt(),
                glowAlpha = 30,
            )
        }

        /** [from] moved a fraction [t] of the way to [to], channel by channel. */
        fun mix(from: Int, to: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (from ushr shift) and 0xFF
                val y = (to ushr shift) and 0xFF
                return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        /** [color] with its alpha set to [alpha] (0..255). */
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }
}
