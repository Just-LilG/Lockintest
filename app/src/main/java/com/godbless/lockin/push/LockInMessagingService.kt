package com.godbless.lockin.push

import com.godbless.lockin.LockInApplication
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.home.formatMinutes
import com.godbless.lockin.home.planWhen
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.usage.Guard
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Receives pushes from Firebase. The server sends data only, so this decides what to show. */
class LockInMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        PushRegistrar.saveToken(this, token)
        val auth = (application as LockInApplication).auth
        scope.launch { PushRegistrar.sync(applicationContext, PartnerRepository(auth)) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "crew") {
            crew(data)
            return
        }
        if (data["type"] != "chat") return
        // Your partner just set or lifted a limit on this phone: pick it up now, even with LockIn open.
        if (data["event"].orEmpty().startsWith("limit_")) Guard.start(this, refresh = true)
        // A lock changed: tell Home to look now, and if your partner just locked you, bring LockIn to the front.
        val event = data["event"].orEmpty()
        if (event.isEmpty()) Push.rememberSound(this, data["sound"])
        // A plan was made or cancelled: Home fetches the plans now.
        if (event == "plan_set" || event == "plan_canceled") (application as LockInApplication).lockPing.update { it + 1 }
        // A repeating lock was set or removed on this phone: Home fetches the repeats and sets the next alarm.
        if (event == "lock_repeat" || event == "lock_repeat_off") (application as LockInApplication).lockPing.update { it + 1 }
        if (event in LockEvents) {
            (application as LockInApplication).lockPing.update { it + 1 }
        }
        if (event == "lock_set" && !Push.inForeground) LockGuard(this).bringBack()
        // Hold the lock right away, before LockIn is even opened: lock_set|<minutes>. The server corrects the
        // time on the first check-in. A lock lifted by the locker ends here too.
        if (event == "lock_set") {
            val mins = data["detail"].orEmpty().split('|').getOrNull(1)?.toIntOrNull()
            if (mins != null) com.godbless.lockin.lock.LockService.hold(this, -1L, mins * 60, data["title"].orEmpty().ifBlank { "Your partner" })
        }
        if (event == "lock_lifted") com.godbless.lockin.lock.LockService.end(this)
        // A lock set for later: wake at its start. A cancelled one takes the alarm back.
        if (event == "lock_planned") {
            data["detail"].orEmpty().split('|').getOrNull(2)?.toLongOrNull()?.let { LockStartAlarm.set(this, it * 1000L) }
        }
        if (event == "lock_canceled") LockStartAlarm.cancel(this)
        // With LockIn open the screen refreshes itself within seconds, so a notification would only repeat it.
        // A message still plays your partner's sound, so you hear it come in. A warning about a lock you set is
        // the exception: it should never be missed.
        if (Push.inForeground && event != "lock_tamper" && event != "protect_tamper") {
            (application as LockInApplication).chatPing.update { it + 1 }
            // In the chat itself the new-message sound is played as the line lands (ChatState), not the sender's pick.
            if (event.isEmpty() && !Push.chatOpen && NotifyPrefs.allows(this, "")) {
                MessageSound.alert(this, MessageSound.of(data["sound"]))
            }
            if (event == "nudge" && NotifyPrefs.allows(this, event)) AppSound.Nudge.play(this)
            if (event.startsWith("game_") && NotifyPrefs.allows(this, event)) AppSound.GameRequest.play(this)
            return
        }
        // You switched this kind off on the Notifications page (You tab).
        if (!NotifyPrefs.allows(this, event.substringBefore('|'))) return
        val title = data["title"].orEmpty().ifBlank { "Your partner" }
        var text = data["body"].orEmpty()
        // A planned session reads best with its time in this phone's own time zone.
        if (event == "lock_planned") {
            val parts = data["detail"].orEmpty().split('|')
            val at = parts.getOrNull(2)?.toLongOrNull()
            val len = parts.getOrNull(1)?.toIntOrNull()?.let { formatMinutes(it) }
            if (at != null && len != null) text = "scheduled a $len lock for ${planWhen(at * 1000L)}"
        }
        if (event == "plan_set") {
            val parts = data["detail"].orEmpty().split('|')
            val at = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size >= 3 && at != null) text = "planned ${parts[1]} for ${planWhen(at * 1000L)}"
        }
        Push.show(this, event, title, text, data["partnership"].orEmpty(), data["sound"].orEmpty().ifBlank { "message" })
    }

    /**
     * A crew push. Android shows it by itself while LockIn is closed; this runs when LockIn is open (or the kind
     * is switched off): the Crew tab refreshes, a message plays its sender's sound, and a new session still gets
     * a notification, since you may be on another tab.
     */
    private fun crew(data: Map<String, String>) {
        (application as LockInApplication).crewPing.update { it + 1 }
        val event = data["event"].orEmpty()
        if (!Push.inForeground) return
        if (event.isEmpty()) {
            if (NotifyPrefs.allows(this, "")) MessageSound.alert(this, MessageSound.of(data["sound"]))
            return
        }
        if (event == "session_start" && NotifyPrefs.allows(this, "lock_crew")) {
            Push.showCrew(this, data["title"].orEmpty().ifBlank { "Your crew" }, data["body"].orEmpty())
        }
    }

    private companion object {
        /** Events that change a lock, so Home looks at the server straight away. */
        val LockEvents = setOf(
            "lock_set", "lock_lifted", "emergency_release", "lock_extended", "unlock_denied", "asked_unlock",
            "lock_planned", "lock_canceled", "protect_asked", "protect_allowed", "protect_declined",
        )
    }
}
