package com.godbless.lockin.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** When LockIn calms its motion. Auto follows the phone: battery saver, or animations switched off in Settings. */
enum class MotionMode(val key: String, val label: String) {
    Auto("auto", "Auto"),
    Always("always", "Always"),
    Never("never", "Never"),
}

/**
 * Reduced motion. When it is on, the loops that run all the time stop (the flag, the contour drift, shimmers,
 * pulses), and the slides and springs finish at once instead of travelling. The phone does much less drawing,
 * which is what battery saver is asking for.
 *
 * [reduced] is a Compose state read, so anything that reads it while composing or drawing updates the moment
 * battery saver is switched on or off.
 */
object MotionState {
    private const val PREFS = "motion"

    @set:JvmName("assignMotionMode")
    var mode: MotionMode by mutableStateOf(MotionMode.Auto)
        private set

    /** Battery saver is on right now. */
    var batterySaver: Boolean by mutableStateOf(false)
        private set

    /** Animations are switched off in the phone's developer/accessibility settings. */
    var systemOff: Boolean by mutableStateOf(false)
        private set

    val reduced: Boolean
        get() = when (mode) {
            MotionMode.Always -> true
            MotionMode.Never -> false
            MotionMode.Auto -> batterySaver || systemOff
        }

    private var prefs: android.content.SharedPreferences? = null
    private var registered = false
    private var app: Context? = null

    /** The home-screen widgets run their looping effects only when this allows motion, so they redraw when it changes. */
    private fun redrawWidgets() {
        val a = app ?: return
        runCatching { com.godbless.lockin.widget.LockInWidgets.refreshAll(a) }
    }

    /** Call once at start-up. Keeps listening for battery saver being switched on or off. */
    fun load(context: Context) {
        val app = context.applicationContext
        this.app = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mode = MotionMode.entries.firstOrNull { it.key == p.getString("mode", "auto") } ?: MotionMode.Auto
        refresh(app)
        if (!registered) {
            registered = true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    refresh(c)
                    redrawWidgets()
                }
            }
            runCatching {
                ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
    }

    /** Looks at the phone again (battery saver, animation scale). Cheap; also called when LockIn comes to the front. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        batterySaver = runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)
        systemOff = runCatching {
            Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    fun setMode(m: MotionMode) {
        mode = m
        prefs?.edit()?.putString("mode", m.key)?.apply()
        redrawWidgets()
    }
}

/**
 * [animateFloat] for a loop that runs all the time. With reduced motion on it doesn't run: the value rests at
 * [rest] (the start by default; pass 1f for something that must stay visible, like a text caret).
 */
@Composable
fun InfiniteTransition.calmFloat(
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String = "FloatAnimation",
    rest: Float = initialValue,
): State<Float> =
    if (MotionState.reduced) remember { mutableFloatStateOf(rest) } else animateFloat(initialValue, targetValue, animationSpec, label)
