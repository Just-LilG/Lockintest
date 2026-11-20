package com.godbless.lockin.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.MainActivity
import com.godbless.lockin.R
import com.godbless.lockin.data.AuthResult
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.RemoteLockRepository
import com.godbless.lockin.push.Push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Holds a partner's lock on this phone whether or not LockIn is open.
 *
 * While the lock runs it:
 * - covers every other app with a full-screen LockIn screen the moment you leave LockIn (home, recents,
 *   Settings, split screen, the lot), and counts each time as an escape your partner can see;
 * - keeps going with no signal, after LockIn is swiped away, and after a restart (see [LocalLock]);
 * - checks in with the server every 15 s, which is also how the lock learns it ended early;
 * - tells your partner if LockIn was stopped, if "display over other apps" or the device admin was switched
 *   off, or if the phone's clock was changed;
 * - on a device-owner phone, also blocks safe mode, factory reset, the clock, adb and uninstalling.
 *
 * What always stays open: phone calls (the cover steps aside while a call rings or is on), the emergency dialer
 * (which pauses the cover briefly and tells your partner), and the emergency release inside LockIn.
 */
class LockService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private lateinit var local: LocalLock
    private lateinit var guard: LockGuard

    // The cover window and the state it shows.
    private var cover: ComposeView? = null
    private val coverLeft = mutableIntStateOf(0)
    private val coverEscapes = mutableIntStateOf(0)
    private val coverBy = mutableStateOf("Your partner")
    private val coverCrew = mutableStateOf(false)
    private val coverFloating = mutableStateOf(false)
    private val coverAllowed = mutableStateOf<List<com.godbless.lockin.data.AllowedApp>>(emptyList())
    private var dialPauseUntil = 0L

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    // A clock that jumps while the lock runs is reported once.
    private var lastWall = 0L
    private var lastElapsed = 0L
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            if (lastWall != 0L && abs((wall - lastWall) - (elapsed - lastElapsed)) > 120_000L) warn("time_changed")
            lastWall = wall
            lastElapsed = elapsed
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        local = LocalLock(this)
        guard = LockGuard(this)
        lastWall = System.currentTimeMillis()
        lastElapsed = SystemClock.elapsedRealtime()
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ActionDial) {
            dialPauseUntil = SystemClock.elapsedRealtime() + 45_000L
            warn("emergency_dial", repeat = true)
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        hideCover()
        runCatching { unregisterReceiver(timeReceiver) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    // ----- The loop -----

    private suspend fun run() {
        val app = application as LockInApplication
        val partners = PartnerRepository(app.auth)
        val locks = RemoteLockRepository(partners)
        val crews = com.godbless.lockin.data.CrewRepository(partners)

        // LockIn was not running for a while during this lock (force-stopped, or the phone was off).
        val gap = local.gapMillis()
        local.noteBoot()
        if (local.active && local.takeSafeMode()) {
            // Restarted into safe mode, where LockIn can't run: tell the locker and count it as a try to leave.
            local.addEscape()
            warn("safe_mode|${(gap / 60_000L).coerceAtLeast(1)}")
        } else if (local.active && gap > 90_000L) warn("stopped|${(gap / 60_000L).coerceAtLeast(1)}", repeat = true)

        guard.hardenForLock(true)
        var beatAt = 0L
        var checkAt = 0L
        var sessionAt = 0L
        var coverWasUp = false
        var lastSettingsHome = 0L
        // How long another app has sat on screen under a cover that should be up (see below), and the last Home sent for it.
        var strangerSince = 0L
        var lastStrangerHome = 0L
        var guardFloatSince = 0L
        var lastStrangerLock = 0L
        var lastFloatClose = 0L
        var lastKeep = 0L
        // The allowed apps the other-apps hold (device owner) was last set for.
        var heldFor: Set<String>? = null
        // Whether LockIn was on screen last time round: leaving it is what counts as an escape.
        var wasInApp = Push.inForeground
        var wasInAllowed = false
        // Which apps are on screen. LockIn being open isn't enough: another app can float over it, or share the
        // screen with it, and that one must be covered too.
        val watcher = if (com.godbless.lockin.usage.UsageAccess.granted(this)) {
            com.godbless.lockin.usage.ForegroundWatcher(com.godbless.lockin.usage.UsageReader(this))
        } else {
            null
        }
        val allowed = allowedBesideLockIn()
        // Up to 3 apps your partner lets you keep using during their locks (not during a crew session).
        val allowedStore = AllowedStore(this)
        var allowedApps = allowedStore.load()
        var allowedAt = 0L
        // After leaving an allowed app for the home screen, LockIn is brought back (not counted as an escape).
        var backUntil = 0L

        while (scope.isActive) {
            if (!local.active) break
            local.beat()
            val now = SystemClock.elapsedRealtime()

            // The server decides: check in, learn the time left, or that the lock is over.
            if (now - beatAt > 15_000L) {
                beatAt = now
                val online = app.auth.hasSession() || (now - sessionAt > 60_000L && trySignIn(app).also { sessionAt = now })
                if (online) {
                    // Per device, so the locker sees which of this person's devices are holding the lock.
                    // A crew session (negative id) checks in with the crew instead: is my session still on?
                    val r = if (local.id < 0L) {
                        withContext(Dispatchers.IO) { crews.beat() }.map { b ->
                            if (b.state == "active" && b.id == -local.id) b.remaining else null
                        }
                    } else {
                        withContext(Dispatchers.IO) { locks.beat(0, local.escapes, com.godbless.lockin.data.ThisDevice.id(this@LockService)) }
                    }
                    // Only the person this lock belongs to is believed. Someone else signed in on this phone gets
                    // an answer about their own (empty) locks, which says nothing about this one.
                    val me = app.auth.userId()
                    val ownerOk = local.owner == null || me == null || me == local.owner
                    r.takeIf { ownerOk }?.onSuccess { remaining ->
                        if (remaining == null) {
                            Log.i(TAG, "server says the lock is over")
                            local.clear()
                            app.lockPing.value = app.lockPing.value + 1
                        } else {
                            val drift = kotlin.math.abs(local.remainingMillis() / 1000L - remaining)
                            local.set(local.id, remaining, local.by, me)
                            // Extended, or the clock was off: show the right countdown.
                            if (drift > 5) refreshNotification()
                        }
                    }
                    flushWarnings(partners)
                    // The allowed apps, now and then, in case your partner changed them mid-lock.
                    if (local.id > 0L && now - allowedAt > 60_000L) {
                        allowedAt = now
                        withContext(Dispatchers.IO) { locks.allowed() }.onSuccess { allowedStore.save(it.mine) }
                    }
                }
            }
            allowedApps = if (local.id > 0L) allowedStore.load() else emptyList()
            coverAllowed.value = allowedApps
            // Device owner: every other app is suspended for the lock, so none of them can open at all (no floating
            // window, no split screen). Set again whenever your partner changes the allowed apps.
            if (local.id > 0L) {
                val keepNow = allowedApps.map { it.pkg }.toSet()
                if (keepNow != heldFor) {
                    heldFor = keepNow
                    guard.holdOthers(keepNow + allowed + HelperPkgs)
                }
            }
            if (!local.active) break

            // Something switched off what holds the lock: tell the locker (once each).
            if (now - checkAt > 5_000L) {
                checkAt = now
                // The guard stopped (a restart, or the phone's battery manager) or was switched off: switch it back on.
                if (!SettingsGuardService.running() && now - lastKeep > 10_000L) {
                    lastKeep = now
                    GuardKeeper.ensure(this)
                }
                if (!guard.canOverlay()) warn("overlay_off")
                if (!guard.isAdmin() && !guard.isOwner() && local.alreadySent("admin_seen")) warn("admin_off")
                if (guard.isAdmin() || guard.isOwner()) local.markSent("admin_seen")
                // App-time access and LockIn's guard: switched off after having been on.
                if (!com.godbless.lockin.usage.UsageAccess.granted(this) && local.alreadySent("usage_seen")) warn("usage_off")
                if (com.godbless.lockin.usage.UsageAccess.granted(this)) local.markSent("usage_seen")
                if (Protection.on(this) && Protection.guardSeen(this) && !Protection.guardEnabled(this)) warn("guard_off")
            }

            // Cover other apps whenever LockIn itself isn't on screen, or another app is on screen beside it
            // (a floating window or split screen) that came up after LockIn did.
            // An allowed app on screen (and nothing else but the home screen or phone) needs no cover.
            val allowedPkgs = allowedApps.map { it.pkg }.toSet()
            // Just opened one (from the lock page or the cover): the app takes a moment to come up. Until it does,
            // neither the cover nor an escape.
            val opening = now < allowedOpenAt + 4_000L
            // Screens an allowed app opens for itself (share sheet, file and photo pickers, permission prompts,
            // Google sign-in) count as part of it, but only straight after it.
            val helpers = if (wasInAllowed || opening) HelperPkgs else emptySet()
            var floating = false
            var inAllowed = false
            var bareHome = false
            if (watcher != null) runCatching {
                watcher.poll()
                val visible = watcher.onScreen.filter { it != packageName && it !in allowed }
                val core = visible.filter { it !in helpers }
                val mine = watcher.resumedAt(packageName)
                floating = Push.inForeground && core.any { it !in allowedPkgs && watcher.resumedAt(it) >= mine }
                inAllowed = !Push.inForeground && visible.isNotEmpty() && core.all { it in allowedPkgs }
                bareHome = visible.isEmpty()
            }
            // An allowed app is judged from the usage log alone, which needs app-time access and can lag behind the app
            // coming up: with it off or late, the lock saw "no allowed app on screen", covered it and sent you back to
            // LockIn, which looks like the app closing. The guard's exact window list settles it when it is on.
            if (!inAllowed && !Push.inForeground) {
                val ws2 = SettingsGuardService.appWindows()
                if (ws2 != null) {
                    val vis = ws2.map { it.pkg }.filter { it.isNotEmpty() && it != packageName && it != "android" && it !in allowed }
                    if (vis.isNotEmpty() && vis.filter { it !in helpers }.all { it in allowedPkgs }) {
                        inAllowed = true
                        bareHome = false
                    }
                }
            }
            // LockIn is "in front" but may only be a small floating window (Xiaomi/HyperOS small window, freeform, split
            // screen) with another app full screen behind it, fully usable. The usage log can miss that (it needs
            // app-time access, and judges by who resumed last), so the guard's exact window list decides too: LockIn
            // not filling the screen, or any other non-allowed app window beside it, means the cover is wanted.
            // Not while an allowed app is coming up (LockIn's window is mid-animation and looks small), not while an
            // allowed app fills the screen, and only once it has held for 1.5 s.
            var guardFloat = false
            if (Push.inForeground && !opening && now >= backUntil) {
                val ws = SettingsGuardService.appWindows()
                if (ws != null && ws.none { it.pkg in allowedPkgs && it.full }) {
                    val smallMine = ws.any { it.pkg == packageName } && ws.none { it.pkg == packageName && it.full }
                    val beside = ws.any {
                        it.pkg.isNotEmpty() && it.pkg != packageName && it.pkg != "android" && !it.pip &&
                            it.pkg !in allowed && it.pkg !in allowedPkgs && it.pkg !in helpers
                    }
                    guardFloat = smallMine || beside
                }
            }
            if (guardFloat) {
                if (guardFloatSince == 0L) guardFloatSince = now
                if (now - guardFloatSince > 1_500L) floating = true
            } else {
                guardFloatSince = 0L
            }
            // Left an allowed app for the home screen: that's just finishing with it. Back to LockIn's lock page.
            if (wasInAllowed && !inAllowed && bareHome && !Push.inForeground && !opening) {
                backUntil = now + 2_500L
                runCatching {
                    packageManager.getLaunchIntentForPackage(packageName)?.let {
                        startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
            }
            val settling = (opening || now < backUntil) && !floating
            coverFloating.value = floating
            // A call steps the cover aside so it can be answered, but only while nothing else is on screen:
            // ringing your own number and then opening a game must not switch the cover off.
            // The emergency-dialer pause works the same way: it used to switch the cover off for a flat 45 s, so
            // tapping "Emergency call" and then opening any app gave 45 s of free use (and the button is always there to
            // do it again). Now both steps aside only while nothing but the phone app / home screen is up.
            val pauseActive = inCall() || now <= dialPauseUntil
            val stranger = pauseActive && strangerOnScreen(watcher, allowed, allowedPkgs)
            val coverWanted = ((!Push.inForeground && !inAllowed) || floating) && !settling && !(pauseActive && !stranger) && guard.screenOn()
            // With "display over other apps" off, the guard (an accessibility window) draws the cover instead.
            val wantCover = coverWanted && guard.canOverlay()
            val wantFallback = coverWanted && !guard.canOverlay() && SettingsGuardService.running()
            if (coverWanted && !coverWasUp && (wasInApp || wasInAllowed) && !settling) {
                coverEscapes.intValue = local.addEscape()
                refreshNotification()
            } else if (coverEscapes.intValue != local.escapes) {
                coverEscapes.intValue = local.escapes
            }
            wasInApp = Push.inForeground
            // Through a hand-off (opening an app, coming back) remember where we were, so the next round judges right.
            wasInAllowed = inAllowed || (wasInAllowed && settling)
            coverLeft.intValue = (local.remainingMillis() / 1000L).toInt()
            coverBy.value = local.by
            coverCrew.value = local.id < 0L
            // Android hides our cover on Settings and permission screens. Opened from a notification, one of those
            // would leave an app in a small window (or Settings itself) usable: go Home.
            if (coverWanted && now - lastSettingsHome > 1_200L) {
                val wins = SettingsGuardService.appWindows()
                val up = if (wins != null && wins.isNotEmpty()) wins.map { it.pkg } else runCatching { watcher?.onScreen?.toList() }.getOrNull().orEmpty()
                val settingsUp = up.any { com.godbless.lockin.usage.ScreenKinds.isSettings(it) }
                val otherApp = up.any { it != packageName && it !in allowed && it !in allowedPkgs && !com.godbless.lockin.usage.ScreenKinds.isSettings(it) }
                if (settingsUp && (wins != null || otherApp || watcher?.current?.let { com.godbless.lockin.usage.ScreenKinds.isSettings(it) } == true)) {
                    lastSettingsHome = now
                    com.godbless.lockin.usage.ScreenKinds.goHome(this)
                }
            }
            // The cover is only a window drawn over the app, and Android can hide it: Android 12+ lets any app ask for
            // "hide overlay windows" while it is on screen, and some system and OEM screens do it too. Our own
            // bookkeeping would still say "cover up", so that app would stay usable. If another app stays on screen while
            // the cover should be up, send the person Home (which also stops it running behind the cover).
            if (coverWanted && strangerOnScreen(watcher, allowed, allowedPkgs)) {
                if (strangerSince == 0L) {
                    strangerSince = now
                } else if (now - strangerSince > 2_500L && now - lastStrangerHome > 3_000L) {
                    lastStrangerHome = now
                    com.godbless.lockin.usage.ScreenKinds.goHome(this)
                }
                // A small window of another app: try to close it the way a person would (its frame's close button, or Back).
                // Home alone doesn't remove a floating window on some phones (HyperOS small window, freeform).
                if (floating && now - lastFloatClose > 1_200L) {
                    lastFloatClose = now
                    SettingsGuardService.appWindows()
                        ?.filter { !it.full && !it.pip && it.pkg.isNotEmpty() && it.pkg != packageName && it.pkg != "android" && it.pkg !in allowed && it.pkg !in allowedPkgs }
                        ?.forEach { SettingsGuardService.closeApp(it.pkg) }
                }
                // Still there after Home was sent several times: lock the screen. Unlocking brings the cover straight back.
                if (now - strangerSince > 9_000L && now - lastStrangerLock > 20_000L) {
                    lastStrangerLock = now
                    if (!guard.lockScreenNow()) SettingsGuardService.lockScreen()
                }
            } else {
                strangerSince = 0L
            }
            if (wantCover) showCover() else hideCover()
            // The guard's own cover is an accessibility window, which sits above the app-overlay cover AND above the
            // floating windows some phones draw over it. So it goes up as well whenever a small window is open, or
            // another app has stayed usable under the normal cover for a second, as well as when "display over other
            // apps" is off. This is what stops a floating window from being used through the lock.
            val strangerHeld = strangerSince != 0L && now - strangerSince > 1_000L
            val a11yCover = coverWanted && SettingsGuardService.running() && (wantFallback || floating || strangerHeld)
            if (a11yCover) SettingsGuardService.showLockCover(local.by, coverLeft.intValue) else SettingsGuardService.hideLockCover()
            coverWasUp = coverWanted

            // Waits for the next round, but wakes at once when the guard sees a window open, close or move.
            withTimeoutOrNull(if (wantCover || wantFallback) 500L else 1_000L) { wake.receive() }?.let { delay(250L) }
        }

        // The lock is over.
        hideCover()
        SettingsGuardService.hideLockCover()
        guard.hardenForLock(false)
        flushWarnings(partners)
        stopSelf()
    }

    private suspend fun trySignIn(app: LockInApplication): Boolean {
        if (Push.inForeground) return false
        if (app.auth.savedUser() == null) return false
        return withContext(Dispatchers.IO) { app.auth.restore() } is AuthResult.SignedIn
    }

    /**
     * Another app (not LockIn, the home screen, the phone app or an allowed app) is on screen. Read from the usage log
     * when app-time access is on, and from the guard's window list when the guard is on: either one is enough, so
     * switching off one of the two doesn't blind this.
     */
    private fun strangerOnScreen(
        watcher: com.godbless.lockin.usage.ForegroundWatcher?,
        allowed: Set<String>,
        allowedPkgs: Set<String>,
    ): Boolean {
        fun other(p: String) = p.isNotEmpty() && p != packageName && p != "android" && p !in allowed && p !in allowedPkgs
        // The guard's window list is exact; the usage log lags and keeps stale entries. So the guard decides when it can,
        // and the usage log only when the guard is off or could not read a window's package.
        val wins = SettingsGuardService.appWindows()
        if (wins != null) {
            if (wins.any { other(it.pkg) && !it.pip }) return true
            if (wins.none { it.pkg.isEmpty() }) return false
        }
        return watcher != null && runCatching { watcher.onScreen.any { other(it) } }.getOrDefault(false)
    }

    /** A phone call is ringing or on: the cover steps aside so it can be answered. */
    private fun inCall(): Boolean {
        val mode = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    // ----- Warnings to the locker -----

    private fun warn(kind: String, repeat: Boolean = false) {
        // Warnings go to the partner who locked you. A crew session you joined yourself has nobody to warn.
        if (local.id < 0L) return
        val key = kind.substringBefore('|')
        // A protection switched off for real voids the lock: no session is written for it (the server does the same).
        if (key in VoidKinds) local.markVoided()
        if (repeat) {
            // Things that can happen again (LockIn was stopped again, the emergency dialer used again) are told each
            // time, up to a few. Once per lock hid every one after the first: stop LockIn, wait, repeat.
            if (local.bump(key) > MaxRepeatWarnings) return
        } else {
            if (local.alreadySent(key)) return
            local.markSent(key)
        }
        local.enqueue("lock_tamper|$kind")
        Log.i(TAG, "warning queued: $kind")
    }

    private suspend fun flushWarnings(partners: PartnerRepository) {
        for (code in local.queued()) {
            // lock_tamper|<kind>|<detail>: the server checks a lock is really on this phone before telling the locker.
            val parts = code.split('|')
            val kind = parts.getOrNull(1) ?: continue
            withContext(Dispatchers.IO) { partners.lockTamper(kind, parts.getOrNull(2).orEmpty()) }
                .onSuccess { local.dequeue(code) }
        }
    }

    // ----- The cover -----

    /** Opens an app your partner allowed during this lock. */
    private fun openAllowed(pkg: String) {
        if (coverAllowed.value.none { it.pkg == pkg }) return
        allowedOpenAt = SystemClock.elapsedRealtime()
        runCatching {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hideCover()
            }
        }
    }

    /** Apps that may share the screen with LockIn during a lock: the home screen and the phone app. */
    private fun allowedBesideLockIn(): Set<String> {
        val out = HashSet<String>()
        runCatching {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName?.let { out.add(it) }
        }
        runCatching { getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage?.let { out.add(it) } }
        out.add("com.android.systemui")
        // The call screen is a separate app on many phones (and the emergency dialer another): with the stricter
        // "step aside only while nothing else is up" rule, a call must never be taken for "another app".
        out.addAll(listOf(
            "com.android.incallui", "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.incallui", "com.samsung.android.dialer",
        ))
        return out
    }

    private fun showCover() {
        if (cover != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Tic-tac-toe with the partner who holds this lock, for a break. Not offered during a crew session.
        val ttt = if (local.id > 0L) com.godbless.lockin.data.TttRepository(PartnerRepository((application as LockInApplication).auth)) else null
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LockService)
            setViewTreeSavedStateRegistryOwner(this@LockService)
            setContent {
                LockCover(
                    by = coverBy.value,
                    crew = coverCrew.value,
                    floating = coverFloating.value,
                    allowed = coverAllowed.value,
                    onOpenApp = { openAllowed(it) },
                    secondsLeft = coverLeft.intValue,
                    escapes = coverEscapes.intValue,
                    onBack = { openLockIn() },
                    onEmergencyCall = { emergencyDial() },
                    game = ttt,
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Reach under the navigation bar too (Android 11+ keeps overlay windows above it otherwise, leaving a
            // strip of whatever is behind). The content pads itself away from the bars.
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (added) {
            cover = view
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun hideCover() {
        val view = cover ?: return
        cover = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    private fun openLockIn() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** Opens the dialer for an emergency call. The cover steps aside for 45 s (a call keeps it aside) and the locker is told. */
    private fun emergencyDial() {
        dialPauseUntil = SystemClock.elapsedRealtime() + 45_000L
        warn("emergency_dial", repeat = true)
        hideCover()
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startAsForeground(): Boolean = try {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(Channel) == null) {
            manager.createNotificationChannel(
                NotificationChannel(Channel, "Partner lock", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while your partner's lock holds this phone"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = lockNotification(open)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, NotificationId, notification, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not start in the foreground: ${e.message}")
        false
    }

    /**
     * The ongoing "Locked by …" notification. Android counts the time down by itself (a chronometer to the end),
     * and it shows when the phone opens again.
     */
    private fun lockNotification(open: PendingIntent): android.app.Notification {
        val l = LocalLock(this)
        val endsAt = System.currentTimeMillis() + l.remainingMillis()
        val at = java.time.Instant.ofEpochMilli(endsAt).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()))
        return NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_stat_lockin)
            .setColor(0xFFC6F432.toInt())
            .setColorized(true)
            .setLargeIcon(com.godbless.lockin.push.Push.avatar(l.by))
            .setContentTitle(if (l.id < 0L) "Locked in with ${l.by}" else "Locked by ${l.by}")
            .setContentText("Your phone opens again at $at.")
            .setSubText(if (l.escapes > 0) "${l.escapes} tries to leave" else null)
            .setWhen(endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    /** Re-shows the countdown after the server corrected the time left. */
    private fun refreshNotification() {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        com.godbless.lockin.push.NotificationManagerCompatSafe.notify(this, NotificationId, lockNotification(open))
    }

    companion object {
        private const val TAG = "LockInLock"
        private const val Channel = "partner_lock"
        private const val NotificationId = 1003

        /** How many times one repeatable warning (stopped, emergency dialer) is told to the locker in a single lock. */
        private const val MaxRepeatWarnings = 5
        internal const val ActionDial = "com.godbless.lockin.LOCK_DIAL"

        /** The guard taps this when a window changes, so the lock looks right away (conflated: many taps are one). */
        private val wake = Channel<Unit>(Channel.CONFLATED)

        internal fun nudge() {
            wake.trySend(Unit)
        }

        /** When an allowed app was last opened (elapsed realtime): the cover holds back while it comes up. */
        @Volatile
        internal var allowedOpenAt = 0L

        /** System screens an allowed app may open for itself: share sheets, pickers, permission prompts, Google sign-in. */
        private val HelperPkgs = setOf(
            "android", "com.android.intentresolver",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui",
            "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.google.android.gms", "com.google.android.gsf",
        )

        /**
         * Opens [pkg], one of [apps] your partner allows during this lock, from LockIn's lock page. The lock is told the
         * list first (so it never covers an app the page shows) and holds the cover back while the app comes up.
         * False if it can't be opened.
         */
        fun openAllowedApp(context: Context, pkg: String, apps: List<com.godbless.lockin.data.AllowedApp>): Boolean {
            if (apps.none { it.pkg == pkg }) return false
            if (apps.isNotEmpty()) AllowedStore(context).save(apps)
            val launch = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return false
            allowedOpenAt = SystemClock.elapsedRealtime()
            return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }

        /** The warnings that void a lock (must match _void_kind on the server). "Went quiet" and the emergency dialer don't. */
        private val VoidKinds = setOf("admin_off", "overlay_off", "usage_off", "guard_off", "safe_mode", "time_changed")

        /**
         * The emergency call button on the lock page: the cover steps aside for the call (as it does from the cover
         * itself), your partner is told the dialer was opened, and the dialer opens.
         */
        fun dial(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LockService::class.java).setAction(ActionDial))
            }
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        /** Starts the service if a partner lock is active on this phone. Safe to call often. */
        fun sync(context: Context) {
            if (!LocalLock(context).active) {
                // A lock that ran out while LockIn wasn't running: make sure nothing it shut stays shut.
                LockGuard(context).hardenForLock(false)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LockService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the lock service: ${it.message}") }
        }

        /** A lock is running with [remainingSeconds] left (from the server, or a push). Remember it and hold it. */
        fun hold(context: Context, lockId: Long, remainingSeconds: Int, by: String) {
            if (remainingSeconds <= 0) return
            val l = LocalLock(context)
            val fresh = !l.active || l.id != lockId
            l.set(lockId, remainingSeconds, by, signedInId(context))
            sync(context)
            if (fresh) redrawWidgets(context)
        }

        /** Who is signed in on this phone (or, with no session yet after a restart, who was last known to be). */
        internal fun signedInId(context: Context): String? =
            (context.applicationContext as? LockInApplication)?.auth?.userId() ?: Protection.owner(context)

        /** The home-screen widgets show a lock's countdown: redraw them when one starts or ends. */
        private fun redrawWidgets(context: Context) {
            val app = context.applicationContext
            Thread { runCatching { com.godbless.lockin.widget.LockInWidgets.refreshAll(app) } }.start()
        }

        /**
         * The partner lock ended (time, unlocked, emergency release). The service sees this within a second and
         * stops. A crew session on the phone is left alone: it ends with [endCrew].
         */
        fun end(context: Context) {
            val l = LocalLock(context)
            if (l.id < 0L) return
            val was = l.active
            l.clear()
            if (was) redrawWidgets(context)
        }

        /**
         * You're in crew session [sessionId] with [remainingSeconds] left: hold it like a lock. A partner lock
         * already on the phone wins (it's stricter), so the crew session doesn't replace it.
         */
        fun holdCrew(context: Context, sessionId: Long, remainingSeconds: Int, crewName: String) {
            if (remainingSeconds <= 0 || sessionId <= 0L) return
            val l = LocalLock(context)
            if (l.active && l.id > 0L) return
            l.set(-sessionId, remainingSeconds, crewName, signedInId(context))
            sync(context)
            redrawWidgets(context)
        }

        /** The crew session is over for you (it finished, or you left early). */
        fun endCrew(context: Context) {
            val l = LocalLock(context)
            if (l.id < 0L && l.active) { l.clear(); redrawWidgets(context) } else if (l.id < 0L) l.clear()
        }

        /** The crew session this phone is holding, or 0. */
        fun crewSessionHeld(context: Context): Long {
            val l = LocalLock(context)
            return if (l.active && l.id < 0L) -l.id else 0L
        }
    }
}
