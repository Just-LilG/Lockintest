package com.godbless.lockin.update

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.godbless.lockin.BuildConfig
import com.godbless.lockin.dyn.DynInfo
import com.godbless.lockin.dyn.DynUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A newer build that has been published. [sha256] is the checksum of the APK, checked after the download. */
class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val url: String,
    val sha256: String,
    val size: Long,
    /** When true the "Later" button is hidden. */
    val mandatory: Boolean,
)

/** What latest.json offers: a full APK build, a code-only update, both or neither. */
class Published(val apk: UpdateInfo?, val dyn: DynInfo?)

/**
 * Updates without an app store. A small file, latest.json, sits in the public "updates" bucket next to the
 * APK. The app compares its own version code with it, downloads the APK, checks the checksum and hands it
 * to Android's installer, which asks the person to confirm. Android only installs it if it is signed with
 * the same key as the app already on the phone.
 */
object Updates {
    private val base get() = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/updates"

    /** What is published, keeping only what is newer than this build. Both null when nothing is. */
    suspend fun check(): Result<Published> = withContext(Dispatchers.IO) {
        runCatching {
            // The minute in the address keeps a cached copy from hiding a fresh release for long.
            val conn = URL("$base/latest.json?m=${System.currentTimeMillis() / 60_000L}").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            try {
                // A missing file answers with a 4xx: nothing has been published yet.
                if (conn.responseCode in 400..499) return@runCatching Published(null, null)
                if (conn.responseCode != 200) error("Couldn't check for updates (${conn.responseCode}).")
                val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val apk = if (j.has("versionCode") && j.getInt("versionCode") > BuildConfig.VERSION_CODE) {
                    UpdateInfo(
                        versionCode = j.getInt("versionCode"),
                        versionName = j.getString("versionName"),
                        notes = j.optString("notes", ""),
                        url = j.getString("url"),
                        sha256 = j.getString("sha256").lowercase(),
                        size = j.optLong("size", 0L),
                        mandatory = j.optBoolean("mandatory", false),
                    )
                } else {
                    null
                }
                // Only ever download from our own storage.
                if (apk != null && !apk.url.startsWith("${BuildConfig.SUPABASE_URL}/storage/")) error("Unexpected update address.")
                Published(apk, DynUpdates.parse(j))
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "updates")

    /** Deletes downloaded APKs. Called when this build is already the newest, so a finished update leaves nothing behind. */
    fun clean(context: Context) {
        dir(context).deleteRecursively()
        context.getExternalFilesDir("updates")?.listFiles()?.forEach { it.delete() }
    }

    // ---- System download: keeps going when the person leaves the app, retries and resumes by itself ----

    /** Where one download stands. [status] is a DownloadManager.STATUS_* value. */
    class Dl(val status: Int, val done: Long, val total: Long, val reason: Int)

    private const val ApkType = "application/vnd.android.package-archive"

    private fun manager(context: Context) = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun spool(context: Context, info: UpdateInfo): File? =
        context.getExternalFilesDir("updates")?.let { File(it, "lockin-${info.versionCode}.apk") }

    /** Starts the download and returns its id, or null when this phone has nowhere to put it (then the in-app download is used). */
    fun enqueue(context: Context, info: UpdateInfo): Long? = runCatching {
        val target = spool(context, info) ?: return null
        target.parentFile?.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("LockIn ${info.versionName}")
            .setDescription("Downloading the update")
            .setMimeType(ApkType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        manager(context).enqueue(request)
    }.getOrNull()

    /** The state of download [id], or null when it no longer exists (cancelled or cleared). */
    fun query(context: Context, id: Long): Dl? = runCatching {
        manager(context).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            Dl(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }.getOrNull()

    fun cancel(context: Context, id: Long) {
        runCatching { manager(context).remove(id) }
    }

    /** A finished download: checks the checksum, then moves it where the installer can read it. */
    suspend fun finish(context: Context, info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val from = spool(context, info)?.takeIf { it.exists() } ?: error("The downloaded update is missing. Try again.")
            val sha = MessageDigest.getInstance("SHA-256")
            from.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                }
            }
            if (sha.digest().joinToString("") { "%02x".format(it) } != info.sha256) {
                from.delete()
                error("The download didn't match what was published. Try again.")
            }
            val folder = dir(context).apply { deleteRecursively(); mkdirs() }
            val ready = File(folder, "lockin-${info.versionCode}.apk")
            from.copyTo(ready, overwrite = true)
            from.delete()
            ready
        }
    }

    /** Whether LockIn is on screen right now. Android only lets an app open the installer from the foreground. */
    fun onScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Downloads the APK and checks its checksum. [onProgress] gets 0 to 1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = dir(context).apply { deleteRecursively(); mkdirs() }
                val file = File(folder, "lockin-${info.versionCode}.apk")
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                try {
                    if (conn.responseCode != 200) error("The download failed (${conn.responseCode}).")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                    val sha = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sha.update(buf, 0, n)
                                done += n
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val got = sha.digest().joinToString("") { "%02x".format(it) }
                    if (got != info.sha256) {
                        file.delete()
                        error("The download didn't match what was published. Try again.")
                    }
                    file
                } catch (e: Throwable) {
                    file.delete()
                    throw e
                } finally {
                    conn.disconnect()
                }
            }
        }

    /** Android asks each app to be allowed to install apps ("install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
