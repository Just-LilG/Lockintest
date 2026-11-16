package com.godbless.lockin.push

import com.godbless.lockin.ui.firstGlyph
import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notifications from your partner.
 *
 * - Each kind has its own channel (Messages, Locks, Nudges, Plans, App limits, grouped under "Partner"), so the
 *   phone's settings can treat them differently, and its own slot, so a chat message never replaces a lock alert.
 * - Messages are a conversation: your partner's avatar, the last few lines, and a reply box right in the
 *   notification (see [NotifyActionReceiver]). They also show in Android's Conversations section.
 * - An unlock request has Unlock and Not yet buttons; a warning about the lock opens the lock page.
 * - Tapping opens the right place: the chat, the lock page, or the Partner tab.
 */
object Push {
    const val ExtraOpen = "lockin.open"
    const val OpenChat = "chat"
    const val OpenLock = "lock"
    const val OpenPartner = "partner"
    const val OpenCrew = "crew"
    const val OpenCrewChat = "crew_chat"
    /** Home (a "reached the summit" notification opens it). */
    const val OpenParty = "party"
    /** From a widget: Start (lock in on your own, or choose to lock your partner). */
    const val OpenStart = "start"
    /** From a widget: the full history of climbs. */
    const val OpenClimbs = "climbs"

    // Channels. The old single "partner" channel is removed on first use.
    private const val Group = "partner_group"
    const val ChMessages = "partner_messages"
    const val ChLocks = "partner_locks"
    // "partner_nudges2" is the nudge channel with LockIn's own Nudge sound. A channel's sound can't change once it
    // exists, so it is a new one; the first ("partner_nudges", default tone) stays for a server that still names it.
    const val ChNudges = "partner_nudges2"
    const val ChPlans = "partner_plans"
    // Game invites and moves ring with the game-request sound (a channel's sound is fixed once made).
    const val ChGames = "partner_games"
    const val ChLimits = "partner_limits"
    private const val OldChannel = "partner"

    // One slot per kind.
    const val IdMessages = 2001
    const val IdLocks = 2002
    const val IdNudges = 2003
    const val IdPlans = 2004
    const val IdLimits = 2005
    const val IdGames = 2008
    const val IdPartnership = 2006

    internal const val ShortcutId = "partner_chat"
    internal const val ReplyKey = "reply"

    private val started = AtomicInteger(0)

    /** True while any LockIn screen is visible. */
    val inForeground: Boolean get() = started.get() > 0

    /** Called once from the Application. Counts visible screens and clears the chat notification when LockIn opens. */
    fun watch(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
                clear(activity)
            }
            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { if (it > 0) it - 1 else 0 }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Creates the channels once, each with its own feel. */
    fun ensureChannels(context: Context) {
        val m = manager(context)
        ensureNudgeChannel(context, m)
        ensureGamesChannel(context, m)
        if (m.getNotificationChannel(ChLocks) != null && m.getNotificationChannel(MessageSound.Whistle.channelId) != null) return
        m.createNotificationChannelGroup(NotificationChannelGroup(Group, "Partner"))
        MessageSound.ensureAll(context, Group)
        if (m.getNotificationChannel(ChLocks) != null) return
        fun channel(id: String, name: String, about: String, importance: Int, buzz: LongArray?) =
            NotificationChannel(id, name, importance).apply {
                description = about
                group = Group
                if (buzz != null) {
                    enableVibration(true)
                    vibrationPattern = buzz
                }
                enableLights(true)
                lightColor = 0xFFC6F432.toInt()
            }
        m.createNotificationChannels(
            listOf(
                channel(ChLocks, "Locks", "Locks on you, unlock requests, and warnings about a lock you set", NotificationManager.IMPORTANCE_HIGH, longArrayOf(0, 220, 120, 220)),
                channel(ChPlans, "Plans", "Sessions and repeating locks your partner plans", NotificationManager.IMPORTANCE_DEFAULT, null),
                channel(ChLimits, "App limits", "Limits your partner sets on your apps", NotificationManager.IMPORTANCE_DEFAULT, null),
            ),
        )
        runCatching { m.deleteNotificationChannel(OldChannel) }
        runCatching { m.deleteNotificationChannel(ChMessages) }
    }

    /** The Nudges channel, with the Nudge sound. Made on its own so phones that already have the others get it too. */
    private fun ensureNudgeChannel(context: Context, m: NotificationManager) {
        if (m.getNotificationChannel(ChNudges) != null) return
        m.createNotificationChannelGroup(NotificationChannelGroup(Group, "Partner"))
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        m.createNotificationChannel(
            NotificationChannel(ChNudges, "Nudges", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your partner nudges you to lock in"
                group = Group
                setSound(AppSound.Nudge.soundUri(context), attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 60, 80, 60, 80, 60)
                enableLights(true)
                lightColor = 0xFFC6F432.toInt()
            },
        )
    }

    /** The Games channel, with the game-request sound. Made on its own so phones that already have the others get it too. */
    private fun ensureGamesChannel(context: Context, m: NotificationManager) {
        if (m.getNotificationChannel(ChGames) != null) return
        m.createNotificationChannelGroup(NotificationChannelGroup(Group, "Partner"))
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        m.createNotificationChannel(
            NotificationChannel(ChGames, "Games", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your partner sends you a game or plays their turn"
                group = Group
                setSound(AppSound.GameRequest.soundUri(context), attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 70, 70, 70)
                enableLights(true)
                lightColor = 0xFFC6F432.toInt()
            },
        )
    }

    /** True while the chat with your partner is on screen (a new message then plays its in-chat sound). */
    @Volatile
    var chatOpen: Boolean = false

    internal fun channelFor(kind: NotifyKind, context: Context? = null): String = when (kind) {
        // Messages ring with the sound your partner picked (the last one heard), each its own channel.
        NotifyKind.Messages -> context?.let { lastSound(it).channelId } ?: MessageSound.Message.channelId
        NotifyKind.Locks -> ChLocks
        NotifyKind.Nudges -> ChNudges
        NotifyKind.Plans -> ChPlans
        NotifyKind.Limits -> ChLimits
        NotifyKind.Games -> ChGames
    }

    /** Opens LockIn at [open] (chat, lock, partner) or at Home when null. Each target gets its own request code. */
    internal fun openIntent(context: Context, open: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (open != null) putExtra(ExtraOpen, open)
        }
        val code = when (open) { OpenChat -> 11; OpenLock -> 12; OpenPartner -> 13; OpenCrew -> 14; OpenCrewChat -> 15; OpenParty -> 16; else -> 10 }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, what: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, NotifyActionReceiver::class.java).setAction(what),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ----- The partner as a person, with an avatar drawn like the app's -----

    /** A round avatar: the first letter of [name] on lime, like the app's own. */
    internal fun avatar(name: String, sizePx: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC6F432.toInt() }
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bg)
        val letter = name.firstGlyph().ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0B0B0C.toInt()
            textSize = sizePx * 0.46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = sizePx / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(letter, sizePx / 2f, y, text)
        return bmp
    }

    private fun partnerPerson(name: String): Person =
        Person.Builder().setName(name).setKey("partner").setIcon(IconCompat.createWithBitmap(avatar(name))).build()

    private fun mePerson(): Person = Person.Builder().setName("You").setKey("me").build()

    /** A long-lived shortcut to the chat, which is what lets Android show messages as a conversation. */
    private fun publishShortcut(context: Context, name: String) {
        runCatching {
            val open = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ExtraOpen, OpenChat)
            val shortcut = ShortcutInfoCompat.Builder(context, ShortcutId)
                .setShortLabel(name)
                .setLongLabel("Chat with $name")
                .setIcon(IconCompat.createWithAdaptiveBitmap(avatar(name, 288)))
                .setPerson(partnerPerson(name))
                .setLongLived(true)
                .setIntent(open)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    // ----- Message history for the conversation notification -----

    private fun historyPrefs(context: Context) = context.getSharedPreferences("notif_chat", Context.MODE_PRIVATE)

    private fun history(context: Context): List<JSONObject> = runCatching {
        val arr = JSONArray(historyPrefs(context).getString("lines", "[]"))
        List(arr.length()) { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())

    internal fun addHistory(context: Context, mine: Boolean, text: String) {
        val lines = (history(context) + JSONObject().put("me", mine).put("text", text).put("at", System.currentTimeMillis())).takeLast(6)
        historyPrefs(context).edit().putString("lines", JSONArray(lines).toString()).apply()
    }

    private fun clearHistory(context: Context) {
        historyPrefs(context).edit().remove("lines").apply()
    }

    // ----- Showing -----

    /**
     * Shows a push from your partner. [event] is the event code ("" for a chat message), [partnership] the pairing
     * id (for replies), [name] your partner's name.
     */
    fun show(context: Context, event: String, name: String, text: String, partnership: String, sound: String = "message") {
        if (!canShow(context)) return
        ensureChannels(context)
        val kind = NotifyPrefs.kindOf(event)
        if (kind == NotifyKind.Messages && event.isEmpty()) {
            if (partnership.isNotBlank()) historyPrefs(context).edit().putString("pid", partnership).apply()
            historyPrefs(context).edit().putString("name", name).putString("sound", MessageSound.of(sound).key).apply()
            addHistory(context, mine = false, text = text)
            showConversation(context, name, silent = false)
            return
        }
        val (id, open) = when {
            event == "lock_tamper" -> IdLocks to OpenLock
            event == "protect_tamper" -> IdLocks to OpenChat
            event == "asked_unlock" -> IdLocks to OpenLock
            event == "protect_asked" -> IdLocks to OpenChat
            kind == NotifyKind.Locks -> IdLocks to null
            kind == NotifyKind.Nudges -> IdNudges to null
            kind == NotifyKind.Games -> IdGames to OpenChat
            kind == NotifyKind.Plans -> IdPlans to OpenPartner
            kind == NotifyKind.Limits -> IdLimits to OpenPartner
            event.startsWith("unpair") -> IdPartnership to OpenPartner
            event == "summit" -> IdPartnership to OpenParty
            else -> IdPartnership to OpenChat
        }
        val builder = NotificationCompat.Builder(context, channelFor(kind, context))
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setLargeIcon(avatar(name))
            .setContentTitle(name)
            .setContentText(capitalized(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(capitalized(text)))
            .setCategory(if (kind == NotifyKind.Plans) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (kind == NotifyKind.Plans || kind == NotifyKind.Limits) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openIntent(context, open))
        when (event) {
            // They asked you to let them out: answer from right here.
            "asked_unlock" -> {
                builder.setContentTitle("$name asked to be unlocked")
                    .setContentText("Let them out now, or say not yet.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("Let them out now, or say not yet. You can also open the lock to see their reason."))
                    .addAction(0, "Unlock", action(context, NotifyActionReceiver.Unlock, 21))
                    .addAction(0, "Not yet", action(context, NotifyActionReceiver.NotYet, 22))
            }
            // They want to switch something off on their phone: answer from right here.
            "protect_asked" -> {
                builder.setContentTitle("$name needs your OK")
                    .setContentText(capitalized(text))
                    .addAction(0, "Allow", action(context, NotifyActionReceiver.ProtectAllow, 23))
                    .addAction(0, "Not yet", action(context, NotifyActionReceiver.ProtectNo, 24))
            }
            "lock_tamper" -> builder.setContentTitle("Heads up: $name's lock").addAction(0, "Open lock", openIntent(context, OpenLock))
            "protect_tamper" -> builder.setContentTitle("Heads up: $name's protection").addAction(0, "Open chat", openIntent(context, OpenChat))
            "summit" -> builder.setContentTitle("$name reached the summit \uD83C\uDFD4\uFE0F")
                .setContentText(capitalized(text))
            "nudge" -> builder.setContentTitle("$name nudged you").setContentText("Time to lock in.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Time to lock in. Start a session and $name will see it."))
                .addAction(0, "Lock in", openIntent(context, null))
        }
        NotificationManagerCompatSafe.notify(context, id, builder.build())
    }

    const val IdTest = 2007

    /**
     * Anything on this phone that would keep a message silent, in plain words, or null when it should ring.
     * Checks LockIn's notifications, the Messages channels, Do not disturb, silent mode and the volume.
     */
    fun soundProblem(context: Context): SoundProblem? {
        val m = manager(context)
        if (!canShow(context) || !m.areNotificationsEnabled()) {
            return SoundProblem("Notifications are off for LockIn", "Turn them on so messages can reach you.", null)
        }
        ensureChannels(context)
        val group = runCatching { m.getNotificationChannelGroup(Group) }.getOrNull()
        if (Build.VERSION.SDK_INT >= 28 && group?.isBlocked == true) {
            return SoundProblem("Partner notifications are blocked", "Turn the Partner group back on in your phone's settings.", null)
        }
        for (s in MessageSound.entries) {
            val ch = m.getNotificationChannel(s.channelId) ?: m.getNotificationChannel(s.legacyChannelId) ?: continue
            val issue = when {
                ch.importance == NotificationManager.IMPORTANCE_NONE -> "is blocked"
                ch.importance < NotificationManager.IMPORTANCE_DEFAULT -> "is set to silent"
                ch.sound == null -> "has its sound switched off"
                else -> null
            }
            if (issue != null) {
                return SoundProblem("\"Messages · ${s.title}\" $issue", "Your partner's messages may come in without a sound. Set it back to make sound in your phone's settings.", s.channelId)
            }
        }
        if (m.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) {
            return SoundProblem("Do not disturb is on", "Messages arrive but stay quiet until it's off.", null)
        }
        val am = context.getSystemService(android.media.AudioManager::class.java)
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
            return SoundProblem("Your phone is on silent or vibrate", "Turn the sound back on to hear messages.", null)
        }
        if (am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0) {
            return SoundProblem("Notification volume is all the way down", "Turn it up to hear messages.", null)
        }
        return null
    }

    class SoundProblem(val title: String, val detail: String, val channel: String?)

    /**
     * Sends a test message notification in 5 seconds, so you can lock the screen first and hear it as your
     * partner's messages would sound. Uses the sound of [sound].
     */
    fun sendTest(context: Context, partner: String, sound: MessageSound) {
        val app = context.applicationContext
        ensureChannels(app)
        MessageSound.ensureChannel(app, sound, Group)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val n = NotificationCompat.Builder(app, sound.channelId)
                .setSmallIcon(R.drawable.ic_stat_lockin)
                .setColor(0xFFC6F432.toInt())
                .setLargeIcon(avatar(partner))
                .setContentTitle(partner)
                .setContentText("Test: this is how my messages sound. (${sound.title})")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent(app, null))
                .build()
            NotificationManagerCompatSafe.notify(app, IdTest, n)
        }, 5000)
    }

    const val IdCrew = 2008

    /** Crew news while LockIn is open (a session started): opens the Crew tab. */
    fun showCrew(context: Context, title: String, text: String) {
        if (!canShow(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, ChLocks)
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, OpenCrew))
            .build()
        NotificationManagerCompatSafe.notify(context, IdCrew, n)
    }

    /** Notes the sound a message came with, so the settings link and the test use your partner's sound. */
    fun rememberSound(context: Context, key: String?) {
        if (key.isNullOrBlank()) return
        historyPrefs(context).edit().putString("sound", MessageSound.of(key).key).apply()
    }

    /** The sound your partner picked, as last heard on this phone. */
    internal fun lastSound(context: Context): MessageSound = MessageSound.of(historyPrefs(context).getString("sound", null))

    private fun showConversation(context: Context, name: String, silent: Boolean) {
        publishShortcut(context, name)
        val sound = lastSound(context)
        MessageSound.ensureChannel(context, sound, Group)
        val partner = partnerPerson(name)
        val style = NotificationCompat.MessagingStyle(mePerson())
        for (line in history(context)) {
            style.addMessage(line.optString("text"), line.optLong("at"), if (line.optBoolean("me")) null else partner)
        }
        val reply = NotificationCompat.Action.Builder(0, "Reply", action(context, NotifyActionReceiver.Reply, 23))
            .addRemoteInput(RemoteInput.Builder(ReplyKey).setLabel("Message $name").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(0, "Mark as read", action(context, NotifyActionReceiver.Read, 24))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val n = NotificationCompat.Builder(context, sound.channelId)
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setStyle(style)
            .setShortcutId(ShortcutId)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setContentIntent(openIntent(context, OpenChat))
            .addAction(reply)
            .addAction(read)
            .build()
        NotificationManagerCompatSafe.notify(context, IdMessages, n)
    }

    /** After you replied from the notification: show your line in the conversation, quietly. */
    internal fun showReplied(context: Context, text: String) {
        addHistory(context, mine = true, text = text)
        val name = historyPrefs(context).getString("name", null) ?: "Your partner"
        // Your own reply shouldn't ring.
        showConversation(context, name, silent = true)
    }

    internal fun partnershipForReply(context: Context): String? = historyPrefs(context).getString("pid", null)

    /** LockIn opened, or you marked the chat read: clear the conversation. */
    fun clear(context: Context) {
        clearHistory(context)
        manager(context).cancel(IdMessages)
        // Messages and nudges Android showed by itself while LockIn was asleep.
        runCatching {
            manager(context).cancel("lockin_messages", 0)
            manager(context).cancel("lockin_nudges", 0)
        }
    }

    /**
     * Everything LockIn has put in the notification shade: opening the Activity list or a chat means you have seen
     * it, so it should not sit there still looking unread. (Shown notifications carry a tag per kind, id 0.)
     */
    fun clearActivity(context: Context) {
        clear(context)
        clearLockAlerts(context)
        runCatching {
            for (tag in listOf("lockin_plans", "lockin_limits", "lockin_games", "lockin_crew", "lockin_crew_session")) {
                manager(context).cancel(tag, 0)
            }
        }
    }

    /** The crew chat is open: its messages and session news are read. */
    fun clearCrew(context: Context) {
        runCatching {
            manager(context).cancel("lockin_crew", 0)
            manager(context).cancel("lockin_crew_session", 0)
        }
    }

    fun clearLockAlerts(context: Context) {
        manager(context).cancel(IdLocks)
        runCatching { manager(context).cancel("lockin_locks", 0) }
    }

    private fun capitalized(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/** Notifies, ignoring the rare phone that throws (no permission, a channel deleted mid-flight). */
internal object NotificationManagerCompatSafe {
    @android.annotation.SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, n: android.app.Notification) {
        if (!Push.canShow(context)) return
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(id, n) }
    }
}
