package com.godbless.lockin.ludo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.godbless.lockin.R

/** The sounds of Ludo (made by tools/ludo_sfx.py). Played from memory so they land on the beat of the animation. */
internal enum class LudoSound(val raw: Int, val volume: Float) {
    Shake(R.raw.ludo_shake, 0.75f),
    Land(R.raw.ludo_land, 0.9f),
    Hop(R.raw.ludo_hop, 0.55f),
    Out(R.raw.ludo_out, 0.8f),
    Capture(R.raw.ludo_capture, 1f),
    Safe(R.raw.ludo_safe, 0.5f),
    Home(R.raw.ludo_home, 0.85f),
    Six(R.raw.ludo_six, 0.7f),
    Turn(R.raw.ludo_turn, 0.65f),
    Tick(R.raw.ludo_tick, 0.45f),
    Win(R.raw.ludo_win, 0.9f),
    Lose(R.raw.ludo_lose, 0.7f),
    Pop(R.raw.ludo_pop, 0.6f),
    Start(R.raw.ludo_start, 0.7f),
}

internal object LudoSfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<LudoSound, Int>()
    private val ready = HashSet<Int>()
    private var shaking = 0

    /** Muted from the game's own speaker button (kept for the session). */
    var muted = false

    fun load(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        for (s in LudoSound.entries) ids[s] = p.load(context.applicationContext, s.raw, 1)
        pool = p
    }

    /** Plays [s] at [rate] (0.5..2, higher is higher-pitched), unless muted or the phone is on silent or vibrate. */
    fun play(context: Context, s: LudoSound, rate: Float = 1f, volume: Float = 1f): Int {
        if (muted) return 0
        val am = context.applicationContext.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0
        if (pool == null) load(context)
        val id = ids[s] ?: return 0
        if (id !in ready) return 0
        val v = (s.volume * volume).coerceIn(0f, 1f)
        return runCatching { pool?.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f)) ?: 0 }.getOrDefault(0)
    }

    /** The dice rattling, looped until [stopShake]. */
    fun startShake(context: Context) {
        stopShake()
        if (muted) return
        val am = context.applicationContext.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (pool == null) load(context)
        val id = ids[LudoSound.Shake] ?: return
        if (id !in ready) return
        shaking = runCatching { pool?.play(id, 0.6f, 0.6f, 1, -1, 1f) ?: 0 }.getOrDefault(0)
    }

    fun stopShake() {
        if (shaking != 0) runCatching { pool?.stop(shaking) }
        shaking = 0
    }

    fun release() {
        stopShake()
        pool?.release()
        pool = null
        ids.clear()
        ready.clear()
    }
}
