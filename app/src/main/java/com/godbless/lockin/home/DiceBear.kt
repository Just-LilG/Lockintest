package com.godbless.lockin.home

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

    /** Styles whose hair or facial hair can be steered, so "Male" and "Female" mean something. Others show under "All". */
    fun supportsLook(style: String) = style == "open-peeps" || style == "lorelei" || style == "notionists"

    // DiceBear has no gender setting: these steer the features that read as one (hairstyle, facial hair, earrings).
    private val PeepsMale = listOf(
        "afro", "flatTop", "grayShort", "hatBeanie", "hatHip", "mohawk", "mohawk2", "noHair1", "noHair2", "noHair3",
        "pomp", "shaved1", "shaved2", "shaved3", "short1", "short2", "short3", "short4", "short5", "dreads1", "cornrows",
    )
    private val PeepsFemale = listOf(
        "bangs", "bangs2", "bantuKnots", "bun", "bun2", "buns", "grayBun", "hijab", "long", "longAfro", "longBangs",
        "longCurly", "mediumBangs", "mediumBangs2", "mediumBangs3", "mediumStraight", "turban", "twists", "twists2",
    )

    private fun lookQuery(style: String, look: String): String = when {
        look == "m" && style == "open-peeps" -> "&head=${PeepsMale.joinToString(",")}&facialHairProbability=45"
        look == "f" && style == "open-peeps" -> "&head=${PeepsFemale.joinToString(",")}&facialHairProbability=0"
        look == "m" && (style == "lorelei" || style == "notionists") -> "&beardProbability=100"
        look == "f" && style == "lorelei" -> "&beardProbability=0&earringsProbability=60"
        look == "f" && style == "notionists" -> "&beardProbability=0"
        else -> ""
    }

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

    fun key(style: String, seed: String, look: String = "") =
        "$style-${seed.filter { it.isLetterOrDigit() }.take(40)}" + if (look.isEmpty() || !supportsLook(style)) "" else "-$look"

    fun peek(style: String, seed: String, look: String = ""): ImageBitmap? = memory.get(key(style, seed, look))

    suspend fun load(context: Context, style: String, seed: String, look: String = ""): ImageBitmap? {
        val k = key(style, seed, look)
        memory.get(k)?.let { return it }
        if (System.currentTimeMillis() - (failedAt[k] ?: 0L) < 60_000L) return null
        val file = runCatching { File(File(context.applicationContext.filesDir, "avatars").apply { mkdirs() }, "$k.png") }.getOrNull()
        return withContext(Dispatchers.IO) {
            // On the phone already?
            if (file != null && file.length() > 0L) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.let { memory.put(k, it); return@withContext it }
            }
            var bytes: ByteArray? = null
            val base = "$Api/$style/png?seed=${java.net.URLEncoder.encode(seed, "UTF-8")}&size=$Px&backgroundColor=$Backgrounds"
            // The look options first; if the server doesn't accept them (a 400), draw the plain avatar instead of nothing.
            val extra = lookQuery(style, look)
            val urls = if (extra.isEmpty()) listOf(base) else listOf(base + extra, base)
            var urlIndex = 0
            gate.withPermit {
                for (attempt in 0..3) {
                    var conn: HttpURLConnection? = null
                    try {
                        val url = urls[urlIndex]
                        conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 12_000
                        conn.setRequestProperty("User-Agent", "LockIn-Android")
                        val code = conn.responseCode
                        if (code == 200) { bytes = conn.inputStream.use { it.readBytes() }; break }
                        if (code == 400 && urlIndex < urls.lastIndex) { urlIndex++; continue }
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
                prune(file.parentFile)
            }
            bmp.asImageBitmap().also { memory.put(k, it) }
        }
    }

    /** Shuffling in the picker saves a lot of pictures; keep the newest few hundred. */
    private fun prune(dir: File?) {
        val files = dir?.listFiles { f -> f.name.endsWith(".png") } ?: return
        if (files.size <= 400) return
        files.sortedBy { it.lastModified() }.take(files.size - 300).forEach { runCatching { it.delete() } }
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
internal fun rememberDiceBear(style: String, seed: String, look: String = ""): ImageBitmap? {
    val context = LocalContext.current
    var bmp by remember(style, seed, look) { mutableStateOf(DiceBear.peek(style, seed, look)) }
    LaunchedEffect(style, seed, look) { if (bmp == null) bmp = DiceBear.load(context, style, seed, look) }
    return bmp
}

/**
 * The picture for [style], [seed] and [look], for the picker. When any of them changes, the old picture stays until
 * the new one has arrived and then fades into it, so choosing never flashes an empty circle. While the first picture
 * loads, a soft pulsing disc stands in.
 */
@Composable
internal fun DiceImage(style: String, seed: String, look: String, modifier: Modifier = Modifier) {
    val bmp = rememberDiceBear(style, seed, look)
    var last by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bmp) { if (bmp != null) last = bmp }
    androidx.compose.animation.Crossfade(targetState = last, animationSpec = androidx.compose.animation.core.tween(240), label = "dice", modifier = modifier) { img ->
        if (img != null) {
            androidx.compose.foundation.Image(
                bitmap = img,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
                0.25f, 0.6f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "pulse",
            )
            Box(Modifier.fillMaxSize().background(com.godbless.lockin.ui.LockInColors.Edge.copy(alpha = pulse)))
        }
    }
}
