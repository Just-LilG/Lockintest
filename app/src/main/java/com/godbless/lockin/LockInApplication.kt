package com.godbless.lockin

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.godbless.lockin.data.AuthRepository
import com.godbless.lockin.data.SupabaseAuthRepository
import com.godbless.lockin.dyn.DynStore
import com.godbless.lockin.push.Push
import kotlinx.coroutines.flow.MutableStateFlow

/** Holds the one auth repository so it outlives screen rotations and the link-from-email hand-off. */
class LockInApplication : Application() {
    val auth: AuthRepository by lazy { SupabaseAuthRepository(this) }

    /**
     * A pairing code that arrived in a link while nobody was signed in, or before the Home screen
     * was up. Home picks it up, asks "Pair with ...?", then clears it.
     */
    val incomingPairCode = MutableStateFlow<String?>(null)

    /**
     * Counts up when a push says a lock changed (your partner locked or unlocked you, or you used the
     * emergency release). Home refreshes the lock straight away when it changes.
     */
    val lockPing = MutableStateFlow(0)

    /** Counts up when a crew push arrives (a message, a session started or left), so the Crew tab refreshes. */
    val crewPing = MutableStateFlow(0)

    /** Counts up when any push from your partner arrives while LockIn is open, so the chat looks at once instead of at its next turn. */
    val chatPing = MutableStateFlow(0)

    /** Set when a notification was tapped. Home opens the chat (once you are paired), then clears it. */
    val openChat = MutableStateFlow(false)

    /** A notification asked to open the lock page ("lock") or the Partner tab ("partner"). Home clears it. */
    val openTarget = MutableStateFlow<String?>(null)

    /** Set once the permissions page has been offered in this run of the app, so it shows once per open. */
    @Volatile
    var permissionsOffered = false

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is written down and shown next time LockIn opens.
        CrashLog.install(this)
        // The chosen theme, before anything draws (the lock cover and limit screens use it too). Its settings file
        // lives in normal storage, which can't be read right after a restart; dark until then.
        runCatching { com.godbless.lockin.ui.ThemeState.load(this) }
        // Reduced motion follows battery saver; listens for it being switched on or off.
        runCatching { com.godbless.lockin.ui.MotionState.load(this) }
        Push.watch(this)
        // Right after a restart, before the first unlock (started for the boot watcher), normal storage can't
        // be read yet: touching it would crash. The boot watcher only needs the lock, so stop here.
        val unlocked = runCatching { getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != false }.getOrDefault(true)
        if (!unlocked) return
        // Voice notes and photos live in the app's own storage (not the cache): once your partner has theirs, the server
        // deletes its copy, so the phone must not be allowed to clear this on its own. Older ones sat in the cache.
        com.godbless.lockin.data.VoiceDir.dir = java.io.File(filesDir, "voice").apply { mkdirs() }
        com.godbless.lockin.data.VoiceDir.legacy = java.io.File(cacheDir, "voice")
        com.godbless.lockin.data.MediaDir.dir = java.io.File(filesDir, "chat_media").apply { mkdirs() }
        // Off the main thread: the first time, the sounds are copied into the Notifications folder.
        Thread { runCatching { Push.ensureChannels(this) } }.start()
        // A partner's lock that was running when LockIn last stopped picks up again straight away.
        com.godbless.lockin.lock.LockService.sync(this)
        // A downloaded code update that is still running ten seconds after start is healthy. One that keeps
        // dying before this is thrown away and the installed code runs instead (see DynStore).
        Handler(Looper.getMainLooper()).postDelayed({ DynStore.confirm(this) }, 10_000)
    }
}
