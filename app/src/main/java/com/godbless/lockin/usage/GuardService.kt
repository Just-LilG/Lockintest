package com.godbless.lockin.usage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import com.godbless.lockin.data.AuthResult
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.ReportRow
import com.godbless.lockin.data.UsageRepository
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.push.Push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs while there is something to do for your partner: it reports how long each app was open (every
 * ten minutes), keeps the limits they set up to date, and covers an app with a "limit reached" screen
 * once its time is up. On a phone where LockIn is the device owner it suspends the app instead, so it
 * can't be opened at all until midnight. The limits are kept on the phone, so they hold with no signal.
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile
    private var refreshNow = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == Guard.ActionRefresh) refreshNow = true
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(Channel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(Channel, "Limits and sharing", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Shown while LockIn holds your app limits and shares app time with your partner"
                    },
                )
            }
            val open = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(this, Channel)
                .setSmallIcon(R.drawable.ic_stat_lockin)
                .setContentTitle("LockIn is on")
                .setContentText("Keeping your protection on, holding app limits and sharing app time.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            ServiceCompat.startForeground(this, NotificationId, notification, type)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun run() {
        val app = application as LockInApplication
        val reader = UsageReader(this)
        val store = LimitStore(this)
        val watcher = ForegroundWatcher(reader)
        val guard = LockGuard(this)
        val usage = UsageRepository(PartnerRepository(app.auth))

        var limitsAt = 0L
        var reportAt = 0L
        var fullReportAt = 0L
        var keepAt = 0L
        var sessionAt = 0L
        var watchAt = 0L
        var flushAt = 0L
        var pingAt = 0L
        val partnersRepo = PartnerRepository(app.auth)
        val devices = com.godbless.lockin.data.DeviceRepository(partnersRepo)
        // The cover, made on the main thread (it's a window).
        val chrome = ScreenKinds.chrome(this)
        val overlay = kotlinx.coroutines.withContext(Dispatchers.Main) { LimitOverlay(this@GuardService) }
        this.overlay = overlay
        // "Ask to lift it" on the limit screen sends the partner a real message, then opens the chat when it can.
        overlay.onAskSend = { label: String ->
            scope.launch {
                runCatching {
                    if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
                    val partners = PartnerRepository(app.auth)
                    val pid = (partners.pairing().getOrNull() as? com.godbless.lockin.data.Pairing.Paired)?.id
                    if (pid != null) partners.send(pid, "Can you lift my $label limit for today?")
                }
            }
        }
        // Seconds each limited app was seen on screen today, counted here as well. Android's usage log stops
        // counting an app the moment another one comes to the front, which a floating window gets around.
        val seen = getSharedPreferences("guard_seen", MODE_PRIVATE)
        var lastTick = 0L
        val usedCache = HashMap<String, Pair<Int, Long>>()
        // The same seconds, held in memory and written out every few seconds (writing a file every second was needless work).
        val seenMem = HashMap<String, Long>()
        var seenFlushAt = 0L
        // When the cover first had no limited app behind it (0 while the app is there).
        var coverGoneAt = 0L

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val watching = Guard.watching(this)
            if (!(Guard.wanted(this) || refreshNow || watching)) {
                // Nothing left to do (or no permission): never leave an app suspended with nobody to switch it back on.
                Guard.releaseSuspended(this)
                stopSelf()
                return
            }

            // Network work needs a signed-in session. After a restart there is none until someone opens the app,
            // so sign back in quietly with the remembered one (unless the app is open and doing it itself).
            val online = app.auth.hasSession() || (now - sessionAt > 60_000 && trySignIn(app).also { sessionAt = now })

            // The watch: has anything that holds LockIn's protection been switched off? Looked at every few seconds, from
            // here as well as from the guard, so switching the guard off doesn't switch the watch off. What it finds goes
            // to the partner as soon as there is a connection, and a quiet "still here" keeps the partner's "seen ..." fresh.
            if (watching) {
                if (now - watchAt > 3_000) {
                    watchAt = now
                    runCatching { com.godbless.lockin.lock.ProtectionWatch.check(this) }
                }
                if (online && now - flushAt > 15_000) {
                    flushAt = now
                    runCatching { com.godbless.lockin.lock.ProtectionWatch.flush(this, partnersRepo) }
                }
                if (online && now - pingAt > 120_000) {
                    pingAt = now
                    devices.ping(this)
                }
            }
            if (online) {
                // While the cover is up, ask often: your partner may be lifting the limit right now, and a push can be late.
                if (refreshNow || now - limitsAt > (if (overlay.showing) 15_000L else 5 * 60_000L)) {
                    refreshNow = false
                    limitsAt = now
                    usage.myLimits().onSuccess {
                        store.save(it)
                        keepAt = 0
                    }
                }
                if (store.reporting && now - reportAt > 10 * 60_000) {
                    reportAt = now
                    val full = now - fullReportAt > 60 * 60_000
                    if (full) fullReportAt = now
                    val days = reader.days(if (full) 7 else 1)
                    val rows = days.flatMap { (day, apps) -> apps.map { ReportRow(day.toString(), it.pkg, it.label, it.seconds, it.limitable) } }
                    usage.report(rows)
                }
            }

            val limits = store.load()
            if (now - keepAt > 30_000) {
                keepAt = now
                keepSuspensions(limits, reader, store, guard)
            }

            if (limits.isNotEmpty() && UsageAccess.granted(this)) {
                watcher.poll()
                // Every app on screen, not just the newest: a floating or split-screen app counts too.
                // The window list from the guard is exact; without it, the usage log is the best there is.
                val wins = com.godbless.lockin.lock.SettingsGuardService.appWindows()
                val haveWins = wins != null && wins.isNotEmpty()
                // A window Android won't let the guard read (no package) must not hide an app from the count: when there
                // is one, the usage log is added to what's known to be on screen.
                val hidden = haveWins && wins!!.any { it.pkg.isEmpty() }
                val onScreen = if (haveWins) wins!!.mapNotNull { it.pkg.ifEmpty { null } }.toSet() + (if (hidden) watcher.onScreen else emptySet()) else watcher.onScreen
                val today = java.time.LocalDate.now().toString()
                val tick = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 3_000L)
                lastTick = now
                var over: Limit? = null
                for (limit in limits) {
                    if (limit.pkg !in onScreen) continue
                    val key = "$today|${limit.pkg}"
                    val mine = (seenMem[key] ?: seen.getLong(key, 0L)) + tick
                    seenMem[key] = mine
                    // Android's count, worked out every few seconds and counted up in between.
                    val cached = usedCache[limit.pkg]
                    val logged = if (cached == null || now - cached.second > 5_000) {
                        reader.usedToday(limit.pkg, now).also { usedCache[limit.pkg] = it to now }
                    } else {
                        cached.first + ((now - cached.second) / 1000).toInt()
                    }
                    val used = maxOf(logged, (mine / 1000).toInt())
                    if (used >= limit.seconds && over == null) over = limit
                }
                // Small window or split screen: the limited app is not the only app with a window. The home screen and
                // system UI don't count (the usage log never lets go of them), and neither does our own app.
                // The guard's window list is exact: the app has a window that isn't full screen (floating, split or picture in
                // picture), or its own window couldn't be read and some other app window on screen is small.
                val floating = over != null && if (haveWins) {
                    wins!!.any { it.pkg == over.pkg && !it.full } ||
                        (wins.none { it.pkg == over.pkg } && wins.any { it.pkg.isEmpty() && !it.full })
                } else {
                    onScreen.any { it != over.pkg && it !in chrome } || overlay.sinceAnswered() > 2_500L
                }
                if (seenMem.isNotEmpty() && now - seenFlushAt > 5_000L) {
                    seenFlushAt = now
                    seenMem.keys.removeAll { !it.startsWith("$today|") }
                    seen.edit().apply { seenMem.forEach { (k, v) -> putLong(k, v) } }.apply()
                }
                if (over != null) {
                    coverGoneAt = 0L
                    // Where its small window is (so a cover can be put over just that, if closing it ourselves fails).
                    val small = if (floating && haveWins) {
                        (wins!!.firstOrNull { it.pkg == over.pkg && !it.full } ?: wins.firstOrNull { it.pkg.isEmpty() && !it.full })?.bounds
                    } else null
                    block(over, guard, store, floating, onScreen, small)
                } else if (overlay.showing) {
                    val coverPkg = overlay.pkg
                    if (coverPkg != null && coverPkg in onScreen) {
                        // Its app is still on screen but nothing is over its limit any more: your partner lifted or raised the
                        // limit (or a new day began). Let the person straight back in.
                        coverGoneAt = 0L
                        closeTries = 0
                        kotlinx.coroutines.withContext(Dispatchers.Main) { overlay.hide() }
                    } else {
                        // The limited app is gone. The cover goes once you've answered it (or LockIn closed the window), or
                        // after a while if you haven't: it must never be left sitting over the home screen.
                        if (coverGoneAt == 0L) coverGoneAt = now
                        if (overlay.answered || overlay.closing || now - coverGoneAt > CoverGraceMs) {
                            // Whatever of the app is left running in the background goes with it.
                            coverPkg?.let { ScreenKinds.killBackground(this, it) }
                            closeTries = 0
                            coverGoneAt = 0L
                            kotlinx.coroutines.withContext(Dispatchers.Main) { overlay.hide() }
                        }
                    }
                }
                if (now % 600_000L < 1_500L) forgetOldDays(seen, today)
            } else if (overlay.showing) {
                // No limits left (or LockIn can no longer read app times): nothing can be over, so the cover has no job.
                // This is what happens when your partner removes the limit you asked them to lift.
                coverGoneAt = 0L
                closeTries = 0
                kotlinx.coroutines.withContext(Dispatchers.Main) { overlay.hide() }
            }

            delay(if (limits.isNotEmpty()) 1_000L else if (watching) 3_000L else 20_000L)
        }
    }

    private suspend fun trySignIn(app: LockInApplication): Boolean {
        if (Push.inForeground) return false
        if (app.auth.savedUser() == null) return false
        return app.auth.restore() is AuthResult.SignedIn
    }

    /** Switches back on any app whose limit is gone, raised, or reset by a new day. */
    private fun keepSuspensions(limits: List<Limit>, reader: UsageReader, store: LimitStore, guard: LockGuard) {
        val held = store.suspended()
        if (held.isEmpty()) return
        val keep = HashSet<String>()
        for (pkg in held) {
            val limit = limits.firstOrNull { it.pkg == pkg }
            if (limit != null && reader.usedToday(pkg) >= limit.seconds) keep.add(pkg) else guard.suspend(pkg, false)
        }
        store.setSuspended(keep)
    }

    private var closeTries = 0
    private var lastBlock = 0L
    private var lastSettingsHome = 0L
    private var overlay: LimitOverlay? = null

    private fun forgetOldDays(seen: android.content.SharedPreferences, today: String) {
        val stale = seen.all.keys.filter { !it.startsWith("$today|") }
        if (stale.isNotEmpty()) seen.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /**
     * The app's time is up and it's on screen: suspend it where we can, send it home, and cover the screen
     * with the reason. The cover is a window over everything, so a floating window can't get around it.
     */
    private suspend fun block(limit: Limit, guard: LockGuard, store: LimitStore, floating: Boolean, onScreen: Set<String>, small: android.graphics.Rect?) {
        val hard = guard.isOwner() && guard.suspend(limit.pkg, true)
        if (hard && limit.pkg !in store.suspended()) store.setSuspended(store.suspended() + limit.pkg)

        val cover = overlay
        if (guard.canOverlay() && cover != null) {
            // With the guard on, a small window is closed by LockIn itself (see below), so the person isn't left to
            // do it (and can't carry on watching while they get round to it).
            val selfClose = floating && com.godbless.lockin.lock.SettingsGuardService.running()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val fresh = !cover.showing
                if (fresh) {
                    // A full-screen app goes home at once, so it stops under the cover.
                    ScreenKinds.goHome(this@GuardService)
                    // Once it's out of sight, end what is left of it (a video playing behind the home screen).
                    if (!floating) scope.launch { delay(900); ScreenKinds.killBackground(this@GuardService, limit.pkg) }
                }
                cover.show(limit, hard, floating, selfClose)
                if (!selfClose) cover.coverWindow(null)
            }
            if (selfClose) {
                // Press its window's close button, or Back while it has the focus. About every second until it's gone.
                closeTries++
                com.godbless.lockin.lock.SettingsGuardService.closeApp(limit.pkg)
                // Still there after a few tries: this phone's window bar isn't one we can press. Put the cover over just
                // that window (following it if it's moved) so it can be closed by hand, everything else free.
                kotlinx.coroutines.withContext(Dispatchers.Main) { cover.coverWindow(if (closeTries > 4) small else null) }
            } else {
                closeTries = 0
            }
            // Settings and permission screens make Android hide our cover, which would leave the limited app (in a
            // small window beside them) free to use. Reaching one from a notification is the usual way in: Home.
            val nowMs = SystemClock.elapsedRealtime()
            if (onScreen.any { ScreenKinds.isSettings(it) } && nowMs - lastSettingsHome > 1_200L) {
                lastSettingsHome = nowMs
                ScreenKinds.goHome(this)
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 2_500) return
        lastBlock = now
        // With "display over other apps" off, LockIn's accessibility guard can still send the app away.
        com.godbless.lockin.lock.SettingsGuardService.goHome()
        run {
            // Without "display over other apps" Android won't let us open a screen from the background.
            Push.show(this, "limit_reached", "${limit.label} limit reached", "${limit.by} set ${durationText(limit.seconds)} a day. It starts again at midnight.", "")
        }
    }

    private companion object {
        const val Channel = "guard"
        const val NotificationId = 1002
        /** How long the cover stays over the home screen, unanswered, once the limited app is gone. */
        const val CoverGraceMs = 15_000L
    }
}
