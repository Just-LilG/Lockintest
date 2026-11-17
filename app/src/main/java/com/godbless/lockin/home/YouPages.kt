@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.godbless.lockin.auth.Biometrics
import com.godbless.lockin.data.AuthRepository
import com.godbless.lockin.data.AuthResult
import com.godbless.lockin.data.Profile
import com.godbless.lockin.data.ProfileRepository
import com.godbless.lockin.push.NotifyKind
import com.godbless.lockin.push.NotifyPrefs
import com.godbless.lockin.push.Push
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.rise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val DayMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)

private fun dateText(millis: Long, f: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(f)

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** Who you are: kept on the server, with the name on this phone shown until the server answers. */
internal class YouState(
    private val repo: ProfileRepository,
    val auth: AuthRepository,
    private val scope: CoroutineScope,
    startName: String,
    private val context: android.content.Context? = null,
) {
    var profile by mutableStateOf<Profile?>(null)
        private set
    var name by mutableStateOf(startName)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** The sound your partner hears when you message them. Shows the new pick at once, then saves it. */
    var sound by mutableStateOf("message")
        private set
    var soundError by mutableStateOf<String?>(null)
        private set

    /** A notification kind was switched on or off: tell the server, so it stops (or starts) showing it. */
    fun syncMuted(context: android.content.Context) {
        val app = context.applicationContext
        scope.launch { com.godbless.lockin.push.PushRegistrar.syncMuted(app, repo.partners) }
    }

    fun pickSound(key: String) {
        val before = sound
        sound = key
        soundError = null
        scope.launch {
            repo.setSound(key).onFailure {
                sound = before
                soundError = "Couldn't save that sound. Try again."
            }
        }
    }

    /** Sends your avatar to the server; until it takes, the change stays marked as unsent and is retried. */
    fun saveAvatar(spec: com.godbless.lockin.data.AvatarSpec) {
        scope.launch { repo.setAvatar(spec).onSuccess { Identity.markPushed() } }
    }

    val displayName: String get() = name.trim().ifEmpty { "Climber" }
    val email: String get() = profile?.email?.ifBlank { null } ?: auth.userEmail()
    val sinceText: String
        get() = profile?.since?.takeIf { it > 0 }?.let { "Climbing since ${dateText(it, MonthYear)}" } ?: "Climbing with LockIn"

    suspend fun refresh() {
        repo.me().onFailure { if (profile == null) loadFailed = true }.onSuccess { p ->
            loadFailed = false
            profile = p
            sound = p.sound
            Identity.adopt(p.avatar)
            if (p.name.isNotBlank() && p.name != name) {
                name = p.name
                auth.setUserName(p.name)
            }
        }
    }

    /** Set while the account is being deleted, and when it could not be. */
    var deleting by mutableStateOf(false)
        private set
    var deleteError by mutableStateOf<String?>(null)

    /** True when the last attempt to load the profile failed (so "Account since" can offer a retry). */
    var loadFailed by mutableStateOf(false)
        private set

    /** Deletes the account on the server; [done] runs only when it is really gone (the caller then signs out). */
    fun deleteAccount(done: () -> Unit) {
        if (deleting) return
        deleteError = null
        // Deleting an account would also end a lock or a pairing, so it is held back while either is on. The
        // server refuses too (delete_my_account), so this is only the quick, offline-friendly answer.
        val ctx = context
        if (ctx != null && com.godbless.lockin.lock.LocalLock(ctx).active) {
            deleteError = "You can't delete your account while your phone is locked."
            return
        }
        if (ctx != null && com.godbless.lockin.lock.Protection.on(ctx)) {
            deleteError = "Unpair from your partner first (they have to agree), then you can delete your account."
            return
        }
        deleting = true
        scope.launch {
            repo.deleteAccount()
                .onSuccess { done() }
                .onFailure {
                    deleteError = (it as? com.godbless.lockin.data.ApiException)?.message?.takeIf { m -> "lock" in m || "Unpair" in m }
                        ?: "Couldn't delete your account just now. Check your connection and try again."
                }
            deleting = false
        }
    }

    /** Saves a new name. [done] runs only when the server took it. */
    fun rename(newName: String, done: () -> Unit) {
        val clean = newName.trim()
        if (clean.isEmpty()) {
            error = "Enter a name."
            return
        }
        if (busy) return
        busy = true
        error = null
        scope.launch {
            repo.rename(clean)
                .onSuccess {
                    name = it
                    auth.setUserName(it)
                    done()
                }
                .onFailure { error = it.message ?: "Couldn't save that. Try again." }
            busy = false
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Frame
// ---------------------------------------------------------------------------------------------

/** The frame for every page opened from You: back, a heading, and the page underneath. */
@Composable
internal fun YouSubFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    /** The page is laid out in [TwoColumns]: on a big screen it gets the full wide width. */
    wide: Boolean = false,
    content: @Composable ColumnScope.(State<Float>) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        PageScaffold(wide = wide) { entrance ->
            Row(Modifier.fillMaxWidth().rise(entrance, 0)) { BackButton(onBack) }
            Spacer(Modifier.height(14.dp))
            PageTitle(title, subtitle, entrance)
            Spacer(Modifier.height(20.dp))
            content(entrance)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(YouCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, YouCard)
            .then(if (padded) Modifier.padding(18.dp) else Modifier),
        content = content,
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
    }
}

// ---------------------------------------------------------------------------------------------
// Edit profile
// ---------------------------------------------------------------------------------------------

@Composable
internal fun EditProfilePage(you: YouState, onBack: () -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(you.name) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { you.error = null }
    YouSubFrame("Profile", "How your partner sees you.", onBack) { entrance ->
        Row(Modifier.fillMaxWidth().rise(entrance, 1), verticalAlignment = Alignment.CenterVertically) {
            Avatar(draft.ifBlank { you.displayName }, boxSize = 72.dp, filled = true, ring = LockInColors.Ink, me = true)
            Column(Modifier.padding(start = 14.dp)) {
                Text(draft.trim().ifEmpty { you.displayName }, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 28.sp), maxLines = 1)
                Text(you.sinceText, style = LockInText.Small.copy(color = LockInColors.Mist))
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.rise(entrance, 2)) {
            LockInField(
                label = "Name",
                value = draft,
                onValueChange = { if (it.length <= 40) draft = it; you.error = null },
                placeholder = "Ama Mensah",
                imeAction = ImeAction.Done,
                error = you.error,
                onDone = {
                    you.rename(draft) {
                        Toast.makeText(context, "Name saved.", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                },
            )
            Text("${draft.trim().length}/40", style = LockInText.Small, modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.rise(entrance, 3)) {
            InfoLine("Email", you.email.ifBlank { "Not available" })
            val sinceMs = you.profile?.since?.takeIf { it > 0 }
            if (sinceMs == null && you.loadFailed) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("Account since", style = LockInText.Small.copy(color = LockInColors.Mist))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), modifier = Modifier.weight(1f))
                        SmallPill("Retry", { scope.launch { you.refresh() } })
                    }
                }
            } else InfoLine("Account since", sinceMs?.let { dateText(it, DayMonthYear) } ?: "Loading…")
            Spacer(Modifier.height(4.dp))
            Text("Your email is how you sign in. It isn't shown to your partner.", style = LockInText.Small)
        }
        Spacer(Modifier.height(22.dp))
        LockInButton(
            "Save",
            onClick = {
                you.rename(draft) {
                    Toast.makeText(context, "Name saved.", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            },
            modifier = Modifier.rise(entrance, 4),
            loading = you.busy,
            enabled = !you.busy && draft.trim().isNotEmpty() && draft.trim() != you.name.trim(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------------------------

@Composable
internal fun NotificationsPage(you: YouState, plans: PlanState, onBack: () -> Unit) {
    val context = LocalContext.current
    val kinds = remember { mutableStateMapOf<NotifyKind, Boolean>().apply { NotifyKind.entries.forEach { put(it, NotifyPrefs.on(context, it)) } } }
    var systemOn by remember { mutableStateOf(true) }
    fun check() {
        systemOn = Push.canShow(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(Unit) { check() }
    // Coming back from the phone's settings: read the switch again.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) check() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun openSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }.onFailure { Toast.makeText(context, "This phone can't open that screen.", Toast.LENGTH_SHORT).show() }
    }

    YouSubFrame("Notifications", "Choose what LockIn tells you about.", onBack, wide = true) { entrance ->
        // On a tablet: the message sound and what you are told about on the left; how and when on the right.
        TwoColumns(leftWeight = 1f, gap = 24.dp, left = {
        SoundPicker(you, Modifier.rise(entrance, 1))
        Spacer(Modifier.height(18.dp))
        Collapse(!systemOn, Modifier.rise(entrance, 1)) {
            Column {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(LockInColors.Flare))
                        Text("Notifications are off for LockIn", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp), modifier = Modifier.padding(start = 10.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Your phone is blocking them, so the switches below can't do anything yet.", style = LockInText.Small.copy(color = LockInColors.Body))
                    Spacer(Modifier.height(12.dp))
                    LockInButton("Turn on in phone settings", onClick = ::openSettings)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        Card(Modifier.rise(entrance, 2), padded = false) {
            NotifyKind.entries.forEachIndexed { i, kind ->
                if (i > 0) TileDivider()
                SwitchLine(kind.title, kind.detail, kinds[kind] == true) {
                    kinds[kind] = it
                    NotifyPrefs.set(context, kind, it)
                    you.syncMuted(context)
                }
                // Each kind is its own channel on the phone, with its own sound and vibration.
                Collapse(kinds[kind] == true) {
                    Text(
                        "Sound and vibration",
                        style = LockInText.Small.copy(color = LockInColors.Lime),
                        modifier = Modifier
                            .padding(start = 16.dp, bottom = 12.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                com.godbless.lockin.push.Push.ensureChannels(context)
                                com.godbless.lockin.push.MessageSound.ensureChannel(context, com.godbless.lockin.push.Push.lastSound(context), "partner_group")
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            .putExtra(Settings.EXTRA_CHANNEL_ID, com.godbless.lockin.push.Push.channelFor(kind, context)),
                                    )
                                }.onFailure { openSettings() }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        }, right = {
        SoundCheck(Modifier.rise(entrance, 3))
        Spacer(Modifier.height(14.dp))
        QuietHoursCard(Modifier.rise(entrance, 3))
        Spacer(Modifier.height(14.dp))
        Card(Modifier.rise(entrance, 3), padded = false) {
            SwitchLine("Session reminders", "A nudge 10 minutes before a session you planned.", plans.reminders) { plans.setReminders(it) }
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.rise(entrance, 4), padded = false) {
            TileRow(Glyph.Bell, "Phone notification settings", null, ::openSettings)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Locks still take over your screen when Locks is off; you just won't get the notification.",
            style = LockInText.Small,
            modifier = Modifier.padding(horizontal = 6.dp).rise(entrance, 5),
        )
        })
    }
}

private fun minutesText(minutes: Int): String =
    java.time.LocalTime.of(minutes / 60 % 24, minutes % 60)
        .format(DateTimeFormatter.ofPattern(if (ClockPrefs.is24h) "HH:mm" else "h:mm a", Locale.getDefault()))

/** Quiet hours: nothing is shown between two times. Locks still come through, because they need you. */
@Composable
private fun QuietHoursCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NotifyPrefs.quietOn(context)) }
    var from by remember { mutableStateOf(NotifyPrefs.quietFrom(context)) }
    var to by remember { mutableStateOf(NotifyPrefs.quietTo(context)) }
    fun pick(initial: Int, set: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> set(h * 60 + m) }, initial / 60, initial % 60, ClockPrefs.is24h).show()
    }
    Card(modifier, padded = false) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ToggleRow("Quiet hours", on, { on = it; NotifyPrefs.setQuiet(context, it) })
            Text("Messages, nudges and plans wait quietly. Locks still come through.", style = LockInText.Small, modifier = Modifier.padding(end = 60.dp))
        }
        Collapse(on) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeChip("From", minutesText(from), Modifier.weight(1f)) { pick(from) { from = it; NotifyPrefs.setQuietFrom(context, it) } }
                TimeChip("Until", minutesText(to), Modifier.weight(1f)) { pick(to) { to = it; NotifyPrefs.setQuietTo(context, it) } }
            }
        }
    }
}

@Composable
private fun TimeChip(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        ToggleRow(title, checked, onChange)
        Text(detail, style = LockInText.Small, modifier = Modifier.padding(end = 60.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Your partner
// ---------------------------------------------------------------------------------------------

@Composable
internal fun PartnerInfoPage(
    partner: PartnerState,
    locks: LockState,
    onChat: () -> Unit,
    onInvite: () -> Unit,
    onBack: () -> Unit,
) {
    val paired = partner.paired
    val stats = LocalStats.current
    LaunchedEffect(paired?.id) { if (paired != null) locks.loadRules() }

    YouSubFrame("Your partner", if (paired != null) "The person you climb with." else "Climb with someone.", onBack) { entrance ->
        if (paired == null) {
            Card(Modifier.rise(entrance, 1)) {
                Text("You're climbing solo", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                Spacer(Modifier.height(4.dp))
                Text("Pair with a friend to lock each other in, chat, plan sessions and see each other's progress.", style = LockInText.Small.copy(color = LockInColors.Body))
                Spacer(Modifier.height(14.dp))
                LockInButton("Invite your partner", onClick = onInvite)
            }
            return@YouSubFrame
        }
        Card(Modifier.rise(entrance, 1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(paired.partnerName, boxSize = 64.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(paired.partnerName, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 28.sp), maxLines = 1)
                    Text("Paired since ${dateText(paired.since, DayMonthYear)}", style = LockInText.Small.copy(color = LockInColors.Mist))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Message", onClick = onChat, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.rise(entrance, 2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberTile("Days together", "${stats.daysTogether}", Modifier.weight(1f))
                NumberTile("Hours together", formatMinutes((stats.hoursTogether * 60).toInt()), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberTile("Sessions together", "${stats.sessionsTogether}", Modifier.weight(1f))
                NumberTile("Best streak together", if (stats.bestTogetherStreak == 1) "1 day" else "${stats.bestTogetherStreak} days", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionTitle("Lock rules", Modifier.rise(entrance, 3))
        Spacer(Modifier.height(12.dp))
        RulesCard(locks, Modifier.rise(entrance, 4))
        Spacer(Modifier.height(28.dp))
        UnpairCard(partner, Modifier.rise(entrance, 5))
    }
}

@Composable
private fun NumberTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(YouCard)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, YouCard)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist))
        Text(value, style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Sign-in and security
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SecurityPage(you: YouState, onSignOut: () -> Unit, onConfirm: (ConfirmSpec) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val auth = you.auth
    val saved = remember { auth.savedUser() }
    val canBio = remember { Biometrics.available(context) }
    var bio by remember { mutableStateOf(saved?.biometric == true) }

    var changing by rememberSaveable { mutableStateOf(false) }
    var current by rememberSaveable { mutableStateOf("") }
    var noPassword by rememberSaveable { mutableStateOf(false) }
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    var pwError by remember { mutableStateOf<String?>(null) }
    var pwBusy by remember { mutableStateOf(false) }
    var pwDone by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun savePassword() {
        pwError = when {
            !noPassword && current.isEmpty() -> "Enter your current password."
            pw.length < 8 -> "Use at least 8 characters."
            pw != pw2 -> "The two passwords don't match."
            else -> null
        }
        if (pwError != null || pwBusy) return
        pwBusy = true
        scope.launch {
            // Prove it's you first (unless this account has no password yet, like one made with Google).
            if (!noPassword) {
                val check = auth.signIn(you.email, current, remember = saved != null)
                if (check is AuthResult.Failure) {
                    pwError = "That isn't your current password."
                    pwBusy = false
                    return@launch
                }
            }
            when (val r = auth.setNewPassword(pw, remember = saved != null)) {
                is AuthResult.Failure -> pwError = r.message
                else -> {
                    pwDone = true
                    current = ""
                    pw = ""
                    pw2 = ""
                    changing = false
                    Toast.makeText(context, "Password changed.", Toast.LENGTH_SHORT).show()
                }
            }
            pwBusy = false
        }
    }

    YouSubFrame("Sign-in and security", "Keep your account yours.", onBack) { entrance ->
        Card(Modifier.rise(entrance, 1)) {
            InfoLine("Signed in as", you.email.ifBlank { you.displayName })
            InfoLine("This phone", if (saved != null) "Remembers you" else "Signs you out when the session ends")
        }
        Spacer(Modifier.height(14.dp))

        Card(Modifier.rise(entrance, 2), padded = false) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ToggleRow("Fingerprint sign-in", bio && canBio && saved != null, { on ->
                    when {
                        !canBio -> Toast.makeText(context, "Set up a fingerprint or face unlock on this phone first.", Toast.LENGTH_LONG).show()
                        saved == null -> Toast.makeText(context, "Sign in with \"Remember me\" on to use this.", Toast.LENGTH_LONG).show()
                        !on -> { auth.setBiometric(false); bio = false }
                        else -> {
                            val activity = context.findActivity() as? FragmentActivity
                            if (activity == null) {
                                auth.setBiometric(true); bio = true
                            } else {
                                Biometrics.prompt(activity, "Turn on fingerprint sign-in", "Confirm it's you") { ok ->
                                    if (ok) { auth.setBiometric(true); bio = true }
                                }
                            }
                        }
                    }
                })
                Text(
                    when {
                        !canBio -> "This phone has no fingerprint or face unlock set up."
                        saved == null -> "Available when this phone remembers you."
                        else -> "Open LockIn with your fingerprint instead of your password."
                    },
                    style = LockInText.Small,
                    modifier = Modifier.padding(end = 60.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Card(Modifier.rise(entrance, 3)) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { changing = !changing; pwError = null },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Password", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Text(if (pwDone) "Changed just now" else "Change the password you sign in with", style = LockInText.Small)
                }
                SmallPill(if (changing) "Cancel" else "Change", { changing = !changing; pwError = null })
            }
            Collapse(changing) {
                Column(Modifier.padding(top = 14.dp)) {
                    if (!noPassword) {
                        LockInField("Current password", current, { current = it; pwError = null }, "The one you use now", isPassword = true)
                        Spacer(Modifier.height(10.dp))
                    }
                    LockInField("New password", pw, { pw = it; pwError = null }, "At least 8 characters", isPassword = true)
                    Spacer(Modifier.height(10.dp))
                    LockInField(
                        "Type it again", pw2, { pw2 = it; pwError = null }, "Same as above",
                        isPassword = true, imeAction = ImeAction.Done, error = pwError, onDone = ::savePassword,
                    )
                    Spacer(Modifier.height(14.dp))
                    LockInButton("Save password", onClick = ::savePassword, loading = pwBusy, enabled = !pwBusy)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (noPassword) "I do have a password" else "No password yet? I signed in with Google",
                        style = LockInText.Small.copy(color = LockInColors.Lime),
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { noPassword = !noPassword; pwError = null }.padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        LockInButton(
            "Sign out",
            onClick = { onConfirm(ConfirmSpec("Sign out?", "You'll need your email and password to get back in. Anything waiting to sync is sent first.", "Sign out", danger = false, onConfirm = onSignOut)) },
            kind = ButtonKind.Outline,
            modifier = Modifier.rise(entrance, 4),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Your message sound
// ---------------------------------------------------------------------------------------------

/**
 * The sound your partner's phone plays when you message them. Tap one to hear it; the one with the lime ring is
 * yours. The picked chip bounces, and a little wave plays across it while the sound is heard.
 */
@Composable
private fun SoundPicker(you: YouState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val partner = LocalPartnerName.current
    Column(modifier.fillMaxWidth()) {
        Text("Your message sound", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
        Spacer(Modifier.height(2.dp))
        Text(
            "What $partner hears when you message them. Tap one to hear it.",
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
        Spacer(Modifier.height(12.dp))
        val sounds = com.godbless.lockin.push.MessageSound.entries
        sounds.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    SoundChip(s.title, you.sound == s.key, Modifier.weight(1f)) {
                        com.godbless.lockin.push.MessageSound.play(context, s)
                        if (you.sound != s.key) you.pickSound(s.key)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        you.soundError?.let { Text(it, style = LockInText.Error) }
    }
}

@Composable
private fun SoundChip(title: String, picked: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    // A short wave runs across the chip each time it's tapped, as if the sound is moving through it.
    val wave = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pop by androidx.compose.animation.core.animateFloatAsState(
        if (picked) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "picked",
    )
    Row(
        modifier
            .height(52.dp)
            // A small bounce when it becomes your pick.
            .graphicsLayer { val k = 1f + 0.16f * pop * (1f - pop); scaleX = k; scaleY = k }
            .clip(shape)
            .background(if (picked) LockInColors.LimeWash else LockInColors.Slate)
            .border(if (picked) 2.dp else 1.dp, if (picked) LockInColors.Lime else LockInColors.Graphite, shape)
            .springClick {
                onTap()
                scope.launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, androidx.compose.animation.core.tween(900))
                }
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Three bars that bounce while the wave passes.
        androidx.compose.foundation.Canvas(Modifier.size(width = 18.dp, height = 18.dp)) {
            val w = size.width / 5f
            for (i in 0..2) {
                val phase = (wave.value * 3f - i * 0.25f).coerceIn(0f, 1f)
                val bump = if (wave.value in 0.001f..0.999f) kotlin.math.sin(phase * Math.PI).toFloat() else 0f
                val h = size.height * (0.35f + 0.55f * bump + if (picked) 0.1f else 0f)
                drawRoundRect(
                    if (picked) LockInColors.Lime else LockInColors.Silver,
                    topLeft = androidx.compose.ui.geometry.Offset(i * 2 * w, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                )
            }
        }
        Text(
            title,
            style = LockInText.Label.copy(color = if (picked) LockInColors.Snow else LockInColors.Body, fontSize = 14.sp),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            maxLines = 1,
        )
        if (picked) Box(Modifier.size(9.dp).clip(CircleShape).background(LockInColors.Lime))
    }
}

// ---------------------------------------------------------------------------------------------
// Sound check: can your partner's messages actually make a sound on this phone?
// ---------------------------------------------------------------------------------------------

/**
 * Looks for anything on this phone that keeps messages quiet (a silenced channel, Do not disturb, silent mode,
 * volume at zero) and says so, with a way to fix it. "Send a test" posts a message notification in 5 seconds, so
 * you can lock the screen and hear exactly what your partner's messages sound like.
 */
@Composable
private fun SoundCheck(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val partner = LocalPartnerName.current
    // Checked again each time you come back to LockIn, say after fixing something in the phone's settings.
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val problem = remember(tick) { com.godbless.lockin.push.Push.soundProblem(context) }
    var sent by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(sent) {
        if (sent) { kotlinx.coroutines.delay(6000); sent = false }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(if (problem != null) 1.5.dp else 1.dp, if (problem != null) LockInColors.Flare else LockInColors.Graphite, shape)
            .then(Modifier.animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f)))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (problem != null) LockInColors.Flare else LockInColors.Lime))
            Text(
                problem?.title ?: "Message sounds are on",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            problem?.detail ?: "$partner's messages should ring with their sound. Send a test to hear it.",
            style = LockInText.Small.copy(color = LockInColors.Body),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (problem != null) {
                LockInButton("Fix it", onClick = {
                    runCatching {
                        val intent = if (problem.channel != null) {
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, problem.channel)
                        } else {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        }
                        context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }, modifier = Modifier.weight(1f))
            }
            LockInButton(
                if (sent) "Lock your screen…" else "Send a test",
                onClick = {
                    com.godbless.lockin.push.Push.sendTest(context, partner, com.godbless.lockin.push.Push.lastSound(context))
                    sent = true
                },
                kind = ButtonKind.Outline,
                enabled = !sent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
