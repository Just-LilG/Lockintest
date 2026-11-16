package com.godbless.lockin.dyn

import android.content.Context
import android.os.Build
import com.godbless.lockin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** A published code-only update (see scripts/publish-dynamic.sh). */
class DynInfo(
    val dynamicVersion: Long,
    val stableApi: Int,
    val url: String,
    /** Checksum of the whole bundle file. */
    val sha256: String,
    val size: Long,
    val notes: String,
)

/** Downloads a code bundle, checks it, and stages it for the next start. */
object DynUpdates {

    /** Code updates need Android 9+ and a public key built into the app. Without a key none is ever accepted. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 28 && BuildConfig.DYNAMIC_PUBKEY.isNotEmpty()

    /** Reads the entry for this build type ("debug" or "release") from latest.json's "dynamic" object. */
    internal fun parse(root: JSONObject): DynInfo? = runCatching {
        val j = root.optJSONObject("dynamic")?.optJSONObject(BuildConfig.BUILD_TYPE) ?: return@runCatching null
        if (!j.getString("url").startsWith("${BuildConfig.SUPABASE_URL}/storage/")) return@runCatching null
        DynInfo(
            dynamicVersion = j.getLong("dynamicVersion"),
            stableApi = j.getInt("stableApiVersion"),
            url = j.getString("url"),
            sha256 = j.getString("sha256").lowercase(),
            size = j.optLong("size", 0L),
            notes = j.optString("notes", ""),
        )
    }.getOrNull()

    /** True if [info] fits this app and is newer than what runs, what is already waiting, and what crashed. */
    fun wanted(context: Context, info: DynInfo): Boolean =
        supported() &&
            info.stableApi == BuildConfig.STABLE_API &&
            info.dynamicVersion > DynStore.running() &&
            info.dynamicVersion > DynStore.stagedVersion(context) &&
            info.dynamicVersion > DynStore.bad(context)

    /** A bundle is waiting for a restart. */
    fun waiting(context: Context): Boolean = supported() && DynStore.stagedVersion(context) > DynStore.running()

    /** Downloads and checks the bundle, then stages it. The change takes effect after a restart. */
    suspend fun download(context: Context, info: DynInfo, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val work = File(context.cacheDir, "dynwork").apply { deleteRecursively(); mkdirs() }
            try {
                val bundle = File(work, "bundle.zip")
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                try {
                    if (conn.responseCode != 200) error("The download failed (${conn.responseCode}).")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                    val sha = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    conn.inputStream.use { input ->
                        bundle.outputStream().use { out ->
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
                    if (sha.digest().joinToString("") { "%02x".format(it) } != info.sha256) error("The download didn't match what was published. Try again.")
                } finally {
                    conn.disconnect()
                }

                // Only these three names are read; anything else in the zip is ignored.
                var manifest: ByteArray? = null
                var signature: ByteArray? = null
                val dex = File(work, "classes.dex")
                ZipInputStream(bundle.inputStream().buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        when (e.name) {
                            "manifest.json" -> manifest = zip.readBytes()
                            "signature.der" -> signature = zip.readBytes()
                            "classes.dex" -> dex.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                val m = manifest ?: error("The update is incomplete.")
                val s = signature ?: error("The update is incomplete.")
                val ok = DynVerify.check(m, s, dex, BuildConfig.DYNAMIC_PUBKEY, BuildConfig.BUILD_TYPE, BuildConfig.STABLE_API)
                    ?: error("The update didn't pass the safety check, so it was not used.")
                if (ok.dynamicVersion != info.dynamicVersion) error("The update didn't match what was published.")
                DynStore.stage(context, m, s, dex)
            } finally {
                work.deleteRecursively()
            }
        }
    }
}
