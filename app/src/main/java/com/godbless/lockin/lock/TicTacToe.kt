package com.godbless.lockin.lock

import android.os.SystemClock
import com.godbless.lockin.ui.handoffSwap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.TttGame
import com.godbless.lockin.data.TttRepository
import com.godbless.lockin.data.tttMessage
import com.godbless.lockin.data.tttNoGame
import com.godbless.lockin.live.LiveChannel
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.calmFloat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tic-tac-toe against your partner, played like a chat game: you make your move, it goes to them, they play when they
 * like and it comes back. Nobody has to be locked and nobody has to be here at the same time. Each of you places three
 * marks; after that your turn is to pick up one of your own marks (hold it, drag it) and put it on any empty square.
 * The board follows the server, so both phones show the same game; you can only move on your turn.
 */
@Composable
internal fun TicTacToe(
    repo: TttRepository,
    name: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The live line to my partner, when there is one: their hand on the board, and their moves, as they happen. */
    live: LiveChannel? = null,
) {
    val context = LocalContext.current
    var game by remember { mutableStateOf<TttGame?>(null) }
    var hadGame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // My move, shown at once while the server answers.
    var optimistic by remember { mutableStateOf<TttGame?>(null) }
    var prevStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // ---- The live line: my partner's hand, and their moves the instant they make them ----
    // Their move shown before the server has confirmed it (dropped as soon as the real answer arrives).
    var incomingOpt by remember { mutableStateOf<TttGame?>(null) }
    var incomingBase by remember { mutableLongStateOf(0L) }
    var peer by remember { mutableStateOf<PeerTouch?>(null) }
    var peerHereAt by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val liveUp by (live?.up ?: remember { MutableStateFlow(false) }).collectAsState()

    LaunchedEffect(Unit) { GameSfx.load(context) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = SystemClock.uptimeMillis() } }

    fun accept(g: TttGame) {
        val cur = game
        // A late answer must not undo a newer board.
        if (g.none || cur == null || cur.none || g.rev >= cur.rev) game = g
        if (incomingOpt != null && g.rev > incomingBase) incomingOpt = null
        if (!g.none) hadGame = true
        error = null
    }

    DisposableEffect(live) {
        live?.acquire()
        onDispose {
            live?.send("ttt_bye")
            live?.release()
        }
    }
    // Say hello now and every few seconds, so each phone knows the other is at the board.
    LaunchedEffect(liveUp) {
        while (liveUp) {
            live?.send("ttt_here")
            delay(3000)
        }
    }
    LaunchedEffect(live) {
        val l = live ?: return@LaunchedEffect
        l.events.collect { ev ->
            val now = SystemClock.uptimeMillis()
            when (ev.name) {
                LiveChannel.UP -> l.send("ttt_here")
                "ttt_here" -> {
                    val first = now - peerHereAt > 7000L
                    peerHereAt = now
                    if (first) l.send("ttt_here")
                }
                "ttt_bye" -> {
                    peerHereAt = 0L
                    peer = null
                }
                "ttt_touch" -> {
                    peerHereAt = now
                    val held = ev.data.optInt("h", -1)
                    val down = ev.data.optInt("d", 0) == 1
                    val x = ev.data.optDouble("x", -1.0).toFloat()
                    val y = ev.data.optDouble("y", -1.0).toFloat()
                    peer = if ((!down && held < 0) || x < 0f || y < 0f) null else PeerTouch(x, y, held, down, now)
                }
                "ttt_drop" -> {
                    peerHereAt = now
                    peer = null
                    val cur = game
                    if (cur != null && cur.status == "playing" && !cur.myTurn) {
                        val next = cur.afterTheirMove(ev.data.optInt("f", -1), ev.data.optInt("t", -1))
                        if (next !== cur) {
                            incomingBase = cur.rev
                            incomingOpt = next
                        }
                    }
                }
                "ttt_undo" -> incomingOpt = null
                // They have moved for real: fetch the board now instead of waiting for the next refresh.
                "ttt_moved" -> scope.launch { repo.state().onSuccess { accept(it) } }
            }
        }
    }
    // A hand that has gone quiet is gone; a move that was never confirmed is taken back.
    LaunchedEffect(peer) {
        val p = peer ?: return@LaunchedEffect
        delay(if (p.down) 1600L else 2600L)
        if (peer === p) peer = null
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
        else if (tttNoGame(firstError)) repo.state().getOrNull()?.let { accept(it) }
        else error = tttMessage(firstError)
        while (true) {
            val s = game
            // Quick while you are both at a live board, slow when nothing is happening.
            val wait = when {
                s == null -> 1100L
                // With the live line up, moves come through it; the refresh is only a safety net (and keeps "here" fresh).
                liveUp && s.status == "playing" -> 5000L
                s.status == "playing" && s.otherHere -> if (s.myTurn) 2000L else 1000L
                s.status == "playing" && !s.myTurn -> 2500L
                else -> 4000L
            }
            delay(wait)
            if (busy) continue
            repo.state().onSuccess { accept(it) }.onFailure { error = tttMessage(it) }
        }
    }

    /** Runs one server call. [opt] is the game to show straight away; it is dropped when the call answers. */
    fun send(opt: TttGame?, block: suspend () -> Result<TttGame>) {
        if (busy) return
        busy = true
        optimistic = opt
        scope.launch {
            try {
                val r = block()
                val e = r.exceptionOrNull()
                if (e == null) {
                    accept(r.getOrThrow())
                    live?.send("ttt_moved")
                } else {
                    // Refresh first: accept() clears the error, which is set after.
                    repo.state().onSuccess { accept(it) }
                    if (!tttNoGame(e)) error = tttMessage(e)
                    live?.send("ttt_undo")
                }
            } finally {
                optimistic = null
                busy = false
            }
        }
    }

    fun place(cell: Int) {
        val cur = game ?: return
        if (busy || cur.status != "playing" || !cur.myTurn || cur.mustSlide || cur.board.getOrNull(cell) != '.') return
        GameSfx.play(context, Sfx.Place)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        live?.send("ttt_drop", JSONObject().put("f", -1).put("t", cell))
        send(cur.afterMyMove(cell)) { repo.move(cell) }
    }

    fun slide(from: Int, to: Int) {
        val cur = game ?: return
        if (busy || cur.status != "playing" || !cur.myTurn || !cur.mustSlide) return
        if (cur.board.getOrNull(from)?.toString() != cur.iAm || cur.board.getOrNull(to) != '.') return
        live?.send("ttt_drop", JSONObject().put("f", from).put("t", to))
        send(cur.afterMySlide(from, to)) { repo.slide(from, to) }
    }

    val g: TttGame? = optimistic ?: incomingOpt ?: game

    // A firmer tap, and a sound, when a game I was playing ends.
    val statusNow = g?.status
    LaunchedEffect(statusNow) {
        if (prevStatus == "playing" && (statusNow == "won" || statusNow == "draw")) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Let a last slide land before the fanfare.
            delay(if ((g?.lastFrom ?: -1) >= 0) 650 else 300)
            if (g?.winner == "me") GameSfx.play(context, Sfx.Win) else if (g?.winner == "them") GameSfx.play(context, Sfx.Lose)
        }
        prevStatus = statusNow
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Header: back, and whether your partner is at the board right now.
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
        Spacer(Modifier.height(16.dp))

        val headline = when {
            g == null && error == null -> "Setting up the board"
            g == null -> "Couldn't open the game"
            g.none && hadGame -> "$name ended the game"
            g.none -> "No game yet"
            g.status == "won" -> if (g.winner == "me") "You won" else "$name won"
            g.status == "draw" -> "A draw"
            g.status == "waiting" -> if (g.invitedByMe) "Waiting for $name" else "Joining $name"
            g.myTurn && g.mustSlide -> "Move a mark"
            g.myTurn -> "Your turn"
            else -> "$name's turn"
        }
        AnimatedContent(
            targetState = headline,
            transitionSpec = { handoffSwap() },
            label = "ttt-head",
        ) { t ->
            Text(
                t,
                style = LockInText.Title.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    color = if (g != null && (g.myTurn || g.winner == "me")) LockInColors.Lime else LockInColors.Snow,
                ),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        val marksOut = g?.board?.count { it.toString() == g.iAm } ?: 0
        Text(
            when {
                g == null || g.none -> ""
                g.status == "playing" && g.myTurn && g.mustSlide -> "Hold one of your marks and drag it to an empty square."
                g.status == "playing" && g.myTurn -> "Tap a square to place your mark. ${marksOut + 1} of 3."
                g.status == "playing" && !g.otherHere -> "Your move is sent. We'll let you know when $name plays."
                g.status == "playing" -> "$name is choosing…"
                else -> ""
            },
            style = LockInText.Small.copy(color = LockInColors.Mist),
            textAlign = TextAlign.Center,
            modifier = Modifier.height(36.dp).padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (g != null && !g.none) PlayerChips(g, name)
        Spacer(Modifier.height(14.dp))

        Board(
            g = g,
            headline = headline,
            enabled = !busy,
            onPlace = { place(it) },
            onSlide = { from, to -> slide(from, to) },
            peer = peer,
            onTouch = { x, y, held, down ->
                live?.send("ttt_touch", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("h", held).put("d", if (down) 1 else 0))
            },
        )

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = LockInText.Error, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))
        when {
            g == null || (g.none && !hadGame) -> {
                if (error != null) LockInButton("Try again", onClick = {
                    error = null
                    send(null) { repo.play() }
                })
            }
            g.none -> LockInButton("Start a new game", onClick = { send(null) { repo.play() } }, loading = busy)
            g.over -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LockInButton("Play again", onClick = { send(null) { repo.again() } }, modifier = Modifier.weight(1f), loading = busy)
                LockInButton("End game", onClick = { scope.launch { repo.quit(); onBack() } }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            }
            else -> LockInButton(
                "End game",
                onClick = { scope.launch { repo.quit(); onBack() } },
                kind = ButtonKind.Outline,
            )
        }
    }
}

/** You and your partner, with each one's mark and wins; whoever's turn it is gets the accent border. */
@Composable
private fun PlayerChips(g: TttGame, name: String) {
    val theirMark = if (g.iAm == "X") "O" else "X"
    val myTurn = g.status == "playing" && g.myTurn
    val theirTurn = g.status == "playing" && !g.myTurn
    Row(Modifier.fillMaxWidth().widthIn(max = 340.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("You", g.iAm, g.winsMe, myTurn || g.winner == "me", Modifier.weight(1f))
        Chip(name, theirMark, g.winsThem, theirTurn || g.winner == "them", Modifier.weight(1f))
    }
}

@Composable
private fun Chip(label: String, mark: String, wins: Int, active: Boolean, modifier: Modifier) {
    val border by animateColorAsState(if (active) LockInColors.Lime else LockInColors.Graphite, tween(240), label = "chip")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.5.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mark,
            style = LockInText.Title.copy(fontSize = 20.sp, lineHeight = 22.sp, color = if (mark == "X") LockInColors.Lime else LockInColors.Snow),
        )
        Text(
            label,
            style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 14.sp),
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(wins.toString(), style = LockInText.Label.copy(color = LockInColors.Mist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
    }
}

private val CELL_NAMES = listOf(
    "top left", "top centre", "top right",
    "middle left", "centre", "middle right",
    "bottom left", "bottom centre", "bottom right",
)

/** What the gesture handler needs to know right now (it outlives any one recomposition). */
private class Live(val board: String, val canSlide: Boolean, val canPlace: Boolean, val myCh: Char)

/** Where my partner's hand is: [x],[y] are fractions of the board (0 to 1), [held] is the square of the mark they hold (or -1). */
internal class PeerTouch(val x: Float, val y: Float, val held: Int, val down: Boolean, val at: Long)

/** A mark on its way from one square to another: [fromPx] is where it was let go (null: the centre of [from]). */
private class Flight(val ch: Char, val from: Int, val to: Int, val fromPx: Offset?, val byMe: Boolean)

/**
 * The board. Marks draw themselves in with a soft pop. Once you have three out, you hold one of yours and it lifts
 * off the board (bigger, with a shadow), follows your finger, empty squares light up as places to land, and on release
 * it springs into its new square with a squash, a ripple and a thud. Your partner's slides play the same way, as an
 * arc across the board. A win sweeps a line across and bursts.
 */
@Composable
private fun Board(
    g: TttGame?,
    headline: String,
    enabled: Boolean,
    onPlace: (Int) -> Unit,
    onSlide: (Int, Int) -> Unit,
    peer: PeerTouch? = null,
    onTouch: (Float, Float, Int, Boolean) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val board = g?.board ?: "........."
    val mineMark = g?.iAm ?: "X"
    val myCh = mineMark.firstOrNull() ?: 'X'
    val live = g != null && g.status == "playing" && g.myTurn && enabled
    val canSlide = live && g != null && g.mustSlide
    val canPlace = live && g != null && !g.mustSlide

    val onPlaceNow = rememberUpdatedState(onPlace)
    val onSlideNow = rememberUpdatedState(onSlide)
    val latest = rememberUpdatedState(Live(board, canSlide, canPlace, myCh))
    val onTouchNow = rememberUpdatedState(onTouch)
    val density = LocalDensity.current

    // ---- My hand, for my partner to watch ----
    val boardPxRef = remember { FloatArray(1) }
    val touchAt = remember { LongArray(1) }
    val memo = remember { arrayOfNulls<FloatArray>(1) }
    /** Tells my partner where my finger is and which mark I hold (or that I let go). About 30 times a second at most. */
    fun touch(pos: Offset?, held: Int, down: Boolean, force: Boolean = false) {
        val px = boardPxRef[0]
        val now = SystemClock.uptimeMillis()
        if (!force && down && now - touchAt[0] < 30L) return
        touchAt[0] = now
        val x = if (pos != null && px > 0f) (pos.x / px).coerceIn(0f, 1f) else -1f
        val y = if (pos != null && px > 0f) (pos.y / px).coerceIn(0f, 1f) else -1f
        memo[0] = if (x >= 0f && (held >= 0 || down)) floatArrayOf(x, y, held.toFloat(), if (down) 1f else 0f) else null
        onTouchNow.value(x, y, held, down)
    }
    // A hand held still sends nothing by itself: repeat it so it does not look as if it let go.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val m = memo[0] ?: continue
            if (SystemClock.uptimeMillis() - touchAt[0] >= 480L) {
                touchAt[0] = SystemClock.uptimeMillis()
                onTouchNow.value(m[0], m[1], m[2].toInt(), m[3] == 1f)
            }
        }
    }

    // ---- Their hand, as it arrives ----
    val theirCh = if (myCh == 'X') 'O' else 'X'
    val peerOn = peer != null && g != null && g.status == "playing" && !g.myTurn
    val peerX = remember { Animatable(0f) }
    val peerY = remember { Animatable(0f) }
    val peerSeen = remember { booleanArrayOf(false) }
    val peerFrac = remember { arrayOfNulls<FloatArray>(1) }
    val peerFracAt = remember { LongArray(1) }
    LaunchedEffect(peer) {
        val p = peer
        if (p == null) {
            peerSeen[0] = false
            return@LaunchedEffect
        }
        if (p.held >= 0 && p.down) {
            peerFrac[0] = floatArrayOf(p.x, p.y)
            peerFracAt[0] = SystemClock.uptimeMillis()
        }
        if (!peerSeen[0]) {
            peerSeen[0] = true
            peerX.snapTo(p.x)
            peerY.snapTo(p.y)
        } else {
            launch { peerX.animateTo(p.x, tween(70, easing = LinearEasing)) }
            launch { peerY.animateTo(p.y, tween(70, easing = LinearEasing)) }
        }
    }

    // ---- Static marks: per-square draw-in and pop ----
    val progress = remember { List(9) { Animatable(0f) } }
    val pop = remember { List(9) { Animatable(1f) } }
    val shown = remember { mutableStateListOf<Char>().apply { repeat(9) { add('.') } } }
    for (i in 0 until 9) {
        val ch = board.getOrElse(i) { '.' }
        LaunchedEffect(ch) {
            if (ch != '.') {
                shown[i] = ch
                if (g != null && g.lastFrom >= 0 && g.lastTo == i) {
                    // Arrived by a slide: the flight draws it moving, this just holds it in place.
                    progress[i].snapTo(1f)
                    pop[i].snapTo(1f)
                } else {
                    pop[i].snapTo(0.72f)
                    launch { pop[i].animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
                    progress[i].animateTo(1f, tween(280, easing = UiGlide))
                }
            } else if (g != null && g.lastFrom == i) {
                // Left by a slide: it is in the air, not wiping out.
                progress[i].snapTo(0f)
                shown[i] = '.'
            } else {
                progress[i].animateTo(0f, tween(240, easing = UiGlide))
                shown[i] = '.'
            }
        }
    }

    // ---- Picking up and dragging one of my marks ----
    var lifted by remember { mutableIntStateOf(-1) }
    var dragPx by remember { mutableStateOf<Offset?>(null) }
    var hover by remember { mutableIntStateOf(-1) }
    val liftAnim = remember { Animatable(0f) }
    var flight by remember { mutableStateOf<Flight?>(null) }
    val fp = remember { Animatable(0f) }
    val landing = remember { Animatable(0f) }
    val ripple = remember { Animatable(1f) }
    var rippleAt by remember { mutableIntStateOf(-1) }
    var rippleMine by remember { mutableStateOf(true) }

    fun centreOf(i: Int, cell: Float) = Offset((i % 3) * cell + cell / 2f, (i / 3) * cell + cell / 2f)

    /** Runs a flight to its end: the move, then the landing (thud, squash, ripple). */
    fun fly(f: Flight, arc: Boolean) {
        flight = f
        scope.launch {
            fp.snapTo(0f)
            if (arc) fp.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            else fp.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            GameSfx.play(context, Sfx.Drop)
            haptic.performHapticFeedback(if (f.byMe) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
            rippleAt = f.to
            rippleMine = f.byMe
            launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(620, easing = UiGlide)) }
            landing.snapTo(1f)
            flight = null
            landing.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 520f))
        }
    }

    fun drop(from: Int, to: Int, startPx: Offset?) {
        // The mark is in flight from here on, so the lift is over.
        lifted = -1
        dragPx = null
        hover = -1
        scope.launch { liftAnim.snapTo(0f) }
        touch(null, -1, false, force = true)
        fly(Flight(myCh, from, to, startPx, true), arc = false)
        onSlideNow.value(from, to)
    }

    fun putDown() {
        // Back where it came from, springing home.
        val from = lifted
        val px = dragPx
        lifted = -1
        dragPx = null
        hover = -1
        scope.launch { liftAnim.snapTo(0f) }
        touch(null, -1, false, force = true)
        if (from >= 0 && px != null) fly(Flight(myCh, from, from, px, true), arc = false)
    }

    fun pickUp(cell: Int, px: Offset?) {
        lifted = cell
        dragPx = px
        GameSfx.play(context, Sfx.Pick)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        touch(px ?: centreOf(cell, boardPxRef[0] / 3f), cell, px != null, force = true)
        scope.launch { liftAnim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f)) }
    }

    // The slide my partner (or a refresh) just made: play it as an arc. Placements make a sound.
    val prevBoard = remember { arrayOfNulls<String>(1) }
    // A slide that has just arrived in this board: its landing square stays empty and its start square is already
    // clear, so the mark is only ever drawn moving (no one-frame flash of it in the wrong place).
    val slideTo: Int
    val slideFrom: Int
    run {
        val old = prevBoard[0]
        val f = g?.lastFrom ?: -1
        val t = g?.lastTo ?: -1
        val fresh = old != null && old != board && f in 0..8 && t in 0..8 && old[f] != '.' && old[t] == '.'
        slideTo = if (fresh) t else -1
        slideFrom = if (fresh) f else -1
    }
    LaunchedEffect(board, g == null) {
        if (g == null) return@LaunchedEffect
        val old = prevBoard[0]
        prevBoard[0] = board
        if (old == null || old == board) return@LaunchedEffect
        val to = g.lastTo
        val from = g.lastFrom
        val theirs = to in 0..8 && board[to] != myCh && board[to] != '.'
        if (!theirs) return@LaunchedEffect
        if (from in 0..8 && old[from] != '.' && old[to] == '.') {
            if (flight == null) {
                // If I watched them carry it, it carries on from where their finger let go.
                val pf = peerFrac[0]
                val fresh = pf != null && SystemClock.uptimeMillis() - peerFracAt[0] < 2500L && boardPxRef[0] > 0f
                val startPx = pf?.takeIf { fresh }?.let { Offset(it[0] * boardPxRef[0], it[1] * boardPxRef[0] - with(density) { 14.dp.toPx() }) }
                fly(Flight(board[to], from, to, startPx, false), arc = startPx == null)
            }
        } else if (from < 0 && old[to] == '.') {
            GameSfx.play(context, Sfx.Place)
            rippleAt = to
            rippleMine = false
            scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(620, easing = UiGlide)) }
        }
    }

    // Nothing stays lifted once it is no longer my move.
    LaunchedEffect(canSlide) {
        if (!canSlide && lifted >= 0 && flight == null) {
            lifted = -1
            dragPx = null
            hover = -1
            liftAnim.snapTo(0f)
        }
    }

    // ---- Winning ----
    val sweep = remember { Animatable(0f) }
    val burst = remember { Animatable(1f) }
    val line = g?.line.orEmpty()
    LaunchedEffect(line) {
        if (line.size == 3) {
            sweep.snapTo(0f)
            burst.snapTo(1f)
            delay(if (g != null && g.lastFrom >= 0) 760 else 280)
            sweep.animateTo(1f, tween(480, easing = UiGlide))
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1000, easing = UiGlide))
        } else {
            sweep.snapTo(0f)
            burst.snapTo(1f)
        }
    }

    // ---- Idle motion ----
    val pulse by rememberInfiniteTransition(label = "ttt").calmFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "pulse",
    )
    val beat = 1f - abs(pulse * 2f - 1f)
    val glowAnim = remember { Animatable(0f) }
    LaunchedEffect(live) { glowAnim.animateTo(if (live) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 140f)) }
    val rim by animateColorAsState(if (live) LockInColors.Lime else LockInColors.Graphite, tween(260), label = "rim")
    val shape = RoundedCornerShape(30.dp)

    val tileFill = LockInColors.Ink.copy(alpha = 0.55f)
    val mine = LockInColors.Lime
    val theirs = LockInColors.Snow
    val won = LockInColors.Lime
    val lost = LockInColors.Flare
    val winner = g?.winner.orEmpty()

    val summary = buildString {
        append(headline).append(". You are ").append(mineMark).append(". ")
        val parts = (0 until 9).filter { board.getOrElse(it) { '.' } != '.' }.map { "${board[it]} in ${CELL_NAMES[it]}" }
        if (parts.isEmpty()) append("The board is empty.") else append(parts.joinToString(", ")).append(".")
    }

    // Tap on a square (also what a screen reader's double tap does): place, pick up, or put the held mark down.
    fun tapCell(i: Int) {
        val now = latest.value
        val c = now.board.getOrElse(i) { '.' }
        val canSlide = now.canSlide
        val canPlace = now.canPlace
        val myCh = now.myCh
        when {
            canPlace && c == '.' -> onPlaceNow.value(i)
            canSlide && lifted >= 0 && c == '.' -> drop(lifted, i, null)
            canSlide && c == myCh && lifted == i -> putDown()
            canSlide && c == myCh -> {
                if (lifted >= 0) { lifted = i; dragPx = null } else pickUp(i, null)
            }
        }
    }

    val tapNow = rememberUpdatedState({ i: Int -> tapCell(i) })
    val dropNow = rememberUpdatedState({ a: Int, b: Int, px: Offset? -> drop(a, b, px) })
    val putDownNow = rememberUpdatedState({ putDown() })
    val pickUpNow = rememberUpdatedState({ c: Int, px: Offset? -> pickUp(c, px) })

    Box(
        Modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(if (live) (1.5f + pulse).dp else 1.dp, rim, shape)
            .semantics { contentDescription = summary }
            .onSizeChanged { boardPxRef[0] = it.width.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cell = size.width / 3f
                    if (cell <= 0f) return@awaitEachGesture
                    fun cellAt(o: Offset): Int {
                        val col = (o.x / cell).toInt().coerceIn(0, 2)
                        val row = (o.y / cell).toInt().coerceIn(0, 2)
                        return row * 3 + col
                    }
                    val live0 = latest.value
                    val start = cellAt(down.position)
                    val startsOnMine = live0.canSlide && live0.board.getOrElse(start) { '.' } == live0.myCh
                    if (!startsOnMine) {
                        // A tap: place, or put a held mark down. Anything else is ignored. While it is my move my partner
                        // sees my finger on the board.
                        val mineTurn = live0.canSlide || live0.canPlace
                        if (mineTurn) touch(down.position, lifted, true, force = true)
                        var upAt: Offset? = null
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUp()) { ch.consume(); upAt = ch.position; break }
                            if (!ch.pressed) break
                            if (mineTurn) touch(ch.position, lifted, true)
                        }
                        if (mineTurn) touch(null, lifted, false, force = true)
                        if (upAt != null && mineTurn) tapNow.value(cellAt(upAt))
                        return@awaitEachGesture
                    }
                    // On one of my marks: lift it at once and follow the finger; a tap without movement keeps it held.
                    val wasHeld = lifted == start
                    if (!wasHeld) { if (lifted >= 0) { lifted = start; dragPx = down.position } else pickUpNow.value(start, down.position) }
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var pos = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        pos = ch.position
                        if (ch.changedToUp()) { ch.consume(); break }
                        if (!ch.pressed) { pos = Offset(-1f, -1f); break }
                        if (!dragging && (pos - down.position).getDistance() > slop) dragging = true
                        touch(pos, lifted, true)
                        if (dragging) {
                            ch.consume()
                            dragPx = pos
                            val h = cellAt(pos).takeIf { pos.x in 0f..size.width.toFloat() && pos.y in 0f..size.height.toFloat() && latest.value.board[it] == '.' } ?: -1
                            if (h != hover) {
                                hover = h
                                if (h >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    if (dragging) {
                        val inside = pos.x in 0f..size.width.toFloat() && pos.y in 0f..size.height.toFloat()
                        val target = if (inside) cellAt(pos) else -1
                        if (target >= 0 && target != start && latest.value.board[target] == '.') dropNow.value(start, target, pos)
                        else putDownNow.value()
                    } else if (wasHeld) {
                        putDownNow.value()
                    } else {
                        // Tapped: it stays held, drawn where it was.
                        dragPx = null
                        touch(Offset((start % 3 + 0.5f) * cell, (start / 3 + 0.5f) * cell), start, false, force = true)
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val cell = w / 3f
            // A soft glow behind a board that is waiting for you (it grows and shrinks, no fading).
            val glowSize = glowAnim.value
            if (glowSize > 0.01f) {
                drawCircle(
                    Brush.radialGradient(listOf(mine.copy(alpha = 0.16f), Color.Transparent), Offset(w / 2f, w / 2f), w * 0.78f * glowSize),
                    radius = w * 0.78f * glowSize,
                    center = Offset(w / 2f, w / 2f),
                )
            }
            val gap = 7.dp.toPx()
            val corner = CornerRadius(16.dp.toPx())
            // The nine squares. While a mark is held, empty ones light up as places to land.
            for (i in 0 until 9) {
                val tl = Offset((i % 3) * cell + gap / 2f + 5.dp.toPx(), (i / 3) * cell + gap / 2f + 5.dp.toPx())
                val sz = Size(cell - gap - 10.dp.toPx(), cell - gap - 10.dp.toPx())
                drawRoundRect(tileFill, tl, sz, corner)
                val empty = board.getOrElse(i) { '.' } == '.'
                if (lifted >= 0 && empty) {
                    val isHover = hover == i
                    drawRoundRect(mine.copy(alpha = if (isHover) 0.22f else 0.07f + 0.05f * beat), tl, sz, corner)
                    drawRoundRect(
                        mine.copy(alpha = if (isHover) 0.95f else 0.35f + 0.2f * beat), tl, sz, corner,
                        style = Stroke(if (isHover) 2.5.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
                if (lifted == i) {
                    // The empty socket the held mark came from.
                    drawRoundRect(mine.copy(alpha = 0.4f), tl, sz, corner, style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))))
                }
            }
            if (rippleAt in 0..8 && ripple.value < 1f) {
                val c = centreOf(rippleAt, cell)
                val t = ripple.value
                drawCircle(
                    if (rippleMine) mine else theirs, radius = cell * (0.18f + 0.42f * t), center = c,
                    style = Stroke(width = (6.dp.toPx() * (1f - t)).coerceAtLeast(0.1f)),
                )
            }

            val winning = g != null && g.over && g.line.size == 3
            val fl = flight
            for (i in 0 until 9) {
                val ch = shown[i]
                val p = progress[i].value
                if (ch == '.' || p <= 0f) continue
                if (i == lifted || i == slideTo || i == slideFrom || (peerOn && peer?.held == i)) continue
                if (fl != null && fl.to == i) continue
                val c = centreOf(i, cell)
                val isMine = ch == myCh
                var scale = pop[i].value
                // My marks breathe a little when it's time to move one: they can be picked up.
                if (canSlide && isMine) scale *= 1f + 0.035f * beat
                if (winning && g?.line?.contains(i) == true) scale *= 1f + 0.09f * sin(burst.value.coerceIn(0f, 1f) * PI.toFloat()) * (if (sweep.value >= 1f) 1f else 0f)
                if (rippleAt == i && landing.value != 0f && fl == null) scale *= 1f - 0.14f * landing.value
                drawMark(ch, c, cell, p, if (isMine) mine else theirs, scale, 0f)
            }

            // My partner's hand: the mark they hold (floating where their finger is), or a glow on the square they are touching.
            val pt = peer
            if (peerOn && pt != null) {
                val at = Offset(peerX.value * w, peerY.value * w)
                val under = ((at.y / cell).toInt().coerceIn(0, 2)) * 3 + (at.x / cell).toInt().coerceIn(0, 2)
                fun tl(i: Int) = Offset((i % 3) * cell + gap / 2f + 5.dp.toPx(), (i / 3) * cell + gap / 2f + 5.dp.toPx())
                val sz = Size(cell - gap - 10.dp.toPx(), cell - gap - 10.dp.toPx())
                if (pt.held in 0..8) {
                    drawRoundRect(
                        theirs.copy(alpha = 0.4f), tl(pt.held), sz, corner,
                        style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))),
                    )
                    if (pt.down && board.getOrElse(under) { '.' } == '.') {
                        drawRoundRect(theirs.copy(alpha = 0.16f), tl(under), sz, corner)
                        drawRoundRect(theirs.copy(alpha = 0.8f), tl(under), sz, corner, style = Stroke(2.dp.toPx()))
                    }
                    val c = if (pt.down) Offset(at.x.coerceIn(0f, w), (at.y - 14.dp.toPx()).coerceIn(0f, w)) else centreOf(pt.held, cell)
                    drawLifted(theirCh, c, cell, theirs, 1.26f, 1f, 0f)
                } else if (pt.down) {
                    drawRoundRect(theirs.copy(alpha = 0.13f), tl(under), sz, corner)
                    drawCircle(theirs.copy(alpha = 0.22f), 20.dp.toPx(), at)
                    drawCircle(theirs.copy(alpha = 0.9f), 6.dp.toPx(), at)
                }
            }

            // The mark in my hand, or in the air.
            if (lifted >= 0) {
                val ch = board.getOrElse(lifted) { myCh }
                val c = dragPx?.let { Offset(it.x.coerceIn(0f, w), (it.y - 14.dp.toPx() * liftAnim.value).coerceIn(0f, w)) } ?: centreOf(lifted, cell)
                drawLifted(ch, c, cell, mine, 1f + 0.26f * liftAnim.value, liftAnim.value, 0f)
            }
            if (fl != null) {
                val to = centreOf(fl.to, cell)
                val from = fl.fromPx ?: centreOf(fl.from, cell)
                val t = fp.value
                var pos = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                val lift: Float
                var rot = 0f
                if (!fl.byMe && fl.fromPx == null) {
                    // An arc across, with a little tilt.
                    val hump = sin(t.coerceIn(0f, 1f) * PI.toFloat())
                    pos = Offset(pos.x, pos.y - cell * 0.34f * hump)
                    rot = 10f * hump * (if (to.x >= from.x) 1f else -1f)
                    lift = hump
                } else {
                    lift = (1f - t).coerceIn(0f, 1f)
                }
                val color = if (fl.ch == myCh) mine else theirs
                drawLifted(fl.ch, pos, cell, color, 1f + 0.26f * lift, lift, rot)
            }

            if (line.size == 3 && sweep.value > 0f) {
                val a = centreOf(line[0], cell)
                val b = centreOf(line[2], cell)
                val dx = b.x - a.x
                val dy = b.y - a.y
                val from = Offset(a.x - dx * 0.12f, a.y - dy * 0.12f)
                val to = Offset(a.x + dx * 1.12f * sweep.value, a.y + dy * 1.12f * sweep.value)
                val col = if (winner == "me") won else lost
                drawLine(col.copy(alpha = 0.28f), from, to, 16.dp.toPx(), StrokeCap.Round)
                drawLine(col, from, to, 7.dp.toPx(), StrokeCap.Round)
            }
            // A burst of sparks from the winning line; they shrink away.
            if (line.size == 3 && burst.value > 0f && burst.value < 1f) {
                val col = if (winner == "me") won else lost
                val rnd = java.util.Random(11L)
                val t = burst.value
                val ease = 1f - (1f - t) * (1f - t)
                for (k in 0 until 30) {
                    val src = centreOf(line[k % 3], cell)
                    val ang = rnd.nextFloat() * 2f * PI.toFloat()
                    val speed = cell * (0.35f + rnd.nextFloat() * 0.75f)
                    val r = (2f + rnd.nextFloat() * 4f).dp.toPx() * (1f - t)
                    val gravity = cell * 0.35f * t * t
                    drawCircle(col, r, Offset(src.x + cos(ang) * speed * ease, src.y + sin(ang) * speed * ease + gravity))
                }
            }
        }
        // Nine invisible squares on top: they tell a screen reader what is in each, and take its taps.
        Column(Modifier.matchParentSize()) {
            for (row in 0 until 3) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until 3) {
                        val i = row * 3 + col
                        val c = board.getOrElse(i) { '.' }
                        val what = when {
                            c == '.' -> "empty"
                            c == myCh -> "your $c" + if (lifted == i) ", picked up" else ""
                            else -> "their $c"
                        }
                        val label = CELL_NAMES[i].replaceFirstChar { it.uppercase() } + ", " + what
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics {
                                    contentDescription = label
                                    role = Role.Button
                                    onClick { tapCell(i); true }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Waits for [id] to lift; null if it was cancelled (or moved away, which a plain tap never does). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUpOrCancel(id: androidx.compose.ui.input.pointer.PointerId): Offset? {
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: return null
        if (ch.changedToUp()) { ch.consume(); return ch.position }
        if (!ch.pressed) return null
    }
}

/** A mark sitting on the board: [scale] about its centre, [p] how much of it is drawn (0 to 1). */
private fun DrawScope.drawMark(ch: Char, c: Offset, cell: Float, p: Float, color: Color, sc: Float, rotation: Float) {
    withTransform({
        scale(scaleX = sc, scaleY = sc, pivot = c)
        if (rotation != 0f) rotate(rotation, c)
    }) {
        glowStroke(ch, c, cell, p, color, 9.dp.toPx())
    }
}

/** A mark held above the board: bigger, with a shadow that drops further away the higher it is. */
private fun DrawScope.drawLifted(ch: Char, c: Offset, cell: Float, color: Color, sc: Float, height: Float, rotation: Float) {
    val r = cell * 0.34f
    val shadowC = Offset(c.x + 4.dp.toPx() * height, c.y + (10.dp.toPx() + 14.dp.toPx() * height) * (0.4f + 0.6f * height))
    drawCircle(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f * (0.4f + 0.6f * height)), Color.Transparent), shadowC, r * (1f + 0.3f * height)),
        r * (1f + 0.3f * height), shadowC,
    )
    withTransform({
        scale(scaleX = sc, scaleY = sc, pivot = c)
        if (rotation != 0f) rotate(rotation, c)
    }) {
        glowStroke(ch, c, cell, 1f, color, 9.dp.toPx())
    }
}

/** The mark's strokes twice: a wide, faint one for a glow, then the crisp one. */
private fun DrawScope.glowStroke(ch: Char, c: Offset, cell: Float, p: Float, color: Color, width: Float) {
    if (ch == 'X') {
        drawX(c.x, c.y, cell * 0.2f, p, color.copy(alpha = 0.18f), width * 2.3f)
        drawX(c.x, c.y, cell * 0.2f, p, color, width)
    } else {
        drawO(c.x, c.y, cell * 0.21f, p, color.copy(alpha = 0.18f), width * 2.3f)
        drawO(c.x, c.y, cell * 0.21f, p, color, width)
    }
}

private fun DrawScope.drawX(cx: Float, cy: Float, r: Float, p: Float, color: Color, width: Float) {
    val first = (p * 2f).coerceIn(0f, 1f)
    val second = (p * 2f - 1f).coerceIn(0f, 1f)
    drawLine(color, Offset(cx - r, cy - r), Offset(cx - r + 2 * r * first, cy - r + 2 * r * first), width, StrokeCap.Round)
    if (second > 0f) drawLine(color, Offset(cx + r, cy - r), Offset(cx + r - 2 * r * second, cy - r + 2 * r * second), width, StrokeCap.Round)
}

private fun DrawScope.drawO(cx: Float, cy: Float, r: Float, p: Float, color: Color, width: Float) {
    drawArc(
        color, -90f, 360f * p, false,
        topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}
