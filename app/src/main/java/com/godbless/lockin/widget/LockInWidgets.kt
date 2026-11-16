package com.godbless.lockin.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import com.godbless.lockin.lock.LocalLock
import com.godbless.lockin.push.Push
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * LockIn's home-screen widgets.
 *
 * - [TodayWidget] (2x2, wider gives more): today's ring against your goal, the streak, Lock in. While a partner's
 *   lock holds this phone it turns into a live countdown; during your own session it counts up.
 * - [RopeWidget] (4x2): you and your partner climbing the logo's mountain, each as far as today's goal; buttons
 *   to lock in or lock them, or a countdown while a lock runs between you.
 * - [WeekWidget] (4x2): the week's total, the change on last week, the streak and a bar per day.
 *
 * They draw from [WidgetSnapshot] (written by Home) and from the phone's own record of a lock ([LocalLock]),
 * so they stay right while LockIn is closed. Countdowns tick on the home screen by themselves (Chronometer);
 * the rest is redrawn every minute while something is running, at midnight, and whenever Home publishes.
 */
internal object LockInWidgets {
    const val ActionTick = "com.godbless.lockin.widget.TICK"

    private fun providers(): List<LockInWidgetProvider> = listOf(TodayWidget(), RopeWidget(), WeekWidget())

    /** Redraws every LockIn widget on the home screen, then sets the next redraw. Safe to call from any thread. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        runCatching {
            val mgr = AppWidgetManager.getInstance(app) ?: return
            for (p in providers()) {
                val ids = mgr.getAppWidgetIds(ComponentName(app, p.javaClass))
                for (id in ids) update(app, mgr, p, id)
            }
            schedule(app)
        }
    }

    fun update(context: Context, mgr: AppWidgetManager, provider: LockInWidgetProvider, id: Int) {
        runCatching {
            val (w, h) = sizeOf(context, mgr.getAppWidgetOptions(id), provider.defaultSize)
            val look = WidgetLook.of(context)
            val now = System.currentTimeMillis()
            val live = Live.read(context, WidgetSnapshot.load(context), now)
            val views = provider.draw(context, look, live, w, h)
            mgr.updateAppWidget(id, views)
        }.onFailure { android.util.Log.w("LockInWidget", "draw failed", it) }
    }

    /** The widget's size in dp: the portrait size (narrow and tall) in portrait, the landscape one otherwise. */
    private fun sizeOf(context: Context, o: Bundle?, fallback: Pair<Float, Float>): Pair<Float, Float> {
        if (o == null) return fallback
        val land = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (w <= 0 || h <= 0) fallback else w.toFloat() to h.toFloat()
    }

    private fun anyPlaced(context: Context): Boolean = runCatching {
        val mgr = AppWidgetManager.getInstance(context)
        providers().any { mgr.getAppWidgetIds(ComponentName(context, it.javaClass)).isNotEmpty() }
    }.getOrDefault(false)

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7301,
        Intent(context, TodayWidget::class.java).setAction(ActionTick),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The next redraw: in a minute while a lock or session runs (on the second a lock ends), else just after
     * midnight, when today starts again. Nothing when no widget is placed.
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = tickIntent(context)
        if (!anyPlaced(context)) { am.cancel(pi); return }
        val now = System.currentTimeMillis()
        val live = Live.read(context, WidgetSnapshot.load(context), now)
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000L
        var at = midnight
        var exact = false
        if (live.running) {
            at = minOf(at, now + 60_000L)
            for (end in listOf(live.meEnd, live.themEnd)) {
                if (end > now && end + 800L < at) { at = end + 800L; exact = true }
            }
        }
        runCatching {
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact && canExact) am.setExact(AlarmManager.RTC, at, pi)
            else am.setWindow(AlarmManager.RTC, at, if (live.running) 10_000L else 60_000L, pi)
        }
    }

    // ---- Where taps go ---------------------------------------------------------------------------------------

    /** Opens LockIn; with a [target], at that place (see MainActivity / HomeScreen). */
    fun open(context: Context, target: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction("com.godbless.lockin.widget.OPEN." + (target ?: "home"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target != null) i.putExtra(Push.ExtraOpen, target)
        val code = 7400 + (target?.hashCode()?.and(0xFF) ?: 0)
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---- Shared drawing helpers --------------------------------------------------------------------------------

    /** The card, its hairline and the accent glow behind the main figure. */
    fun RemoteViews.card(look: WidgetLook) {
        setInt(R.id.bg, "setColorFilter", look.surface)
        setInt(R.id.rim, "setColorFilter", look.rim)
        setInt(R.id.glow, "setColorFilter", look.accent)
        setInt(R.id.glow, "setImageAlpha", look.glowAlpha)
        // A faint light across the card from its top left: white on dark, the accent on light.
        setInt(R.id.sheen, "setColorFilter", if (look.dark) Color.WHITE else look.accent)
        setInt(R.id.sheen, "setImageAlpha", if (look.dark) 24 else 22)
    }

    /**
     * Whether the widgets run their looping effects (twinkling stars, drifting cloud, the comet round the ring, the
     * shimmer on buttons): the same Motion setting as the app. With reduced motion the calm layouts are used, which
     * don't contain the effects at all.
     */
    fun motion(): Boolean = runCatching { !com.godbless.lockin.ui.MotionState.reduced }.getOrDefault(true)

    /** The card's corner radius in dp (the launcher's own on Android 12+), so a picture laid over the card fits it. */
    fun cornerDp(context: Context): Float {
        val r = context.resources
        return (r.getDimension(R.dimen.widget_radius) / r.displayMetrics.density).coerceIn(8f, 40f)
    }

    fun RemoteViews.tint(id: Int, color: Int) = setInt(id, "setColorFilter", color)

    fun RemoteViews.show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

    /** A Chronometer counting down to [endMillis] (wall clock), or up from [startMillis]. */
    fun RemoteViews.countdown(id: Int, endMillis: Long, now: Long) {
        setChronometer(id, SystemClock.elapsedRealtime() + (endMillis - now), null, true)
        setChronometerCountDown(id, true)
    }

    fun RemoteViews.countup(id: Int, startMillis: Long, now: Long) {
        setChronometer(id, SystemClock.elapsedRealtime() - (now - startMillis), null, true)
        setChronometerCountDown(id, false)
    }

    /** "1h 24m", "52m", "2h". */
    fun dur(seconds: Int): String {
        val m = max(0, seconds) / 60
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    fun clock(context: Context, millis: Long): String = android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))

    /** "4:00 PM" today, "Tomorrow 9:00 AM", "Tue 4:00 PM" further on. */
    fun whenText(context: Context, millis: Long): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val t = clock(context, millis)
        return when (d) {
            today -> t
            today.plusDays(1) -> "Tomorrow $t"
            else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " " + t
        }
    }

    fun initial(name: String, fallback: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: fallback
}

/**
 * The snapshot as it stands this second: today's time with a running session or lock counted in, and the lock on
 * this phone as the phone itself knows it (that record is kept even while LockIn is closed).
 */
internal class Live(
    val snap: WidgetSnapshot,
    val now: Long,
    /** A partner's lock on this phone: when it ends (0: none), how long it is in all, who set it. */
    val meEnd: Long,
    val meTotalMillis: Long,
    val meBy: String,
    /** A lock you set on your partner that is running: when it ends (0: none). */
    val themEnd: Long,
    val themTotalMillis: Long,
    /** Your own session's start, or 0. */
    val sessionStart: Long,
    val todaySeconds: Int,
    val weekSeconds: Int,
    val weekHours: FloatArray,
) {
    val signedIn get() = snap.signedIn
    val paired get() = snap.partnerName != null
    val partner get() = snap.partnerName ?: "Your partner"
    val locked get() = meEnd > now
    val focusing get() = !locked && sessionStart > 0L
    val lockingThem get() = themEnd > now
    val running get() = locked || focusing || lockingThem
    val goalSeconds get() = snap.goalMinutes * 60
    val fraction get() = todaySeconds / goalSeconds.toFloat()
    val partnerFraction get() = snap.partnerTodaySeconds / (snap.partnerGoalMinutes * 60f)
    val todayIndex get() = LocalDate.now().dayOfWeek.value - 1

    companion object {
        fun read(context: Context, snap: WidgetSnapshot, now: Long): Live {
            if (!snap.signedIn) return Live(snap, now, 0L, 0L, "", 0L, 0L, 0L, 0, 0, FloatArray(7))
            val local = runCatching { LocalLock(context) }.getOrNull()
            val localLeft = runCatching { if (local?.active == true) local.remainingMillis() else 0L }.getOrDefault(0L)
            val meEnd = when {
                localLeft > 0L -> now + localLeft
                snap.lockOnMeEnd > now -> snap.lockOnMeEnd
                else -> 0L
            }
            val meTotal = if (meEnd > 0L) max(snap.lockOnMeTotal * 1000L, meEnd - now) else 0L
            // A crew session holds the phone the same way (with a negative id and the crew's name).
            val crew = localLeft > 0L && runCatching { local!!.id < 0L }.getOrDefault(false)
            val localBy = runCatching { local?.by }.getOrNull() ?: "Your partner"
            val meBy = if (crew) localBy else snap.lockOnMeBy.ifBlank { localBy }
            val themEnd = if (snap.lockOnThemEnd > now) snap.lockOnThemEnd else 0L
            val themTotal = if (themEnd > 0L) max(snap.lockOnThemTotal * 1000L, themEnd - now) else 0L
            // A session left "running" by a LockIn that was closed long ago isn't shown as running for ever.
            val session = snap.sessionStart.takeIf { it > 0L && now - it in 0L..8 * 3_600_000L } ?: 0L
            val extra = when {
                meEnd > 0L -> ((meTotal - (meEnd - now)) / 1000L).toInt()
                session > 0L -> ((now - session) / 1000L).toInt()
                else -> 0
            }.coerceAtLeast(0)
            val week = snap.week.copyOf()
            val idx = LocalDate.now().dayOfWeek.value - 1
            week[idx] = week[idx] + extra / 3600f
            return Live(
                snap, now, meEnd, meTotal, meBy, themEnd, themTotal, session,
                snap.todaySeconds + extra, snap.weekSeconds + extra, week,
            )
        }
    }
}

/** What every LockIn widget shares: redraw on every update, resize and tick. */
abstract class LockInWidgetProvider : AppWidgetProvider() {
    /** The size to draw for when the launcher hasn't said (dp). */
    internal abstract val defaultSize: Pair<Float, Float>

    internal abstract fun draw(context: Context, look: WidgetLook, live: Live, widthDp: Float, heightDp: Float): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) LockInWidgets.update(context, manager, this, id)
        LockInWidgets.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        LockInWidgets.update(context, manager, this, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LockInWidgets.ActionTick) LockInWidgets.refreshAll(context)
        else super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) {
        LockInWidgets.schedule(context)
    }
}

// ---- Today ---------------------------------------------------------------------------------------------------

class TodayWidget : LockInWidgetProvider() {
    override val defaultSize = 160f to 160f

    override fun draw(context: Context, look: WidgetLook, live: Live, widthDp: Float, heightDp: Float): RemoteViews =
        if (widthDp >= 250f) wide(context, look, live, heightDp) else small(context, look, live, widthDp, heightDp)

    private fun RemoteViews.header(context: Context, look: WidgetLook, live: Live, wide: Boolean) = with(LockInWidgets) {
        val label = when {
            !live.signedIn -> "LockIn"
            live.locked -> if (wide) "Locked · by ${live.meBy}" else "Locked"
            live.focusing -> "Focusing"
            else -> context.getString(R.string.widget_today_label)
        }
        setTextViewText(R.id.head_label, label)
        setTextColor(R.id.head_label, if (live.locked || live.focusing) look.accent else look.mist)
        val streak = live.signedIn && !live.locked && live.snap.streak > 0
        show(R.id.chip, streak)
        if (streak) {
            tint(R.id.chip_bg, look.raised)
            tint(R.id.chip_icon, look.accent)
            setTextColor(R.id.chip_text, look.frost)
            setTextViewText(R.id.chip_text, if (wide) "${live.snap.streak} ${if (live.snap.streak == 1) "day" else "days"}" else "${live.snap.streak}")
            setContentDescription(R.id.chip, "${live.snap.streak} day streak")
        }
    }

    private fun small(context: Context, look: WidgetLook, live: Live, widthDp: Float, heightDp: Float): RemoteViews = with(LockInWidgets) {
        val motion = motion()
        val v = RemoteViews(context.packageName, if (motion) R.layout.widget_today_small else R.layout.widget_today_small_calm)
        v.card(look)
        v.header(context, look, live, wide = false)
        if (live.locked) {
            v.setImageViewResource(R.id.head_icon, R.drawable.ic_w_lock)
            v.tint(R.id.head_icon, look.accent)
        } else {
            v.setImageViewResource(R.id.head_icon, R.drawable.widget_logo)
            v.tint(R.id.head_icon, look.snow)
        }
        val ringDp = (minOf(widthDp - 26f, heightDp - 90f)).coerceIn(56f, 132f)
        val frac = when {
            live.locked -> 1f - (live.meEnd - live.now) / live.meTotalMillis.toFloat()
            live.signedIn -> live.fraction
            else -> 0f
        }
        v.setImageViewBitmap(R.id.ring, WidgetArt.ring(context, look, ringDp, frac))
        if (motion) {
            v.show(R.id.fx_ring, live.signedIn)
            v.show(R.id.fx_done, live.signedIn && !live.locked && live.fraction >= 1f)
        }
        v.setTextColor(R.id.big, look.snow)
        v.setTextColor(R.id.clock, look.snow)
        v.setTextColor(R.id.ring_sub, look.mist)
        v.setTextColor(R.id.caption, look.body)
        v.tint(R.id.fab_bg, look.accent)
        v.tint(R.id.fab_icon, look.onAccent)
        v.setContentDescription(R.id.fab, "Lock in")
        v.setOnClickPendingIntent(R.id.fab, open(context, Push.OpenStart))
        v.setOnClickPendingIntent(R.id.root, open(context, null))

        when {
            !live.signedIn -> {
                v.show(R.id.big, true); v.show(R.id.clock, false); v.show(R.id.fab, false)
                v.setTextViewText(R.id.big, "—")
                v.setTextViewText(R.id.ring_sub, "")
                v.setTextViewText(R.id.caption, "Open LockIn to sign in")
            }
            live.locked -> {
                v.show(R.id.big, false); v.show(R.id.clock, true); v.show(R.id.fab, false)
                v.countdown(R.id.clock, live.meEnd, live.now)
                v.setTextViewText(R.id.ring_sub, "left")
                v.setTextViewText(R.id.caption, "Ends ${clock(context, live.meEnd)}")
            }
            live.focusing -> {
                v.show(R.id.big, false); v.show(R.id.clock, true); v.show(R.id.fab, false)
                v.countup(R.id.clock, live.sessionStart, live.now)
                v.setTextViewText(R.id.ring_sub, "this session")
                v.setTextViewText(R.id.caption, "${dur(live.todaySeconds)} today")
            }
            else -> {
                v.show(R.id.big, true); v.show(R.id.clock, false); v.show(R.id.fab, true)
                v.setTextViewText(R.id.big, dur(live.todaySeconds))
                v.setTextViewText(R.id.ring_sub, "of ${dur(live.goalSeconds)}")
                v.setTextViewText(
                    R.id.caption,
                    when {
                        live.lockingThem -> "${live.partner} locked"
                        live.fraction >= 1f -> "Summit reached"
                        else -> "${dur(live.goalSeconds - live.todaySeconds)} to go"
                    },
                )
            }
        }
        v.setContentDescription(R.id.ring, describe(live))
        v
    }

    private fun wide(context: Context, look: WidgetLook, live: Live, heightDp: Float): RemoteViews = with(LockInWidgets) {
        val motion = motion()
        val v = RemoteViews(context.packageName, if (motion) R.layout.widget_today_wide else R.layout.widget_today_wide_calm)
        v.card(look)
        v.header(context, look, live, wide = true)
        v.show(R.id.head_icon, live.locked)
        v.tint(R.id.head_icon, look.accent)
        val ringDp = (heightDp - 24f).coerceIn(70f, 150f)
        val frac = when {
            live.locked -> 1f - (live.meEnd - live.now) / live.meTotalMillis.toFloat()
            live.signedIn -> live.fraction
            else -> 0f
        }
        v.setImageViewBitmap(R.id.ring, WidgetArt.ring(context, look, ringDp, frac))
        if (motion) {
            v.show(R.id.fx_ring, live.signedIn)
            v.show(R.id.fx_done, live.signedIn && !live.locked && live.fraction >= 1f)
        }
        v.setContentDescription(R.id.ring, describe(live))
        v.setTextColor(R.id.ring_big, look.snow)
        v.setTextColor(R.id.ring_sub, look.mist)
        v.tint(R.id.ring_icon, look.accent)
        v.setTextColor(R.id.big, look.snow)
        v.setTextColor(R.id.clock, look.snow)
        v.setTextColor(R.id.line_text, look.body)
        v.tint(R.id.line_icon, look.mist)
        v.tint(R.id.primary_bg, look.accent)
        v.tint(R.id.primary_icon, look.onAccent)
        v.setTextColor(R.id.primary_text, look.onAccent)
        v.tint(R.id.ghost_rim, look.raised)
        v.tint(R.id.ghost_icon, look.frost)
        v.setOnClickPendingIntent(R.id.root, open(context, null))
        v.setOnClickPendingIntent(R.id.ghost, open(context, Push.OpenChat))
        v.setContentDescription(R.id.ghost, "Chat with ${live.partner}")
        val pct = "${(live.fraction * 100f).roundToInt().coerceAtMost(999)}%"
        // Short widgets drop the buttons and keep the numbers.
        val roomy = heightDp >= 130f

        fun line(icon: Int, text: String) {
            v.setImageViewResource(R.id.line_icon, icon)
            v.setTextViewText(R.id.line_text, text)
        }
        when {
            !live.signedIn -> {
                v.show(R.id.ring_icon, false); v.show(R.id.ring_big, true)
                v.setTextViewText(R.id.ring_big, "—"); v.setTextViewText(R.id.ring_sub, "")
                v.show(R.id.big, true); v.show(R.id.clock, false)
                v.setTextViewText(R.id.big, "LockIn")
                line(R.drawable.ic_w_lock, "Sign in to see your day")
                v.show(R.id.actions, roomy); v.show(R.id.ghost, false); v.show(R.id.primary_icon, false)
                v.setTextViewText(R.id.primary_text, "Open LockIn")
                v.setOnClickPendingIntent(R.id.primary, open(context, null))
            }
            live.locked -> {
                v.show(R.id.ring_icon, true); v.show(R.id.ring_big, false)
                v.setTextViewText(R.id.ring_sub, "${(frac * 100f).roundToInt()}% done")
                v.show(R.id.big, false); v.show(R.id.clock, true)
                v.countdown(R.id.clock, live.meEnd, live.now)
                line(R.drawable.ic_w_clock, "Ends ${clock(context, live.meEnd)}")
                v.show(R.id.actions, roomy); v.show(R.id.ghost, live.paired); v.show(R.id.primary_icon, false)
                v.setTextViewText(R.id.primary_text, "Open")
                v.setOnClickPendingIntent(R.id.primary, open(context, null))
            }
            else -> {
                v.show(R.id.ring_icon, false); v.show(R.id.ring_big, true)
                v.setTextViewText(R.id.ring_big, pct)
                v.setTextViewText(R.id.ring_sub, "of ${dur(live.goalSeconds)} goal")
                if (live.focusing) {
                    v.show(R.id.big, false); v.show(R.id.clock, true)
                    v.countup(R.id.clock, live.sessionStart, live.now)
                } else {
                    v.show(R.id.big, true); v.show(R.id.clock, false)
                    v.setTextViewText(R.id.big, dur(live.todaySeconds))
                }
                val next = live.snap.nextAt.takeIf { it > live.now }
                when {
                    live.lockingThem -> line(R.drawable.ic_w_lock, "${live.partner} locked · ends ${clock(context, live.themEnd)}")
                    live.focusing -> line(R.drawable.ic_w_flag, "${dur(live.todaySeconds)} today")
                    next != null -> line(R.drawable.ic_w_clock, listOf(live.snap.nextTitle.ifBlank { "Session" }, whenText(context, next)).joinToString(" · "))
                    live.fraction >= 1f -> line(R.drawable.ic_w_flag, "Summit reached today")
                    else -> line(R.drawable.ic_w_flag, "${dur(live.goalSeconds - live.todaySeconds)} to the summit")
                }
                v.show(R.id.actions, roomy); v.show(R.id.ghost, live.paired)
                if (live.focusing) {
                    v.show(R.id.primary_icon, false)
                    v.setTextViewText(R.id.primary_text, "Back to session")
                    v.setOnClickPendingIntent(R.id.primary, open(context, null))
                } else {
                    v.show(R.id.primary_icon, true)
                    v.setTextViewText(R.id.primary_text, "Lock in")
                    v.setOnClickPendingIntent(R.id.primary, open(context, Push.OpenStart))
                }
            }
        }
        v
    }

    private fun describe(live: Live): String = with(LockInWidgets) {
        when {
            !live.signedIn -> "Not signed in"
            live.locked -> "Locked by ${live.meBy}"
            else -> "${dur(live.todaySeconds)} of ${dur(live.goalSeconds)} today"
        }
    }
}

// ---- Roped ---------------------------------------------------------------------------------------------------

class RopeWidget : LockInWidgetProvider() {
    override val defaultSize = 320f to 180f

    override fun draw(context: Context, look: WidgetLook, live: Live, widthDp: Float, heightDp: Float): RemoteViews = with(LockInWidgets) {
        val motion = motion()
        val v = RemoteViews(context.packageName, if (motion) R.layout.widget_rope else R.layout.widget_rope_calm)
        v.card(look)
        v.tint(R.id.head_icon, look.snow)
        v.setTextColor(R.id.head_label, look.mist)
        v.tint(R.id.corner_rim, look.raised)
        v.tint(R.id.corner_icon, look.frost)
        v.setTextColor(R.id.me_time, look.accent)
        v.setTextColor(R.id.me_label, look.mist)
        v.setTextColor(R.id.them_label, look.mist)
        v.setTextColor(R.id.them_time, look.snow)
        v.tint(R.id.primary_bg, look.accent)
        v.tint(R.id.primary_icon, look.onAccent)
        v.setTextColor(R.id.primary_text, look.onAccent)
        v.tint(R.id.ghost_rim, look.raised)
        v.setTextColor(R.id.ghost_text, look.frost)
        v.tint(R.id.status_bg, look.raised)
        v.tint(R.id.status_icon, look.accent)
        v.setTextColor(R.id.status_text, look.frost)
        v.setTextColor(R.id.status_clock, look.accent)
        v.setOnClickPendingIntent(R.id.root, open(context, null))

        val paired = live.signedIn && live.paired
        val compact = heightDp < 140f
        val showStatus = live.signedIn && (live.locked || live.lockingThem)
        val showActions = live.signedIn && !showStatus && !compact

        v.setTextViewText(
            R.id.head_label,
            when {
                !live.signedIn -> "LockIn"
                paired -> "Roped · You & ${live.partner}"
                else -> "Today's climb"
            },
        )
        v.show(R.id.corner, paired)
        v.setOnClickPendingIntent(R.id.corner, open(context, Push.OpenChat))
        v.setContentDescription(R.id.corner, "Chat with ${live.partner}")

        // The whole card is one picture (sky, ranges, mountain, rope, climbers); the mountain stands in whatever is left
        // between the header and the rows under it, which is the box the layout keeps free (header 28 under 12 of padding).
        val artH = (heightDp - 24f - 28f - 22f - (if (showActions || showStatus) 41f else 0f)).coerceIn(36f, 220f)
        val sceneW = max(widthDp, 160f)
        val sceneH = max(heightDp, 96f)
        v.setImageViewBitmap(
            R.id.art,
            WidgetArt.scene(
                context, look, sceneW, sceneH, artTopDp = 40f, artHeightDp = artH,
                you = if (live.signedIn) live.fraction else 0f,
                them = if (paired) live.partnerFraction else null,
                youInitial = initial(live.snap.myName, "Y"),
                themInitial = initial(live.partner, "P"),
                radiusDp = cornerDp(context),
            ),
        )
        if (motion) {
            // Stars and a shooting star belong to the night scene; low cloud drifts by day and night.
            val night = live.signedIn && look.dark
            v.show(R.id.fx_stars_a, night); v.show(R.id.fx_stars_b, night); v.show(R.id.fx_stars_c, night); v.show(R.id.fx_shoot, night)
            v.show(R.id.fx_mist, live.signedIn)
        }
        v.setContentDescription(
            R.id.art,
            if (paired) "You ${dur(live.todaySeconds)}, ${live.partner} ${dur(live.snap.partnerTodaySeconds)} today"
            else "You ${dur(live.todaySeconds)} today",
        )

        if (!live.signedIn) {
            v.setTextViewText(R.id.me_time, "")
            v.setTextViewText(R.id.me_label, "Open LockIn to sign in")
            v.show(R.id.them_label, false); v.show(R.id.them_time, false)
        } else {
            v.setTextViewText(R.id.me_time, if (live.focusing) "${dur(live.todaySeconds)} ·" else dur(live.todaySeconds))
            v.setTextViewText(R.id.me_label, if (live.focusing) "climbing now" else "you")
            v.show(R.id.them_label, true)
            v.show(R.id.them_time, paired)
            if (paired) {
                v.setTextViewText(R.id.them_label, live.partner)
                v.setTextViewText(R.id.them_time, dur(live.snap.partnerTodaySeconds))
            } else {
                v.setTextViewText(R.id.them_label, "Goal ${dur(live.goalSeconds)}")
            }
        }

        v.show(R.id.actions, showActions)
        v.show(R.id.status, showStatus)
        if (showActions) {
            v.setTextViewText(R.id.primary_text, if (live.focusing) "Session" else "Lock in")
            v.show(R.id.primary_icon, !live.focusing)
            v.setOnClickPendingIntent(R.id.primary, open(context, if (live.focusing) null else Push.OpenStart))
            if (paired) {
                v.setTextViewText(R.id.ghost_text, "Lock ${live.partner}")
                v.setOnClickPendingIntent(R.id.ghost, open(context, Push.OpenLock))
            } else {
                v.setTextViewText(R.id.ghost_text, "Pair up")
                v.setOnClickPendingIntent(R.id.ghost, open(context, Push.OpenPartner))
            }
        }
        if (showStatus) {
            if (live.locked) {
                v.setTextViewText(R.id.status_text, "Locked by ${live.meBy}")
                v.countdown(R.id.status_clock, live.meEnd, live.now)
                v.setOnClickPendingIntent(R.id.status, open(context, null))
            } else {
                v.setTextViewText(R.id.status_text, "${live.partner} is locked")
                v.countdown(R.id.status_clock, live.themEnd, live.now)
                v.setOnClickPendingIntent(R.id.status, open(context, Push.OpenLock))
            }
        }
        v
    }
}

// ---- This week -----------------------------------------------------------------------------------------------

class WeekWidget : LockInWidgetProvider() {
    override val defaultSize = 320f to 170f

    override fun draw(context: Context, look: WidgetLook, live: Live, widthDp: Float, heightDp: Float): RemoteViews = with(LockInWidgets) {
        val motion = motion()
        val v = RemoteViews(context.packageName, if (motion) R.layout.widget_week else R.layout.widget_week_calm)
        v.card(look)
        v.setTextColor(R.id.head_label, look.mist)
        v.setTextColor(R.id.big, look.snow)
        v.tint(R.id.streak_icon, look.accent)
        v.setTextColor(R.id.streak_text, look.body)
        v.tint(R.id.chip_bg, look.raised)
        v.setOnClickPendingIntent(R.id.root, open(context, if (live.signedIn) Push.OpenClimbs else null))

        val delta = live.snap.weekDelta
        val showDelta = live.signedIn && delta.isNotBlank() && live.snap.lastWeekSeconds > 0
        v.show(R.id.chip, showDelta)
        if (showDelta) {
            v.setTextViewText(R.id.chip_text, "$delta vs last week")
            v.setTextColor(R.id.chip_text, if (live.snap.weekUp) look.accent else look.body)
        }
        v.setTextViewText(R.id.big, if (live.signedIn) dur(live.weekSeconds) else "—")
        val streak = live.snap.streak
        v.show(R.id.streak_icon, live.signedIn && streak > 0)
        v.setTextViewText(
            R.id.streak_text,
            when {
                !live.signedIn -> "Open LockIn to sign in"
                streak > 0 -> "$streak-day streak"
                else -> "Start a streak today"
            },
        )
        val barsW = (widthDp - 30f).coerceAtLeast(120f)
        val barsH = (heightDp - 22f - 24f - 38f - 6f).coerceIn(30f, 200f)
        val goalHours = live.snap.goalMinutes / 60f
        val todayHours = live.weekHours.getOrElse(live.todayIndex) { 0f }
        v.setImageViewBitmap(
            R.id.bars,
            WidgetArt.week(
                context, look, barsW, barsH, live.weekHours, live.todayIndex, goalHours,
                goalTag = "Goal ${dur(live.goalSeconds)}",
                todayLabel = if (live.signedIn && todayHours > 0f) dur((todayHours * 3600f).roundToInt()) else "",
            ),
        )
        // A pale band crosses the chart now and then (on dark cards, where it shows).
        if (motion) v.show(R.id.fx_glint, live.signedIn && look.dark)
        // The best day so far, when the widget is wide enough to have room for it.
        val bestIndex = live.weekHours.indices.maxByOrNull { live.weekHours[it] } ?: 0
        val bestHours = live.weekHours.getOrElse(bestIndex) { 0f }
        val showBest = live.signedIn && widthDp >= 300f && bestHours > 0f
        v.show(R.id.best, showBest)
        if (showBest) {
            v.setTextColor(R.id.best_label, look.mist)
            v.setTextColor(R.id.best_value, look.snow)
            val day = java.time.DayOfWeek.of(bestIndex + 1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            v.setTextViewText(R.id.best_value, "$day · ${dur((bestHours * 3600f).roundToInt())}")
        }
        val days = live.weekHours.count { it >= goalHours && goalHours > 0f }
        v.setContentDescription(R.id.bars, "Goal reached on $days ${if (days == 1) "day" else "days"} this week")
        v
    }
}
