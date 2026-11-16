package com.godbless.lockin.usage

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.godbless.lockin.MainActivity
import com.godbless.lockin.push.Push
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.LockInTheme

/**
 * Covers an app whose daily limit is used up. It sends the person home, or to the chat to ask their
 * partner to lift the limit. It has no way into the limited app.
 */
class BlockActivity : ComponentActivity() {
    private class Shown(val label: String, val seconds: Int, val by: String, val hard: Boolean)

    private var shown by mutableStateOf<Shown?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        read(intent)
        // Back goes home too: there is nothing behind this screen to return to.
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            LockInTheme {
                shown?.let { LimitReached(it.label, it.seconds, it.by, it.hard, floating = false, onHome = ::goHome, onAsk = ::askPartner) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        shown = Shown(
            intent.getStringExtra(ExtraLabel).orEmpty().ifBlank { "This app" },
            intent.getIntExtra(ExtraSeconds, 0),
            intent.getStringExtra(ExtraBy).orEmpty().ifBlank { "Your partner" },
            intent.getBooleanExtra(ExtraHard, false),
        )
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun askPartner() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Push.ExtraOpen, Push.OpenChat),
        )
        finish()
    }

    companion object {
        private const val ExtraLabel = "label"
        private const val ExtraSeconds = "seconds"
        private const val ExtraBy = "by"
        private const val ExtraHard = "hard"

        fun intent(context: Context, limit: Limit, hard: Boolean): Intent =
            Intent(context, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ExtraLabel, limit.label)
                .putExtra(ExtraSeconds, limit.seconds)
                .putExtra(ExtraBy, limit.by)
                .putExtra(ExtraHard, hard)
    }
}

@Composable
internal fun LimitReached(label: String, seconds: Int, by: String, hard: Boolean, floating: Boolean, onHome: () -> Unit, onAsk: () -> Unit, room: Int = 0, asked: Boolean = false, closing: Boolean = false, compact: Boolean = false) {
    if (compact) {
        // Over just the small window, after closing it ourselves failed: so it can be closed by hand.
        Column(
            Modifier.fillMaxSize().background(LockInColors.Ink).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$label is done for today.", style = LockInText.Label.copy(color = LockInColors.Flare))
            Spacer(Modifier.height(6.dp))
            Text("Close this window.", style = LockInText.Small)
        }
        return
    }
    if (room > 0) {
        // The cover has stepped aside to a bar so the floating window above it can be closed or moved.
        Column(
            Modifier
                .fillMaxWidth()
                .background(LockInColors.Ink)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text("Close $label's small window above", style = LockInText.Label.copy(color = LockInColors.Flare))
            Spacer(Modifier.height(4.dp))
            Text("The limit comes back in ${room}s.", style = LockInText.Small)
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .readable(Widths.Sheet)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Limit reached",
            style = LockInText.Label.copy(color = LockInColors.OnLime),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(LockInColors.Lime)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text("$label is done for today.", style = LockInText.Headline)
        Spacer(Modifier.height(12.dp))
        Text(
            "$by set ${durationText(seconds)} a day for it, and that's used up. " +
                if (hard) "It's paused until midnight." else "It's closed until midnight.",
            style = LockInText.Lead,
        )
        if (floating) {
            // Opened in a floating or split window: going home leaves it on screen.
            Spacer(Modifier.height(16.dp))
            Text(
                if (closing) "$label is open in a small window. LockIn is closing it." else "$label is still open in a small window. Tap Go home, then close it from the bar that appears.",
                style = LockInText.Small.copy(color = LockInColors.Flare),
            )
        }
        Spacer(Modifier.height(32.dp))
        LockInButton("Go home", onClick = onHome, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        LockInButton(
            if (asked) "Asked $by" else "Ask $by to lift it",
            onClick = onAsk,
            modifier = Modifier.fillMaxWidth(),
            kind = ButtonKind.Outline,
            enabled = !asked,
        )
        Spacer(Modifier.height(4.dp))
        Text(if (asked) "$by got your request in LockIn." else "Only $by can change it.", style = LockInText.Small, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
    }
}
