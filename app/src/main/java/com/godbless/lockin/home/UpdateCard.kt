package com.godbless.lockin.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.BuildConfig
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText

private val SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/**
 * A new version is out. A sheet that slides up from the bottom of Home. The scene on top is the app's own
 * mountain: the climber rises toward the summit flag while the update downloads, and reaches it when done.
 */
@Composable
internal fun UpdateCard(update: UpdateState, modifier: Modifier = Modifier) {
    val info = update.info
    val restart = update.phase == UpdatePhase.Restart
    if (info == null && update.dyn == null && !restart) return

    val downloading = update.phase == UpdatePhase.Downloading
    val mandatory = info?.mandatory == true
    val notes = (info?.notes ?: update.dyn?.notes.orEmpty())
        .lineSequence().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }.take(3).toList()

    // 0 at the foot of the mountain, 1 at the flag.
    val target = when {
        restart || update.phase == UpdatePhase.Ready -> 1f
        downloading -> update.progress.coerceIn(0f, 1f)
        else -> 0f
    }
    val climb by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "climb")

    Column(
        modifier
            .fillMaxWidth()
            .clip(SheetShape)
            .background(LockInColors.Slate)
            .border(1.dp, Brush.verticalGradient(listOf(LockInColors.Lime.copy(alpha = 0.75f), Color.Transparent)), SheetShape)
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 10.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(LockInColors.Rim),
        )
        Spacer(Modifier.height(10.dp))
        UpdateScene(climb, Modifier.fillMaxWidth().height(112.dp))
        Spacer(Modifier.height(14.dp))

        // Version pill: what you have, then what is coming.
        val newName = info?.versionName
        Row(
            Modifier
                .clip(CircleShape)
                .background(LockInColors.LimeWash)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    restart -> "Installed"
                    newName != null -> "${BuildConfig.VERSION_NAME}  →  $newName"
                    else -> "Code update"
                },
                style = LockInText.Label.copy(fontSize = 13.sp, color = LockInColors.Lime),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                restart -> "Restart LockIn to finish"
                downloading && update.progress <= 0f -> "Starting…"
                downloading -> "Climbing… ${(update.progress * 100).toInt()}%"
                update.phase == UpdatePhase.Ready -> "Ready to install"
                else -> "A new LockIn is ready"
            },
            style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp),
            textAlign = TextAlign.Center,
        )

        if (!restart && notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notes.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(LockInColors.Lime))
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = LockInText.Sub.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 2)
                    }
                }
            }
        } else if (restart) {
            Spacer(Modifier.height(4.dp))
            Text(
                "The new code loads the next time the app starts.",
                style = LockInText.Sub.copy(fontSize = 14.sp),
                textAlign = TextAlign.Center,
            )
        }

        update.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = LockInText.Error, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(18.dp))
        if (downloading) {
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                drawRoundRect(LockInColors.Graphite, size = size, cornerRadius = r)
                if (climb > 0f) {
                    val w = (size.width * climb).coerceAtLeast(size.height)
                    drawRoundRect(LockInColors.Lime, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
                }
            }
            if (update.detail.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(update.detail, style = LockInText.Sub.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
            }
            // Minimize: the card hides, the download carries on, and it comes back when LockIn is opened again.
            if (!mandatory) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Hide, keep downloading",
                    style = LockInText.Label.copy(color = LockInColors.Silver),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { update.dismissed = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        } else {
            LockInButton(
                when {
                    restart -> "Restart now"
                    update.phase == UpdatePhase.Ready -> "Install"
                    else -> "Update now"
                },
                onClick = update::update,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!mandatory) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Not now",
                    style = LockInText.Label.copy(color = LockInColors.Silver),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { update.dismissed = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** The mountain with a trail, a summit flag and a climber at [climb] (0..1) along the trail. */
@Composable
private fun UpdateScene(climb: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Back range, a quiet silhouette.
        val back = Path().apply {
            moveTo(0f, h)
            lineTo(w * 0.00f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.38f)
            lineTo(w * 0.30f, h * 0.58f)
            lineTo(w * 0.40f, h * 0.46f)
            lineTo(w * 0.52f, h)
            close()
        }
        drawPath(back, LockInColors.Graphite.copy(alpha = 0.55f))

        // Front range with the summit.
        val summit = Offset(w * 0.64f, h * 0.16f)
        val front = Path().apply {
            moveTo(w * 0.20f, h)
            lineTo(w * 0.46f, h * 0.50f)
            lineTo(w * 0.54f, h * 0.60f)
            lineTo(summit.x, summit.y)
            lineTo(w * 0.78f, h * 0.44f)
            lineTo(w * 0.88f, h * 0.58f)
            lineTo(w * 1.00f, h * 0.50f)
            lineTo(w * 1.00f, h)
            close()
        }
        drawPath(front, Brush.verticalGradient(listOf(LockInColors.Edge.copy(alpha = 0.9f), LockInColors.Graphite), startY = summit.y, endY = h))

        // Snow cap: its top corners sit ON the two slopes (a point a little way down each edge from the summit), and the
        // ragged lower edge stays inside the mountain. (Fixed corners stuck out past the slopes like wings.)
        val leftFoot = Offset(w * 0.54f, h * 0.60f)
        val rightFoot = Offset(w * 0.78f, h * 0.44f)
        fun along(from: Offset, to: Offset, t: Float) = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        val capLeft = along(summit, leftFoot, 0.27f)
        val capRight = along(summit, rightFoot, 0.36f)
        val cap = Path().apply {
            moveTo(summit.x, summit.y)
            lineTo(capLeft.x, capLeft.y)
            lineTo(w * 0.628f, h * 0.335f)
            lineTo(w * 0.645f, h * 0.285f)
            lineTo(w * 0.662f, h * 0.345f)
            lineTo(w * 0.678f, h * 0.295f)
            lineTo(capRight.x, capRight.y)
            close()
        }
        drawPath(cap, LockInColors.Snow.copy(alpha = 0.92f))

        // The trail, from the foot to the flag.
        val start = Offset(w * 0.30f, h * 0.93f)
        val trail = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(w * 0.52f, h * 0.88f, w * 0.40f, h * 0.70f, w * 0.56f, h * 0.62f)
            cubicTo(w * 0.70f, h * 0.55f, w * 0.52f, h * 0.40f, summit.x, summit.y + h * 0.10f)
        }
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 9f))
        drawPath(trail, LockInColors.Mist.copy(alpha = 0.7f), style = Stroke(width = 2.5f, cap = StrokeCap.Round, pathEffect = dash))

        val measure = PathMeasure().apply { setPath(trail, false) }
        val len = measure.length
        // The part already climbed, drawn solid.
        val done = Path()
        measure.getSegment(0f, len * climb, done, true)
        drawPath(done, LockInColors.Lime, style = Stroke(width = 4f, cap = StrokeCap.Round))

        // Flag on the summit.
        val pole = Offset(summit.x, summit.y)
        drawLine(LockInColors.Snow, pole, Offset(pole.x, pole.y - h * 0.17f), strokeWidth = 3f, cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(pole.x, pole.y - h * 0.17f)
            lineTo(pole.x + w * 0.075f, pole.y - h * 0.135f)
            lineTo(pole.x, pole.y - h * 0.10f)
            close()
        }
        drawPath(flag, LockInColors.Lime)

        // The climber: a lime dot with a glow.
        val at = measure.getPosition(len * climb)
        drawCircle(LockInColors.Lime.copy(alpha = 0.22f), radius = 17f, center = at)
        drawCircle(LockInColors.Lime, radius = 8.5f, center = at)
        drawCircle(LockInColors.OnLime, radius = 3.2f, center = at)
    }
}
