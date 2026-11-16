package com.godbless.lockin.push

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import org.json.JSONArray
import java.io.File
import java.util.Calendar

/**
 * One reminder a day, at a time you pick, to review the flashcards that are due. It only shows when something is
 * actually due (it looks at the cards on this phone when it fires), and sets the next day's alarm each time it
 * rings. It rides on [PlanReminderReceiver] and is re-armed after a restart from the boot receiver.
 */
object QuizReminder {
    internal const val Extra = "quiz_daily"
    private const val Prefs = "quiz_reminder"
    private const val KeyMinute = "minute"
    private const val Code = 7102
    private const val Channel = "study"

    /** Minutes after midnight the reminder rings, or -1 when it is off. */
    fun minute(context: Context): Int = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getInt(KeyMinute, -1)

    fun set(context: Context, minuteOfDay: Int) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putInt(KeyMinute, minuteOfDay).apply()
        if (minuteOfDay < 0) cancel(context) else schedule(context)
    }

    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, PlanReminderReceiver::class.java).putExtra(Extra, true)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, Code, intent, flags)
    }

    private fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, create = false)?.let { alarms.cancel(it); it.cancel() }
    }

    /** Sets the alarm for the next time the picked minute comes round (today if it hasn't passed). */
    fun schedule(context: Context) {
        val m = minute(context)
        if (m < 0) return
        val now = System.currentTimeMillis()
        val at = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now + 30_000L) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val pi = pending(context, create = true) ?: return
        Alarms.at(context, at, pi)
    }

    fun rearm(context: Context) = schedule(context)

    /** How many cards are due, read straight from the deck file (no UI needed). */
    private fun dueCount(context: Context): Int = runCatching {
        val file = File(File(context.filesDir, "study"), "cards.json")
        val a = JSONArray(file.readText())
        val now = System.currentTimeMillis()
        (0 until a.length()).count { a.getJSONObject(it).optLong("due") <= now }
    }.getOrDefault(0)

    internal fun fire(context: Context) {
        schedule(context) // tomorrow's
        val due = dueCount(context)
        if (due <= 0 || !Push.canShow(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(Channel) == null) {
            manager.createNotificationChannel(
                NotificationChannel(Channel, "Study", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A daily reminder to review your flashcards"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setContentTitle(if (due == 1) "1 flashcard is due" else "$due flashcards are due")
            .setContentText("A few minutes now keeps them from piling up.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(1200, notification)
    }
}
