package com.godbless.lockin.home

import android.Manifest
import androidx.compose.animation.core.animateFloat
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =============================================================================================
// Recording
// =============================================================================================

/** A finished recording: the file, its length, and its loudness as 32 digits 0-9 (for the waveform). */
internal class Recording(val file: File, val ms: Int, val wave: String)

/** Records a voice message: AAC in .m4a, mono, 44.1 kHz at 96 kbps (clear speech, about 12 KB a second). At most 2 minutes, so under 1.5 MB. */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Float>()
    /** When it was paused (0 if it is not), and how long it has spent paused in all. Paused time does not count. */
    private var pausedAt = 0L
    private var pausedTotal = 0L

    val elapsedMs: Long
        get() = if (recorder == null) 0L else (if (pausedAt != 0L) pausedAt else System.currentTimeMillis()) - startedAt - pausedTotal

    val isPaused: Boolean get() = pausedAt != 0L

    fun pause(): Boolean {
        val r = recorder ?: return false
        if (pausedAt != 0L) return true
        val ok = runCatching { r.pause() }.isSuccess
        if (ok) pausedAt = System.currentTimeMillis()
        return ok
    }

    fun resume(): Boolean {
        val r = recorder ?: return false
        if (pausedAt == 0L) return true
        val ok = runCatching { r.resume() }.isSuccess
        if (ok) {
            pausedTotal += System.currentTimeMillis() - pausedAt
            pausedAt = 0L
        }
        return ok
    }

    fun start(): Boolean {
        cancel()
        val f = File(context.cacheDir, "rec-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(MaxMs)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            pausedAt = 0L
            pausedTotal = 0L
            levels.clear()
            true
        }.getOrElse {
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** How loud it is right now, 0 to 1. Call every ~80 ms while recording; it also builds the waveform. */
    fun level(): Float {
        val a = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        val v = sqrt((a / 32767f).coerceIn(0f, 1f))
        if (recorder != null && pausedAt == 0L) levels.add(v)
        return v
    }

    /** Stops and returns the recording, or null if it was too short to send (under 0.7 s). */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val f = file
        val ms = elapsedMs.toInt()
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || ms < 700) {
            f?.delete()
            return null
        }
        return Recording(f, ms.coerceAtMost(MaxMs), wave(levels))
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }

    companion object {
        const val MaxMs = 120_000

        /** Squeezes the loudness readings into 32 digits. */
        fun wave(levels: List<Float>, bars: Int = 32): String {
            if (levels.isEmpty()) return "3".repeat(bars)
            val peak = (levels.maxOrNull() ?: 1f).coerceAtLeast(0.05f)
            return (0 until bars).joinToString("") { i ->
                val from = i * levels.size / bars
                val to = ((i + 1) * levels.size / bars).coerceAtLeast(from + 1).coerceAtMost(levels.size)
                val v = levels.subList(from.coerceAtMost(levels.size - 1), to).maxOrNull() ?: 0f
                ((v / peak) * 9f).roundToInt().coerceIn(1, 9).toString()
            }
        }
    }
}

// =============================================================================================
// Playing
// =============================================================================================

/** Plays one voice message at a time, for every chat. Pause keeps your place; the speed button cycles 1x, 1.5x, 2x. */
internal object VoicePlayer {
    /** The path loaded now (playing or paused), or null. */
    var playing by mutableStateOf<String?>(null)
        private set
    /** The loaded one is paused: it keeps its place, and tapping it again carries on. */
    var paused by mutableStateOf(false)
        private set
    /** The path being downloaded, or null. */
    var loading by mutableStateOf<String?>(null)
        private set
    /** How far through the one playing, 0 to 1. */
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 1, 1.5 or 2 times. Kept between messages. */
    var speed by mutableFloatStateOf(1f)
        private set

    private var player: MediaPlayer? = null

    /**
     * Everything here runs in the player's own scope, not the bubble's. A bubble that scrolls out of the list leaves
     * composition and cancels its scope; if the progress timer lived there, the message would keep playing with a
     * frozen bar, and a download would stay stuck on its spinner.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var fetching: Job? = null

    /** Which tap is current. A download whose tap is no longer current (cancelled, or another message chosen) is dropped. */
    private var turn = 0
    private var focus: android.media.AudioFocusRequest? = null
    private var audio: android.media.AudioManager? = null

    fun clearError() { error = null }

    /** Back to idle: nothing loaded, bar at the start, so the next tap plays the message from the beginning. */
    fun stop() {
        ticker?.cancel()
        ticker = null
        val mp = player
        player = null
        playing = null
        paused = false
        progress = 0f
        if (mp != null) {
            mp.setOnCompletionListener(null)
            mp.setOnErrorListener(null)
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        focus?.let { f -> audio?.abandonAudioFocusRequest(f) }
        focus = null
    }

    /** The message reached its end (or the player died): go back to idle. Ignored if another message took over. */
    private fun finished(mp: MediaPlayer) {
        if (player === mp) stop()
    }

    /** Jumps to [fraction] (0 to 1) of the loaded message. */
    fun seekTo(path: String, fraction: Float) {
        val mp = player ?: return
        if (playing != path) return
        val f = fraction.coerceIn(0f, 1f)
        val ok = runCatching { mp.seekTo((mp.duration * f).toInt()) }.isSuccess
        if (ok) progress = f else finished(mp)
    }

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        val mp = player ?: return
        // Setting a speed on a paused player would start it, so only apply it live.
        if (!paused) applySpeed(mp)
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    /**
     * Play [path]. Tapping the one playing pauses it; tapping a paused one carries on; tapping one that is still
     * downloading cancels the download; tapping one that has finished plays it again from the start. [fetch] gets
     * the file (from the phone or the server).
     */
    fun toggle(context: Context, path: String, fetch: suspend (String) -> Result<File>) {
        val app = context.applicationContext
        if (loading == path) {
            turn++
            fetching?.cancel()
            loading = null
            return
        }
        if (playing == path) {
            val mp = player
            if (mp == null) { stop(); return }
            val ok = runCatching {
                if (paused) {
                    requestFocus(app)
                    mp.start()
                    applySpeed(mp)
                    paused = false
                } else {
                    mp.pause()
                    paused = true
                }
            }.isSuccess
            // A player that no longer answers is dropped, so the button goes back to "play" instead of sticking.
            if (!ok) stop()
            return
        }
        stop()
        error = null
        val mine = ++turn
        loading = path
        fetching?.cancel()
        fetching = scope.launch {
            val file = fetch(path).getOrElse {
                if (mine == turn) {
                    loading = null
                    error = if ((it as? com.godbless.lockin.data.ApiException)?.status == 404) "That voice message is no longer on the server."
                    else it.message ?: "Couldn't load that voice message."
                }
                return@launch
            }
            if (mine != turn) return@launch
            loading = null
            requestFocus(app)
            val mp = MediaPlayer()
            val ready = runCatching {
                mp.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.setOnCompletionListener { finished(mp) }
                mp.setOnErrorListener { _, _, _ -> finished(mp); true }
                mp.start()
            }.isSuccess
            if (!ready) {
                runCatching { mp.release() }
                focus?.let { f -> audio?.abandonAudioFocusRequest(f) }
                focus = null
                error = "Couldn't play that voice message."
                return@launch
            }
            player = mp
            playing = path
            paused = false
            progress = 0f
            if (speed != 1f) applySpeed(mp)
            track(mp)
        }
    }

    /** Moves the bar while it plays, and is the safety net if the end-of-message callback never arrives. */
    private fun track(mp: MediaPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            var ticks = 0
            while (isActive && player === mp) {
                if (!paused) {
                    val pos = runCatching { mp.currentPosition }.getOrNull()
                    val dur = runCatching { mp.duration }.getOrNull()
                    if (pos != null && dur != null && dur > 0) progress = (pos / dur.toFloat()).coerceIn(0f, 1f)
                    if (++ticks > 8 && !runCatching { mp.isPlaying }.getOrDefault(false)) {
                        finished(mp)
                        break
                    }
                }
                delay(50)
            }
        }
    }

    /** Asks other audio (music, a video) to wait while a voice message plays, and lets them carry on afterwards. */
    private fun requestFocus(context: Context) {
        val am = audio ?: context.applicationContext.getSystemService(android.media.AudioManager::class.java).also { audio = it }
        if (focus != null) return
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }
}

/** "0:07", "1:32". */
internal fun clipLength(ms: Int): String {
    val s = (ms + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// =============================================================================================
// The voice bubble
// =============================================================================================

/**
 * A voice message: a play button, its waveform (filling in as it plays; tap or drag it to jump), and its length (counting
 * down while it plays). While it is loaded a speed button appears. [mine] colours it like your own bubbles.
 */
@Composable
internal fun VoiceBubble(path: String, ms: Int, wave: String, mine: Boolean, fetch: suspend (String) -> Result<File>) {
    val context = LocalContext.current
    val active = VoicePlayer.playing == path
    val paused = active && VoicePlayer.paused
    val playing = active && !paused
    val loading = VoicePlayer.loading == path
    val fg = if (mine) LockInColors.OnLime else LockInColors.Snow
    val dim = if (mine) LockInColors.OnLime.copy(alpha = 0.35f) else LockInColors.Edge
    val progress by animateFloatAsState(if (active) VoicePlayer.progress else 0f, spring(dampingRatio = 1f, stiffness = 900f), label = "voice")
    val press by animateFloatAsState(if (playing) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "play")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(230.dp)) {
        Box(
            Modifier
                .size(38.dp)
                .graphicsLayer { scaleX = press; scaleY = press }
                .clip(CircleShape)
                .background(if (mine) LockInColors.OnLime else LockInColors.Lime)
                .clickable(role = Role.Button) { VoicePlayer.toggle(context, path, fetch) }
                .semantics {
                    contentDescription = when {
                        loading -> "Loading voice message. Tap to cancel"
                        playing -> "Pause voice message"
                        paused -> "Resume voice message"
                        else -> "Play voice message, ${clipLength(ms)}"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val iconColor = if (mine) LockInColors.Lime else LockInColors.OnLime
            if (loading) {
                // Still downloading: a turning ring, never the pause bars, until it really plays.
                RingSpinner(iconColor, size = 16.dp, stroke = 2.dp)
            } else Canvas(Modifier.size(14.dp)) {
                if (playing) {
                    val w = size.width / 3.2f
                    drawRoundRect(iconColor, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(w / 3f))
                    drawRoundRect(iconColor, Offset(size.width * 0.88f - w, 0f), Size(w, size.height), CornerRadius(w / 3f))
                } else {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.2f, size.height); close()
                    }
                    drawPath(p, iconColor)
                }
            }
        }
        // The waveform: bars from the recording, lit up to where it's playing.
        val bars = wave.ifBlank { "3".repeat(32) }.map { (it - '0').coerceIn(1, 9) }
        var barsWidth by remember { mutableIntStateOf(1) }
        Canvas(
            Modifier
                .weight(1f)
                .height(30.dp)
                .padding(horizontal = 10.dp)
                .onSizeChanged { barsWidth = it.width.coerceAtLeast(1) }
                .pointerInput(active) {
                    if (active) detectTapGestures { VoicePlayer.seekTo(path, it.x / barsWidth) }
                }
                .pointerInput(active) {
                    if (active) detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        VoicePlayer.seekTo(path, change.position.x / barsWidth)
                    }
                },
        ) {
            val n = bars.size
            val gap = size.width / n
            val bw = (gap * 0.55f).coerceAtLeast(1.5f)
            bars.forEachIndexed { i, b ->
                val h = size.height * (0.18f + 0.82f * b / 9f)
                val lit = (i + 0.5f) / n <= progress
                drawRoundRect(
                    if (lit || (!active && mine)) fg else dim,
                    Offset(i * gap + (gap - bw) / 2f, (size.height - h) / 2f),
                    Size(bw, h),
                    CornerRadius(bw / 2f),
                )
            }
        }
        if (active) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .clip(CircleShape)
                    .background(fg.copy(alpha = 0.14f))
                    .clickable(role = Role.Button) { VoicePlayer.cycleSpeed() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .semantics { contentDescription = "Playback speed" },
            ) {
                Text(
                    if (VoicePlayer.speed == 1f) "1x" else if (VoicePlayer.speed == 1.5f) "1.5x" else "2x",
                    style = LockInText.Small.copy(color = fg, fontSize = 11.sp),
                )
            }
        }
        Text(
            clipLength(if (active) (ms * (1f - progress)).toInt() else ms),
            style = LockInText.Small.copy(color = fg, fontSize = 12.sp),
        )
    }
}

// =============================================================================================
// Replies
// =============================================================================================

/** What a quoted message says, short: its text, or "Voice message 0:07". */
internal fun quoteText(body: String, voiceMs: Int, voice: Boolean): String =
    if (voice) "Voice message ${clipLength(voiceMs)}" else body.replace('\n', ' ').take(120)

/** The quote at the top of a reply: who said what, with a lime edge. Tap to jump to it. */
@Composable
internal fun ReplyQuote(name: String, text: String, mine: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(if (mine) LockInColors.OnLime.copy(alpha = 0.12f) else LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(if (mine) LockInColors.OnLime else LockInColors.Lime))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(name, style = LockInText.Small.copy(color = if (mine) LockInColors.OnLime else LockInColors.Lime, fontSize = 12.sp), maxLines = 1)
            Text(
                text,
                style = LockInText.Small.copy(color = if (mine) LockInColors.OnLime.copy(alpha = 0.8f) else LockInColors.Silver, fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Above the message box while you're replying: who and what, and × to stop replying. */
@Composable
internal fun ReplyBar(name: String, text: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LockInColors.Slate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(48.dp).background(LockInColors.Lime))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Replying to $name", style = LockInText.Small.copy(color = LockInColors.Lime, fontSize = 12.sp), maxLines = 1)
            Text(text, style = LockInText.Small.copy(color = LockInColors.Silver), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Stop replying" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Close, LockInColors.Silver, boxSize = 12.dp, strokeWidth = 2.2f)
        }
    }
}

/**
 * Swipe a message to the right (or press and hold it) to reply. It follows your finger a little, a reply
 * arrow appears, and past the line it snaps back with a tick of haptics and starts the reply.
 */
internal fun Modifier.swipeToReply(enabled: Boolean, onReply: () -> Unit, onLongPress: () -> Unit = onReply): Modifier = composed {
    if (!enabled) return@composed this
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    this
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (armed) onReply()
                    armed = false
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
                },
                onDragCancel = {
                    armed = false
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
                },
            ) { change, drag ->
                val next = (offset.value + drag * 0.6f).coerceIn(0f, trigger * 1.3f)
                if (next != offset.value) change.consume()
                scope.launch { offset.snapTo(next) }
                val now = next >= trigger
                if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                armed = now
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            })
        }
        .graphicsLayer { translationX = offset.value }
}

// =============================================================================================
// Hold to record
// =============================================================================================

/** What the mic is doing, for the strip that replaces the message box while recording. */
internal class MicState {
    var recording by mutableStateOf(false)
    /** Recording hands-free: you let go, and the strip gives you a bin, pause and send. */
    var locked by mutableStateOf(false)
    var paused by mutableStateOf(false)
    /** Stopped and waiting: you can listen to it, then send it or throw it away. */
    var preview by mutableStateOf<Recording?>(null)
    var elapsedMs by mutableIntStateOf(0)
    var level by mutableFloatStateOf(0f)
    /** How far you've slid left to cancel, 0 to 1. */
    var slide by mutableFloatStateOf(0f)
    /** How far you've slid up to lock, 0 to 1. */
    var lockPull by mutableFloatStateOf(0f)

    // Set by the mic button, which owns the recorder, so the strip's buttons can drive it.
    internal var send: () -> Unit = {}
    internal var cancel: () -> Unit = {}
    internal var togglePause: () -> Unit = {}
    internal var review: () -> Unit = {}
}

/**
 * The mic button.
 *  - Hold it to record; let go to send; slide left to cancel; slide up to lock (then you can let go).
 *  - A quick tap starts a locked recording straight away.
 *  - Locked: the strip has a bin, a pause button and the timer; this button turns into Send.
 * The first time, it asks for the microphone. It grows and pulses with your voice while you hold it.
 */
@Composable
internal fun MicButton(state: MicState, onRecorded: (Recording) -> Unit, onTooShort: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val recorder = remember { VoiceRecorder(context) }
    val cancelAt = with(LocalDensity.current) { 110.dp.toPx() }
    val lockAt = with(LocalDensity.current) { 90.dp.toPx() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val sent by rememberUpdatedState(onRecorded)
    val tooShort by rememberUpdatedState(onTooShort)

    fun reset() {
        state.recording = false
        state.locked = false
        state.paused = false
        state.preview = null
        state.slide = 0f
        state.lockPull = 0f
        state.level = 0f
        state.elapsedMs = 0
    }
    state.send = {
        val ready = state.preview
        if (ready != null) {
            // Listened to it: send exactly that.
            VoicePlayer.stop()
            reset()
            sent(ready)
        } else if (state.recording) {
            reset()
            val rec = recorder.stop()
            if (rec != null) sent(rec) else tooShort()
        }
    }
    state.cancel = {
        if (state.recording) {
            state.preview?.let { VoicePlayer.stop(); it.file.delete() }
            reset()
            recorder.cancel()
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    // Stop recording but keep it, so it can be heard before it goes.
    state.review = {
        if (state.recording && state.locked && state.preview == null) {
            val rec = recorder.stop()
            if (rec != null) {
                state.preview = rec
                state.paused = false
                state.level = 0f
                state.elapsedMs = rec.ms
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else {
                reset()
                tooShort()
            }
        }
    }
    state.togglePause = {
        if (state.recording) {
            if (state.paused) { if (recorder.resume()) state.paused = false }
            else if (recorder.pause()) state.paused = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Never leave the microphone running if the chat closes.
    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            state.preview?.let { VoicePlayer.stop(); it.file.delete() }
            reset()
        }
    }
    // A phone doesn't let a background app listen. If you leave while recording, it pauses, so nothing is lost silently.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && state.recording && state.locked && !state.paused && state.preview == null) {
                if (recorder.pause()) state.paused = true
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // While recording: read the level and the time, and stop at the 2 minute mark.
    LaunchedEffect(state.recording) {
        while (state.recording) {
            if (state.preview == null) {
                state.level = if (state.paused) 0f else recorder.level()
                state.elapsedMs = recorder.elapsedMs.toInt()
                if (state.elapsedMs >= VoiceRecorder.MaxMs) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.send()
                }
            }
            delay(80)
        }
    }
    val holding = state.recording && !state.locked
    val grow by animateFloatAsState(if (holding) 1.35f + state.level * 0.25f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "mic")

    Box(
        modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
                translationX = -state.slide * cancelAt * 0.5f
                translationY = -state.lockPull * lockAt * 0.5f
            }
            .clip(CircleShape)
            .background(if (state.recording) LockInColors.Lime else LockInColors.Graphite)
            .pointerInput(granted, state.locked) {
                if (state.locked) {
                    // Hands-free: this button is Send.
                    detectTapGestures { state.send() }
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!granted) {
                        ask.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (!recorder.start()) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.recording = true
                    state.paused = false
                    state.slide = 0f
                    state.lockPull = 0f
                    var cancelled = false
                    var lock = false
                    var lastUp = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        lastUp = change.uptimeMillis
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        // Follow whichever way you are going, so a slight wobble in the other direction does nothing.
                        state.slide = if (-dx > -dy) (-dx / cancelAt).coerceIn(0f, 1f) else 0f
                        state.lockPull = if (-dy > -dx) (-dy / lockAt).coerceIn(0f, 1f) else 0f
                        if (state.slide >= 1f) { cancelled = true; break }
                        if (state.lockPull >= 1f) { lock = true; break }
                        if (!change.pressed) break
                        change.consume()
                    }
                    if (!state.recording) return@awaitEachGesture // hit the 2 minute mark and already sent
                    if (lock || (!cancelled && lastUp - down.uptimeMillis < 280L)) {
                        // Slid up, or just tapped: keep recording with your hands free.
                        state.locked = true
                        state.slide = 0f
                        state.lockPull = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        return@awaitEachGesture
                    }
                    if (cancelled) state.cancel() else state.send()
                }
            }
            .semantics {
                contentDescription = if (state.locked) "Send voice message" else "Hold to record a voice message, or tap to start"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (state.locked) {
            GlyphIcon(Glyph.ArrowUp, LockInColors.OnLime, boxSize = 24.dp, strokeWidth = 2.2f)
        } else Canvas(Modifier.size(22.dp)) {
            val c = if (state.recording) LockInColors.OnLime else LockInColors.Snow
            val w = size.width
            val h = size.height
            drawRoundRect(c, Offset(w * 0.34f, 0f), Size(w * 0.32f, h * 0.62f), CornerRadius(w * 0.16f))
            drawArc(c, 0f, 180f, false, Offset(w * 0.18f, h * 0.22f), Size(w * 0.64f, h * 0.58f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f))
            drawLine(c, Offset(w / 2f, h * 0.8f), Offset(w / 2f, h), w * 0.09f)
        }
    }
}

/** While you hold the mic: a little padlock above it. Slide up into it to keep recording with your hands free. */
@Composable
internal fun BoxScope.LockHint(state: MicState) {
    AnimatedVisibility(
        visible = state.recording && !state.locked,
        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-158).dp),
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { it },
        exit = slideOutVertically(spring(stiffness = 600f)) { it },
    ) {
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .graphicsLayer { translationY = -state.lockPull * 40.dp.toPx() }
                .width(42.dp)
                .height(84.dp)
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.5.dp, LockInColors.Graphite, shape),
            contentAlignment = Alignment.TopCenter,
        ) {
            GlyphIcon(Glyph.Lock, LockInColors.Silver, Modifier.padding(top = 12.dp), boxSize = 20.dp, strokeWidth = 2f)
            Canvas(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(14.dp, 8.dp)) {
                val c = LockInColors.Mist
                val w = 2.dp.toPx()
                drawLine(c, Offset(0f, size.height), Offset(size.width / 2f, 0f), w, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(size.width / 2f, 0f), Offset(size.width, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/**
 * Replaces the message box while recording.
 * Held: a red dot, the time, live level bars, "slide to cancel".
 * Locked: a bin to throw it away, the time (it goes red in the last 10 seconds), the bars, and pause / resume.
 */
@Composable
internal fun RecordingStrip(state: MicState, modifier: Modifier = Modifier) {
    val history = remember { ArrayDeque<Float>() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.level, state.recording) {
        if (!state.recording) { history.clear(); return@LaunchedEffect }
        if (!state.paused) history.addLast(state.level)
        while (history.size > 28) history.removeFirst()
        tick++
    }
    val ending = state.elapsedMs >= VoiceRecorder.MaxMs - 10_000
    val ready = state.preview
    if (ready != null) {
        PreviewStrip(state, ready, modifier)
        return
    }
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(LockInColors.Slate)
            .padding(start = if (state.locked) 4.dp else 16.dp, end = if (state.locked) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.locked) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { state.cancel() }.semantics { contentDescription = "Throw away this recording" },
                contentAlignment = Alignment.Center,
            ) { Bin(LockInColors.Flare) }
        }
        val blink = !state.paused && (state.elapsedMs / 500) % 2 == 0
        Box(
            Modifier
                .padding(start = if (state.locked) 4.dp else 0.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (state.paused) LockInColors.Mist else if (blink) LockInColors.Flare else LockInColors.Flare.copy(alpha = 0.3f)),
        )
        Text(
            clipLength(state.elapsedMs),
            style = LockInText.Label.copy(color = if (ending) LockInColors.Flare else LockInColors.Snow, fontSize = 15.sp),
            modifier = Modifier.padding(start = 10.dp),
        )
        val bars = history.toList()
        Canvas(Modifier.weight(1f).height(26.dp).padding(horizontal = 12.dp)) {
            @Suppress("UNUSED_EXPRESSION") tick
            val n = 28
            val gap = size.width / n
            val bw = gap * 0.5f
            for (i in 0 until n) {
                val v = bars.getOrNull(i - (n - bars.size)) ?: 0f
                val h = size.height * (0.12f + 0.88f * v)
                drawRoundRect(if (state.paused) LockInColors.Mist else LockInColors.Lime, Offset(i * gap, (size.height - h) / 2f), Size(bw, h), CornerRadius(bw / 2f))
            }
        }
        if (state.locked) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { state.togglePause() }
                    .semantics { contentDescription = if (state.paused) "Carry on recording" else "Pause recording" },
                contentAlignment = Alignment.Center,
            ) {
                if (state.paused) Box(Modifier.size(16.dp).clip(CircleShape).background(LockInColors.Flare))
                else Canvas(Modifier.size(16.dp)) {
                    val w = size.width / 3.2f
                    drawRoundRect(LockInColors.Snow, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(w / 3f))
                    drawRoundRect(LockInColors.Snow, Offset(size.width * 0.88f - w, 0f), Size(w, size.height), CornerRadius(w / 3f))
                }
            }
            // Stop here and listen to it before sending.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { state.review() }
                    .semantics { contentDescription = "Stop and listen before sending" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(LockInColors.Snow))
            }
        } else {
            Text(
                if (state.slide > 0.6f) "Let go to cancel" else "‹ Slide to cancel",
                style = LockInText.Small.copy(color = if (state.slide > 0.6f) LockInColors.Flare else LockInColors.Mist),
                modifier = Modifier.graphicsLayer { translationX = -state.slide * 40f },
            )
        }
    }
}

/** A finished recording waiting to go: a bin, play / pause, the bars (tap or drag to jump), and its length. */
@Composable
private fun PreviewStrip(state: MicState, rec: Recording, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = rec.file.absolutePath
    val active = VoicePlayer.playing == key
    val playing = active && !VoicePlayer.paused
    val progress = if (active) VoicePlayer.progress else 0f
    val bars = rec.wave.ifBlank { "3".repeat(32) }.map { (it - '0').coerceIn(1, 9) }
    var barsWidth by remember { mutableIntStateOf(1) }
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(LockInColors.Slate)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { state.cancel() }.semantics { contentDescription = "Throw away this recording" },
            contentAlignment = Alignment.Center,
        ) { Bin(LockInColors.Flare) }
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(LockInColors.Lime)
                .clickable(role = Role.Button) { VoicePlayer.toggle(context, key) { Result.success(rec.file) } }
                .semantics { contentDescription = if (playing) "Pause" else "Listen to the recording" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                if (playing) {
                    val w = size.width / 3.2f
                    drawRoundRect(LockInColors.OnLime, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(w / 3f))
                    drawRoundRect(LockInColors.OnLime, Offset(size.width * 0.88f - w, 0f), Size(w, size.height), CornerRadius(w / 3f))
                } else {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2f); lineTo(size.width * 0.2f, size.height); close()
                    }
                    drawPath(p, LockInColors.OnLime)
                }
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(30.dp)
                .padding(horizontal = 10.dp)
                .onSizeChanged { barsWidth = it.width.coerceAtLeast(1) }
                .pointerInput(active) {
                    if (active) detectTapGestures { VoicePlayer.seekTo(key, it.x / barsWidth) }
                }
                .pointerInput(active) {
                    if (active) detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        VoicePlayer.seekTo(key, change.position.x / barsWidth)
                    }
                },
        ) {
            val n = bars.size
            val gap = size.width / n
            val bw = (gap * 0.55f).coerceAtLeast(1.5f)
            bars.forEachIndexed { i, b ->
                val h = size.height * (0.18f + 0.82f * b / 9f)
                val lit = (i + 0.5f) / n <= progress
                drawRoundRect(
                    if (lit) LockInColors.Lime else LockInColors.Edge,
                    Offset(i * gap, (size.height - h) / 2f),
                    Size(bw, h),
                    CornerRadius(bw / 2f),
                )
            }
        }
        Text(
            clipLength(if (active) (rec.ms * (1f - progress)).toInt() else rec.ms),
            style = LockInText.Small.copy(color = LockInColors.Snow, fontSize = 13.sp),
            modifier = Modifier.padding(end = 10.dp),
        )
    }
}

/** A little bin, drawn. */
@Composable
private fun Bin(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = 1.9.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, Offset(size.width * 0.12f, size.height * 0.24f), Offset(size.width * 0.88f, size.height * 0.24f), w, cap)
        drawLine(color, Offset(size.width * 0.38f, size.height * 0.24f), Offset(size.width * 0.38f, size.height * 0.1f), w, cap)
        drawLine(color, Offset(size.width * 0.62f, size.height * 0.24f), Offset(size.width * 0.62f, size.height * 0.1f), w, cap)
        drawLine(color, Offset(size.width * 0.38f, size.height * 0.1f), Offset(size.width * 0.62f, size.height * 0.1f), w, cap)
        drawRoundRect(color, Offset(size.width * 0.22f, size.height * 0.32f), Size(size.width * 0.56f, size.height * 0.6f), CornerRadius(w * 1.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(color, Offset(size.width * 0.42f, size.height * 0.46f), Offset(size.width * 0.42f, size.height * 0.78f), w, cap)
        drawLine(color, Offset(size.width * 0.58f, size.height * 0.46f), Offset(size.width * 0.58f, size.height * 0.78f), w, cap)
    }
}
