package com.godbless.lockin.lock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.godbless.lockin.R

/** The little sounds of the game. Loaded once, played from memory so they land on the beat of the animation. */
internal enum class Sfx(val raw: Int, val volume: Float) {
    Place(R.raw.sfx_game_place, 0.9f),
    Pick(R.raw.sfx_game_pick, 0.7f),
    Drop(R.raw.sfx_game_drop, 1f),
    Win(R.raw.sfx_game_win, 1f),
    Lose(R.raw.sfx_game_lose, 0.8f),
}

internal object GameSfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<Sfx, Int>()
    private val ready = HashSet<Int>()

    /** Call when the game opens, so the first sound is not late. */
    fun load(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        for (s in Sfx.entries) ids[s] = p.load(app, s.raw, 1)
        pool = p
    }

    /** Plays [sfx] unless the phone is on silent or vibrate. */
    fun play(context: Context, sfx: Sfx) {
        val am = context.applicationContext.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (pool == null) load(context)
        val id = ids[sfx] ?: return
        if (id !in ready) return
        runCatching { pool?.play(id, sfx.volume, sfx.volume, 1, 0, 1f) }
    }

    fun release() {
        pool?.release()
        pool = null
        ids.clear()
        ready.clear()
    }
}
