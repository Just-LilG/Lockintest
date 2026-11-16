package com.godbless.lockin.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.godbless.lockin.BuildConfig
import com.godbless.lockin.R
import com.godbless.lockin.dyn.DynInfo
import com.godbless.lockin.dyn.DynUpdates
import com.godbless.lockin.dyn.RestartActivity
import com.godbless.lockin.update.UpdateInfo
import com.godbless.lockin.update.Updates
import kotlinx.coroutines.CoroutineScope
import android.app.DownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Restart: a code update is downloaded and waits for the app to restart. */
internal enum class UpdatePhase { Idle, Checking, Downloading, Ready, Restart }

/**
 * Looks for a newer build and applies it. A full build (new APK) goes through Android's installer. A
 * code-only update is downloaded, checked and loaded the next time the app starts, with no installer.
 */
internal class UpdateState(private val context: Context, private val scope: CoroutineScope) {
    /** A newer APK. */
    var info by mutableStateOf<UpdateInfo?>(null)
        private set

    /** A newer code-only update (when there is no newer APK). */
    var dyn by mutableStateOf<DynInfo?>(null)
        private set

    var phase by mutableStateOf(UpdatePhase.Idle)
        private set
    var progress by mutableStateOf(0f)
        private set

    /** What the download is waiting for, e.g. "Waiting for a connection…". Empty when it is moving. */
    var detail by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The card is hidden after "Later", until the app is opened again. */
    var dismissed by mutableStateOf(false)

    private var file: File? = null
    private val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    init {
        // An update downloaded earlier is still waiting for a restart.
        if (DynUpdates.waiting(context)) phase = UpdatePhase.Restart
        else NotificationManagerCompat.from(context).cancel(NotificationId)
    }

    /** Whether the update card should show. */
    val showCard: Boolean
        get() = when {
            phase == UpdatePhase.Restart -> !dismissed
            info != null -> !dismissed || info?.mandatory == true
            dyn != null -> !dismissed
            else -> false
        }

    /** The line under "App updates" on the You tab. */
    val status: String
        get() = when {
            phase == UpdatePhase.Checking -> "Checking…"
            phase == UpdatePhase.Downloading -> "${(progress * 100).toInt()}%"
            phase == UpdatePhase.Restart -> "Restart to finish"
            phase == UpdatePhase.Ready -> "Tap to install"
            info != null -> "${info?.versionName} is ready"
            dyn != null -> "Update ready"
            error != null -> "Couldn't check"
            else -> "Up to date"
        }

    /** Checks each time the app comes to the front, at most every ten minutes, unless [force] (the person tapped "App updates"). */
    suspend fun check(force: Boolean = false) {
        if (phase == UpdatePhase.Checking || phase == UpdatePhase.Downloading) return
        val last = prefs.getLong("checked", 0L)
        if (!force && System.currentTimeMillis() - last < 10 * 60 * 1000L) return
        val before = phase
        phase = UpdatePhase.Checking
        error = null
        Updates.check()
            .onSuccess { p ->
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                info = p.apk
                p.apk?.let { resumeDownload(it) }
                // A full build wins: it replaces the code as well.
                dyn = p.dyn?.takeIf { p.apk == null && DynUpdates.wanted(context, it) }
                if (p.apk == null && dyn == null) {
                    Updates.clean(context)
                    forgetDownload()
                    file = null
                    if (force && !DynUpdates.waiting(context)) {
                        Toast.makeText(context, "LockIn ${BuildConfig.VERSION_NAME} is the newest version.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .onFailure {
                error = it.message
                if (force) Toast.makeText(context, it.message ?: "Couldn't check for updates.", Toast.LENGTH_SHORT).show()
            }
        phase = if (DynUpdates.waiting(context)) UpdatePhase.Restart else if (before == UpdatePhase.Ready) UpdatePhase.Ready else UpdatePhase.Idle
    }

    /** Does the next step: download, install, or restart. */
    fun update() {
        if (phase == UpdatePhase.Downloading || phase == UpdatePhase.Checking) return
        when {
            info != null -> updateApk()
            dyn != null -> updateCode()
            phase == UpdatePhase.Restart -> RestartActivity.now(context)
        }
    }

    private fun updateApk() {
        val target = info ?: return
        val ready = file
        if (ready != null && ready.exists()) {
            install(ready)
            return
        }
        phase = UpdatePhase.Downloading
        progress = 0f
        detail = ""
        error = null
        scope.launch {
            // This phone never started the background download for this version: download in the app.
            if (prefs.getInt("inAppFor", -1) == target.versionCode) {
                downloadInApp(target)
                return@launch
            }
            // A download from before is picked up where it is; otherwise the system starts a new one.
            val id = savedDownload(target) ?: Updates.enqueue(context, target)?.also {
                prefs.edit().putLong("dlId", it).putInt("dlCode", target.versionCode).apply()
            }
            if (id == null) downloadInApp(target) else watch(target, id, installWhenDone = true)
        }
    }

    /** The id of a download already started for this version, if the system still has it. */
    private fun savedDownload(target: UpdateInfo): Long? {
        val id = prefs.getLong("dlId", -1L)
        if (id < 0 || prefs.getInt("dlCode", -1) != target.versionCode) return null
        return id.takeIf { Updates.query(context, it) != null }
    }

    private fun forgetDownload() {
        prefs.getLong("dlId", -1L).takeIf { it >= 0 }?.let { Updates.cancel(context, it) }
        prefs.edit().remove("dlId").remove("dlCode").apply()
    }

    /**
     * Follows a system download. It keeps going when LockIn is closed or in the background, so this only
     * shows how far it is. [installWhenDone] opens the installer when it finishes while LockIn is on screen;
     * otherwise the card just says "Ready to install".
     */
    private suspend fun watch(target: UpdateInfo, id: Long, installWhenDone: Boolean) {
        phase = UpdatePhase.Downloading
        val started = System.currentTimeMillis()
        var lastDone = -1L
        var lastMove = started
        while (true) {
            val st = Updates.query(context, id)
            if (st != null && st.done != lastDone) {
                lastDone = st.done
                lastMove = System.currentTimeMillis()
            }
            // Queued or paused and not one byte in: the system won't start it here. Use the in-app download instead.
            val stuck = st != null && st.done == 0L &&
                (st.status == DownloadManager.STATUS_PENDING || st.status == DownloadManager.STATUS_PAUSED) &&
                System.currentTimeMillis() - started > 20_000
            if (stuck) {
                Updates.cancel(context, id)
                forgetDownload()
                prefs.edit().putInt("inAppFor", target.versionCode).apply()
                detail = ""
                downloadInApp(target)
                return
            }
            when {
                st == null -> {
                    prefs.edit().remove("dlId").remove("dlCode").apply()
                    phase = UpdatePhase.Idle
                    error = "The download was cancelled."
                    return
                }
                st.status == DownloadManager.STATUS_SUCCESSFUL -> {
                    Updates.finish(context, target)
                        .onSuccess {
                            prefs.edit().remove("dlId").remove("dlCode").apply()
                            file = it
                            phase = UpdatePhase.Ready
                            if (installWhenDone && Updates.onScreen()) install(it)
                        }
                        .onFailure {
                            forgetDownload()
                            phase = UpdatePhase.Idle
                            error = it.message ?: "The download failed."
                        }
                    return
                }
                st.status == DownloadManager.STATUS_FAILED -> {
                    forgetDownload()
                    phase = UpdatePhase.Idle
                    error = "The download failed (code ${st.reason}). Check your connection and try again."
                    return
                }
                else -> {
                    // The system sometimes has no size yet; latest.json has it.
                    val total = if (st.total > 0) st.total else target.size
                    if (total > 0) progress = (st.done.toFloat() / total).coerceIn(0f, 1f)
                    detail = when {
                        st.status == DownloadManager.STATUS_PAUSED -> "Waiting for a connection…"
                        st.status == DownloadManager.STATUS_PENDING -> "Starting the download…"
                        System.currentTimeMillis() - lastMove > 8_000 -> "Still waiting for data…"
                        else -> ""
                    }
                }
            }
            delay(500)
        }
    }

    /** Back in the app with a download that carried on (or finished) while it was away. */
    private fun resumeDownload(target: UpdateInfo) {
        if (phase == UpdatePhase.Downloading || file?.exists() == true) return
        val id = savedDownload(target) ?: return
        scope.launch { watch(target, id, installWhenDone = false) }
    }

    /** Fallback for a phone with no place for the system download: the old in-app download. */
    private suspend fun downloadInApp(target: UpdateInfo) {
        Updates.download(context, target) { progress = it }
            .onSuccess {
                file = it
                phase = UpdatePhase.Ready
                detail = ""
                if (Updates.onScreen()) install(it)
            }
            .onFailure {
                phase = UpdatePhase.Idle
                detail = ""
                error = it.message ?: "The download failed."
            }
    }

    private fun updateCode() {
        val target = dyn ?: return
        phase = UpdatePhase.Downloading
        progress = 0f
        error = null
        scope.launch {
            DynUpdates.download(context, target) { progress = it }
                .onSuccess {
                    dyn = null
                    dismissed = false
                    phase = UpdatePhase.Restart
                    notifyRestart()
                }
                .onFailure {
                    phase = UpdatePhase.Idle
                    error = it.message ?: "The download failed."
                }
        }
    }

    private fun install(apk: File) {
        // Android makes you allow each app to install apps once. Send them there, then they tap Update again.
        if (!Updates.canInstall(context)) {
            Toast.makeText(context, "Allow LockIn to install updates, then come back and tap Update.", Toast.LENGTH_LONG).show()
            context.startActivity(Updates.allowIntent(context))
            return
        }
        context.startActivity(Updates.installIntent(context, apk))
    }

    /** "LockIn updated. Restart the app." Tapping it opens LockIn, where the card has the Restart button. */
    @SuppressLint("MissingPermission")
    private fun notifyRestart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(NotificationChannel(Channel, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: return
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NotificationId,
            NotificationCompat.Builder(context, Channel)
                .setSmallIcon(R.drawable.ic_stat_lockin)
                .setContentTitle("LockIn updated")
                .setContentText("Restart the app to finish.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The "App updates" row: do the next step if one is ready, otherwise look for an update now. */
    fun tap() {
        if (info != null || dyn != null || phase == UpdatePhase.Restart) update() else scope.launch { check(force = true) }
    }

    private companion object {
        const val Channel = "updates"
        const val NotificationId = 1003
    }
}
