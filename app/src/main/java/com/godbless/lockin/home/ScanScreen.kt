package com.godbless.lockin.home

import com.godbless.lockin.ui.calmFloat
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ControlShape
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.godbless.lockin.data.PairLink
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * Point the camera at your partner's QR code. The first code that is really a LockIn invite is
 * handed to [onCode] (once); anything else only shows a hint. The camera runs only while this
 * screen is up, and nothing is saved or uploaded.
 */
@Composable
internal fun ScanScreen(onCode: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        if (granted) {
            Camera(onCode = onCode, onCamera = { camera = it; torch = false })
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera access", style = LockInText.Title.copy(textAlign = TextAlign.Center))
                Spacer(Modifier.height(8.dp))
                Text(
                    "LockIn only uses the camera on this screen, to read your partner's QR code. You can also type the code.",
                    style = LockInText.Sub.copy(color = LockInColors.Silver, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(20.dp))
                if (!asked) {
                    LockInButton("Allow camera", { request.launch(Manifest.permission.CAMERA) })
                } else {
                    // After a "no", Android may not ask again, so the switch lives in Settings.
                    LockInButton(
                        "Open settings",
                        {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    )
                }
            }
        }

        // Top bar: back, title, flashlight (only on phones that have one).
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPad)) {
            BackButton(onBack)
            Text("Scan to pair", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 17.sp), modifier = Modifier.align(Alignment.Center))
            if (granted && camera?.cameraInfo?.hasFlashUnit() == true) {
                TorchButton(
                    on = torch,
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        // The camera feed is busy, so the button gets a solid backing to stay readable.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .clip(ControlShape)
                .background(LockInColors.Ink.copy(alpha = 0.78f)),
        ) {
            LockInButton("Type the code instead", onBack, kind = ButtonKind.Outline)
        }
    }
}

/** A round flashlight switch, in the same style as the back button. Lime while the light is on. */
@Composable
private fun TorchButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (on) LockInColors.Lime else LockInColors.Slate)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = if (on) "Turn flashlight off" else "Turn flashlight on" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val bolt = Path().apply {
                moveTo(13f * u, 2f * u)
                lineTo(5f * u, 13.5f * u)
                lineTo(11f * u, 13.5f * u)
                lineTo(10f * u, 22f * u)
                lineTo(19f * u, 10f * u)
                lineTo(13f * u, 10f * u)
                close()
            }
            if (on) drawPath(bolt, LockInColors.OnLime)
            else drawPath(bolt, LockInColors.Snow, style = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val ScannerWarmingUp = "Getting the scanner ready… you can type the code instead."

// Reading the camera frame for ML Kit (proxy.image) is marked experimental in CameraX; this is its standard use.
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun Camera(onCode: (String) -> Unit, onCamera: (Camera?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onCode)
    val haptic = LocalHapticFeedback.current
    var hint by remember { mutableStateOf("Point at your partner's QR code") }
    var done by remember { mutableStateOf(false) }

    // A wrong code (not ours) shows a hint for a moment, then the usual line comes back.
    LaunchedEffect(hint) {
        if (hint.startsWith("That")) {
            delay(2500)
            hint = "Point at your partner's QR code"
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            if (hint == ScannerWarmingUp) hint = "Point at your partner's QR code"
                            val text = codes.firstNotNullOfOrNull { it.rawValue }
                            if (text != null && !done) {
                                val code = PairLink.parse(text)
                                if (code != null) {
                                    done = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    deliver(code)
                                } else {
                                    hint = "That isn't a LockIn code."
                                }
                            }
                        }
                        // The scanner lives in Google Play services and may still be downloading on a new phone.
                        .addOnFailureListener { if (!done) hint = ScannerWarmingUp }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    p.unbindAll()
                    onCamera(p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            onCamera(null)
            provider?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        ScanOverlay(found = done, hint = if (done) "Got it" else hint)
    }
}

/**
 * Dims everything except a square to aim at, marks its corners in lime, and sweeps a line through it
 * while it looks. The hint sits under the frame (placed from the same numbers, so it never drifts).
 */
@Composable
private fun ScanOverlay(found: Boolean, hint: String) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = (maxWidth * 0.68f).coerceAtMost(300.dp)
        val top = maxHeight * 0.30f
        val left = (maxWidth - frame) / 2

        val sweep by rememberInfiniteTransition(label = "scan").calmFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sweep",
        )
        val wash by animateFloatAsState(if (found) 1f else 0f, tween(250), label = "found-wash")

        Canvas(Modifier.fillMaxSize()) {
            val l = left.toPx()
            val t = top.toPx()
            val s = frame.toPx()
            val r = 28.dp.toPx()
            val arm = 34.dp.toPx()
            val corner = CornerRadius(r, r)

            // Everything outside the frame is dimmed: one shape (the whole screen) with a rounded hole in it.
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(l, t, l + s, t + s, corner))
            }
            drawPath(dim, LockInColors.Ink.copy(alpha = 0.64f))
            drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(l, t), Size(s, s), corner, style = Stroke(1.dp.toPx()))
            if (wash > 0f) drawRoundRect(LockInColors.Lime.copy(alpha = 0.18f * wash), Offset(l, t), Size(s, s), corner)

            // Lime corners that follow the frame's rounded outline.
            val brackets = Path().apply {
                moveTo(l, t + r + arm); lineTo(l, t + r)
                arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false); lineTo(l + r + arm, t)

                moveTo(l + s - r - arm, t); lineTo(l + s - r, t)
                arcTo(Rect(l + s - 2 * r, t, l + s, t + 2 * r), 270f, 90f, false); lineTo(l + s, t + r + arm)

                moveTo(l + s, t + s - r - arm); lineTo(l + s, t + s - r)
                arcTo(Rect(l + s - 2 * r, t + s - 2 * r, l + s, t + s), 0f, 90f, false); lineTo(l + s - r - arm, t + s)

                moveTo(l + r + arm, t + s); lineTo(l + r, t + s)
                arcTo(Rect(l, t + s - 2 * r, l + 2 * r, t + s), 90f, 90f, false); lineTo(l, t + s - r - arm)
            }
            drawPath(brackets, LockInColors.Lime, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // The sweeping line, fading out at both ends. It stops once a code is found.
            if (!found) {
                val inset = 22.dp.toPx()
                val y = t + inset + (s - 2 * inset) * sweep
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, LockInColors.Lime.copy(alpha = 0.9f), Color.Transparent),
                        startX = l + inset,
                        endX = l + s - inset,
                    ),
                    Offset(l + inset, y),
                    Offset(l + s - inset, y),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        val problem = hint.startsWith("That")
        val soft = hint == ScannerWarmingUp
        Text(
            hint,
            style = LockInText.Label.copy(
                color = when {
                    problem -> LockInColors.Flare
                    soft -> LockInColors.Silver
                    found -> LockInColors.Lime
                    else -> LockInColors.Snow
                },
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = top + frame + 22.dp)
                .padding(horizontal = 24.dp)
                .background(LockInColors.Ink.copy(alpha = 0.8f), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
