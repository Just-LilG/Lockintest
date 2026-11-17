package com.godbless.lockin.home

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Illustrated avatars from the DiceBear HTTP API (https://www.dicebear.com/how-to-use/http-api/).
 *
 * The same style + seed always draws the same picture, so a person looks the same everywhere. Each picture is fetched
 * once, kept on the phone (app storage, not the cache), and after that never needs the network again.
 *
 * - Only styles released under CC0 are offered, so no credit line is needed.
 * - The seed is an opaque code, never a name: the first name is hashed before it is sent, so DiceBear is not told who
 *   anyone is.
 * - DiceBear limits PNG requests (10 a second), so requests are queued two at a time with a gap, a 429 is retried
 *   later, and a failed one is not tried again for a minute.
 */
internal object DiceBear {
    const val DefaultStyle = "lorelei"

    /** (style name in the API, label). All CC0 1.0. */
    val Styles = listOf(
        "lorelei" to "Lorelei",
        "notionists" to "Notionists",
        "open-peeps" to "Open Peeps",
        "thumbs" to "Thumbs",
        "pixel-art" to "Pixel",
        "rings" to "Rings",
    )

    private const val Api = "https://api.dicebear.com/9.x"
    private const val Px = 256 // the most the PNG endpoint serves; scaled down for smaller avatars
    private const val Backgrounds = "b6e3f4,c0aede,d1d4f9,ffd5dc,ffdfbf"

    private val memory = LruCache<String, ImageBitmap>(96)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(2)

    /** The same code for the same person on every phone: a hash of their first name. */
    fun seedFor(name: String): String {
        val first = name.trim().substringBefore(' ').lowercase().ifEmpty { "you" }
        val h = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return h.take(8).joinToString("") { "%02x".format(it) }
    }

    /** A fresh random code, for "Shuffle". */
    fun randomSeed(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    fun key(style: String, seed: String) = "$style-${seed.filter { it.isLetterOrDigit() }.take(40)}"

    fun peek(style: String, seed: String): ImageBitmap? = memory.get(key(style, seed))

    suspend fun load(context: Context, style: String, seed: String): ImageBitmap? {
        val k = key(style, seed)
        memory.get(k)?.let { return it }
        if (System.currentTimeMillis() - (failedAt[k] ?: 0L) < 60_000L) return null
        val file = runCatching { File(File(context.applicationContext.filesDir, "avatars").apply { mkdirs() }, "$k.png") }.getOrNull()
        return withContext(Dispatchers.IO) {
            // On the phone already?
            if (file != null && file.length() > 0L) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.let { memory.put(k, it); return@withContext it }
            }
            var bytes: ByteArray? = null
            gate.withPermit {
                for (attempt in 0..2) {
                    var conn: HttpURLConnection? = null
                    try {
                        val url = "$Api/$style/png?seed=${java.net.URLEncoder.encode(seed, "UTF-8")}&size=$Px&backgroundColor=$Backgrounds"
                        conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 12_000
                        conn.setRequestProperty("User-Agent", "LockIn-Android")
                        val code = conn.responseCode
                        if (code == 200) { bytes = conn.inputStream.use { it.readBytes() }; break }
                        if (code != 429 && code !in 500..599) break
                    } catch (_: Exception) {
                        // fall through to retry
                    } finally {
                        conn?.disconnect()
                    }
                    delay(900L * (attempt + 1))
                }
                delay(120) // keeps the pace under DiceBear's PNG limit
            }
            val data = bytes
            val bmp = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp == null) {
                failedAt[k] = System.currentTimeMillis()
                return@withContext null
            }
            if (file != null) runCatching {
                val tmp = File(file.parentFile, "$k.tmp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(file)) tmp.delete()
            }
            bmp.asImageBitmap().also { memory.put(k, it) }
        }
    }

    /** Removes every saved picture (Settings can offer this next to "clear photos and voice notes"). */
    fun clear(context: Context) {
        memory.evictAll()
        failedAt.clear()
        File(context.applicationContext.filesDir, "avatars").listFiles()?.forEach { runCatching { it.delete() } }
    }
}

/** The picture for [style] and [seed]: null while it is loading, or if it couldn't be fetched (the caller keeps its letter). */
@Composable
internal fun rememberDiceBear(style: String, seed: String): ImageBitmap? {
    val context = LocalContext.current
    var bmp by remember(style, seed) { mutableStateOf(DiceBear.peek(style, seed)) }
    LaunchedEffect(style, seed) { if (bmp == null) bmp = DiceBear.load(context, style, seed) }
    return bmp
}
