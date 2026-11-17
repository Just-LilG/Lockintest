package com.godbless.lockin.home

import com.godbless.lockin.ui.calmFloat
import com.godbless.lockin.ui.pushSwap
import com.godbless.lockin.ui.handoffSwap
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.godbless.lockin.ui.ControlShape
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.PairLink
import com.godbless.lockin.data.Pairing
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rise

private val PairShape = RoundedCornerShape(24.dp)

/** "K7M2QX9P" shown as "K7M2 QX9P", which is easier to read out loud. */
private fun spaced(code: String) = if (code.length == 8) code.substring(0, 4) + " " + code.substring(4) else code

/** "23 h 41 m", or "41 m" in the last hour. */
private fun expiresIn(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes m"
}

@Composable
private fun rememberNowEveryHalfMinute(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The Partner tab before you have a partner. One job at a time: "My code" shows what to send, "Enter
 * code" takes theirs (scan or type). Both people use the same screen; whoever enters the other's code
 * finishes the pairing.
 */
@Composable
internal fun PairingPage(state: PartnerState, onScan: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var code by rememberSaveable { mutableStateOf("") }

    PageScaffold(wide = true, key = "tab-pairing") { entrance ->
        PageTitle("Partner", "Pair up with someone who keeps you honest.", entrance)
        Spacer(Modifier.height(18.dp))

        // A scanned code or tapped link: say who it is and ask first.
        state.candidate?.let { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(PairShape)
                    .background(LockInColors.LimeWash)
                    .border(1.5.dp, LockInColors.Lime, PairShape)
                    .padding(20.dp),
            ) {
                Text("Pair with ${c.name}?", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
                Spacer(Modifier.height(4.dp))
                Text(
                    "You'll be able to chat, see each other's sessions and lock each other's phones.",
                    style = LockInText.Sub.copy(color = LockInColors.Body),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LockInButton("Not now", state::dismissCandidate, Modifier.weight(1f), kind = ButtonKind.Outline)
                    LockInButton("Pair", state::confirmCandidate, Modifier.weight(1f), enabled = !state.busy)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        val wide = com.godbless.lockin.ui.twoPane()
        TwoColumns(leftWeight = 1f, left = {
        PairTabs(tab, onSelect = { tab = it }, modifier = Modifier.rise(entrance, 1))
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier
                .rise(entrance, 2)
                .fillMaxWidth()
                .clip(PairShape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, PairShape)
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxWidth().clipToBounds(),
                // The tabs slide side by side (no fading), in the direction you moved.
                transitionSpec = { pushSwap(vertical = false, dir = if (targetState > initialState) 1 else -1) },
                label = "pair-tab",
            ) { t ->
                if (t == 0) MyCode(state) else EnterCode(state, code, onCode = { code = it }, onScan = onScan)
            }
        }

        val problem = state.error.takeIf { state.pairing !is Pairing.Loading }
        if (problem != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                problem,
                style = LockInText.Error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        }, right = {
            // The room beside the code on a tablet: what pairing gets you.
            if (wide) PairingPerks(Modifier.rise(entrance, 3))
        })
    }
}

/** What a partner does for you, shown beside the code on a tablet. */
@Composable
private fun PairingPerks(modifier: Modifier = Modifier) {
    val perks = listOf(
        Triple(Glyph.Lock, "Lock each other's phones", "Set a time and a reason. They can ask to be let out; you decide."),
        Triple(Glyph.Peak, "Climb the same mountain", "See each other's progress up today's climb, live."),
        Triple(Glyph.Bell, "Nudge and chat", "A tap on their climber nudges them. Messages ring with your own sound."),
        Triple(Glyph.Target, "Keep each other honest", "Daily app limits, shared app time, and who left a lock early."),
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(PairShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, PairShape)
            .padding(22.dp),
    ) {
        Text("With a partner", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
        Spacer(Modifier.height(16.dp))
        perks.forEachIndexed { i, (glyph, title, sub) ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(LockInColors.LimeWash), contentAlignment = Alignment.Center) {
                    GlyphIcon(glyph, LockInColors.Lime, boxSize = 20.dp, strokeWidth = 2f)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                    Text(sub, style = LockInText.Small.copy(color = LockInColors.Body))
                }
            }
        }
    }
}

/** Two choices side by side with a pill that slides under the one you pick. */
@Composable
private fun PairTabs(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("My code", "Enter code")
    val shape = ControlShape
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape),
    ) {
        val itemWidth = maxWidth / labels.size
        val x by animateDpAsState(itemWidth * selected, spring(dampingRatio = 0.75f, stiffness = 420f), label = "pair-pill")
        Box(
            Modifier
                .offset(x = x)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(5.dp)
                .clip(shape)
                .background(LockInColors.Graphite),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = LockInText.Label.copy(
                            color = if (i == selected) LockInColors.Snow else LockInColors.Silver,
                            fontSize = 15.sp,
                            fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

/** What to send your partner: the QR code, the code in words, and ways to pass it on. */
@Composable
private fun MyCode(state: PartnerState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AnimatedContent(
        targetState = state.pairing,
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        contentKey = { it::class },
        transitionSpec = { handoffSwap(ms = 320) },
        label = "invite",
    ) { p ->
        when (p) {
            is Pairing.Pending -> {
                val now = rememberNowEveryHalfMinute()
                val left = p.expires - now
                fun copy() {
                    clipboard.setText(AnnotatedString(p.code))
                    Toast.makeText(context, "Code copied.", Toast.LENGTH_SHORT).show()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrCode(PairLink.web(p.code), Modifier.size(196.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        spaced(p.code),
                        style = LockInText.Headline.copy(fontFamily = Fonts.Display, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = 3.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button, onClickLabel = "Copy code") { copy() }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (left > 0) {
                        // The app checks every few seconds, so this really is live.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulsingDot()
                            Text(
                                "Waiting for your partner to join",
                                style = LockInText.Small.copy(color = LockInColors.Silver),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("Code works for ${expiresIn(left)}", style = LockInText.Small.copy(color = LockInColors.Mist))
                    } else {
                        Text("This code has expired. Make a new one.", style = LockInText.Small.copy(color = LockInColors.Flare))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        LockInButton(
                            "Share",
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Lock in with me on LockIn! Tap to pair: ${PairLink.web(p.code)}\n\nOr open the app, go to Partner and enter the code ${spaced(p.code)}.",
                                    )
                                }
                                context.startActivity(Intent.createChooser(send, "Send your code"))
                            },
                            modifier = Modifier.weight(1.3f),
                        )
                        LockInButton("Copy", onClick = ::copy, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Make a new code",
                        style = LockInText.Small.copy(color = if (state.busy) LockInColors.Mist else LockInColors.Silver, fontSize = 13.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.busy, role = Role.Button, onClick = state::makeInvite)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Pairing.Loading -> Column(Modifier.fillMaxWidth()) {
                Text(
                    if (state.error != null) state.error.orEmpty() else "Checking…",
                    style = LockInText.Sub.copy(color = LockInColors.Silver),
                )
                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    LockInButton("Try again", state::retry, kind = ButtonKind.Outline)
                }
            }
            else -> Column(Modifier.fillMaxWidth()) {
                Text("Make a code for your partner", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "They scan it or type it in their app. It takes a few seconds.",
                    style = LockInText.Sub.copy(color = LockInColors.Silver),
                )
                Spacer(Modifier.height(18.dp))
                LockInButton("Make my code", state::makeInvite, loading = state.busy, enabled = !state.busy)
            }
        }
    }
}

/** Their code: scan it, or type it. The code boxes fill in as you go and Join lights up at eight. */
@Composable
private fun EnterCode(state: PartnerState, code: String, onCode: (String) -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Got their code?", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp))
        Spacer(Modifier.height(16.dp))
        LockInButton("Scan their QR code", onScan, kind = ButtonKind.Outline)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).height(1.dp).background(LockInColors.Graphite))
            Text("or type it", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(horizontal = 12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(LockInColors.Graphite))
        }
        Spacer(Modifier.height(16.dp))
        CodeBoxes(code, onChange = onCode, onDone = { if (code.length == 8 && !state.busy) state.join(code) })
        Spacer(Modifier.height(18.dp))
        LockInButton(
            "Join",
            onClick = { state.join(code) },
            loading = state.busy,
            enabled = code.length == 8 && !state.busy,
        )
    }
}

@Composable
private fun PulsingDot() {
    val pulse = rememberInfiniteTransition(label = "waiting")
    val alpha by pulse.calmFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "waiting-alpha",
        rest = 1f,
    )
    Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(LockInColors.Lime))
}

/**
 * Eight boxes for the code, four and four, filled as you type. One invisible text field underneath does
 * the real work, so paste, autofill and the keyboard behave normally. Anything that isn't a letter or
 * number is dropped; a pasted invite link is read for its code.
 */
@Composable
private fun CodeBoxes(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val blink = rememberInfiniteTransition(label = "caret")
    val caret by blink.calmFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "caret-alpha",
        rest = 1f,
    )
    val active = value.length.coerceAtMost(7)
    BasicTextField(
        value = value,
        onValueChange = { raw -> onChange(PairLink.parse(raw) ?: raw.filter { it.isLetterOrDigit() }.uppercase().take(8)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "Invite code, ${value.length} of 8 characters entered" },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until 8) {
                    if (i == 4) Spacer(Modifier.width(12.dp)) else if (i > 0) Spacer(Modifier.width(6.dp))
                    val filled = i < value.length
                    val isActive = focused && i == active && value.length < 8
                    Box(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LockInColors.Ink)
                            .border(
                                if (isActive) 2.dp else 1.5.dp,
                                when {
                                    isActive -> LockInColors.Lime
                                    filled -> LockInColors.Rim
                                    else -> LockInColors.Graphite
                                },
                                RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (filled) {
                            Text(
                                value[i].toString(),
                                style = LockInText.Input.copy(fontFamily = Fonts.Display, fontSize = 22.sp),
                            )
                        } else if (isActive) {
                            Box(Modifier.width(2.dp).height(24.dp).graphicsLayer { alpha = caret }.background(LockInColors.Lime))
                        }
                    }
                }
            }
        },
    )
}

/** At the bottom of the Partner tab: end the pairing. It asks twice, because the chat goes with it. */
/**
 * Unpairing needs both of you. You ask, your partner agrees (or says not now). An unanswered request goes
 * through after 7 days, so nobody can be held in a pairing, but it can't be used to slip out of a lock: the
 * server won't take a request while a lock is on you, and no new locks start on you while it's open.
 */
@Composable
internal fun UnpairCard(partner: PartnerState, modifier: Modifier = Modifier) {
    val paired = partner.paired ?: return
    val name = paired.partnerName
    var confirming by rememberSaveable { mutableStateOf(false) }
    val due = if (paired.unpairDue > 0L) {
        java.time.Instant.ofEpochMilli(paired.unpairDue).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
    } else ""
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.animation.AnimatedContent(
            targetState = paired.unpairBy,
            transitionSpec = { handoffSwap(ms = 320) },
            label = "unpair",
        ) { by ->
            when (by) {
                "them" -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.5.dp, LockInColors.Lime, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                ) {
                    Text("$name wants to unpair", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "If you agree, the pairing ends and your chat is deleted. If you say not now, they can ask again later." +
                            if (due.isNotEmpty()) " With no answer, it goes through on $due." else "",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LockInButton("Not now", onClick = { partner.answerUnpair(false) }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline, enabled = !partner.busy)
                        LockInButton("Agree", onClick = { partner.answerUnpair(true) }, modifier = Modifier.weight(1f), loading = partner.busy, enabled = !partner.busy)
                    }
                }
                "me" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("You asked $name to unpair", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Waiting for them to agree." + if (due.isNotEmpty()) " If they don't answer, it goes through on $due." else "",
                        style = LockInText.Small.copy(color = LockInColors.Body),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    SmallPill("Take it back", onClick = { partner.cancelUnpair() })
                }
                else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!confirming) {
                        SmallPill("Ask to unpair from $name", onClick = { confirming = true })
                    } else {
                        Text(
                            "$name has to agree. If they don't answer within 7 days, it goes through anyway. You can't ask while a lock is on you.",
                            style = LockInText.Small.copy(color = LockInColors.Body),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LockInButton("Keep $name", onClick = { confirming = false }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
                            LockInButton(
                                "Ask",
                                onClick = { confirming = false; partner.leave() },
                                modifier = Modifier.weight(1f),
                                loading = partner.busy,
                                enabled = !partner.busy,
                            )
                        }
                    }
                }
            }
        }
        partner.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = LockInText.Error, textAlign = TextAlign.Center)
        }
    }
}

/** On Home, in place of the partner cards, until someone is paired. */
@Composable
internal fun InviteCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(PairShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, PairShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Invite your partner", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
            Spacer(Modifier.height(2.dp))
            Text("Share a code and lock in together.", style = LockInText.Small.copy(color = LockInColors.Silver))
        }
        GlyphIcon(Glyph.Chevron, LockInColors.Silver, boxSize = 20.dp)
    }
}
