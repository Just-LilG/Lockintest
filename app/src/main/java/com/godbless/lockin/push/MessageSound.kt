package com.godbless.lockin.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.godbless.lockin.R

/**
 * LockIn's own message sounds. You pick one for your account, and it's what your partner's phone plays when
 * you message them (the server sends your choice with each message).
 *
 * Android fixes a sound per notification channel, so each sound has its own Messages channel, made the first
 * time a message with that sound arrives. Muting or changing one in the phone's settings only touches that one.
 */
enum class MessageSound(val key: String, val title: String, val raw: Int) {
    Message("message", "LockIn", R.raw.sound_message),
    Fah("fah", "Fah", R.raw.sound_fah),
    OhNo("oh_no", "Oh no oh no", R.raw.sound_oh_no),
    Pikachu("pikachu", "Pika", R.raw.sound_pikachu),
    RainHush("rain_hush", "Rain hush", R.raw.sound_rain_hush),
    Sneeze("sneeze", "Sneeze", R.raw.sound_sneeze),
    Whistle("whistle", "Whistle", R.raw.sound_whistle);

    /**
     * The sound by its file name, not its number. Resource numbers can change from one build to the next, and a
     * channel keeps the address it was made with forever, so a numbered address could end up pointing at nothing
     * and the channel would go quiet.
     */
    fun uri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/sound_$key")

    /**
     * The Messages channel that plays this sound. The server names the same id when it sends a message.
     * "m2" channels play the sound from a real file in the phone's Notifications folder (see [soundUri]).
     */
    val channelId: String get() = "lockin_m2_$key"

    /** The 0.6.3 channel the server still names until push v13 is deployed. Plays the sound from inside the app. */
    val legacyChannelId: String get() = "lockin_msg_$key"

    /**
     * The address to give a channel. Many phones (Transsion, Xiaomi, Oppo, often on Android 12 and 13) play
     * nothing, or their default tone, for a sound stored inside the app. They always play a real audio file
     * in the phone's Notifications folder, the same place their own sound picker reads, so from Android 10
     * the sound is copied there once (as "LockIn Fah" and so on) and the channel points at that file.
     * Falls back to the in-app address if the copy can't be made.
     */
    fun soundUri(context: Context): Uri =
        (if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { mediaFile(context) }.getOrNull() else null) ?: uri(context)

    @androidx.annotation.RequiresApi(29)
    private fun mediaFile(context: Context): Uri? = notificationFile(context, title, raw)

    companion object {
        fun of(key: String?): MessageSound = entries.firstOrNull { it.key == key } ?: Message

        private var preview: MediaPlayer? = null

        /** Plays a sound once, for the picker. A new preview stops the last one. */
        fun play(context: Context, sound: MessageSound) {
            preview?.runCatching { stop(); release() }
            preview = runCatching {
                MediaPlayer.create(context.applicationContext, sound.raw)?.apply {
                    setOnCompletionListener { it.release(); if (preview === it) preview = null }
                    start()
                }
            }.getOrNull()
        }


        /**
         * Plays a message's sound while LockIn is open (no notification then, so nothing else would make a sound).
         * It plays as a notification sound, so the phone's silent mode, vibrate mode and notification volume apply.
         */
        fun alert(context: Context, sound: MessageSound) = playAlert(context, sound.raw)

        /**
         * Makes every Messages channel up front. Android shows a message by itself while LockIn sleeps, on the
         * channel the server names, so all of them must already exist. Also removes the old ones from 0.6.1/0.6.2.
         */
        fun ensureAll(context: Context, groupId: String) {
            val m = context.getSystemService(NotificationManager::class.java)
            entries.forEach { ensureChannel(context, it, groupId) }
            m.notificationChannels
                .filter { it.id.startsWith("partner_messages") }
                .forEach { runCatching { m.deleteNotificationChannel(it.id) } }
        }

        /** Makes the Messages channel for [sound] if it doesn't exist yet. */
        fun ensureChannel(context: Context, sound: MessageSound, groupId: String) {
            val m = context.getSystemService(NotificationManager::class.java)
            // The channel the server names today (0.6.3), kept until push v13 names the new one.
            make(m, sound.legacyChannelId, "Messages · ${sound.title} (classic)", groupId) { sound.uri(context) }
            make(m, sound.channelId, "Messages · ${sound.title}", groupId) { sound.soundUri(context) }
        }

        private fun make(m: NotificationManager, id: String, name: String, groupId: String, uri: () -> Uri) {
            if (m.getNotificationChannel(id) != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            m.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Your partner's messages, with the sound they picked"
                    group = groupId
                    setSound(uri(), attrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 120)
                    enableLights(true)
                    lightColor = 0xFFC6F432.toInt()
                },
            )
        }
    }
}

@androidx.annotation.RequiresApi(29)
internal fun notificationFile(context: Context, title: String, raw: Int): Uri? {
    val resolver = context.contentResolver
    val collection = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val name = "LockIn $title.ogg"
    val folder = android.os.Environment.DIRECTORY_NOTIFICATIONS + "/LockIn/"
    // Already copied (by this install).
    resolver.query(
        collection,
        arrayOf(android.provider.MediaStore.Audio.Media._ID),
        "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${android.provider.MediaStore.Audio.Media.RELATIVE_PATH}=?",
        arrayOf(name, folder),
        null,
    )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0)) }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Audio.Media.TITLE, "LockIn $title")
        put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
        put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, folder)
        put(android.provider.MediaStore.Audio.Media.IS_NOTIFICATION, 1)
        put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: return null
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { out -> context.resources.openRawResource(raw).use { it.copyTo(out) } } != null
    }.getOrDefault(false)
    if (!ok) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    values.clear()
    values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

private var alertPlayer: MediaPlayer? = null

/**
 * Plays a short sound while LockIn is open (no notification then, so nothing else would make a sound). It plays as a
 * notification sound, so the phone's silent mode, vibrate mode and notification volume apply.
 */
internal fun playAlert(context: Context, raw: Int) {
    val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val app = context.applicationContext
    val am = app.getSystemService(android.media.AudioManager::class.java)
    if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
    alertPlayer?.runCatching { stop(); release() }
    alertPlayer = runCatching {
        MediaPlayer.create(app, raw, attrs, am.generateAudioSessionId())?.apply {
            setOnCompletionListener { it.release(); if (alertPlayer === it) alertPlayer = null }
            start()
        }
    }.getOrNull()
}

/**
 * Sounds that belong to an event, not to a person's pick: the nudge, and a new message while you're in the chat.
 * The nudge also has its own notification channel (below) for when LockIn is closed.
 */
internal enum class AppSound(val key: String, val title: String, val raw: Int) {
    Nudge("nudge", "Nudge", R.raw.sound_nudge),
    NewMessage("new_message", "New message", R.raw.sound_new_message),
    GameRequest("game_request", "Game request", R.raw.sound_game_request);

    fun play(context: Context) = playAlert(context, raw)

    fun uri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/sound_$key")

    /** The address for a channel: the same real file in the Notifications folder that the message sounds use. */
    fun soundUri(context: Context): Uri =
        (if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { notificationFile(context, title, raw) }.getOrNull() else null) ?: uri(context)
}
