package com.godbless.lockin.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.data.Pairing
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.RemoteLockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The buttons on LockIn's notifications: reply to a message, mark it read, and answer an unlock request.
 * Each runs off the main thread and lets the receiver go when done.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LockInApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!app.auth.hasSession() && app.auth.savedUser() != null) app.auth.restore()
                val partners = PartnerRepository(app.auth)
                when (intent.action) {
                    Reply -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Push.ReplyKey)?.toString()?.trim().orEmpty()
                        if (text.isEmpty()) return@launch
                        val pid = Push.partnershipForReply(context)
                            ?: (partners.pairing().getOrNull() as? Pairing.Paired)?.id
                        val sent = pid != null && partners.send(pid, text).isSuccess
                        // Android expects the notification to be updated after an inline reply, or it keeps spinning.
                        Push.showReplied(context, if (sent) text else "Not sent: $text")
                    }
                    Read -> Push.clear(context)
                    ProtectAllow, ProtectNo -> {
                        val ok = com.godbless.lockin.data.ProtectRepository(partners).answer(allow = intent.action == ProtectAllow).isSuccess
                        Push.clearLockAlerts(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                when {
                                    !ok -> "Couldn't answer. Open LockIn and try there."
                                    intent.action == ProtectAllow -> "Allowed for 10 minutes."
                                    else -> "Told them not yet."
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    Unlock, NotYet -> {
                        val ok = RemoteLockRepository(partners).answer(approve = intent.action == Unlock).isSuccess
                        Push.clearLockAlerts(context)
                        app.lockPing.update { it + 1 }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                when {
                                    !ok -> "Couldn't answer. Open LockIn and try there."
                                    intent.action == Unlock -> "Unlocked."
                                    else -> "Told them not yet."
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LockInNotify", "action ${intent.action} failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val Reply = "com.godbless.lockin.NOTIFY_REPLY"
        const val Read = "com.godbless.lockin.NOTIFY_READ"
        const val Unlock = "com.godbless.lockin.NOTIFY_UNLOCK"
        const val NotYet = "com.godbless.lockin.NOTIFY_NOT_YET"
        const val ProtectAllow = "com.godbless.lockin.NOTIFY_PROTECT_ALLOW"
        const val ProtectNo = "com.godbless.lockin.NOTIFY_PROTECT_NO"
    }
}
