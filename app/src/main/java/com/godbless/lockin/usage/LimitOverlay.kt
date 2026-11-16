package com.godbless.lockin.usage

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.godbless.lockin.MainActivity
import com.godbless.lockin.push.Push
import com.godbless.lockin.ui.LockInTheme

/**
 * The "limit reached" screen, drawn over everything as a window of its own (like the lock cover), not as an
 * app screen. An app screen can be shrunk into a floating window, or have a floating app opened over it; this
 * can't: it covers the whole screen, floating windows included, until the limited app is gone.
 *
 * While it's up it also takes the audio, so a video playing in the limited app pauses.
 *
 * Main thread only.
 */
internal class LimitOverlay(private val context: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private class Shown(val pkg: String, val label: String, val seconds: Int, val by: String, val hard: Boolean)

    private val shown = mutableStateOf<Shown?>(null)
    /** The limited app is still on screen in a floating or split window, so going home isn't enough. */
    private val floating = mutableStateOf(false)
    /**
     * Seconds left while the cover has stepped aside to a bar at the bottom, so a floating window of the limited
     * app can be reached and closed (0 when the cover is full screen). The grace gets shorter each time.
     */
    private val room = mutableStateOf(0)
    /** LockIn can close the small window itself (the guard is on), so there is no need to step aside for the person to do it. */
    private val canClose = mutableStateOf(false)
    /** The cover has shrunk to just the small window (closing it ourselves didn't work), so it can be closed by hand. */
    private val compact = mutableStateOf(false)
    /** "Ask to lift it" was tapped: the request went to the partner. */
    private val asked = mutableStateOf(false)
    /** Set by the guard service: sends the partner a message asking to lift [label]'s limit. */
    var onAskSend: ((String) -> Unit)? = null
    private var roomUsed = 0
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var focus: AudioFocusRequest? = null

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    val showing: Boolean get() = view != null

    /** LockIn is closing the limited app's small window itself, so the cover goes as soon as the window does. */
    val closing: Boolean get() = floating.value && canClose.value

    /** You tapped a button, so once the app is gone the cover can go too (it stays to be read till then). */
    var answered = false
        private set
    private var answeredAt = 0L

    /** How long ago "Go home" was tapped (0 when it wasn't): an app still on screen after that is in a small window. */
    fun sinceAnswered(): Long = if (answered) android.os.SystemClock.elapsedRealtime() - answeredAt else 0L
    val pkg: String? get() = shown.value?.pkg

    fun show(limit: Limit, hard: Boolean, stillFloating: Boolean, selfClose: Boolean = false) {
        shown.value = Shown(limit.pkg, limit.label, limit.seconds, limit.by, hard)
        floating.value = stillFloating
        canClose.value = selfClose
        if (view != null) {
            applyFocusable()
            return
        }
        answered = false
        asked.value = false
        roomUsed = 0
        room.value = 0
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LimitOverlay)
            setViewTreeSavedStateRegistryOwner(this@LimitOverlay)
            setContent {
                LockInTheme {
                    shown.value?.let {
                        LimitReached(
                            label = it.label,
                            seconds = it.seconds,
                            by = it.by,
                            hard = it.hard,
                            floating = floating.value,
                            room = room.value,
                            asked = asked.value,
                            closing = floating.value && canClose.value,
                            compact = compact.value,
                            onHome = ::goHome,
                            onAsk = ::askPartner,
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (stillFloating && selfClose) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Under the navigation bar too, so no strip of the app behind shows at the bottom.
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        if (runCatching { wm.addView(v, params) }.isSuccess) {
            view = v
            this.params = params
            registry.currentState = Lifecycle.State.RESUMED
            takeAudio()
        } else {
            // Couldn't draw over other apps after all: the old full screen page is better than nothing.
            runCatching { context.startActivity(BlockActivity.intent(context, limit, hard)) }
        }
    }

    fun update(stillFloating: Boolean) {
        floating.value = stillFloating
        applyFocusable()
    }

    /**
     * While a small window is being closed the cover must not take the input focus: the Back key goes to the focused
     * window, and it has to reach the limited app's window, not this cover.
     */
    private fun applyFocusable() {
        val v = view ?: return
        val p = params ?: return
        if (room.value > 0 || compact.value) return
        val want = floating.value && canClose.value
        val has = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        if (want == has) return
        p.flags = if (want) p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, p) }
    }

    /**
     * Closing the small window ourselves isn't working (this phone's window bar isn't one we can press, or the
     * window never takes the Back key): shrink the cover to just that window so it can be closed by hand with
     * everything else on the screen free. The top of the window is left uncovered for its own bar. Null: full
     * screen again.
     */
    fun coverWindow(bounds: android.graphics.Rect?) {
        val v = view ?: return
        val p = params ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (bounds == null) {
            if (!compact.value) return
            compact.value = false
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.x = 0
            p.y = 0
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            runCatching { wm.updateViewLayout(v, p) }
            applyFocusable()
            return
        }
        val bar = (64 * context.resources.displayMetrics.density).toInt()
        val top = if (bounds.height() > bar * 2) bar else 0
        compact.value = true
        p.gravity = Gravity.TOP or Gravity.START
        p.x = bounds.left
        p.y = bounds.top + top
        p.width = bounds.width()
        p.height = bounds.height() - top
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        main.removeCallbacksAndMessages(null)
        room.value = 0
        compact.value = false
        registry.currentState = Lifecycle.State.CREATED
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        giveBackAudio()
    }

    /**
     * Home. If the app was full screen, that closes it and the cover goes. If it's in a floating window and the
     * guard is on, LockIn closes the window itself (GuardService keeps at it, and ends the app's process), so
     * the cover just stays. Without the guard LockIn can't, and the window sits under the cover where it can't be
     * touched: the cover steps aside to a bar at the bottom for a few seconds, long enough to close the window,
     * too short to carry on watching. Still there after that, and the cover comes back.
     */
    private fun goHome() {
        if (!answered) answeredAt = android.os.SystemClock.elapsedRealtime()
        answered = true
        // The guard presses Home itself when it is on (that works even where Android won't open the home screen from a
        // background window, as on some phones); otherwise the home screen is opened directly.
        ScreenKinds.goHome(context)
        // Still here a moment later: the first try didn't take. Try again, both ways.
        main.postDelayed({
            if (view == null) return@postDelayed
            ScreenKinds.goHome(context)
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, 1_200L)
        // Only when LockIn can't close the window itself: then the person has to, and needs a few seconds to reach it.
        if (floating.value && room.value == 0 && !canClose.value) makeRoom()
    }

    private fun makeRoom() {
        val v = view ?: return
        val p = params ?: return
        val seconds = intArrayOf(12, 8, 5)[roomUsed.coerceAtMost(2)]
        roomUsed++
        p.height = WindowManager.LayoutParams.WRAP_CONTENT
        p.gravity = Gravity.BOTTOM or Gravity.START
        // Touches outside the bar go to the windows behind it (the floating app's close button).
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, p) }
        room.value = seconds
        tick()
    }

    private fun tick() {
        main.postDelayed({
            if (view == null) return@postDelayed
            room.value = (room.value - 1).coerceAtLeast(0)
            if (room.value > 0) tick() else coverAgain()
        }, 1000L)
    }

    /** Still on screen after the grace: full screen again. */
    private fun coverAgain() {
        val v = view ?: return
        val p = params ?: return
        p.height = WindowManager.LayoutParams.MATCH_PARENT
        p.gravity = Gravity.TOP or Gravity.START
        p.flags = p.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, p) }
        room.value = 0
    }

    private fun askPartner() {
        if (!answered) answeredAt = android.os.SystemClock.elapsedRealtime()
        answered = true
        if (!asked.value) {
            asked.value = true
            // The request itself goes out right away, so it works even when the cover stays up over LockIn
            // (the limited app is still open in a small window).
            shown.value?.let { onAskSend?.invoke(it.label) }
        }
        // A full screen app is covered by LockIn coming to the front, and the cover goes with it. With the app in a
        // small window the cover has to stay, so there is nothing to open: the screen says it was asked.
        if (floating.value) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Push.ExtraOpen, Push.OpenChat),
            )
        }
    }

    /** Asks for the audio, so the limited app's video or music pauses while the cover is up. */
    private fun takeAudio() {
        if (Build.VERSION.SDK_INT < 26) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { am.requestAudioFocus(req) }.isSuccess) focus = req
    }

    private fun giveBackAudio() {
        val req = focus ?: return
        focus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }
}
