package com.godbless.lockin.lock

import android.os.SystemClock
import com.godbless.lockin.ui.handoffSwap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.WdGame
import com.godbless.lockin.data.WdGuess
import com.godbless.lockin.data.WordDuelRepository
import com.godbless.lockin.data.wdMessage
import com.godbless.lockin.data.wdNoGame
import com.godbless.lockin.live.LiveChannel
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Word Duel against your partner. One of you sets a secret 5-letter word (with an optional clue), the other has 6 tries, and
 * after each round you swap. It is played like the other chat games: nobody has to be here at the same time. When you are both
 * here, the live line shows you each other: the letters your partner is typing appear on your screen as they type them, a guess
 * flips on both phones together, and the one who set the word can send little reactions that float up the guesser's board.
 */
@Composable
internal fun WordDuel(
    repo: WordDuelRepository,
    name: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    live: LiveChannel? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var game by remember { mutableStateOf<WdGame?>(null) }
    var hadGame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var clue by remember { mutableStateOf("") }
    // My guess while the server scores it (the guesser can't know the colours before it answers).
    var pending by remember { mutableStateOf<String?>(null) }
    // Their guess shown on the setter's screen at once (the setter knows the word), until the server confirms it.
    var incomingOpt by remember { mutableStateOf<WdGame?>(null) }
    var incomingBase by remember { mutableLongStateOf(0L) }
    var peerTyped by remember { mutableStateOf("") }
    var peerHereAt by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var baseline by remember { mutableIntStateOf(-1) }
    var prevStatus by remember { mutableStateOf<String?>(null) }
    val reactions = remember { mutableStateListOf<Reaction>() }
    var reactionId by remember { mutableLongStateOf(0L) }
    val shake = remember { Animatable(0f) }
    val liveUp by (live?.up ?: remember { MutableStateFlow(false) }).collectAsState()

    LaunchedEffect(Unit) {
        GameSfx.load(context)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WordBank.load(context) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = SystemClock.uptimeMillis() } }

    fun accept(g: WdGame) {
        val cur = game
        if (g.none || cur == null || cur.none || g.rev >= cur.rev) {
            // The first look (and a new round) is not news: its rows are drawn finished, not flipped.
            if (cur == null || cur.none || cur.round != g.round) baseline = g.guesses.size.takeIf { cur == null || cur.none } ?: 0
            game = g
        }
        if (incomingOpt != null && g.rev > incomingBase) incomingOpt = null
        if (!g.none) hadGame = true
        error = null
    }

    DisposableEffect(live) {
        live?.acquire()
        onDispose {
            live?.send("wd_bye")
            live?.release()
        }
    }
    LaunchedEffect(liveUp) {
        while (liveUp) {
            live?.send("wd_here")
            delay(3000)
        }
    }
    LaunchedEffect(live) {
        val l = live ?: return@LaunchedEffect
        l.events.collect { ev ->
            val now = SystemClock.uptimeMillis()
            when (ev.name) {
                LiveChannel.UP -> l.send("wd_here")
                "wd_here" -> {
                    val first = now - peerHereAt > 7000L
                    peerHereAt = now
                    if (first) l.send("wd_here")
                }
                "wd_bye" -> { peerHereAt = 0L; peerTyped = "" }
                "wd_type" -> {
                    peerHereAt = now
                    peerTyped = ev.data.optString("t").filter { it in 'a'..'z' }.take(5)
                }
                "wd_try" -> {
                    peerHereAt = now
                    peerTyped = ""
                    val cur = game
                    val w = ev.data.optString("w")
                    if (cur != null && cur.iSet && cur.status == "guessing" && cur.word.length == 5 && w.length == 5 &&
                        cur.guesses.none { it.word == w } && cur.guesses.size < cur.tries
                    ) {
                        incomingBase = cur.rev
                        incomingOpt = cur.copy(guesses = cur.guesses + WdGuess(w, wdMarks(w, cur.word)))
                    }
                }
                "wd_undo" -> incomingOpt = null
                "wd_moved" -> scope.launch { repo.state().onSuccess { accept(it) } }
                "wd_react" -> {
                    val e = ev.data.optString("e").take(8)
                    if (e.isNotEmpty()) reactions.add(Reaction(++reactionId, e))
                }
            }
        }
    }
    LaunchedEffect(incomingOpt) {
        if (incomingOpt == null) return@LaunchedEffect
        delay(3500L)
        incomingOpt = null
    }

    LaunchedEffect(Unit) {
        val first = repo.play()
        val firstError = first.exceptionOrNull()
        if (firstError == null) accept(first.getOrThrow())
        else if (wdNoGame(firstError)) repo.state().getOrNull()?.let { accept(it) }
        else error = wdMessage(firstError)
        while (true) {
            val s = game
            val wait = when {
                s == null -> 1100L
                liveUp && !s.over -> 5000L
                !s.over && s.otherHere -> if (s.myTurn) 2000L else 1000L
                !s.over && !s.myTurn -> 2500L
                else -> 4000L
            }
            delay(wait)
            if (busy) continue
            repo.state().onSuccess { accept(it) }.onFailure { error = wdMessage(it) }
        }
    }

    fun send(block: suspend () -> Result<WdGame>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val r = block()
                val e = r.exceptionOrNull()
                if (e == null) {
                    accept(r.getOrThrow())
                    live?.send("wd_moved")
                } else {
                    repo.state().onSuccess { accept(it) }
                    if (!wdNoGame(e)) error = wdMessage(e)
                    live?.send("wd_undo")
                }
            } finally {
                pending = null
                busy = false
            }
        }
    }

    val g: WdGame? = incomingOpt ?: game
    val shownGuesses = g?.guesses.orEmpty()
    val iGuess = g != null && !g.none && !g.iSet
    val canType = g != null && !busy && pending == null &&
        ((g.status == "guessing" && !g.iSet) || (g.status == "setting" && g.iSet))

    // What I type is for my partner to watch while I guess (never while I choose the secret).
    LaunchedEffect(typed, iGuess, g?.status) {
        if (iGuess && g?.status == "guessing") live?.send("wd_type", JSONObject().put("t", typed))
    }
    // A new round or a finished guess starts me with an empty row.
    LaunchedEffect(g?.round, g?.status, shownGuesses.size) { typed = ""; error = null }
    LaunchedEffect(g?.round) { clue = "" }

    // The sound and a firm tap when a round ends, once the last tile has turned.
    val statusNow = g?.status
    LaunchedEffect(statusNow) {
        if (prevStatus == "guessing" && (statusNow == "solved" || statusNow == "failed")) {
            delay(1050L)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val iWon = if (statusNow == "solved") !g!!.iSet else g!!.iSet
            GameSfx.play(context, if (iWon) Sfx.Win else Sfx.Lose)
        }
        prevStatus = statusNow
    }

    fun problem(text: String) {
        error = text
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            for (d in floatArrayOf(-12f, 12f, -9f, 9f, -5f, 5f, 0f)) shake.animateTo(d, tween(45, easing = LinearEasing))
        }
    }

    fun submit() {
        val cur = g ?: return
        if (!canType) return
        if (typed.length < 5) { problem("Too short. Words have 5 letters."); return }
        if (!WordBank.isWord(typed)) { problem("That's not in the word list."); return }
        if (cur.status == "guessing") {
            if (cur.guesses.any { it.word == typed }) { problem("You already tried that."); return }
            val w = typed
            pending = w
            GameSfx.play(context, Sfx.Place)
            live?.send("wd_try", JSONObject().put("w", w))
            send { repo.guess(w) }
        } else {
            val w = typed
            val c = clue
            GameSfx.play(context, Sfx.Place)
            send { repo.set(w, c) }
        }
    }

    fun typeKey(c: Char) {
        if (!canType || typed.length >= 5) return
        typed += c
        error = null
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun back() {
        if (!canType || typed.isEmpty()) return
        typed = typed.dropLast(1)
        error = null
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- Header ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Back",
                style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, LockInColors.Graphite, CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
            Spacer(Modifier.weight(1f))
            val hereLive = peerHereAt > 0L && clock - peerHereAt < 7500L
            val here = g != null && !g.none && (g.otherHere || hereLive)
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (here) LockInColors.Lime else LockInColors.Rim))
            Text(
                if (here) "$name is here" else "$name isn't here",
                style = LockInText.Small.copy(color = if (here) LockInColors.Frost else LockInColors.Mist),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        val headline = when {
            g == null && error == null -> "Setting up the game"
            g == null -> "Couldn't open the game"
            g.none && hadGame -> "$name ended the game"
            g.none -> "No game yet"
            g.status == "setting" && g.iSet -> "Pick a word for $name"
            g.status == "setting" -> "$name is picking a word"
            g.status == "guessing" && !g.iSet -> "Your turn to guess"
            g.status == "guessing" -> "$name is guessing"
            g.status == "solved" && !g.iSet -> "You got it"
            g.status == "solved" -> "$name got it"
            g.iSet -> "$name ran out of tries"
            else -> "Out of tries"
        }
        AnimatedContent(
            targetState = headline,
            transitionSpec = { handoffSwap() },
            label = "wd-head",
        ) { t ->
            Text(
                t,
                style = LockInText.Title.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    color = if (g != null && !g.none && (g.myTurn || (g.status == "solved" && !g.iSet) || (g.status == "failed" && g.iSet))) LockInColors.Lime else LockInColors.Snow,
                ),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        val sub = when {
            g == null || g.none -> ""
            g.status == "setting" && g.iSet -> "Type a 5-letter word, or let LockIn pick one."
            g.status == "setting" -> "You'll get a push the moment it's ready."
            g.status == "guessing" && !g.iSet -> "${g.triesLeft} ${if (g.triesLeft == 1) "try" else "tries"} left." + if (g.clue.isNotEmpty()) " Clue: ${g.clue}." else ""
            g.status == "guessing" -> "Your word is ${g.word.uppercase()}. ${g.triesLeft} ${if (g.triesLeft == 1) "try" else "tries"} left."
            g.status == "solved" && !g.iSet -> "+${g.points} ${if (g.points == 1) "point" else "points"}. The word was ${g.word.uppercase()}."
            g.status == "solved" -> "They solved ${g.word.uppercase()} in ${g.guesses.size}."
            g.iSet -> "The word was ${g.word.uppercase()}. +2 for you."
            else -> "The word was ${g.word.uppercase()}. +2 for $name."
        }
        Text(
            sub,
            style = LockInText.Small.copy(color = LockInColors.Mist),
            textAlign = TextAlign.Center,
            modifier = Modifier.height(36.dp).padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (g != null && !g.none) {
            Row(Modifier.fillMaxWidth().widthIn(max = 340.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreChip("You", if (g.iSet) "sets" else "guesses", g.scoreMe, g.myTurn, Modifier.weight(1f))
                ScoreChip(name, if (g.iSet) "guesses" else "sets", g.scoreThem, !g.over && !g.myTurn, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- The play area ----
        when {
            g == null || g.none -> {}
            g.status == "setting" && g.iSet -> {
                SetWord(typed, shake.value, clue, onClue = { clue = if (clue == it) "" else it })
            }
            g.status == "setting" -> Waiting()
            else -> {
                Box(Modifier.fillMaxWidth().widthIn(max = 340.dp), contentAlignment = Alignment.BottomCenter) {
                    GuessBoard(
                        g = g,
                        guesses = shownGuesses,
                        baseline = baseline,
                        typed = if (iGuess) (pending ?: typed) else peerTyped,
                        locked = pending != null,
                        myRow = iGuess && !g.over,
                        shake = shake.value,
                    )
                    for (r in reactions.toList()) {
                        key(r.id) { FloatingEmoji(r.emoji) { reactions.remove(r) } }
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = LockInText.Error, textAlign = TextAlign.Center)
        }

        // ---- Keys and buttons ----
        Spacer(Modifier.height(14.dp))
        if (g != null && !g.none && !g.over && canType) {
            Keyboard(
                states = if (g.status == "guessing") keyStates(shownGuesses) else emptyMap(),
                onKey = { typeKey(it) },
                onEnter = { submit() },
                onBack = { back() },
            )
            Spacer(Modifier.height(12.dp))
        }
        if (g != null && g.status == "guessing" && g.iSet) {
            // The setter can cheer, tease or sweat along: the emoji float up the guesser's board.
            Row(Modifier.fillMaxWidth().widthIn(max = 340.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (e in listOf("🔥", "😏", "🥶", "👀", "👏")) {
                    Text(
                        e,
                        fontSize = 26.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(LockInColors.Slate)
                            .clickable(role = Role.Button) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                reactions.add(Reaction(++reactionId, e))
                                live?.send("wd_react", JSONObject().put("e", e))
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            g == null || (g.none && !hadGame) -> {
                if (error != null) LockInButton("Try again", onClick = { error = null; send { repo.play() } })
            }
            g.none -> LockInButton("Start a new game", onClick = { send { repo.play() } }, loading = busy)
            g.over -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Next round", onClick = { send { repo.next() } }, modifier = Modifier.weight(1f), loading = busy)
                LockInButton("End game", onClick = { scope.launch { repo.quit(); live?.send("wd_moved"); onBack() } }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            }
            g.status == "setting" && g.iSet -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Pick for me", onClick = { typed = WordBank.pick(); error = null }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline, enabled = !busy)
                LockInButton("Send word", onClick = { submit() }, modifier = Modifier.weight(1f), loading = busy, enabled = typed.length == 5)
            }
            else -> {}
        }
        if (g != null && !g.none && !g.over) {
            Spacer(Modifier.height(10.dp))
            LockInButton(
                "End game",
                onClick = { scope.launch { repo.quit(); live?.send("wd_moved"); onBack() } },
                kind = ButtonKind.Outline,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

private class Reaction(val id: Long, val emoji: String)

private val Amber = Color(0xFFE6B53A)

private fun markFill(mark: Char): Color = when (mark) {
    'g' -> LockInColors.Lime
    'y' -> Amber
    else -> LockInColors.tone(0.16f)
}

private fun markText(mark: Char): Color = when (mark) {
    'g' -> LockInColors.OnLime
    'y' -> Color(0xFF1B1405)
    else -> LockInColors.Frost
}

/** The best result each letter has had so far: g beats y beats b. */
private fun keyStates(guesses: List<WdGuess>): Map<Char, Char> {
    val rank = mapOf('b' to 1, 'y' to 2, 'g' to 3)
    val out = HashMap<Char, Char>()
    for (gs in guesses) for (i in 0 until minOf(5, gs.word.length, gs.marks.length)) {
        val c = gs.word[i]
        val m = gs.marks[i]
        if ((rank[m] ?: 0) > (rank[out[c]] ?: 0)) out[c] = m
    }
    return out
}

@Composable
private fun ScoreChip(label: String, role: String, score: Int, active: Boolean, modifier: Modifier) {
    val border by animateColorAsState(if (active) LockInColors.Lime else LockInColors.Graphite, tween(240), label = "wd-chip")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.5.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp), maxLines = 1)
            Text(role, style = LockInText.Small.copy(color = LockInColors.Mist))
        }
        Text(score.toString(), style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp, color = LockInColors.Snow))
    }
}

/** One tile. [flip] runs 0 to 1: the tile turns edge-on and comes back showing its result at the half. */
@Composable
private fun Tile(letter: Char?, mark: Char?, flip: Float, size: Dp, lockedIn: Boolean = false, glow: Boolean = false) {
    val face = flip >= 0.5f
    val turn = abs(1f - 2f * flip).coerceAtLeast(0.02f)
    val pop by animateFloatAsState(if (letter != null) 1f else 0.9f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "wd-pop")
    val shape = RoundedCornerShape(10.dp)
    val showMark = mark != null && face
    val bg = if (showMark) markFill(mark!!) else LockInColors.Slate
    val line = when {
        showMark -> Color.Transparent
        letter != null -> if (lockedIn) LockInColors.Lime else LockInColors.Frost
        glow -> LockInColors.Lime.copy(alpha = 0.55f)
        else -> LockInColors.Graphite
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleY = turn; scaleX = pop }
            .clip(shape)
            .background(bg)
            .border(2.dp, line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter.uppercaseChar().toString(),
                style = LockInText.Title.copy(
                    fontSize = (size.value * 0.5f).sp,
                    lineHeight = (size.value * 0.56f).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showMark) markText(mark!!) else LockInColors.Snow,
                ),
            )
        }
    }
}

/** Six rows of five tiles: the guesses so far (turning over as they arrive), the row being typed, and empty rows. */
@Composable
private fun GuessBoard(
    g: WdGame,
    guesses: List<WdGuess>,
    baseline: Int,
    typed: String,
    locked: Boolean,
    myRow: Boolean,
    shake: Float,
) {
    val context = LocalContext.current
    val rows = remember { List(6) { Animatable(0f) } }
    for (r in 0 until 6) {
        val word = guesses.getOrNull(r)?.word
        LaunchedEffect(word, g.round) {
            if (word == null) {
                rows[r].snapTo(0f)
            } else if (r < baseline) {
                rows[r].snapTo(1f)
            } else {
                rows[r].snapTo(0f)
                launch {
                    repeat(5) { k ->
                        delay(if (k == 0) 140L else 110L)
                        GameSfx.play(context, Sfx.Pick)
                    }
                }
                rows[r].animateTo(1f, tween(900, easing = LinearEasing))
            }
        }
    }
    val pulse by rememberInfiniteTransition(label = "wd-row").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "wd-row-pulse",
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val tile = ((maxWidth - gap * 4) / 5).coerceAtMost(58.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until 6) {
                val gs = guesses.getOrNull(r)
                val isCurrent = gs == null && r == guesses.size && !g.over
                val offsetX = if (isCurrent && myRow) shake else 0f
                Row(
                    Modifier.offset { IntOffset((offsetX * density).roundToInt(), 0) },
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (k in 0 until 5) {
                        if (gs != null) {
                            val p = ((rows[r].value - k * 0.12f) / 0.4f).coerceIn(0f, 1f)
                            Tile(gs.word.getOrNull(k), gs.marks.getOrNull(k), p, tile)
                        } else if (isCurrent) {
                            Tile(typed.getOrNull(k), null, 0f, tile, lockedIn = locked, glow = myRow && k == typed.length && pulse > 0.5f)
                        } else {
                            Tile(null, null, 0f, tile)
                        }
                    }
                }
            }
        }
    }
}

/** The setter's word: five tiles, a clue to pick, and a hint of what happens next. */
@Composable
private fun SetWord(typed: String, shake: Float, clue: String, onClue: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 340.dp)) {
        val gap = 8.dp
        val tile = ((maxWidth - gap * 4) / 5).coerceAtMost(58.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.offset { IntOffset((shake * density).roundToInt(), 0) },
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (k in 0 until 5) Tile(typed.getOrNull(k), null, 0f, tile, lockedIn = typed.length == 5, glow = k == typed.length)
            }
            Spacer(Modifier.height(14.dp))
            Text("Add a clue (optional)", style = LockInText.Small.copy(color = LockInColors.Mist))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in listOf("Animal", "Food", "Place", "Thing", "Feeling", "Action", "Nature", "Person")) {
                    val on = clue == c
                    Text(
                        c,
                        style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) LockInColors.Lime else LockInColors.Slate)
                            .border(1.dp, if (on) LockInColors.Lime else LockInColors.Graphite, CircleShape)
                            .clickable(role = Role.Button) { onClue(c) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Waiting for the word: five tiles bouncing one after the other. */
@Composable
private fun Waiting() {
    val t by rememberInfiniteTransition(label = "wd-wait").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "wd-wait-t",
    )
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 340.dp)) {
        val gap = 8.dp
        val tile = ((maxWidth - gap * 4) / 5).coerceAtMost(58.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (k in 0 until 5) {
                val ph = ((t - k * 0.09f) % 1f + 1f) % 1f
                val hop = if (ph < 0.35f) sin(ph / 0.35f * PI.toFloat()) else 0f
                Box(Modifier.padding(horizontal = gap / 2).offset(y = (-14f * hop).dp)) { Tile(null, null, 0f, tile, glow = hop > 0.5f) }
            }
        }
    }
}

/** An emoji from the setter, rising up the board. It grows in, rises, and shrinks away (no fading). */
@Composable
private fun FloatingEmoji(emoji: String, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(1700, easing = UiGlide))
        onDone()
    }
    val sway = remember { (Math.random() * 120.0 - 60.0).toFloat() }
    Text(
        emoji,
        fontSize = 38.sp,
        modifier = Modifier.graphicsLayer {
            val v = p.value
            translationY = -v * 230.dp.toPx()
            translationX = sway.dp.toPx() * sin(v * PI.toFloat() * 1.5f)
            val s = if (v < 0.12f) v / 0.12f else if (v > 0.82f) ((1f - v) / 0.18f).coerceAtLeast(0f) else 1f
            scaleX = s
            scaleY = s
        },
    )
}

private val KEY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

@Composable
private fun Keyboard(states: Map<Char, Char>, onKey: (Char) -> Unit, onEnter: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        KEY_ROWS.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (i == 1) Spacer(Modifier.weight(0.5f))
                if (i == 2) KeyCap("Enter", null, Modifier.weight(1.5f), onClick = onEnter, label = "Enter")
                for (c in row) KeyCap(c.uppercaseChar().toString(), states[c], Modifier.weight(1f), onClick = { onKey(c) })
                if (i == 2) KeyCap("⌫", null, Modifier.weight(1.5f), onClick = onBack, label = "Delete")
                if (i == 1) Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun KeyCap(text: String, state: Char?, modifier: Modifier, onClick: () -> Unit, label: String? = null) {
    val shape = RoundedCornerShape(9.dp)
    val bg by animateColorAsState(if (state != null) markFill(state) else LockInColors.Slate, tween(260), label = "wd-key")
    val fg = if (state != null) markText(state) else LockInColors.Snow
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (state != null) Color.Transparent else LockInColors.Graphite, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label ?: text },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LockInText.Label.copy(color = fg, fontSize = if (text.length > 1 && text != "⌫") 12.sp else 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}
