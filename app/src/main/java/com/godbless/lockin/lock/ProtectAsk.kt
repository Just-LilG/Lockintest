package com.godbless.lockin.lock

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.ProtectRepository
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay

/**
 * "<Partner> has to say yes first." Asks the partner to allow [kind] and waits for the answer. When they allow,
 * the window is opened (see [Protection.open]) and [onAllowed] runs; Not yet or closing calls [onClose].
 */
@Composable
internal fun ProtectAskSheet(kind: String, onAllowed: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LockInApplication
    val partner = remember { Protection.partner(context) }
    val repo = remember { ProtectRepository(PartnerRepository(app.auth)) }

    // idle | asking | waiting | declined | offline
    var stage by remember { mutableStateOf("idle") }
    var requestId by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        stage = "asking"
        if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
        val first = repo.ask(kind).getOrNull()
        if (first == null) { stage = "offline"; return@LaunchedEffect }
        fun finish(left: Int) {
            Protection.open(context, kind, left.coerceAtLeast(60))
            onAllowed()
        }
        when (first.state) {
            "none" -> { Protection.clear(context); onAllowed(); return@LaunchedEffect }
            "allowed" -> { finish(first.left); return@LaunchedEffect }
            "declined" -> { note = "$partner said not yet. You can ask again in ${(first.left + 59) / 60} min."; stage = "declined"; return@LaunchedEffect }
        }
        requestId = first.id
        stage = "waiting"
        // Ask the server every few seconds for up to ten minutes.
        repeat(200) {
            delay(3_000)
            val r = repo.status(requestId).getOrNull() ?: return@repeat
            when (r.state) {
                "allowed" -> { finish(r.left); return@LaunchedEffect }
                "declined" -> { note = "$partner said not yet."; stage = "declined"; return@LaunchedEffect }
                "expired" -> { note = "That request ran out. Ask again."; stage = "declined"; return@LaunchedEffect }
            }
        }
        note = "No answer yet. Ask again when $partner is around."
        stage = "declined"
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose).navigationBarsPadding().padding(20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, RoundedCornerShape(28.dp))
                    .clickable(enabled = false) {}
                    .padding(22.dp),
            ) {
                Text("$partner has to say yes first", style = LockInText.Headline.copy(fontSize = 24.sp, lineHeight = 30.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    when (stage) {
                        "waiting" -> "Asked. $partner got a notification. This opens as soon as they allow it."
                        "asking" -> "Asking $partner..."
                        "declined" -> note
                        "offline" -> "No connection. LockIn can't ask $partner right now, so this stays as it is."
                        else -> "To ${Protection.phrase(kind)}, ${partner} has to allow it. You get ten minutes once they do."
                    },
                    style = LockInText.Sub.copy(color = LockInColors.Body),
                )
                Spacer(Modifier.height(18.dp))
                LockInButton(
                    text = when (stage) { "waiting" -> "Waiting for $partner"; "asking" -> "Asking..."; "idle" -> "Ask $partner"; else -> "Ask again" },
                    onClick = { if (stage != "waiting" && stage != "asking") attempt++ },
                    loading = stage == "asking" || stage == "waiting",
                )
                Spacer(Modifier.height(10.dp))
                LockInButton(text = "Never mind", onClick = onClose, kind = ButtonKind.Outline)
            }
        }
    }
}

/** Convenience for places that only have a Context: a short toast when the answer comes by itself. */
internal fun toastAllowed(context: android.content.Context) {
    Toast.makeText(context, "Allowed. You have ten minutes.", Toast.LENGTH_SHORT).show()
}
