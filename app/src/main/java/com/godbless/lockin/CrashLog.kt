package com.godbless.lockin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why LockIn last crashed, so the next time it opens it can show the reason and you can copy it.
 * Nothing leaves the phone unless you share it.
 */
object CrashLog {
    private const val FileName = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${it.longVersionCodeCompat()})" }
                }.getOrDefault("?")
                val text = buildString {
                    appendLine("LockIn $version crashed ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(Log.getStackTraceString(error))
                }
                File(app.filesDir, FileName).writeText(text.take(60_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FileName).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FileName).delete() }
    }
}

/**
 * "LockIn closed unexpectedly": shown once after a crash, over everything, with the reason and buttons to copy
 * or share it. Dismissing it forgets the crash.
 */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return
    // The line that names the error, for the headline.
    val reason = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(160) ?: "Unknown error"
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .navigationBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, shape)
                .padding(20.dp),
        ) {
            Text("LockIn closed unexpectedly", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Copy the details and send them to whoever builds LockIn, so the cause can be fixed.",
                style = LockInText.Small.copy(color = LockInColors.Body),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(LockInColors.Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Column {
                    Text(reason, style = LockInText.Small.copy(color = LockInColors.Flare, fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(text, style = LockInText.Small.copy(color = LockInColors.Silver, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LockInButton("Copy details", onClick = {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("LockIn crash", text))
                    }
                }, modifier = Modifier.weight(1f))
                LockInButton("Share", onClick = {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash details")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            LockInButton("Dismiss", onClick = {
                CrashLog.clear(context)
                report = null
            }, kind = ButtonKind.Outline)
        }
    }
}
