package com.godbless.lockin.home

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clipToBounds
import com.godbless.lockin.ui.readable
import androidx.compose.foundation.layout.navigationBars
import com.godbless.lockin.ui.Widths
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import com.godbless.lockin.ui.twoPane
import com.godbless.lockin.ui.isTablet
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import com.godbless.lockin.LockInApplication
import com.godbless.lockin.push.Push
import com.godbless.lockin.push.PushRegistrar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import com.godbless.lockin.ui.safeTake
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.godbless.lockin.intro.clamp01
import com.godbless.lockin.intro.easeOutCubic
import com.godbless.lockin.study.CalculatorScreen
import com.godbless.lockin.study.LibraryScreen
import com.godbless.lockin.study.QuizScreen
import com.godbless.lockin.study.ReaderScreen
import com.godbless.lockin.study.StudyKitCard
import com.godbless.lockin.study.StudyKitRow
import com.godbless.lockin.study.StudyStore
import com.godbless.lockin.study.Tool
import com.godbless.lockin.ui.ContourBackdrop
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rememberEntrance
import com.godbless.lockin.ui.rise
import com.godbless.lockin.ui.riseSpring
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import com.godbless.lockin.data.Pairing
import com.godbless.lockin.data.PartnerApp
import com.godbless.lockin.data.PartnerRepository
import com.godbless.lockin.data.PlanRepository
import com.godbless.lockin.data.RemoteLockRepository
import com.godbless.lockin.data.UsageRepository
import com.godbless.lockin.data.SessionRepository
import com.godbless.lockin.lock.LockGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/** How long the "partner locks me" preview lasts, so you can see it end without waiting. */
private const val PreviewSeconds = 90

/** How wide the floating tab bar is, as a share of the screen width. */
private const val TabBarWidth = 0.76f

/**
 * The signed-in app. It owns the tab bar and the running session, and slides between the four
 * pages. Sessions come from [SessionState], goals and plans from [PlanState].
 */
@Composable
fun HomeScreen(
    name: String,
    auth: com.godbless.lockin.data.AuthRepository,
    partners: PartnerRepository,
    sessions: SessionRepository,
    incomingCode: String?,
    onIncomingHandled: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val entrance = rememberEntrance()
    remember { Identity.load(context) }

    var tab by rememberSaveable { mutableStateOf(0) }
    // The lock screen is open, and the session inside it is running (it counts down for three seconds first).
    var locked by rememberSaveable { mutableStateOf(false) }
    var running by rememberSaveable { mutableStateOf(false) }
    var elapsed by rememberSaveable { mutableStateOf(0) }
    // The timer only runs during a session. It keeps its value afterwards so the summary can show it.
    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            elapsed += 1
        }
    }
    var pickups by rememberSaveable { mutableStateOf(0) }
    // The lock on your partner that your own session was started with ("Lock together"), or 0.
    var togetherWith by rememberSaveable { mutableStateOf(0L) }
    var showProtection by rememberSaveable { mutableStateOf(false) }
    var showLockPage by rememberSaveable { mutableStateOf(false) }
    // The Start choice (with a partner): lock in on your own, or lock their phone.
    var showStart by rememberSaveable { mutableStateOf(false) }
    // The You tab's sheets live up here, over the tab bar and clear of the gesture bar.
    var goalOpen by rememberSaveable { mutableStateOf(false) }
    var avatarOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }

    val scope = rememberCoroutineScope()
    val partner = remember { PartnerState(partners, scope) }
    val chat = remember { ChatState(partners, scope) }
    val history = remember { SessionState(sessions, scope) }
    val update = remember { UpdateState(context, scope) }
    val usage = remember { UsageState(UsageRepository(partners), context, scope) }
    val locks = remember {
        LockState(RemoteLockRepository(partners), scope).also { l ->
            // Repeating locks set on this phone: wake at the next start, even with LockIn closed.
            l.onRepeats = { com.godbless.lockin.push.RepeatAlarm.sync(context, it) }
            // Apps your partner allows during their locks: kept on the phone for the lock service.
            l.onAllowedMine = { com.godbless.lockin.lock.AllowedStore(context).save(it) }
        }
    }
    // Devices: every phone each of you uses LockIn on.
    val devices = remember { DevicesState(com.godbless.lockin.data.DeviceRepository(partners), context, scope) }

    // Crew: 3 to 8 friends on one rope (the Crew tab), and its chat.
    val crew = remember { CrewState(com.godbless.lockin.data.CrewRepository(partners), scope, context) }
    var showCrewChat by rememberSaveable { mutableStateOf(false) }

    // Activity: what your partner and your crew did or said, behind the bell on Home.
    val inbox = remember { InboxState(context) }
    var showInbox by rememberSaveable { mutableStateOf(false) }
    // The full history of climbs: it is no tab any more, it opens from Home's "See all".
    var showClimbs by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (tab == 1) tab = 0 }
    val inboxItems by remember {
        androidx.compose.runtime.derivedStateOf { buildInbox(chat.messages.toList(), crew.lines.toList(), partner.name, partners.signedInUser()) }
    }
    val unreadCount = inboxItems.count { inbox.unread(it) }
    LaunchedEffect(chat.loaded, inboxItems, crew.lines.size) {
        inbox.seed(
            if (chat.loaded) inboxItems.filter { !it.crew }.maxOfOrNull { it.id } ?: 0L else null,
            if (crew.lines.isNotEmpty()) inboxItems.filter { it.crew }.maxOfOrNull { it.id } ?: 0L else null,
        )
    }

    // You: your profile (the name you edit on the You tab), and which page from there is open.
    val you = remember { YouState(com.godbless.lockin.data.ProfileRepository(partners), auth, scope, name, context.applicationContext) }
    LaunchedEffect(Unit) {
        Identity.syncUp = { spec -> you.saveAvatar(spec) }
        you.refresh()
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Identity.syncUp = null } }
    var youPage by rememberSaveable { mutableStateOf(YouSub.None) }
    // Kept while a page slides out, so it doesn't go blank on the way.
    var shownYouPage by remember { mutableStateOf(YouSub.None) }
    if (youPage != YouSub.None) shownYouPage = youPage
    val plans = remember { PlanState(context.applicationContext, PlanRepository(partners), scope) }
    // The sheet for planning a session (it opens from Home and from the Partner tab).
    var showPlan by rememberSaveable { mutableStateOf(false) }
    // The app on your partner's phone that you are setting a limit on (null: the picker is closed).
    var limitApp by remember { mutableStateOf<PartnerApp?>(null) }
    var shownLimitApp by remember { mutableStateOf<PartnerApp?>(null) }
    if (limitApp != null) shownLimitApp = limitApp

    // The study tools. They open over anything, including the lock screens. tool: 0 none, 1 calculator,
    // 2 textbooks, 3 quiz. The reader sits on top of those (readerBook is empty when it is closed).
    val study = remember { StudyStore(context) }
    // A quiz your partner sent, opened from the chat (its chat line's code), until it's closed.
    var partnerQuiz by rememberSaveable { mutableStateOf<String?>(null) }
    var tool by rememberSaveable { mutableStateOf(0) }
    var readerBook by rememberSaveable { mutableStateOf("") }
    var readerPage by rememberSaveable { mutableStateOf(-1) }
    fun openTool(t: Tool) { tool = t.ordinal + 1 }
    // Kept while the reader slides out, so it doesn't go blank on the way.
    var shownBook by remember { mutableStateOf("") }
    if (readerBook.isNotEmpty()) shownBook = readerBook
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showScan by rememberSaveable { mutableStateOf(false) }
    // The in-chat sound: a new message from your partner while the chat is on screen.
    DisposableEffect(showChat) {
        Push.chatOpen = showChat
        onDispose { Push.chatOpen = false }
    }
    // Reading the chat, the crew chat or the Activity list counts as seeing it: the bell clears, and so do the
    // notifications still sitting in the shade (they used to stay there looking unread after you had read them).
    LaunchedEffect(showChat, inboxItems) {
        if (showChat) {
            inbox.markSeen(inboxItems, partner = true, crew = false)
            Push.clear(context)
        }
    }
    LaunchedEffect(showCrewChat, inboxItems) {
        if (showCrewChat) {
            inbox.markSeen(inboxItems, partner = false, crew = true)
            Push.clearCrew(context)
        }
    }
    LaunchedEffect(showInbox) { if (showInbox) Push.clearActivity(context) }
    LaunchedEffect(chat) {
        // Where "read up to here" is kept for each chat, for the "New messages" line.
        val readPrefs = context.getSharedPreferences("chat_read", android.content.Context.MODE_PRIVATE)
        chat.readGet = { pid -> readPrefs.getLong("read_$pid", 0L) }
        chat.readSet = { pid, id -> readPrefs.edit().putLong("read_$pid", id).apply() }
        chat.onIncoming = {
            if (Push.chatOpen && com.godbless.lockin.push.NotifyPrefs.allows(context, "")) {
                com.godbless.lockin.push.AppSound.NewMessage.play(context)
            }
        }
    }

    // Push. Register this phone for the signed-in person, ask once for the notification permission when
    // there is a partner to hear from, and open the chat when a notification was tapped.
    LaunchedEffect(Unit) { PushRegistrar.sync(context, partners) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // While paired, switching LockIn off (admin, guard, overlay, uninstall, sign-out) needs the partner's OK.
    // Only a settled answer changes it: Loading and failed refreshes leave it as it was.
    LaunchedEffect(partner.pairing, partner.paired?.partnerName) {
        when (partner.pairing) {
            is com.godbless.lockin.data.Pairing.Loading -> Unit
            else -> com.godbless.lockin.lock.Protection.update(context, partner.paired != null, partner.name, auth.userId())
        }
    }
    LaunchedEffect(partner.paired != null) {
        if (partner.paired != null && Build.VERSION.SDK_INT >= 33 && !Push.canShow(context) && PushRegistrar.shouldAsk(context)) {
            PushRegistrar.markAsked(context)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // A notification asked for the lock page or the Partner tab.
    val openTargetFlag = (context.applicationContext as LockInApplication).openTarget
    val wantTarget by openTargetFlag.collectAsState()
    // A widget's Lock in, handled below once the lock state is known.
    var widgetStart by remember { mutableStateOf(false) }
    LaunchedEffect(wantTarget, partner.paired?.id) {
        when (wantTarget) {
            null -> return@LaunchedEffect
            // From a widget's Lock in: the same as Home's Start (with a partner it asks which kind of lock).
            Push.OpenStart -> { showChat = false; showCrewChat = false; showInbox = false; tab = 0; widgetStart = true }
            Push.OpenClimbs -> { showChat = false; showCrewChat = false; showInbox = false; tab = 0; showClimbs = true }
            Push.OpenLock -> if (partner.paired != null) showLockPage = true
            Push.OpenPartner -> tab = 2
            Push.OpenCrew -> tab = 3
            Push.OpenCrewChat -> { tab = 3; showCrewChat = true }
            // "They reached the summit": back to Home.
            Push.OpenParty -> { showChat = false; showCrewChat = false; showInbox = false; tab = 0 }
        }
        openTargetFlag.value = null
    }
    val openChatFlag = (context.applicationContext as LockInApplication).openChat
    val wantChat by openChatFlag.collectAsState()
    LaunchedEffect(wantChat, partner.pairing) {
        if (!wantChat) return@LaunchedEffect
        when (partner.pairing) {
            is Pairing.Paired -> { showChat = true; openChatFlag.value = false }
            Pairing.Loading -> Unit // wait for the answer
            else -> openChatFlag.value = false // no partner, so there is no chat to open
        }
    }

    // A lock your partner set on you: how long, how much is left, how it ended (0 running, 1 finished, 2 released).
    var partnerLocked by rememberSaveable { mutableStateOf(false) }
    var partnerTotal by rememberSaveable { mutableStateOf(PreviewSeconds) }
    var partnerRemaining by rememberSaveable { mutableStateOf(0) }
    var partnerEnd by rememberSaveable { mutableStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(false) }
    // When the unlock request went out, and when asking is allowed again after a "not yet" (phone clock, ms; 0 = unknown).
    var askedAt by rememberSaveable { mutableStateOf(0L) }
    var retryAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(asked) {
        if (!asked) askedAt = 0L else if (askedAt == 0L) askedAt = System.currentTimeMillis()
    }
    // What the lock on this phone says: the note from your partner, whether you may ask out, and how asking went.
    var partnerMessage by rememberSaveable { mutableStateOf("") }
    var partnerStrict by rememberSaveable { mutableStateOf(false) }
    var partnerTogether by rememberSaveable { mutableStateOf(false) }
    var partnerDenied by rememberSaveable { mutableStateOf(false) }
    // Which real lock the screen above is showing (0 for none, or for the design preview).
    var partnerLockId by rememberSaveable { mutableStateOf(0L) }
    val partnerActive = partnerLocked && partnerEnd == 0
    val sessionActive = running || partnerActive
    LaunchedEffect(partnerLocked) {
        while (partnerLocked && partnerEnd == 0 && partnerRemaining > 0) {
            delay(1000)
            partnerRemaining -= 1
        }
        if (partnerLocked && partnerEnd == 0) {
            partnerEnd = 1
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // A partner lock, once it ends, counts as a session for the person who was locked (history, stats, goal).
    // Its id is fixed per lock, so it is saved once even if this runs again or on another phone.
    var recordedLock by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(partnerEnd, partnerLockId) {
        if (partnerEnd == 0 || partnerLockId == 0L || recordedLock == partnerLockId) return@LaunchedEffect
        recordedLock = partnerLockId
        val held = (partnerTotal - partnerRemaining).coerceAtLeast(0)
        // A lock that had a protection switched off during it was voided (see LocalLock.voided): it doesn't count.
        val lockHere = com.godbless.lockin.lock.LocalLock(context)
        if (lockHere.id == partnerLockId && lockHere.voided) return@LaunchedEffect
        history.record(
            startedAt = System.currentTimeMillis() - held * 1000L,
            seconds = held,
            pickups = pickups,
            title = "Locked by ${partner.name}",
            id = lockSessionId(partnerLockId),
        )
    }

    // However the lock ended on this screen (time up, unlocked, emergency release), let go of it everywhere at once.
    LaunchedEffect(partnerEnd) {
        if (partnerEnd != 0 && partnerLockId != 0L) com.godbless.lockin.lock.LockService.end(context)
    }

    // Opened with no signal (or before the server answers) during a lock: the phone's own record brings the
    // lock screen straight back, so going offline or killing LockIn is no way out.
    LaunchedEffect(Unit) {
        val local = com.godbless.lockin.lock.LocalLock(context)
        if (local.active && !partnerLocked) {
            partnerMessage = ""
            partnerStrict = false
            partnerTogether = false
            partnerDenied = false
            partnerLockId = local.id
            partnerRemaining = (local.remainingMillis() / 1000L).toInt()
            partnerTotal = maxOf(partnerTotal, partnerRemaining)
            partnerEnd = 0
            asked = false
            partnerLocked = true
        }
    }

    // A widget's Lock in: the same as Home's Start (with a partner it asks which kind of lock). Waits until
    // it's known whether there is a partner; does nothing while a lock already holds the phone.
    LaunchedEffect(widgetStart, partner.pairing is Pairing.Loading) {
        if (!widgetStart || partner.pairing is Pairing.Loading) return@LaunchedEffect
        widgetStart = false
        if (locked || partnerLocked) return@LaunchedEffect
        if (partner.paired != null) {
            showStart = true
        } else {
            elapsed = 0
            pickups = 0
            running = false
            locked = true
        }
    }

    // Home-screen widgets: a copy of what they show, kept on the phone for them to draw from while LockIn is
    // closed. Saved (off the main thread) whenever one of these changes; the widgets redraw only on a real change.
    val widgetStats = history.stats
    val widgetNext = plans.next(System.currentTimeMillis())
    LaunchedEffect(
        widgetStats, plans.goalMinutes, plans.partnerGoalMinutes, partner.paired?.id, partner.name, you.name,
        locks.mine, locks.theirs, locks.theirsEndsAt, running, widgetNext?.id,
    ) {
        delay(400)
        val now = System.currentTimeMillis()
        val mine = locks.mine?.takeIf { it.active }
        val theirs = locks.theirs?.takeIf { it.startsIn <= 0 && locks.theirsEndsAt > now }
        val s = widgetStats
        val paired = partner.paired != null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.godbless.lockin.widget.WidgetSnapshot.publish(
                    context,
                    todaySeconds = s.todaySeconds,
                    partnerTodaySeconds = if (paired) s.partnerTodaySeconds else 0,
                    goalMinutes = plans.goalMinutes,
                    partnerGoalMinutes = plans.partnerGoalMinutes,
                    streak = s.streak,
                    week = s.week,
                    weekSeconds = s.weekSeconds,
                    lastWeekSeconds = s.lastWeekSeconds,
                    weekDelta = s.weekDelta,
                    weekUp = s.weekUp,
                    myName = you.name,
                    partnerName = if (paired) partner.name else null,
                    sessionStart = if (running) now - elapsed * 1000L else 0L,
                    lockOnMeEnd = if (mine != null) now + mine.remaining * 1000L else 0L,
                    lockOnMeTotal = mine?.seconds ?: 0,
                    lockOnMeBy = if (mine != null) partner.name else "",
                    lockOnThemEnd = if (theirs != null) locks.theirsEndsAt else 0L,
                    lockOnThemTotal = theirs?.seconds ?: 0,
                    nextTitle = widgetNext?.title ?: "",
                    nextAt = widgetNext?.startsAt ?: 0L,
                )
            }
        }
    }

    // Your partner sees how many times you picked up your phone during their lock.
    LaunchedEffect(pickups) {
        if (partnerActive && partnerLockId != 0L && pickups > 0) locks.report(pickups)
    }

    // What the server says about a lock on this phone decides the lock screen: a new lock takes over the
    // screen, the timer is kept in step with the server's clock, and a lock that ended elsewhere (your
    // partner unlocked you) shows how it ended.
    LaunchedEffect(locks.mine) {
        val m = locks.mine ?: return@LaunchedEffect
        // A lock your partner scheduled for later: wake the app when it begins, even if LockIn is closed by then.
        if (m.upcoming) {
            com.godbless.lockin.push.LockStartAlarm.set(context, System.currentTimeMillis() + m.startsIn * 1000L)
        } else {
            com.godbless.lockin.push.LockStartAlarm.cancel(context)
        }
        // Remembered outside the app so a restart mid-lock brings the lock straight back.
        com.godbless.lockin.push.LockStartAlarm.running(context, if (m.active) System.currentTimeMillis() + m.remaining * 1000L else 0L)
        // The lock service holds the lock even when LockIn is closed, swiped away or offline.
        if (m.active) com.godbless.lockin.lock.LockService.hold(context, m.id, m.remaining, m.by)
        else com.godbless.lockin.lock.LockService.end(context)
        if (m.active) {
            partnerMessage = m.message
            partnerStrict = m.strict
            partnerTogether = m.together
            partnerDenied = m.answer == "denied"
            retryAt = if (partnerDenied && m.retryIn > 0) System.currentTimeMillis() + m.retryIn * 1000L else 0L
            if (partnerLockId != m.id) {
                partnerLockId = m.id
                partnerTotal = m.seconds
                partnerRemaining = m.remaining
                partnerEnd = 0
                asked = m.asked
                pickups = 0
                partnerLocked = true
            } else if (partnerEnd == 0) {
                // Your partner may have added time, or answered a request.
                partnerTotal = m.seconds
                asked = m.asked
                if (kotlin.math.abs(partnerRemaining - m.remaining) > 2) partnerRemaining = m.remaining
            }
        } else if (partnerLockId != 0L && partnerLocked && partnerEnd == 0 && m.lastId == partnerLockId) {
            partnerEnd = when (m.lastEndedBy) {
                "locker" -> 3
                "emergency" -> 2
                else -> 1
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Lock together: your session ends when their lock does (time up, you unlocked them, or they used the
    // emergency release). The server's answer decides, and the local end time covers the gap between polls.
    LaunchedEffect(togetherWith, locks.theirs?.id, locks.theirsEndsAt, running) {
        if (togetherWith == 0L || !running) return@LaunchedEffect
        if (locks.theirs?.id == togetherWith) {
            val wait = locks.theirsEndsAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
        } else {
            // Not there on this refresh: a dropped connection looks the same as an ended lock. Give the next
            // refreshes a few seconds; if the lock shows again this effect restarts and the delay is cancelled.
            delay(8_000L)
        }
        togetherWith = 0L
        running = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        history.record(System.currentTimeMillis() - elapsed * 1000L, elapsed, pickups)
    }

    val guard = remember { LockGuard(context) }
    val activity = remember(context) { context.findActivity() }
    var protection by remember { mutableStateOf(guard.level().label) }
    val permissions = remember { PermissionWatch(context, guard) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    // Offered when someone signs in and on each fresh open of the app, while anything is still switched off.
    LaunchedEffect(locked, partnerLocked) {
        val app = context.applicationContext as LockInApplication
        permissions.refresh()
        if (!app.permissionsOffered && !locked && !partnerLocked && permissions.missing.isNotEmpty()) {
            // Right after signing in, the welcome page is still closing in on Home's mountain card. Opening this page
            // then put the mountain on top of it, so wait until the move has landed and let Home be seen first.
            if (SceneHandoff.flying) {
                kotlinx.coroutines.withTimeoutOrNull(2_500L) { androidx.compose.runtime.snapshotFlow { SceneHandoff.flying }.first { !it } }
                delay(350)
            }
            if (locked || partnerLocked) return@LaunchedEffect
            app.permissionsOffered = true
            showPermissions = true
        }
    }

    // Hold the screen for as long as the session runs, and let go when it ends.
    LaunchedEffect(sessionActive) {
        val a = activity ?: return@LaunchedEffect
        if (sessionActive) guard.engage(a) else guard.release(a)
    }
    // Someone leaving the app mid-session is a pickup: count it and pull LockIn back to the front.
    DisposableEffect(sessionActive) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (sessionActive && event == Lifecycle.Event.ON_STOP && guard.screenOn()) {
                pickups += 1
                guard.bringBack()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                protection = guard.level().label
                permissions.refresh()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // A lock does not go back, and it hides the system bars while it is up.
    BackHandler(enabled = locked || partnerLocked) { }
    // New messages from your partner since the lock began (the Message button on the lock screen shows the count).
    var chatSeen by rememberSaveable { mutableLongStateOf(0L) }
    val newestFromPartner = chat.messages.filter { it.who == Who.Partner }.maxOfOrNull { it.id } ?: 0L
    LaunchedEffect(locked) { if (locked) chatSeen = chat.messages.maxOfOrNull { it.id } ?: 0L }
    LaunchedEffect(showChat, newestFromPartner) { if (showChat) chatSeen = maxOf(chatSeen, newestFromPartner) }
    val lockUnread = chat.messages.count { it.who == Who.Partner && it.id > chatSeen }
    BackHandler(enabled = showProtection && !locked && !partnerLocked) {
        showProtection = false
        protection = guard.level().label
    }
    // Registered last so it wins: the chat can sit on top of a lock screen and goes back to it.
    BackHandler(enabled = tool != 0) { tool = 0 }
    BackHandler(enabled = readerBook.isNotEmpty()) { readerBook = "" }
    BackHandler(enabled = showClimbs && !showInbox && !showChat) { showClimbs = false }
    BackHandler(enabled = showInbox && !showChat) { showInbox = false }
    BackHandler(enabled = showChat) { showChat = false }
    BackHandler(enabled = showCrewChat && !showChat) { showCrewChat = false }
    BackHandler(enabled = showScan) { showScan = false }
    BackHandler(enabled = limitApp != null) { limitApp = null }
    BackHandler(enabled = showPlan) { showPlan = false }
    BackHandler(enabled = showStart) { showStart = false }
    BackHandler(enabled = goalOpen || avatarOpen || confirm != null) { goalOpen = false; avatarOpen = false; confirm = null }
    BackHandler(enabled = showPermissions && !locked && !partnerLocked) { showPermissions = false }
    BackHandler(enabled = showLockPage && !locked && !partnerLocked) { showLockPage = false }
    BackHandler(enabled = youPage != YouSub.None && !locked && !partnerLocked) { youPage = YouSub.None }
    ImmersiveWhile(locked || partnerLocked)

    // Who your partner is, and the chat with them. Both are fetched while the app is on screen: the
    // pairing every few seconds while an invite waits, and the chat faster while it is open.
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    LaunchedEffect(lifecycle) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                partner.refresh()
                delay(if (partner.paired == null) 4_000 else 30_000)
            }
        }
    }
    // The welcome page holds its move until the first picture of Home is the right one.
    LaunchedEffect(partner.pairing is Pairing.Loading, history.loaded) {
        if (partner.pairing !is Pairing.Loading && history.loaded) SceneHandoff.dataReady = true
    }
    LaunchedEffect(partner.paired, partner.name) { history.setPartner(partner.name, partner.paired?.since) }
    LaunchedEffect(plans.restMask) { history.setRestDays(plans.restDays) }
    SideEffect { ClockPrefs.is24h = android.text.format.DateFormat.is24HourFormat(context) }
    LaunchedEffect(partner.paired) {
        chat.bind(partner.paired)
        history.setPartner(partner.name, partner.paired?.since)
        // The apps allowed during locks, both ways (also saved on the phone for the lock service).
        if (partner.paired != null) locks.loadAllowed() else com.godbless.lockin.lock.AllowedStore(context).clear()
    }
    // Starting a lock on you: fetch the allowed apps then too, in case they changed.
    LaunchedEffect(locks.mine?.active) { if (locks.mine?.active == true) locks.loadAllowed() }
    // A pairing link was tapped (or opened the app): go to the Partner tab and ask before joining.
    LaunchedEffect(incomingCode, partner.pairing, locked, partnerActive) {
        val code = incomingCode ?: return@LaunchedEffect
        if (partner.pairing is Pairing.Loading || locked || partnerActive) return@LaunchedEffect
        tab = 2
        if (partner.paired != null) {
            Toast.makeText(context, "You already have a partner.", Toast.LENGTH_LONG).show()
        } else {
            partner.preview(code)
        }
        onIncomingHandled()
    }
    // Locks: looked at when the app opens, straight away when a push says one changed, and every few
    // seconds while the app is on screen (a little faster while a lock runs).
    val lockPing by (context.applicationContext as LockInApplication).lockPing.collectAsState()
    LaunchedEffect(lockPing) {
        if (lockPing > 0) {
            locks.refresh(partner.paired != null)
            if (partner.paired != null) locks.loadRepeats()
            plans.refresh(partner.paired != null)
        }
    }
    // Goals and planned sessions: fetched when the app opens and every half minute while it is on screen.
    LaunchedEffect(lifecycle, partner.paired?.id) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                plans.refresh(partner.paired != null)
                delay(30_000)
            }
        }
    }
    LaunchedEffect(lifecycle, partner.paired?.id) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                locks.refresh(partner.paired != null)
                delay(if (partnerLocked || locks.theirs != null) 6_000 else 20_000)
            }
        }
    }
    // Devices: checked in and fetched when the app opens and every half minute while it is on screen
    // (faster while you have a lock on your partner, so you see each device pick it up).
    LaunchedEffect(lifecycle, partner.paired?.id) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                devices.refresh(partner.paired != null)
                delay(if (locks.theirs != null) 10_000 else 30_000)
            }
        }
    }
    // A newer build: looked for each time the app opens (at most every six hours).
    LaunchedEffect(lifecycle) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { update.check() }
    }
    // App times and limits: fetched when the app opens and every half minute while it is on screen. This also
    // tells the background guard whether to report this phone's app times and hold the limits on it.
    LaunchedEffect(lifecycle, partner.paired?.id) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // App time and sessions share one half-minute tick: two loops waking the radio apart cost twice as much.
                // (They are independent, so one failing never holds up the other.)
                kotlinx.coroutines.coroutineScope {
                    launch { usage.refresh(partner.paired != null) }
                    launch { history.refresh() }
                }
                delay(30_000)
            }
        }
    }
    // The typing dots: only while the chat is open, and a little faster than the messages.
    LaunchedEffect(lifecycle, partner.paired?.id, showChat) {
        if (lifecycle == null || partner.paired == null || !showChat) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                chat.tickTyping()
                delay(1_200)
            }
        }
    }
    LaunchedEffect(lifecycle, partner.paired?.id, showChat) {
        if (lifecycle == null || partner.paired == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                chat.poll()
                delay(if (showChat) 2_500 else 20_000)
            }
        }
    }
    // A push from your partner while LockIn is open: look now instead of at the next turn.
    val chatPing by (context.applicationContext as LockInApplication).chatPing.collectAsState()
    LaunchedEffect(chatPing) { if (chatPing > 0 && partner.paired != null) chat.poll() }
    // The crew: quick while its chat is open or a session is on, slower otherwise, and at once on a crew push.
    val crewPing by (context.applicationContext as LockInApplication).crewPing.collectAsState()
    LaunchedEffect(crewPing) { if (crewPing > 0) crew.refresh() }
    LaunchedEffect(lifecycle, showCrewChat, tab) {
        if (lifecycle == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                crew.refresh()
                delay(
                    when {
                        showCrewChat -> 2_500
                        crew.crew?.session != null || tab == 3 -> 10_000
                        else -> 45_000
                    },
                )
            }
        }
    }

    val myToday = history.stats.todaySeconds
    val partyPrefs = remember { context.getSharedPreferences("summit_party", android.content.Context.MODE_PRIVATE) }

    // Tell your partner once a day that you reached the summit (their phone shows a notification).
    LaunchedEffect(myToday >= plans.goalMinutes * 60, partner.paired?.id) {
        val goal = plans.goalMinutes * 60
        if (partner.paired == null || goal <= 0 || myToday < goal) return@LaunchedEffect
        val day = java.time.LocalDate.now().toString()
        if (partyPrefs.getString("told", "") == day) return@LaunchedEffect
        partyPrefs.edit().putString("told", day).apply()
        chat.event("summit|${myToday / 60}")
    }

    CompositionLocalProvider(
        LocalPartnerName provides partner.name,
        LocalTtt provides remember { com.godbless.lockin.data.TttRepository(partners) },
        LocalWd provides remember { com.godbless.lockin.data.WordDuelRepository(partners) },
        LocalLudo provides remember { com.godbless.lockin.data.LudoRepository(partners) },
        LocalLive provides remember { com.godbless.lockin.live.LiveChannel(partners) },
        com.godbless.lockin.study.LocalAi provides remember { com.godbless.lockin.data.AiRepository(partners) },
        LocalStats provides history.stats,
        LocalGoalMinutes provides plans.goalMinutes,
        LocalPartnerGoalMinutes provides plans.partnerGoalMinutes,
    ) {
    // On a big screen the tabs sit in a rail down the left, and pages slide up and down to match it.
    val rail = com.godbless.lockin.ui.useRail()
    // On a big screen a chat opens as a panel on the right. The page springs narrower to sit beside it (and flows
    // into one column if it no longer fits two), instead of being half hidden underneath. The expand button in the
    // chat header widens the panel; the page follows.
    val chatPane = com.godbless.lockin.ui.twoPane()
    var chatWide by rememberSaveable { mutableStateOf(false) }
    val chatOpen = showChat || showCrewChat
    val chatWidth by androidx.compose.animation.core.animateDpAsState(
        if (chatWide) Widths.Chat else Widths.ChatPane,
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "chat-width",
    )
    val chatInset by androidx.compose.animation.core.animateDpAsState(
        if (chatPane && chatOpen) (if (chatWide) Widths.Chat else Widths.ChatPane) else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "chat-inset",
    )
    Box(Modifier.fillMaxSize().background(LockInColors.Ink)) {
        CompositionLocalProvider(com.godbless.lockin.ui.LocalChatInset provides (if (chatPane && chatOpen) (if (chatWide) Widths.Chat else Widths.ChatPane) else 0.dp)) {
        AnimatedContent(
            targetState = tab,
            // A spring overshoots its target. Heading for 0.dp that means a tiny negative value for a frame, and
            // padding throws on a negative value (crash "Padding must be non-negative"), so it is clamped here.
            modifier = Modifier.fillMaxSize().padding(start = if (rail) Widths.Rail + 8.dp else 0.dp, end = chatInset.coerceAtLeast(0.dp)).clipToBounds(),
            transitionSpec = {
                val forward = targetState > initialState
                if (rail) {
                    // The whole height, both pages moving together: the new page pushes the old one out. (It used to move
                    // a third of the way, so both see-through pages were drawn over each other for a moment.)
                    (slideInVertically(tween(380, easing = UiGlide)) { if (forward) it else -it } togetherWith
                        slideOutVertically(tween(380, easing = UiGlide)) { if (forward) -it else it })
                        .using(null)
                } else {
                    (slideInHorizontally(tween(340, easing = UiGlide)) { if (forward) it else -it } togetherWith
                        slideOutHorizontally(tween(340, easing = UiGlide)) { if (forward) -it else it })
                        .using(null)
                }
            },
            label = "home-tab",
        ) { page ->
            when (page) {
                0 -> HomeTab(
                    name = you.name,
                    locked = running,
                    elapsed = elapsed,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        elapsed = 0
                        pickups = 0
                        running = false
                        locked = true
                    },
                    onProfile = { tab = 4 },
                    unread = unreadCount,
                    onBell = { inbox.open(inboxItems); showInbox = true },
                    onSeeAll = { showClimbs = true },
                    study = study,
                    onOpenTool = ::openTool,
                    paired = partner.paired != null,
                    pairingKnown = partner.pairing !is Pairing.Loading,
                    partnerName = partner.name,
                    onInvite = { tab = 2 },
                    next = plans.next(System.currentTimeMillis()),
                    onNext = { if (plans.next(System.currentTimeMillis()) == null) showPlan = true else tab = 2 },
                    missing = permissions.missing,
                    onFinishSetup = { showPermissions = true },
                    lock = locks,
                    onOpenLock = { locks.error = null; showLockPage = true },
                    // The mountain shows the nudge landing, so no toast here.
                    onNudge = { if (partner.paired != null) chat.event("nudge") },
                    onGoal = { tab = 4 },
                    onStart = { showStart = true },
                    covered = showClimbs || showInbox || showChat || showCrewChat || showScan || showLockPage ||
                        showPermissions || showProtection || tool != 0 || readerBook.isNotEmpty() || youPage != YouSub.None,
                )
                1 -> Unit
                2 -> if (partner.paired == null) PairingPage(partner, onScan = { showScan = true }) else PartnerPage(
                    youName = you.name,
                    busy = partner.busy,
                    usage = usage,
                    lock = locks,
                    onPickApp = { usage.error = null; limitApp = it },
                    partner = partner,
                    devices = devices,
                    onMessage = { showChat = true },
                    onNudge = {
                        chat.event("nudge")
                        Toast.makeText(context, "Nudged ${partner.name}.", Toast.LENGTH_SHORT).show()
                    },
                    onOpenLock = { locks.error = null; showLockPage = true },
                    plans = plans,
                    onPlan = { plans.error = null; showPlan = true },
                )
                3 -> CrewTab(crew, onOpenChat = { showCrewChat = true })
                else -> YouPage(
                    you = you,
                    paired = partner.paired != null,
                    onOpen = { youPage = it },
                    plans = plans,
                    onGoal = { goalOpen = true },
                    onAvatar = { avatarOpen = true },
                )
            }
        }
        }

        // Content slides under a solid ground-coloured wash, so nothing shows through around the bar.
        if (!rail) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to LockInColors.Ink.copy(alpha = 0f),
                        0.5f to LockInColors.Ink.copy(alpha = 0.92f),
                        1f to LockInColors.Ink,
                    ),
                ),
        )
        // Same idea at the top, so scrolled content never runs into the clock and battery icons.
        val statusH = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusH + 16.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to LockInColors.Ink,
                        0.7f to LockInColors.Ink,
                        1f to LockInColors.Ink.copy(alpha = 0f),
                    ),
                ),
        )
        if (rail) {
            HomeRail(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        val p = easeOutCubic(clamp01((entrance.value * 0.9f - 0.3f) / 0.5f))
                        translationX = -(1f - p) * 140.dp.toPx()
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 16.dp),
            )
        } else Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(72.dp + 40.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to LockInColors.Ink.copy(alpha = 0f),
                        0.45f to LockInColors.Ink.copy(alpha = 0.92f),
                        1f to LockInColors.Ink,
                    ),
                ),
        )
        if (!rail) HomeBottomBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    val p = easeOutCubic(clamp01((entrance.value * 0.9f - 0.3f) / 0.5f))
                    translationY = (1f - p) * 140.dp.toPx()
                }
                .navigationBarsPadding()
                .fillMaxWidth(TabBarWidth)
                .widthIn(max = Widths.TabBar)
                .padding(bottom = 12.dp),
        )

        // The protection page slides in from the right over the tabs.
        AnimatedVisibility(
            visible = showProtection,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            ProtectionScreen(guard = guard, onBack = {
                showProtection = false
                protection = guard.level().label
            })
        }

        // The pages that open from You: profile, notifications, partner, sign-in. They slide in from the right.
        AnimatedVisibility(
            visible = youPage != YouSub.None,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            val back = { youPage = YouSub.None }
            when (shownYouPage) {
                YouSub.Profile -> EditProfilePage(you, onBack = back)
                YouSub.Notifications -> NotificationsPage(you, plans, onBack = back)
                YouSub.Partner -> PartnerInfoPage(
                    partner = partner,
                    locks = locks,
                    onChat = { showChat = true },
                    onInvite = { youPage = YouSub.None; tab = 2 },
                    onBack = back,
                )
                YouSub.Security -> SecurityPage(you, onSignOut = onSignOut, onConfirm = { confirm = it }, onBack = back)
                YouSub.Settings -> SettingsPage(
                    onOpen = { youPage = it },
                    onBack = back,
                    you = you,
                    stats = history.stats,
                    protection = protection,
                    onProtection = { showProtection = true },
                    updateStatus = update.status,
                    onUpdate = update::tap,
                    onConfirm = { confirm = it },
                    onSignOut = onSignOut,
                )
                YouSub.Year -> YearPage(history.stats, plans.goalMinutes, onBack = back)
                YouSub.None -> Unit
            }
        }

        // Every climb you have locked in: the page slides in from the right over the tabs.
        AnimatedVisibility(
            visible = showClimbs,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            ClimbsPage(onBack = { showClimbs = false })
        }

        // Locking your partner: the full page slides in from the right.
        AnimatedVisibility(
            visible = showLockPage,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            LockPartnerPage(
                lock = locks,
                usage = usage,
                onBack = { showLockPage = false },
                onMessage = { showChat = true },
                onTogether = {
                    // Lock together: your own focus session begins as theirs does.
                    showLockPage = false
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    elapsed = 0
                    pickups = 0
                    running = false
                    togetherWith = locks.theirs?.id ?: 0L
                    locked = true
                },
            )
        }

        // First sign-in, and each fresh open while something is off: the permissions page slides up.
        AnimatedVisibility(
            visible = showPermissions && !locked && !partnerLocked,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(tween(420, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(360, easing = UiGlide)) { it },
        ) {
            PermissionsScreen(permissions, guard, onDone = { showPermissions = false })
        }

        // The lock screen slides up over everything, tab bar included.
        AnimatedVisibility(
            visible = locked,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(tween(520, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(440, easing = UiGlide)) { it },
        ) {
            LockScreen(
                elapsed = elapsed,
                running = running,
                pickups = pickups,
                onRunning = { running = true },
                onEnd = {
                    running = false
                    togetherWith = 0L
                    history.record(System.currentTimeMillis() - elapsed * 1000L, elapsed, pickups)
                },
                onCancel = {
                    running = false
                    togetherWith = 0L
                    elapsed = 0
                    locked = false
                },
                onClose = { locked = false },
                onOpenTool = ::openTool,
                // Lock together: show the lock you set (your message, the time until it opens) instead of a bare timer.
                together = locks.theirs?.takeIf { togetherWith != 0L && it.id == togetherWith }?.let {
                    TogetherLock(
                        it.message, it.seconds,
                        ((locks.theirsEndsAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0),
                        it.strict,
                        pickups = it.pickups,
                        endsAt = locks.theirsEndsAt,
                    )
                },
                onMessage = { showChat = true },
                quizDue = study.cards.count { study.isDue(it) },
                unread = lockUnread,
                partnerRealName = partner.realName,
                busyElsewhere = tool != 0 || readerBook.isNotEmpty() || showChat,
                onLeftTogether = { chat.event("lock_together_left") },
                onHighFive = { chat.send("\uD83D\uDE4C") },
            )
        }

        // The screen you get when your partner locks you.
        AnimatedVisibility(
            visible = partnerLocked,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInVertically(tween(520, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(440, easing = UiGlide)) { it },
        ) {
            PartnerLockScreen(
                allowed = locks.allowed?.mine ?: emptyList(),
                onOpenApp = { pkg ->
                    com.godbless.lockin.lock.LockService.openAllowedApp(context, pkg, locks.allowed?.mine ?: emptyList())
                },
                remaining = partnerRemaining,
                total = partnerTotal,
                end = PartnerLockEnd.entries[partnerEnd],
                pickups = pickups,
                asked = asked,
                message = partnerMessage,
                strict = partnerStrict,
                together = partnerTogether,
                denied = partnerDenied,
                askedAt = askedAt,
                retryAt = retryAt,
                busyElsewhere = tool != 0 || readerBook.isNotEmpty() || showChat,
                unread = lockUnread,
                incoming = chat.messages
                    .lastOrNull { it.who == Who.Partner && it.id > chatSeen && it.media == null && it.voice == null && !it.deleted }
                    ?.text?.takeIf { it.isNotBlank() },
                emergencies = locks.history.count { !it.mine && it.endedBy == "emergency" && it.startedAt >= System.currentTimeMillis() - 7L * 86_400_000L },
                onQuick = { text -> chat.send(text) },
                onCall = { com.godbless.lockin.lock.LockService.dial(context) },
                onCancelAsk = {
                    asked = false
                    if (partnerLockId != 0L) {
                        locks.cancelAsk { problem ->
                            if (problem != null) {
                                asked = true
                                Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onMessage = { showChat = true },
                onAsk = { reason ->
                    if (partnerLockId == 0L) {
                        asked = true
                        chat.event("asked_unlock")
                    } else {
                        asked = true
                        locks.ask(reason) { problem ->
                            if (problem != null) {
                                asked = false
                                Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onEmergency = {
                    // Let go now. The server is told (and your partner pushed) as soon as there is a connection.
                    partnerEnd = 2
                    if (partnerLockId != 0L) locks.emergency() else chat.event("emergency_release")
                },
                onClose = { partnerLocked = false },
                onOpenTool = ::openTool,
            )
        }

        // The study tools: slide in from the right over the lock screens, and the reader over those.
        AnimatedVisibility(
            visible = tool == 1,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            CalculatorScreen(onBack = { tool = 0 })
        }
        AnimatedVisibility(
            visible = tool == 2,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            LibraryScreen(
                store = study,
                canAdd = !(locked || partnerActive),
                onBack = { tool = 0 },
                onOpen = { id -> readerPage = -1; readerBook = id },
            )
        }
        AnimatedVisibility(
            visible = tool == 3,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            QuizScreen(
                store = study,
                onBack = { tool = 0 },
                onOpenPage = { id, page -> readerPage = (page - 1).coerceAtLeast(0); readerBook = id },
                partnerName = partner.name.takeIf { partner.paired != null && it.isNotBlank() },
                onSendEvent = chat::event.takeIf { partner.paired != null },
                challenge = partnerQuiz,
                onChallengeClosed = { partnerQuiz = null },
            )
        }
        AnimatedVisibility(
            visible = readerBook.isNotEmpty(),
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            ReaderScreen(store = study, bookId = shownBook, startPage = readerPage, onBack = { readerBook = "" })
        }

        // The camera for scanning your partner's QR code.
        AnimatedVisibility(
            visible = showScan,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            ScanScreen(
                onCode = { code ->
                    showScan = false
                    partner.preview(code)
                },
                onBack = { showScan = false },
            )
        }

        // A new version is ready. Not shown during a session.
        AnimatedVisibility(
            visible = update.showCard && !locked && !partnerLocked,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(460, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(340, easing = UiGlide)) { it },
        ) {
            UpdateCard(update)
        }

        // Setting a limit on one of your partner's apps.
        AnimatedVisibility(
            visible = limitApp != null,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            shownLimitApp?.let { app ->
                val current = usage.limitOf(app.pkg)
                LimitPicker(
                    appName = app.label,
                    usedSeconds = app.seconds,
                    currentMinutes = (current?.seconds ?: 0) / 60,
                    busy = usage.busy,
                    error = usage.error,
                    onSave = { minutes -> usage.setLimit(app.pkg, minutes * 60) { limitApp = null } },
                    onRemove = { usage.removeLimit(app.pkg) { limitApp = null } },
                    onBack = { limitApp = null },
                )
            }
        }

        // Start: lock in on your own, or lock your partner's phone.
        StartSheet(
            visible = showStart && !locked && !partnerLocked,
            lock = locks,
            onOwn = {
                showStart = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                elapsed = 0
                pickups = 0
                running = false
                locked = true
            },
            onLockPartner = { showStart = false; locks.error = null; showLockPage = true },
            onDismiss = { showStart = false },
        )

        // The You tab's sheets.
        GoalSheet(visible = goalOpen && !locked && !partnerLocked, plans = plans, onDismiss = { goalOpen = false })
        AvatarSheet(
            visible = avatarOpen && !locked && !partnerLocked,
            name = you.displayName,
            partnerId = partner.paired?.id,
            partnerRealName = partner.realName,
            onDismiss = { avatarOpen = false },
        )
        ConfirmSheet(spec = confirm, onDismiss = { confirm = null })
        // The Crew tab's menu, member and look sheets (they carry their own confirm).
        CrewSheets(crew)

        // Planning a session with your partner.
        AnimatedVisibility(
            visible = showPlan,
            modifier = Modifier.fillMaxSize().blockTouches(),
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            PlanSheet(
                busy = plans.busy,
                error = plans.error,
                onSave = { title, at, minutes -> plans.plan(title, at, minutes) { showPlan = false } },
                onBack = { showPlan = false },
            )
        }

        // On a big screen the chats open as a panel on the right, so the page stays in view beside them.
        val pane = twoPane()
        val chatModifier = if (pane) {
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(chatWidth)
                .border(1.dp, LockInColors.Graphite, RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .blockTouches()
        } else Modifier.fillMaxSize().blockTouches()

        // The activity list slides up over Home, under the chats.
        InboxSheet(
            visible = showInbox,
            items = inboxItems,
            state = inbox,
            partnerName = partner.name,
            onClose = { showInbox = false },
            onOpen = { item ->
                showInbox = false
                if (item.crew) {
                    tab = 3
                    if (item.kind == InboxKind.Crew && item.detail.isNotBlank()) { crew.openChat(); showCrewChat = true }
                } else {
                    showChat = true
                }
            },
            onSettings = { showInbox = false; tab = 4; youPage = YouSub.Notifications },
        )

        // The chat with your partner, above everything (it also opens from the lock screens).
        AnimatedVisibility(
            visible = showChat,
            modifier = chatModifier,
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            ChatScreen(
                chat = chat,
                onBack = { showChat = false },
                onOpenPartner = { showChat = false; tab = 2 },
                unlockAsked = locks.theirs?.asked == true,
                onAnswerUnlock = { locks.answer(it) },
                onAnswerProtect = { allow ->
                    scope.launch { com.godbless.lockin.data.ProtectRepository(partners).answer(allow) }
                },
                onOpenGame = { showChat = false; locks.error = null; showLockPage = true },
                onOpenQuiz = { code -> partnerQuiz = code; showChat = false; tool = 3 },
                status = partnerStatusLine(usage.partner?.reportedAt ?: 0L, System.currentTimeMillis()),
                // Only a side panel can be widened: on a phone the chat is already the whole screen.
                widened = if (pane) chatWide else null,
                onToggleWidth = { chatWide = !chatWide },
            )
        }

        // The crew chat slides in from the right too.
        AnimatedVisibility(
            visible = showCrewChat && !showChat,
            modifier = chatModifier,
            enter = slideInHorizontally(tween(340, easing = UiGlide)) { it },
            exit = slideOutHorizontally(tween(300, easing = UiGlide)) { it },
        ) {
            CrewChat(crew = crew, onBack = { showCrewChat = false })
        }
    }
    }
}

/** Home: today's climb (with locking and nudging your partner on it), the study kit, and how the week is going. */
@Composable
private fun HomeTab(
    name: String,
    locked: Boolean,
    elapsed: Int,
    onToggle: () -> Unit,
    onProfile: () -> Unit,
    /** How many things in the activity list you haven't seen, and opening it. */
    unread: Int,
    onBell: () -> Unit,
    /** Opens the Climbs page, which has the full history. */
    onSeeAll: () -> Unit,
    study: StudyStore,
    onOpenTool: (Tool) -> Unit,
    paired: Boolean,
    /** False while it is still being asked whether there is a partner: no "Invite your partner" card until it is known. */
    pairingKnown: Boolean = true,
    partnerName: String,
    onInvite: () -> Unit,
    next: com.godbless.lockin.data.Plan?,
    onNext: () -> Unit,
    missing: List<Need>,
    onFinishSetup: () -> Unit,
    lock: LockState,
    onOpenLock: () -> Unit,
    onNudge: () -> Unit,
    onGoal: () -> Unit,
    /** With a partner, Start asks whether to lock in alone or lock their phone. */
    onStart: () -> Unit,
    /** A full page is open over Home: its animations rest. */
    covered: Boolean = false,
) {
    // Day and hour follow the clock, so a Home left open past midnight moves its highlighted day and greeting.
    val minuteTick by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val today = remember(minuteTick / 3_600_000L, LocalDate.now()) { LocalDate.now().dayOfWeek.value - 1 }
    val stats = LocalStats.current
    val week = stats.week
    // One line: a very long name is cut instead of wrapping under the bell.
    val first = name.trim().substringBefore(' ').ifEmpty { "there" }.let { if (com.godbless.lockin.ui.graphemes(it).size > 14) it.safeTake(14) + "…" else it }
    val greeting = remember(LocalTime.now().hour) {
        when (LocalTime.now().hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Still up"
        }
    }

    // Home owns its scroll so the header and the mountain can move at their own pace as you scroll.
    val scroll = rememberScrollState()
    val avatarPop = rememberPop(380, "home-avatar")
    val bellPop = rememberPop(520, "home-bell")
    // The greeting types in the first time Home opens in this run of the app; later it is simply there.
    val greetSettled = remember { HomeIntro.greeted }
    LaunchedEffect(Unit) { HomeIntro.greeted = true }
    val (weekSeen, weekProbe) = rememberInView()
    val (recentSeen, recentProbe) = rememberInView()

    PageScaffold(scroll = scroll, entranceMillis = 1100, wide = true, key = "tab-home") { entrance ->
        Row(
            Modifier
                .fillMaxWidth()
                // The greeting drifts up a little slower than the page, so it peels away as you scroll.
                .graphicsLayer { translationY = scroll.value * 0.28f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Your profile on the left, the greeting beside it, and the bell at the right.
            Box(
                Modifier
                    .graphicsLayer { scaleX = avatarPop.value; scaleY = avatarPop.value }
                    .clip(CircleShape)
                    .springClick(onClick = onProfile)
                    .semantics { contentDescription = "Your profile" },
            ) {
                Avatar(first, boxSize = 48.dp, ring = LockInColors.Ink, me = true)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                StaggerText("$greeting,", LockInText.Sub.copy(color = LockInColors.Silver), startDelay = 60L, settled = greetSettled)
                StaggerText(first, LockInText.Title, startDelay = 220L, settled = greetSettled)
            }
            NotificationBell(
                unread = unread,
                onClick = onBell,
                modifier = Modifier.graphicsLayer { scaleX = bellPop.value; scaleY = bellPop.value },
            )
        }
        Spacer(Modifier.height(20.dp))

        // On a tablet: the mountain on the left, everything else beside it. On a phone: one under the other.
        val wide = com.godbless.lockin.ui.twoPane()
        TwoColumns(
            left = {
                AlpineHero(
                    locked = locked,
                    elapsed = elapsed,
                    // Ending a session, or starting without a partner, goes straight through; otherwise ask first.
                    onToggle = { if (locked || !paired) onToggle() else onStart() },
                    lock = if (paired) lock else null,
                    onOpenLock = onOpenLock,
                    onNudge = if (paired) onNudge else null,
                    onGoal = onGoal,
                    // On a big screen the mountain grows to fill the height, so the card isn't a short island in a tall page.
                    sceneHeight = if (wide) (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 300).coerceIn(290, 560).dp else 290.dp,
                    covered = covered,
                    // No parallax beside a second column: the mountain stays put while the right side scrolls.
                    parallax = { if (wide) 0f else scroll.value.toFloat() },
                    // While the welcome page is landing its mountain here the card must already sit where it will rest.
                    modifier = if (SceneHandoff.sceneHidden) Modifier else Modifier.riseSpring(entrance, 1),
                )
                if (paired) ClimberHint()
                Spacer(Modifier.height(14.dp))

                if (missing.isNotEmpty()) {
                    PermissionBanner(missing, onFinishSetup, Modifier.riseSpring(entrance, 2))
                    Spacer(Modifier.height(14.dp))
                }
            },
            right = {
                if (!paired && pairingKnown) {
                    InviteCard(onInvite, Modifier.riseSpring(entrance, 2))
                    Spacer(Modifier.height(14.dp))
                }

                StudyKitRow(study, onOpenTool, Modifier.riseSpring(entrance, 3))
                Spacer(Modifier.height(14.dp))

                // The right column has room on a big screen: what's next, and how your partner's day is going.
                if (wide && paired) {
                    NextSessionCard(next, partnerName, onNext, Modifier.riseSpring(entrance, 3))
                    Spacer(Modifier.height(14.dp))
                    PartnerCard(
                        partnerName, first, onNudge, Modifier.riseSpring(entrance, 4),
                        status = if (stats.partnerTodaySeconds > 0) "${formatMinutes(stats.partnerTodaySeconds / 60)} today" else "Roped to you",
                    )
                    Spacer(Modifier.height(14.dp))
                }

                WeekRange(
                    week, today, LocalGoalMinutes.current / 60f,
                    modifier = Modifier.riseSpring(entrance, 4).then(weekProbe),
                    visible = weekSeen.seen,
                    partner = if (paired) stats.partnerWeek else null,
                )

                // No climbs yet: the mountain already says how to start, so no empty card.
                if (stats.recent.isNotEmpty()) {
                    Spacer(Modifier.height(26.dp))
                    SectionTitle("Recent climbs", Modifier.riseSpring(entrance, 5), trailing = "See all", onTrailing = onSeeAll)
                    Spacer(Modifier.height(12.dp))
                    RecentClimbs(stats.recent.take(2), Modifier.riseSpring(entrance, 6).then(recentProbe), visible = recentSeen.seen)
                }
            },
        )
    }
}
