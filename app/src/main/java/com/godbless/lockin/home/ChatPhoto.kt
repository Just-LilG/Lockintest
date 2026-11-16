package com.godbless.lockin.home

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.ApiException
import com.godbless.lockin.data.MediaDir
import com.godbless.lockin.data.PreparedPhoto
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.MotionState
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =============================================================================================
// Getting a photo ready to send
// =============================================================================================

/**
 * Shrinks a picked photo for sending: turned upright, at most 1600 px on its long side, JPEG (a few hundred KB instead of
 * several MB), plus a ~24 px preview that travels inside the message so the other phone can show a blurred shape at once.
 */
internal object PhotoPrep {
    private const val MaxSide = 1600

    suspend fun prepare(context: Context, uri: Uri): Result<PreparedPhoto> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "unreadable" }
            // Decode no bigger than needed (a 50 MP photo would not fit in memory at full size).
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MaxSide * 2) sample *= 2
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("unreadable")
            val rotation = cr.openInputStream(uri)?.use { rotationOf(it) } ?: 0
            val longest = max(bmp.width, bmp.height)
            val scale = if (longest > MaxSide) MaxSide.toFloat() / longest else 1f
            if (scale < 1f || rotation != 0) {
                val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            // JPEG has no transparency: put a see-through picture on white instead of black.
            if (bmp.hasAlpha()) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                AndroidCanvas(flat).apply { drawColor(AndroidColor.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                bmp = flat
            }
            val out = File(context.cacheDir, "outgoing_${System.nanoTime()}.jpg")
            var q = 82
            do {
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
                q -= 10
            } while (out.length() > 900_000 && q >= 42)
            val k = 24f / max(bmp.width, bmp.height)
            val tiny = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).roundToInt()), max(1, (bmp.height * k).roundToInt()), true)
            val bytes = ByteArrayOutputStream().also { tiny.compress(Bitmap.CompressFormat.JPEG, 55, it) }.toByteArray()
            PreparedPhoto(out, bmp.width, bmp.height, out.length().toInt(), Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun rotationOf(stream: java.io.InputStream): Int = runCatching {
        when (android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90
            3 -> 180
            8 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

// =============================================================================================
// Keeping them on the phone
// =============================================================================================

/**
 * Photos on this phone. A photo is downloaded once into the app's own storage; after that it is shown from there and the
 * server may delete its copy (see 20261116_chat_media.sql). [state] tells a bubble what is going on with a path.
 */
internal object ChatMedia {
    /** Marks a download the server answered "not found" to: its copy is gone and this phone has none. */
    const val Gone = "\u0000gone"

    /** path -> "" while downloading, [Gone], or the words of the last failure. Absent: nothing going on. */
    val state = mutableStateMapOf<String, String>()

    /** Set when a download finishes, so bubbles that were waiting look at the disk again. */
    val arrived = mutableStateMapOf<String, Boolean>()

    /** Counts up when the saved photos are cleared, so every bubble looks again (and downloads what the server still has). */
    var epoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Throws away what is on this phone and tells the bubbles. */
    fun clearAll() {
        MediaDir.clear()
        state.clear()
        arrived.clear()
        epoch++
    }

    fun cached(path: String): File? =
        MediaDir.dir?.let { File(it, path.replace('/', '_')) }?.takeIf { it.exists() && it.length() > 0L }

    /** Makes sure [path] is on this phone (downloading it if needed) and tells the chat, which tells the server. */
    suspend fun ensure(path: String, fetch: suspend (String) -> Result<File>) {
        if (state[path] == "") return
        state[path] = ""
        fetch(path)
            .onSuccess { state.remove(path); arrived[path] = true }
            .onFailure { state[path] = if ((it as? ApiException)?.status == 404) Gone else (it.message ?: "Couldn't load the photo.") }
    }

    /** Forget a failure so the next look tries again. */
    fun reset(path: String) {
        state.remove(path)
    }
}

private object Bitmaps {
    val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

private fun decodeSampled(file: File, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** The picture in [file], decoded off the main thread to about [maxPx] on its long side. Null until ready. */
@Composable
internal fun rememberPhoto(file: File?, maxPx: Int): ImageBitmap? {
    val key = file?.let { "${it.absolutePath}@$maxPx@${it.length()}" }
    var bmp by remember(key) { mutableStateOf(key?.let { Bitmaps.cache.get(it) }) }
    LaunchedEffect(key) {
        if (file != null && key != null && bmp == null) {
            val d = withContext(Dispatchers.IO) { decodeSampled(file, maxPx) }
            if (d != null) Bitmaps.cache.put(key, d)
            bmp = d
        }
    }
    return bmp
}

/** The tiny preview that comes inside a message, as a picture. */
@Composable
internal fun rememberThumb(thumb: String?): ImageBitmap? = remember(thumb) {
    thumb?.let { runCatching { val b = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
}

/** A ring that turns while something loads. Stands still with reduced motion. */
@Composable
internal fun RingSpinner(color: Color, size: Dp = 22.dp, stroke: Dp = 2.5.dp) {
    val spin = if (MotionState.reduced) 0f else rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ring-turn",
    ).value
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        rotate(spin) {
            drawArc(
                color, 0f, 270f, false,
                topLeft = Offset(w / 2f, w / 2f), size = Size(this.size.width - w, this.size.height - w),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

// =============================================================================================
// The photo in a bubble
// =============================================================================================

/**
 * A photo in the chat. It shows the blurred preview at once, then the real picture when it is on the phone. Your partner's
 * photos download by themselves (small files, already shrunk). Tap to open, double tap to heart, hold for the menu.
 */
@Composable
internal fun PhotoBubble(
    m: Message,
    fetch: suspend (String) -> Result<File>,
    maxWidth: Dp,
    onOpen: (File) -> Unit,
    onHeart: () -> Unit,
    onMenu: () -> Unit,
    onRetry: () -> Unit,
) {
    val aspect = if (m.mediaW > 0 && m.mediaH > 0) (m.mediaW.toFloat() / m.mediaH).coerceIn(0.62f, 1.7f) else 1f
    var w = minOf(maxWidth, 250.dp)
    var h = w / aspect
    if (h > 340.dp) { h = 340.dp; w = h * aspect }

    val path = m.media.orEmpty()
    val local = m.localFile
    ChatMedia.arrived[path] // reading it makes this look again when a download finishes
    val file = local ?: ChatMedia.cached(path)
    val status = ChatMedia.state[path]
    // Download by itself, once, whenever the file is not here and the server may still have it. A saved one is "opened" too,
    // which is how the server learns this phone has it.
    val epoch = ChatMedia.epoch
    LaunchedEffect(path, m.gone, local, epoch) {
        if (local == null && path.isNotEmpty() && path != "local" && (file != null || !m.gone)) ChatMedia.ensure(path, fetch)
    }

    val thumb = rememberThumb(m.thumb)
    val full = rememberPhoto(file, 1400)
    val sending = m.delivery == Delivery.Sending
    val failed = m.delivery == Delivery.Failed
    val gone = file == null && (m.gone || status == ChatMedia.Gone)

    Box(
        Modifier
            .size(w, h)
            .clip(RoundedCornerShape(14.dp))
            .background(LockInColors.Graphite)
            .pointerInput(file, status, failed) {
                detectTapGestures(
                    onTap = {
                        when {
                            failed -> onRetry()
                            file != null && !sending -> onOpen(file)
                            file == null && status != null && status != "" && status != ChatMedia.Gone -> ChatMedia.reset(path)
                        }
                    },
                    onDoubleTap = { if (!sending && !failed) onHeart() },
                    onLongPress = { onMenu() },
                )
            }
            .semantics { contentDescription = "Photo" + if (m.text.isNotBlank() && m.text != "Photo") ", ${m.text}" else "" },
    ) {
        // Blurred shape first (a 24 px picture stretched out), the sharp one over it once it is decoded.
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        if (full != null) Image(full, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)

        val dim = sending || failed || gone || (file == null)
        if (dim && full == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        if (sending || failed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (full != null) 0.35f else 0.0f)))

        Box(Modifier.align(Alignment.Center)) {
            when {
                failed -> Pill("Not sent. Tap to try again")
                sending -> Round { RingSpinner(Color.White) }
                gone -> Pill("No longer available")
                file == null && status != null && status != "" -> Pill("Couldn't load. Tap to try again")
                file == null -> Round { RingSpinner(Color.White) }
            }
        }
    }
}

@Composable
private fun Round(content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, style = LockInText.Small.copy(color = Color.White, fontSize = 12.sp))
    }
}

// =============================================================================================
// Full screen
// =============================================================================================

/**
 * A photo, full screen. Pinch or double tap to zoom, drag when zoomed, drag down (when not zoomed) to close.
 * [onSave] puts a copy in the phone's gallery (Android 10 and up).
 */
@Composable
internal fun PhotoViewer(file: File, caption: String, onClose: () -> Unit, onSave: (() -> Unit)?) {
    val bmp = rememberPhoto(file, 2400)
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    val dismiss = remember { Animatable(0f) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                launch { scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { panX.animateTo(0f, spring(stiffness = 400f)) }
                                launch { panY.animateTo(0f, spring(stiffness = 400f)) }
                            } else {
                                val target = 2.6f
                                // Zoom toward the tapped point.
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                launch { scale.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { panX.animateTo(((cx - at.x) * (target - 1f)).coerceIn(-cx * (target - 1f), cx * (target - 1f)), spring(stiffness = 400f)) }
                                launch { panY.animateTo(((cy - at.y) * (target - 1f)).coerceIn(-cy * (target - 1f), cy * (target - 1f)), spring(stiffness = 400f)) }
                            }
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val pinching = event.changes.size >= 2
                            if (pinching || scale.value > 1.02f) {
                                if (event.changes.any { it.positionChanged() }) {
                                    val next = (scale.value * zoom).coerceIn(1f, 5f)
                                    val limitX = size.width * (next - 1f) / 2f
                                    val limitY = size.height * (next - 1f) / 2f
                                    scope.launch {
                                        scale.snapTo(next)
                                        panX.snapTo((panX.value + pan.x).coerceIn(-limitX, limitX))
                                        panY.snapTo((panY.value + pan.y).coerceIn(-limitY, limitY))
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (event.changes.any { it.positionChanged() } && abs(pan.y) >= abs(pan.x) * 0.5f) {
                                // One finger on an unzoomed photo: it follows the finger, and letting go far enough closes it.
                                scope.launch { dismiss.snapTo((dismiss.value + pan.y).coerceAtLeast(0f)) }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        scope.launch {
                            if (dismiss.value > size.height * 0.16f) onClose()
                            else dismiss.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                        }
                        if (scale.value < 1.02f) scope.launch {
                            scale.animateTo(1f, spring(stiffness = 500f)); panX.animateTo(0f, spring(stiffness = 500f)); panY.animateTo(0f, spring(stiffness = 500f))
                        }
                    }
                }
                .graphicsLayer {
                    translationX = panX.value
                    translationY = panY.value + dismiss.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
            else RingSpinner(Color.White, 32.dp)
        }

        // Close and save, on top of the picture.
        Row(
            Modifier.fillMaxWidth().padding(top = top + 10.dp, start = 14.dp, end = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close photo" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, Color.White, boxSize = 16.dp, strokeWidth = 2.2f) }
            if (onSave != null) {
                Box(
                    Modifier.height(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        .clickable(role = Role.Button, onClick = onSave).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Save", style = LockInText.Label.copy(color = Color.White, fontSize = 14.sp)) }
            }
        }
        if (caption.isNotBlank() && caption != "Photo") {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            ) { Text(caption, style = LockInText.Sub.copy(color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)) }
        }
    }
}

/** Copies [file] into the gallery under Pictures/LockIn. True when it worked. Needs Android 10 or newer. */
internal fun savePhotoToGallery(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LockIn_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LockIn")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

// =============================================================================================
// Before sending: look at it, add a caption
// =============================================================================================

/** The picked photo, full screen, with a caption box. Send puts it in the chat; the cross throws it away. */
@Composable
internal fun PhotoCompose(photo: PreparedPhoto?, preparing: Boolean, partner: String, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var caption by remember { mutableStateOf("") }
    val bmp = rememberPhoto(photo?.file, 1800)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(top = top + 10.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onCancel).semantics { contentDescription = "Cancel" },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Close, Color.White, boxSize = 16.dp, strokeWidth = 2.2f) }
            Text("To $partner", style = LockInText.Label.copy(color = Color.White, fontSize = 15.sp), modifier = Modifier.padding(start = 14.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
            else RingSpinner(Color.White, 32.dp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(LockInColors.Slate).padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = caption, onValueChange = { caption = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LockInText.Input, cursorBrush = SolidColor(LockInColors.Lime), maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box {
                            if (caption.isEmpty()) Text("Add a caption", style = LockInText.Input.copy(color = LockInColors.Mist))
                            inner()
                        }
                    },
                )
            }
            Box(
                Modifier.padding(start = 10.dp).size(52.dp).clip(CircleShape).background(if (photo != null && !preparing) LockInColors.Lime else LockInColors.Graphite)
                    .clickable(enabled = photo != null && !preparing, role = Role.Button) { onSend(caption) }
                    .semantics { contentDescription = "Send photo" },
                contentAlignment = Alignment.Center,
            ) {
                if (photo == null || preparing) RingSpinner(Color.White, 22.dp)
                else GlyphIcon(Glyph.ArrowUp, LockInColors.OnLime, boxSize = 24.dp, strokeWidth = 2.2f)
            }
        }
    }
}
