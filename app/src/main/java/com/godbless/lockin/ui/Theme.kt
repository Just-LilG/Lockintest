package com.godbless.lockin.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.godbless.lockin.R

/** How LockIn looks: follow the phone, or always dark, or always light. */
enum class ThemeMode(val label: String) { System("Auto"), Dark("Dark"), Light("Light") }

/**
 * The accent colours to pick from. Each has a dark-theme and a light-theme shade, tuned so text on the accent
 * and the accent on the background both stay readable (WCAG AA for buttons and large text; most pass AA for
 * body text too). Every theme but Lime also warms or cools the greys a touch towards its colour.
 */
enum class Accent(
    val key: String,
    val label: String,
    internal val dark: Long,
    internal val onDark: Long,
    internal val light: Long,
    internal val onLight: Long,
    /** How much the greys lean towards the accent (0 keeps them neutral). */
    internal val tint: Float,
) {
    Lime("lime", "Lime", 0xFFC6F432, 0xFF0B0B0C, 0xFF3F7A00, 0xFFFFFFFF, 0.5f),
    Burgundy("burgundy", "Burgundy", 0xFFD6506E, 0xFFFFFFFF, 0xFF8C1D3A, 0xFFFFFFFF, 1f),
    Ocean("ocean", "Ocean", 0xFF5CB8FF, 0xFF0B0B0C, 0xFF0A5FB4, 0xFFFFFFFF, 0.8f),
    Amber("amber", "Amber", 0xFFFFB534, 0xFF0B0B0C, 0xFF8F5200, 0xFFFFFFFF, 0.7f),
    Violet("violet", "Violet", 0xFFAE93FF, 0xFF0B0B0C, 0xFF5A3BC2, 0xFFFFFFFF, 0.9f),
    Mint("mint", "Mint", 0xFF46DDB8, 0xFF0B0B0C, 0xFF00705A, 0xFFFFFFFF, 0.7f);

    companion object {
        fun of(key: String?): Accent = entries.firstOrNull { it.key == key } ?: Lime
    }
}

/** Every colour the app uses, for one theme. Built by [Palette.build]; read through [LockInColors]. */
class Palette internal constructor(
    val dark: Boolean,
    val accent: Accent,
    val ink: Color, val slate: Color, val graphite: Color, val edge: Color, val rim: Color,
    val mist: Color, val silver: Color, val body: Color, val frost: Color, val snow: Color,
    val lime: Color, val onLime: Color, val limeWash: Color, val flare: Color,
) {
    companion object {
        fun build(accent: Accent, dark: Boolean): Palette {
            val a = Color(if (dark) accent.dark else accent.light)
            // A neutral grey nudged towards the accent by [k] (times the accent's own tint strength).
            fun n(hex: Long, k: Float) = lerp(Color(hex), a, k * accent.tint)
            return if (dark) Palette(
                dark = true, accent = accent,
                ink = n(0xFF0B0B0C, 0.035f), slate = n(0xFF151517, 0.05f), graphite = n(0xFF242427, 0.06f),
                edge = n(0xFF5F5F66, 0.06f), rim = n(0xFF38383D, 0.06f),
                mist = n(0xFF8B8B93, 0.04f), silver = n(0xFFA6A6AE, 0.03f), body = n(0xFFBDBDC4, 0.02f),
                frost = n(0xFFE2E2E6, 0.015f), snow = Color(0xFFFFFFFF),
                lime = a, onLime = Color(accent.onDark), limeWash = a.copy(alpha = 0.14f), flare = Color(0xFFFF8F85),
            ) else Palette(
                dark = false, accent = accent,
                ink = n(0xFFF4F4F6, 0.035f), slate = n(0xFFFFFFFF, 0.012f), graphite = n(0xFFE5E5EA, 0.05f),
                edge = n(0xFF9C9CA5, 0.05f), rim = n(0xFFD0D0D7, 0.05f),
                mist = n(0xFF6B6B74, 0.04f), silver = n(0xFF55555D, 0.03f), body = n(0xFF3B3B42, 0.02f),
                frost = n(0xFF25252A, 0.015f), snow = Color(0xFF0B0B0C),
                lime = a, onLime = Color(accent.onLight), limeWash = a.copy(alpha = 0.12f), flare = Color(0xFFD23F31),
            )
        }
    }
}

/**
 * LockIn's colours. The names come from the original dark design ([Ink] is the background, [Snow] the
 * strongest text, [Lime] the accent); in the light theme they keep their jobs, so [Snow] is near-black text and
 * [Ink] a soft white. Reading any of them in a composable or a draw block follows theme changes at once.
 *
 * Lime means "go / on / done" and is used sparingly: the main button, focus, selection, progress, success and
 * the summit flag. Everything else is the background, the text colour and the greys between. Red is only for errors.
 */
object LockInColors {
    /** The theme in force. Set by [LockInTheme] from [ThemeState]. */
    var palette: Palette by mutableStateOf(Palette.build(Accent.Lime, true))
        internal set

    /** The background everywhere. */
    val Ink: Color get() = palette.ink
    val Slate: Color get() = palette.slate
    val Graphite: Color get() = palette.graphite
    val Edge: Color get() = palette.edge
    /** Outline colour for buttons and fields at rest. */
    val Rim: Color get() = palette.rim
    val Mist: Color get() = palette.mist
    val Silver: Color get() = palette.silver
    val Body: Color get() = palette.body
    val Frost: Color get() = palette.frost
    /** Headlines and primary text. */
    val Snow: Color get() = palette.snow
    /** The accent. Always pair an accent fill with [OnLime] text. */
    val Lime: Color get() = palette.lime
    /** Text and icons on an accent fill. */
    val OnLime: Color get() = palette.onLime
    /** A faint accent wash for chips and highlights. */
    val LimeWash: Color get() = palette.limeWash
    val Flare: Color get() = palette.flare
    val isDark: Boolean get() = palette.dark

    /** A step between the background (0) and the text colour (1), for quiet fills that follow the theme. */
    fun tone(f: Float): Color = lerp(palette.ink, palette.snow, f)
}

/** The chosen look, saved on the phone. */
object ThemeState {
    private const val PREFS = "appearance"
    @set:JvmName("assignMode")
    var mode: ThemeMode by mutableStateOf(ThemeMode.Dark)
        private set
    @set:JvmName("assignAccent")
    var accent: Accent by mutableStateOf(Accent.Lime)
        private set
    private var prefs: SharedPreferences? = null
    private var app: Context? = null

    /** Call once at start-up, before anything is drawn. */
    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        app = context.applicationContext
        mode = runCatching { ThemeMode.valueOf(p.getString("mode", ThemeMode.Dark.name)!!) }.getOrDefault(ThemeMode.Dark)
        accent = Accent.of(p.getString("accent", Accent.Lime.key))
        // Screens that draw before Compose (the lock cover, the block screen) read this straight away.
        LockInColors.palette = Palette.build(accent, isDark(context))
    }

    fun setMode(m: ThemeMode) { mode = m; prefs?.edit()?.putString("mode", m.name)?.apply(); redrawWidgets() }
    fun setAccent(a: Accent) { accent = a; prefs?.edit()?.putString("accent", a.key)?.apply(); redrawWidgets() }

    /** The home-screen widgets wear the same look. */
    private fun redrawWidgets() {
        val c = app ?: return
        Thread { runCatching { com.godbless.lockin.widget.LockInWidgets.refreshAll(c) } }.start()
    }

    /** Dark or not right now, outside Compose (follows the phone in Auto). */
    fun isDark(context: Context): Boolean = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}

@OptIn(ExperimentalTextApi::class)
object Fonts {
    /** Archivo, heavy and slightly expanded, for headlines. */
    val Display = FontFamily(
        Font(
            R.font.archivo, FontWeight.ExtraBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.width(105f)),
        ),
    )

    /** Archivo at full width, for the wordmark. */
    val Wordmark = FontFamily(
        Font(
            R.font.archivo, FontWeight.ExtraBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.width(122f)),
        ),
    )

    /** Figtree for everything you read. */
    val Body = FontFamily(
        Font(R.font.figtree, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.figtree, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.figtree, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
}

/** The text styles. Their colours follow the theme, so they're rebuilt (once) whenever the theme changes. */
object LockInText {
    private class Styles(p: Palette) {
        val headline = TextStyle(fontFamily = Fonts.Display, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4f).sp, color = p.snow)
        val title = TextStyle(fontFamily = Fonts.Display, fontSize = 28.sp, lineHeight = 34.sp, color = p.snow)
        val lead = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, color = p.body)
        val sub = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = p.body)
        val label = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = p.frost)
        val input = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, color = p.snow)
        val button = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        val error = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = p.flare)
        val small = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, color = p.mist)
    }
    private var built: Pair<Palette, Styles>? = null
    private fun set(): Styles {
        val p = LockInColors.palette
        built?.let { if (it.first === p) return it.second }
        return Styles(p).also { built = p to it }
    }

    val Headline: TextStyle get() = set().headline
    val Title: TextStyle get() = set().title
    val Lead: TextStyle get() = set().lead
    val Sub: TextStyle get() = set().sub
    val Label: TextStyle get() = set().label
    val Input: TextStyle get() = set().input
    val Button: TextStyle get() = set().button
    val Error: TextStyle get() = set().error
    val Small: TextStyle get() = set().small
}

@Composable
fun LockInTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemeState.mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }
    val accent = ThemeState.accent
    val palette = remember(accent, dark) { Palette.build(accent, dark) }
    // Swapped in before anything below reads a colour, so the whole app redraws in the new theme at once.
    if (LockInColors.palette !== palette) LockInColors.palette = palette

    // Status and navigation bar icons: light on the dark theme, dark on the light one.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val scheme = if (dark) darkColorScheme(
        primary = palette.lime, onPrimary = palette.onLime, secondary = palette.snow, onSecondary = palette.ink,
        background = palette.ink, onBackground = palette.snow, surface = palette.slate, onSurface = palette.snow,
    ) else lightColorScheme(
        primary = palette.lime, onPrimary = palette.onLime, secondary = palette.snow, onSecondary = palette.ink,
        background = palette.ink, onBackground = palette.snow, surface = palette.slate, onSurface = palette.snow,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
