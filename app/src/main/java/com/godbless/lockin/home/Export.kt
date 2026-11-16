@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.godbless.lockin.data.SessionRow
import java.io.File
import java.time.Instant

private fun csv(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

/** Writes your sessions as a CSV and opens the share sheet, so you can keep a copy of your own data. */
internal fun exportSessions(context: Context, rows: List<SessionRow>) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "lockin-sessions.csv")
        file.bufferedWriter().use { w ->
            w.write("started_at,seconds,minutes,pickups,title\n")
            rows.forEach { r ->
                w.write(Instant.ofEpochMilli(r.startedAt).toString() + "," + r.seconds + "," + (r.seconds / 60) + "," + r.pickups + "," + csv(r.title) + "\n")
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "My LockIn sessions")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export my sessions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "Couldn't export just now.", Toast.LENGTH_SHORT).show() }
}
