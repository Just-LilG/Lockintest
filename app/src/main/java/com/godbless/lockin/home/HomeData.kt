package com.godbless.lockin.home

import java.util.Locale

/** One finished session in the "Recent climbs" list. */
internal class Climb(val title: String, val whenText: String, val minutes: Int, val summit: Boolean)

/**
 * Placeholder data for what has no backend yet (planned sessions, the daily goal). Session numbers
 * now come from [Stats].
 */
internal class Session(val title: String, val timeText: String, val minutes: Int, val summit: Boolean, val pickups: Int)

/** Sessions that happened on the same day, under one heading. */
internal class DayGroup(val label: String, val sessions: List<Session>)

/** A planned session; [plusDays] is how many days from today. */
internal class Upcoming(val plusDays: Long, val time: String, val title: String)

/** "Today, 8:00 pm", "Tomorrow, 10:00 am" or "Wednesday, 7:30 pm" for a time in epoch milliseconds. */
internal fun planWhen(startsAt: Long): String {
    val at = java.time.Instant.ofEpochMilli(startsAt).atZone(java.time.ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), at.toLocalDate())
    val day = when {
        days == 0L -> if (at.hour >= 17) "Tonight" else "Today"
        days == 1L -> "Tomorrow"
        days in 2..6 -> at.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    return "$day, ${at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))}"
}

/** One line in the partner activity feed. */
internal class Activity(val text: String, val whenText: String, val strong: Boolean)

internal enum class Who { Me, Partner, System }

/** Where one of your messages stands: saved, on its way, or not sent (tap to try again). */
internal enum class Delivery { Sent, Sending, Failed }

/** One line in the chat with your partner: a bubble from either of you, or a small event note. */
internal data class Message(
    val text: String,
    val time: String,
    val who: Who,
    val id: Long = 0L,
    /** The message this one answers. */
    val replyTo: Long? = null,
    /** A voice recording's path, if this is a voice message. */
    val voice: String? = null,
    val voiceMs: Int = 0,
    val wave: String = "",
    /** When it was sent (milliseconds), for the activity list. 0 for local notes. */
    val at: Long = 0L,
    /** For an event note: true when your partner did it. */
    val theirs: Boolean = false,
    /** For an event note: the code ("nudge", "lock_set", ...) and the server's id for it. */
    val code: String = "",
    val noteId: Long = 0L,
    /** For an event note that carries data (a partner's quiz): the whole line. */
    val detail: String = "",
    /** A photo: its path on the server (or "local" while it is still being sent), size, and a tiny base64 preview. */
    val media: String? = null,
    val mediaW: Int = 0,
    val mediaH: Int = 0,
    val thumb: String? = null,
    /** The server has deleted its copy of the photo or voice note. This phone may still have it. */
    val gone: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    /** Who reacted with what: user id to emoji. */
    val reactions: Map<String, String> = emptyMap(),
    /** Your partner has read it (your own messages only). */
    val seen: Boolean = false,
    val delivery: Delivery = Delivery.Sent,
    /** Keeps a bubble's place in the list when "sending" becomes "saved". */
    val key: String = "m$id",
    /** True for a message that arrived while the chat was open, so it slides in. */
    val fresh: Boolean = false,
    /** The server's change stamp, to see whether a line changed. */
    val updatedAt: String = "",
    /** A photo being sent: the file on this phone. */
    val localFile: java.io.File? = null,
)


internal fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

internal fun formatClock(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
