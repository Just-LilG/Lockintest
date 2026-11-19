package com.godbless.lockin.lock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.MainActivity
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.ProtectRepository
import com.godbless.lockin.ui.ThemeState
import com.godbless.lockin.usage.UsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LockIn's guard. While this phone is paired it watches the Settings screens (and the uninstall dialog) that
 * would take LockIn's protection off, and when one opens without the partner's OK it sends the person Home and
 * covers the screen with "Ask <partner>". Nothing leaves the phone: it only looks for a few phrases on screen.
 *
 * It is also the second way to cover a locked phone: with "display over other apps" switched off, LockIn's own
 * cover cannot be drawn, so [showLockCover] draws one from here (an accessibility window needs no permission),
 * and the screens that revoke the permission are held back like the others.
 *
 * Android 13+ keeps "restricted settings" on for apps installed outside a store: if the switch for this service
 * is greyed out, App info > the three dots > Allow restricted settings (once).
 *
 * Cover first, look second. A page about LockIn used to be recognised ~200 ms after it opened (events were batched, then a
 * 180 ms wait for the screen to settle), which a fast tap beats. Now:
 *  - a page that is opening and names LockIn (or is a device-admin / uninstall dialog) gets a plain shield over it the moment
 *    Android reports it; the look at what is on it follows at once, and the shield goes if it turns out to be harmless;
 *  - the look is by name and by shape (class of the page, a page about LockIn with one or two switches, one on), not only
 *    by Android's English wording, so it holds in any language;
 *  - a poll twice a second looks again while a Settings screen is up, in case an event was late or missing;
 *  - separately, [ProtectionWatch] notices whatever still gets switched off and tells the partner.
 */
class SettingsGuardService : AccessibilityService() {
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null

    private var shield: View? = null
    private var shieldAt = 0L
    private var scanQueued = false
    private var lastScanAt = 0L
    private var pollTicks = 0
    // The newest window the guard saw open in a Settings-like package (its package and class name).
    private var winPkg = ""
    private var winClass = ""
    // The package of the newest window that opened (any app). While it isn't a Settings-like one there is nothing to hold
    // back, so the poll looks at the foreground app only now and then instead of twice a second.
    @Volatile private var topPkg = ""
    // Package by window id, so the window list is read without asking every app for its root view each time.
    private val winPkgs = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private var block: View? = null
    private var blockKind: String? = null
    private var blockStatus: TextView? = null
    private var blockAsk: Button? = null
    private var askJob: Job? = null

    private var lock: View? = null
    private var lockTitle: TextView? = null
    private var lockTime: TextView? = null

    private val scan = Runnable { scanQueued = false; scanNow() }
    private val dropShield = Runnable { removeShield() }
    private val poll = object : Runnable {
        override fun run() {
            if (instance !== this@SettingsGuardService) return
            runCatching { pollOnce() }
            ui.postDelayed(this, PollMs)
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Protection.markGuardSeen(this)
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, PollMs)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // A window opened, closed, moved or resized (a floating window, split screen): wake the lock service now
        // instead of waiting for its next round, so a cover is up before the other app can be used.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            LockService.nudge()
            return
        }
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) topPkg = pkg
        if (pkg == packageName || !watched(pkg)) return
        if (!Protection.on(this)) return
        val opened = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (opened) {
            winPkg = pkg
            winClass = e.className?.toString().orEmpty()
        }
        // A page that is opening and names LockIn (or is a device-admin or uninstall screen): shield first, look second.
        if (opened && looksHeldBack(e)) showShield()
        scanBurst()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ui.removeCallbacks(poll)
        ui.removeCallbacks(scan)
        removeShield()
        removeBlock()
        removeLockCover()
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ----- Looking at the screen -----

    private fun watched(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == "android" || "settings" in p || "packageinstaller" in p || "permissioncontroller" in p ||
            "phonemaster" in p || "securitycenter" in p || "safecenter" in p || "appmanager" in p || "systemmanager" in p ||
            // Phone makers' battery and "device care" screens, where an app can be put to sleep or force-stopped:
            // Samsung Device care, Oppo/Realme/OnePlus phone manager and battery, Vivo/iQOO, Xiaomi power keeper.
            "phonemanager" in p || "lool" in p || "sm_cn" in p || "devicecare" in p || "iqoo.secure" in p ||
            "vivo.abe" in p || "oplus.battery" in p || "powerkeeper" in p
    }

    /** The page's own title or class says it is about LockIn, or it is a device-admin or uninstall screen. */
    private fun looksHeldBack(e: AccessibilityEvent): Boolean {
        val said = buildString {
            e.text?.forEach { append(it).append(' ') }
            e.contentDescription?.let { append(it) }
        }.lowercase()
        if ("lockin" in said) return true
        val cls = e.className?.toString().orEmpty().lowercase()
        return "deviceadmin" in cls || "uninstall" in cls
    }

    /** Looks now if the last look was a moment ago, otherwise shortly after it: a burst of events costs one look. */
    private fun scanBurst() {
        val since = SystemClock.uptimeMillis() - lastScanAt
        if (since >= ScanGapMs) {
            ui.removeCallbacks(scan)
            scanQueued = false
            scanNow()
        } else {
            scanSoon(ScanGapMs - since)
        }
    }

    private fun scanSoon(inMs: Long = 40L) {
        if (scanQueued) return
        scanQueued = true
        ui.postDelayed(scan, inMs.coerceAtLeast(10L))
    }

    /** Twice a second: the guard's own look if no event has made one lately, and the protection watch every few seconds. */
    private fun pollOnce() {
        pollTicks++
        if (Protection.on(this) && pollTicks % 6 == 0) {
            ProtectionWatch.check(this)
            val app = applicationContext as? LockInApplication
            if (pollTicks % 30 == 0 && app != null && app.auth.hasSession() && ProtectionWatch.queued(this).isNotEmpty()) {
                scope.launch(Dispatchers.IO) { ProtectionWatch.flush(this@SettingsGuardService, PartnerRepository(app.auth)) }
            }
        }
        if (block != null || !Protection.on(this)) return
        val awake = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive != false
        if (!awake) return
        // Asking the foreground app for its root view is a call into that app: twice a second only while a Settings-like
        // page could be up, every three seconds otherwise.
        if (topPkg.isNotEmpty() && !watched(topPkg) && pollTicks % 6 != 0) return
        val pkg = peekPackage() ?: return
        if (pkg != packageName && watched(pkg) && SystemClock.uptimeMillis() - lastScanAt > 1_200L) scanNow()
    }

    private fun peekPackage(): String? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val pkg = root.packageName?.toString()
        if (android.os.Build.VERSION.SDK_INT < 33) runCatching { root.recycle() }
        return pkg
    }

    private fun scanNow() {
        lastScanAt = SystemClock.uptimeMillis()
        if (block != null || !Protection.on(this)) {
            removeShield()
            return
        }
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            // The page hasn't been drawn yet: keep the shield up and look again in a moment.
            if (shield != null) scanSoon()
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName) {
            removeShield()
            return
        }
        val seen = collect(root)
        val kind = classify(seen, if (pkg == winPkg) winClass.lowercase() else "")
        if (kind == null || !Protection.required(this, kind)) {
            // A page that has barely drawn can't be judged yet: hold the shield a little longer.
            if (shield != null && kind == null && seen.text.length < 60 && SystemClock.uptimeMillis() - shieldAt < 700L) {
                scanSoon()
                return
            }
            removeShield()
            return
        }
        // Home first, so the held-back screen is out of the way, then the cover.
        performGlobalAction(GLOBAL_ACTION_HOME)
        showBlock(kind)
        removeShield()
    }

    /** What is on a screen: all its text (lower-cased), how many switches it has and how many of them are on. */
    private class Seen(val text: String, val switches: Int, val checked: Int)

    private fun collect(root: AccessibilityNodeInfo): Seen {
        val sb = StringBuilder()
        var budget = 700
        var switches = 0
        var checked = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || budget-- <= 0) return
            n.text?.let { sb.append(it).append('\n') }
            n.contentDescription?.let { sb.append(it).append('\n') }
            if (n.isCheckable) {
                switches++
                if (n.isChecked) checked++
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull())
        }
        walk(root)
        return Seen(sb.toString().lowercase(), switches, checked)
    }

    /**
     * Which held-back thing this screen is, or null. First by Android's English wording (which says exactly which thing it
     * is), then by LockIn's name and the shape of the page, which works in any language.
     */
    private fun classify(s: Seen, cls: String): String? {
        classifyEnglish(s.text)?.let { return it }
        if ("lockin" !in s.text) return null
        // The device-admin page of an admin that is on, and the uninstall dialog and App info, are recognised by their class.
        if ("deviceadmin" in cls && LockGuard(this).isAdmin()) return Protection.Admin
        if ("uninstall" in cls) return Protection.Uninstall
        if ("installedappdetails" in cls || "appinfo" in cls || "appdetails" in cls) return Protection.Uninstall
        // A page about LockIn with one or two switches, one of them on, while something LockIn needs is on: whichever of
        // "display over other apps", "usage access" or the guard it is, it is held back (asking the partner for "this").
        val needed = Settings.canDrawOverlays(this) || UsageAccess.granted(this) || Protection.guardEnabled(this)
        if (needed && s.switches in 1..2 && s.checked >= 1 && "notification" !in cls) return Protection.Other
        return null
    }

    // ----- The shield: up before the look, so there is nothing to tap while it happens -----

    private fun showShield() {
        val w = wm ?: return
        if (shield != null || block != null || !Protection.on(this)) return
        val v = View(this).apply { setBackgroundColor(0xFF111113.toInt()) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        shield = v
        shieldAt = SystemClock.uptimeMillis()
        if (runCatching { w.addView(v, lp) }.isFailure) {
            shield = null
        } else {
            // Never leave it up: whatever the look finds (or misses), it is gone in a second and a half.
            ui.removeCallbacks(dropShield)
            ui.postDelayed(dropShield, 1_500L)
        }
    }

    private fun removeShield() {
        ui.removeCallbacks(dropShield)
        shield?.let { v -> runCatching { wm?.removeView(v) } }
        shield = null
    }

    /** Which held-back thing this screen is, or null. Phrases are Android's English wording. */
    private fun classifyEnglish(t: String): String? {
        val app = "lockin" in t
        // The guard's own switch: only its detail page carries the service's description.
        if ("needs your partner's ok" in t && Protection.guardEnabled(this)) return Protection.Guard
        if (!app) {
            if (LocalLock(this).active && "developer options" in t && ("usb debugging" in t || "wireless debugging" in t)) return Protection.Other
            return null
        }
        if (("device admin" in t || "device administrator" in t) && "deactivate" in t) return Protection.Admin
        if ("uninstall" in t || "force stop" in t || "clear data" in t || "clear storage" in t || "app info" in t && "storage" in t) return Protection.Uninstall
        val overlayPage = "allow display over other apps" in t || "allow appearing on top" in t ||
            "allow drawing over other apps" in t || "permit drawing over other apps" in t
        if (overlayPage && Settings.canDrawOverlays(this)) return Protection.Overlay
        val usagePage = "permit usage access" in t || "allow usage access" in t || "allow usage tracking" in t
        if (usagePage && UsageAccess.granted(this)) return Protection.Usage
        return null
    }

    // ----- The "ask" cover -----

    private fun accent(): Int = runCatching { ThemeState.accent.dark.toInt() }.getOrDefault(0xFFC6F432.toInt())
    private fun onAccent(): Int = runCatching { ThemeState.accent.onDark.toInt() }.getOrDefault(0xFF0B0B0C.toInt())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun pill(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(26f).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1.5f), stroke)
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, fill: Int, ink: Int, stroke: Int = 0): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ink)
        background = pill(fill, stroke)
        stateListAnimator = null
        minHeight = dp(52f)
        setPadding(dp(18f), 0, dp(18f), 0)
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xF2111113.toInt())
        setPadding(dp(28f), dp(40f), dp(28f), dp(40f))
    }

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun showBlock(kind: String) {
        val w = wm ?: return
        if (block != null) return
        val partner = Protection.partner(this)
        val col = column()
        col.addView(label("$partner has to say yes first", 24f, Color.WHITE, bold = true), lp())
        col.addView(label("To ${Protection.phrase(kind)}, $partner has to allow it. You get ten minutes once they do.", 15f, 0xFFBDBDC4.toInt()), lp(12f))
        val status = label("", 14f, 0xFFA6A6AE.toInt())
        col.addView(status, lp(14f))
        val ask = button("Ask $partner", accent(), onAccent())
        col.addView(ask, lp(22f))
        val never = button("Never mind", 0, Color.WHITE, 0xFF3A3A40.toInt())
        col.addView(never, lp(10f))
        ask.setOnClickListener { startAsk(kind) }
        never.setOnClickListener { removeBlock() }
        block = col
        blockKind = kind
        blockStatus = status
        blockAsk = ask
        runCatching { w.addView(col, params()) }.onFailure { block = null }
    }

    private fun removeBlock() {
        askJob?.cancel()
        askJob = null
        block?.let { v -> runCatching { wm?.removeView(v) } }
        block = null
        blockKind = null
        blockStatus = null
        blockAsk = null
    }

    private fun startAsk(kind: String) {
        if (askJob?.isActive == true) return
        val partner = Protection.partner(this)
        val app = applicationContext as LockInApplication
        val repo = ProtectRepository(PartnerRepository(app.auth))
        askJob = scope.launch {
            blockAsk?.isEnabled = false
            blockStatus?.text = "Asking $partner..."
            if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
            val first = repo.ask(kind).getOrNull()
            if (first == null) {
                blockStatus?.text = "No connection. This stays as it is until $partner can say yes."
                blockAsk?.isEnabled = true
                return@launch
            }
            fun allowed(left: Int) {
                Protection.open(this@SettingsGuardService, kind, left.coerceAtLeast(60))
                blockStatus?.text = "Allowed. You have ten minutes. Open Settings again."
                ui.postDelayed({ removeBlock() }, 2500)
            }
            when (first.state) {
                "none" -> { Protection.clear(this@SettingsGuardService); removeBlock(); return@launch }
                "allowed" -> { allowed(first.left); return@launch }
                "declined" -> {
                    blockStatus?.text = "$partner said not yet. Try again in ${(first.left + 59) / 60} min."
                    blockAsk?.isEnabled = true
                    return@launch
                }
            }
            blockStatus?.text = "Asked. Waiting for $partner..."
            repeat(200) {
                delay(3_000)
                val r = repo.status(first.id).getOrNull() ?: return@repeat
                when (r.state) {
                    "allowed" -> { allowed(r.left); return@launch }
                    "declined" -> { blockStatus?.text = "$partner said not yet."; blockAsk?.isEnabled = true; return@launch }
                    "expired" -> { blockStatus?.text = "That request ran out. Ask again."; blockAsk?.isEnabled = true; return@launch }
                }
            }
            blockStatus?.text = "No answer yet. Ask again when $partner is around."
            blockAsk?.isEnabled = true
        }
    }

    // ----- Cover for a lock, when "display over other apps" is off -----

    private fun showLockCoverNow(by: String, leftSeconds: Int) {
        val w = wm ?: return
        if (lock == null) {
            val col = column()
            col.setBackgroundColor(0xFA0B0B0C.toInt())
            val title = label("Locked by $by", 26f, Color.WHITE, bold = true)
            val time = label("", 54f, accent(), bold = true)
            col.addView(title, lp())
            col.addView(time, lp(14f))
            col.addView(label("This phone is locked until the time runs out. Switch \"display over other apps\" back on for LockIn to get the full cover.", 14f, 0xFFA6A6AE.toInt()), lp(14f))
            val open = button("Open LockIn", accent(), onAccent())
            open.setOnClickListener {
                runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            col.addView(open, lp(26f))
            lock = col
            lockTitle = title
            lockTime = time
            runCatching { w.addView(col, params()) }.onFailure { lock = null; return }
        }
        lockTitle?.text = "Locked by $by"
        val m = leftSeconds / 60
        val s = leftSeconds % 60
        lockTime?.text = if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
    }

    private fun removeLockCover() {
        lock?.let { v -> runCatching { wm?.removeView(v) } }
        lock = null
        lockTitle = null
        lockTime = null
    }

    // ----- Closing a small window -----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The display's real size in pixels, in its current rotation. */
    private fun realScreen(): Pair<Int, Int> {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = manager.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(p)
        return p.x to p.y
    }

    /** A "close" button somewhere in this window that sits within [area]. */
    private fun findClose(root: AccessibilityNodeInfo, area: android.graphics.Rect): AccessibilityNodeInfo? {
        val words = Regex("(?i)\\b(close|dismiss|exit|quit)\\b")
        val skip = Regex("(?i)\\b(all|notifications?|shade|panel)\\b")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var budget = 400
        val box = android.graphics.Rect()
        while (queue.isNotEmpty() && budget-- > 0) {
            val n = queue.removeFirst()
            val label = listOfNotNull(n.contentDescription, n.text, n.viewIdResourceName).joinToString(" ")
            if (label.isNotBlank() && words.containsMatchIn(label) && !skip.containsMatchIn(label)) {
                n.getBoundsInScreen(box)
                if (box.width() > 0 && area.contains(box.centerX(), box.centerY())) return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** [n] or the nearest parent that can be clicked. */
    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var up = 0
        while (cur != null && up++ < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    companion object {
        @Volatile private var instance: SettingsGuardService? = null

        /** Least time between two full looks at a screen (a burst of events costs one), and how often the poll ticks. */
        private const val ScanGapMs = 100L
        private const val PollMs = 500L

        fun running(): Boolean = instance != null

        /** Draws (or updates) the lock cover from the guard. Safe from any thread. */
        fun showLockCover(by: String, leftSeconds: Int) {
            val me = instance ?: return
            me.ui.post { me.showLockCoverNow(by, leftSeconds) }
        }

        fun hideLockCover() {
            val me = instance ?: return
            me.ui.post { me.removeLockCover() }
        }

        /**
         * An app window that is showing: its package ("" when Android won't let us read it), whether it fills the
         * screen, where it is, whether it has the input focus, and whether it is a picture-in-picture window.
         */
        class AppWin(val pkg: String, val full: Boolean, val bounds: android.graphics.Rect, val focused: Boolean, val pip: Boolean)

        /**
         * The app windows on screen right now, read from Android's window list (exact, unlike the usage log, which
         * lists the home screen as "on screen" long after an app opened over it, and loses a small window the
         * moment another window takes focus). Null when the guard isn't switched on.
         *
         * "Full" means the window covers (almost) the whole display, measured against the real display size. A large
         * floating window, a split-screen half and a picture-in-picture window are all NOT full. (It used to be "80%
         * of the screen's area", which called a big floating window full screen.)
         */
        fun appWindows(): List<AppWin>? {
            val me = instance ?: return null
            return runCatching {
                val (sw, sh) = me.realScreen()
                val out = ArrayList<AppWin>()
                val live = HashSet<Int>()
                for (w in me.windows) {
                    if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    live.add(w.id)
                    // A window keeps its package for as long as it lives, so ask for it once (asking every second made
                    // every app on screen answer a request each time).
                    var pkg = me.winPkgs[w.id]
                    if (pkg == null) {
                        val root = w.root
                        pkg = root?.packageName?.toString().orEmpty()
                        if (root != null && android.os.Build.VERSION.SDK_INT < 33) root.recycle()
                        if (pkg.isNotEmpty()) me.winPkgs[w.id] = pkg
                    }
                    val r = android.graphics.Rect()
                    w.getBoundsInScreen(r)
                    val pip = w.isInPictureInPictureMode
                    val full = !pip && r.width() >= sw * 0.96 && r.height() >= sh * 0.90
                    out.add(AppWin(pkg, full, r, w.isFocused, pip))
                }
                me.winPkgs.keys.retainAll(live)
                out
            }.getOrNull()
        }

        /**
         * Tries to close [pkg]'s small window, the way a person would, and says whether it did something this time
         * (so the caller keeps trying until the window is gone):
         *  1. the close button on the window's frame (the system's or the phone maker's own window bar), found among
         *     the other windows next to it;
         *  2. Back, when that window has the input focus, so the key reaches it and not whatever is behind.
         * Never touches the app's own screen.
         */
        fun closeApp(pkg: String): Boolean {
            val me = instance ?: return false
            return runCatching {
                val wins = me.windows
                val target = wins.firstOrNull { w ->
                    w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                        runCatching { w.root?.packageName?.toString() }.getOrNull() == pkg
                } ?: return@runCatching false
                val frame = android.graphics.Rect()
                target.getBoundsInScreen(frame)
                val near = android.graphics.Rect(frame).apply { inset(-me.dp(90), -me.dp(90)) }
                for (w in wins) {
                    if (w.id == target.id) continue
                    val rect = android.graphics.Rect()
                    w.getBoundsInScreen(rect)
                    if (!android.graphics.Rect.intersects(near, rect)) continue
                    val root = runCatching { w.root }.getOrNull() ?: continue
                    val owner = root.packageName?.toString().orEmpty()
                    if (owner == me.packageName || owner == pkg) continue
                    val hit = me.findClose(root, near)
                    if (hit != null && me.clickable(hit)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@runCatching true
                }
                if (target.isFocused) return@runCatching me.performGlobalAction(GLOBAL_ACTION_BACK)
                false
            }.getOrDefault(false)
        }

        /** Locks the screen through the accessibility service (Android 9+). True if it did. */
        fun lockScreen(): Boolean {
            val me = instance ?: return false
            if (android.os.Build.VERSION.SDK_INT < 28) return false
            return me.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }

        /** Sends the person Home (for an app that hit its limit) when no overlay can be drawn. */
        fun goHome(): Boolean {
            val me = instance ?: return false
            return me.performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
}
