package com.godbless.lockin.ludo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.LudoGame
import com.godbless.lockin.data.LudoRepository
import com.godbless.lockin.data.LudoSeat
import com.godbless.lockin.data.ludoMessage
import com.godbless.lockin.live.LiveChannel
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.MotionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val EMOTES = listOf("😂", "🔥", "😤", "😎", "🙏", "👀", "💀", "🎲")

private fun lerpP(a: P, b: P, t: Float) = P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

internal class Banner(val text: String, val seat: Int, val id: Long = System.nanoTime())

internal class FloatEmote(val emoji: String, val corner: Int, val id: Long = System.nanoTime())

/** Everything on the board that moves, and the game as the board shows it (which trails the server by an animation). */
@Stable
internal class LudoAnim {
    var shown by mutableStateOf<LudoGame?>(null)
    var tokens by mutableStateOf(List(16) { -1 })
    var seq = -1L

    var moverT by mutableStateOf(-1)
    var moverAt by mutableStateOf(P(0f, 0f))
    var moverLift by mutableStateOf(0f)
    var moverSquash by mutableStateOf(0f)

    /** Tokens flying home to their yard after a capture: board point, lift (cells) and spin. */
    val flying = mutableStateMapOf<Int, Triple<P, Float, Float>>()

    var dieSeat by mutableStateOf(-1)
    var dieValue by mutableStateOf(6)
    val rx = Animatable(-12f)
    val ry = Animatable(18f)
    val rz = Animatable(0f)
    val lift = Animatable(0f)
    var dieGlow by mutableStateOf(0f)
    var tumbling by mutableStateOf(false)
    private var tumbleSince = 0L
    private var tumbleJob: Job? = null

    var banner by mutableStateOf<Banner?>(null)
    val fx = LudoFx()
    var frame by mutableLongStateOf(0L)
    val shake = Animatable(0f)
    var busy by mutableStateOf(false)

    /** The board's cell size in px and how it is turned, for placing effects. Set by the board as it lays out. */
    var cellPx = 0f
    var turns = 0

    fun at(p: P): Offset = px(p, turns, cellPx)

    fun startTumble(scope: kotlinx.coroutines.CoroutineScope, seat: Int) {
        if (tumbling && dieSeat == seat) return
        dieSeat = seat
        tumbling = true
        tumbleSince = System.currentTimeMillis()
        tumbleJob?.cancel()
        tumbleJob = scope.launch {
            var t = 0f
            var last = 0L
            while (tumbling) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                    last = now
                    t += dt
                }
                rx.snapTo(rx.value + 23f)
                ry.snapTo(ry.value + 15f)
                rz.snapTo(rz.value + 7f)
                lift.snapTo(abs(sin(t * 13f)) * 10f + 4f)
            }
        }
    }

    fun stopTumble() {
        tumbling = false
    }

    /** Throws the die for [seat] and lets it come to rest on [value]. */
    suspend fun settle(scope: kotlinx.coroutines.CoroutineScope, seat: Int, value: Int, quick: Boolean) {
        if (!tumbling || dieSeat != seat) startTumble(scope, seat)
        val waited = System.currentTimeMillis() - tumbleSince
        val least = if (quick) 120L else 420L
        if (waited < least) delay(least - waited)
        tumbling = false
        tumbleJob?.join()
        val (tx, ty) = dieFace(value)
        fun past(cur: Float, target: Float, spins: Int): Float {
            var v = target
            while (v < cur + 360f * spins) v += 360f
            return v
        }
        val ms = if (quick) 240 else 760
        coroutineScope {
            launch { rx.animateTo(past(rx.value, tx, 1), tween(ms, easing = { 1f - (1f - it) * (1f - it) * (1f - it) })) }
            launch { ry.animateTo(past(ry.value, ty, 1), tween(ms, easing = { 1f - (1f - it) * (1f - it) * (1f - it) })) }
            launch { rz.animateTo(past(rz.value, 0f, 0), tween(ms, easing = { 1f - (1f - it) * (1f - it) })) }
            launch {
                lift.animateTo(
                    0f,
                    keyframes {
                        durationMillis = ms
                        lift.value at 0
                        26f at (ms * 0.22f).toInt()
                        0f at (ms * 0.48f).toInt()
                        9f at (ms * 0.66f).toInt()
                        0f at (ms * 0.82f).toInt()
                        2f at (ms * 0.9f).toInt()
                        0f at ms
                    },
                )
            }
        }
        rx.snapTo(rx.value % 360f)
        ry.snapTo(ry.value % 360f)
        rz.snapTo(rz.value % 360f)
        dieValue = value
    }

    suspend fun hop(t: Int, from: P, to: P, height: Float, ms: Int) {
        moverT = t
        val a = Animatable(0f)
        a.animateTo(1f, tween(ms, easing = LinearEasing)) {
            val e = value * value * (3f - 2f * value)
            moverAt = lerpP(from, to, e)
            moverLift = sin(PI.toFloat() * value) * height
        }
        moverLift = 0f
    }
}

/** Where each token sits on the board as drawn: (token, canonical point, scale). Tokens sharing a square fan out. */
private fun layout(tokens: List<Int>, skip: Set<Int>): List<Triple<Int, P, Float>> {
    val groups = LinkedHashMap<String, MutableList<Pair<Int, P>>>()
    val out = ArrayList<Triple<Int, P, Float>>()
    for (t in 0 until 16) {
        if (t in skip) continue
        val pos = tokens[t]
        val p = LudoGeo.point(t / 4, pos, t % 4) ?: continue
        if (pos == -1 || pos == LudoGeo.HOME) {
            out.add(Triple(t, p, if (pos == LudoGeo.HOME) 0.7f else 1f))
            continue
        }
        val key = "${(p.x * 2).roundToInt()}:${(p.y * 2).roundToInt()}"
        groups.getOrPut(key) { ArrayList() }.add(t to p)
    }
    for (g in groups.values) {
        if (g.size == 1) {
            out.add(Triple(g[0].first, g[0].second, 1f))
            continue
        }
        g.forEachIndexed { i, (t, p) ->
            val a = i * 2 * PI / g.size - PI / 4
            out.add(Triple(t, P(p.x + (kotlin.math.cos(a) * 0.2).toFloat(), p.y + (kotlin.math.sin(a) * 0.2).toFloat()), 0.74f))
        }
    }
    return out
}

/**
 * Ludo, for a pair ([scope] "pair") or at a crew table ("crew"). The server deals the dice and keeps the score; this
 * screen plays every roll, hop and capture as it comes, from this phone or anyone else's, and shows the others live:
 * their die rattling as they roll, their finger on the board, their reactions.
 */
@Composable
internal fun LudoGameScreen(
    repo: LudoRepository,
    scope: String,
    live: LiveChannel?,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val co = rememberCoroutineScope()
    val anim = remember { LudoAnim() }
    val latest = remember { MutableStateFlow<LudoGame?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var confirmLeave by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(LudoSfx.muted) }
    val emotes = remember { mutableStateListOf<FloatEmote>() }
    val fingers = remember { mutableStateMapOf<Int, Pair<P, Long>>() }
    val reduced = MotionState.reduced
    val liveUp by (live?.up ?: remember { MutableStateFlow(false) }).collectAsState()
    val me = repo.me()

    fun feed(g: LudoGame) {
        val cur = latest.value
        if (cur == null || g.none || g.seq > cur.seq || (g.seq == cur.seq && g.rev >= cur.rev) || cur.none) latest.value = g
    }

    fun share(g: LudoGame) {
        if (g.json.isNotEmpty()) live?.send("lu_state", JSONObject().put("g", g.json))
    }

    fun act(block: suspend () -> Result<LudoGame>) {
        if (anim.busy) return
        anim.busy = true
        co.launch {
            val r = block()
            r.onSuccess { feed(it); share(it); error = null }
            val failed = r.exceptionOrNull()
            if (failed != null) {
                error = ludoMessage(failed)
                anim.stopTumble()
                LudoSfx.stopShake()
                repo.state(scope).onSuccess { g -> feed(g) }
            }
            anim.busy = false
        }
    }

    DisposableEffect(Unit) {
        LudoSfx.load(context)
        onDispose { LudoSfx.stopShake() }
    }

    // ---- Live: hello, the board from other phones, their dice, fingers and faces.
    DisposableEffect(live) {
        live?.acquire()
        onDispose {
            live?.send("lu_bye")
            live?.release()
        }
    }
    LaunchedEffect(live) {
        val l = live ?: return@LaunchedEffect
        l.events.collect { ev ->
            when (ev.name) {
                LiveChannel.UP -> l.send("lu_here")
                "lu_here", "lu_bye" -> repo.state(scope).onSuccess { feed(it) }
                "lu_state" -> runCatching { repo.parse(ev.data.optString("g")) }.getOrNull()?.let { g ->
                    if (!g.none && g.id == latest.value?.id || latest.value?.none != false) feed(g)
                    // The server stays the truth: check it straight after.
                    co.launch { delay(350); repo.state(scope).onSuccess { feed(it) } }
                }
                "lu_shake" -> {
                    val seat = ev.data.optInt("seat", -1)
                    if (seat in 0..3 && seat != anim.shown?.meSeat) {
                        anim.startTumble(co, seat)
                        LudoSfx.play(context, LudoSound.Shake, volume = 0.6f)
                    }
                }
                "lu_touch" -> {
                    val seat = ev.data.optInt("seat", -1)
                    if (seat in 0..3) {
                        if (ev.data.optBoolean("up")) fingers.remove(seat)
                        else fingers[seat] = P(ev.data.optDouble("x").toFloat(), ev.data.optDouble("y").toFloat()) to System.currentTimeMillis()
                    }
                }
                "lu_emote" -> {
                    val seat = ev.data.optInt("seat", -1)
                    val e = ev.data.optString("e")
                    val turns = LudoGeo.viewTurns(anim.shown?.meSeat ?: -1)
                    if (e in EMOTES && seat in 0..3) {
                        emotes.add(FloatEmote(e, LudoGeo.corner(seat, turns)))
                        LudoSfx.play(context, LudoSound.Pop)
                    }
                }
            }
        }
    }

    // ---- First load, then a steady check (faster when the live line is down).
    LaunchedEffect(Unit) {
        val first = repo.state(scope)
        val have = first.getOrNull()
        if (have == null) error = first.exceptionOrNull()?.let { ludoMessage(it) }
        else if (have.none) repo.open(scope).onSuccess { feed(it); share(it) }.onFailure { error = ludoMessage(it) }
        else feed(have)
        loading = false
        live?.send("lu_here")
        while (true) {
            delay(if (liveUp) 4_000 else 1_600)
            repo.state(scope).onSuccess { feed(it) }
        }
    }

    // ---- When the player on turn runs out of time, any phone at the table asks the server to play it for them.
    val shownNow = anim.shown
    LaunchedEffect(shownNow?.seq, shownNow?.status) {
        val g = shownNow ?: return@LaunchedEffect
        if (!g.playing || g.meSeat < 0) return@LaunchedEffect
        delay(g.msLeftNow() + 1_300L + g.meSeat * 350L)
        repo.tick(scope).onSuccess { feed(it); if (it.seq > g.seq) share(it) }
    }

    // ---- The director: plays whatever happened last, in order, then shows the new state.
    LaunchedEffect(Unit) {
        latest.collect { g ->
            if (g == null) return@collect
            play(g, anim, context, haptic, reduced, co)
        }
    }

    // ---- The frame clock for sparks and confetti, and a clock for the turn timers.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceAtMost(0.05f)
                last = t
                if (anim.fx.busy) {
                    anim.fx.step(dt, 900f)
                    anim.frame = t
                }
            }
            now = System.currentTimeMillis()
        }
    }
    // Fingers that have stopped moving fade out of the picture (by being removed).
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val cut = System.currentTimeMillis() - 1_600
            fingers.filterValues { it.second < cut }.keys.toList().forEach { fingers.remove(it) }
        }
    }
    // The last seconds of my turn tick.
    val g = anim.shown
    val secs = if (g != null && g.myTurn && !anim.busy) ((g.msLeftNow(now) + 999) / 1000).toInt() else -1
    LaunchedEffect(secs) {
        if (secs in 1..5) LudoSfx.play(context, LudoSound.Tick, rate = 1f + (5 - secs) * 0.06f)
    }

    val turns = LudoGeo.viewTurns(g?.meSeat ?: -1)
    val turnColor by animateColorAsState(
        if (g != null && g.playing) LudoPalette.c(g.turn) else LockInColors.Lime,
        spring(stiffness = Spring.StiffnessLow),
        label = "turn-colour",
    )

    fun roll() {
        val cur = anim.shown ?: return
        if (!cur.myTurn || cur.phase != "roll" || anim.busy || anim.tumbling) return
        anim.startTumble(co, cur.meSeat)
        LudoSfx.startShake(context)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        live?.send("lu_shake", JSONObject().put("seat", cur.meSeat))
        act { repo.roll(scope) }
    }

    fun tapBoard(p: P) {
        val cur = anim.shown ?: return
        if (!cur.myTurn) return
        if (cur.phase == "roll") { roll(); return }
        val moves = LudoGeo.movable(anim.tokens, cur.meSeat, cur.dice)
        var best = -1
        var bestD = 0.95f
        for (k in moves) {
            val t = cur.meSeat * 4 + k
            val at = LudoGeo.point(cur.meSeat, anim.tokens[t], k) ?: continue
            val to = LudoGeo.dest(anim.tokens[t], cur.dice)?.let { LudoGeo.point(cur.meSeat, it, k) }
            val d = listOfNotNull(at, to).minOf { kotlin.math.hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()).toFloat() }
            if (d < bestD) { bestD = d; best = k }
        }
        if (best >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            act { repo.move(scope, best) }
        }
    }

    var lastTouch by remember { mutableLongStateOf(0L) }
    fun touch(p: P?) {
        val cur = anim.shown ?: return
        if (cur.meSeat < 0) return
        val t = System.currentTimeMillis()
        if (p == null) {
            live?.send("lu_touch", JSONObject().put("seat", cur.meSeat).put("up", true))
            return
        }
        if (t - lastTouch < 45) return
        lastTouch = t
        live?.send("lu_touch", JSONObject().put("seat", cur.meSeat).put("x", p.x.toDouble()).put("y", p.y.toDouble()))
    }

    fun emote(e: String) {
        val cur = anim.shown ?: return
        if (cur.meSeat < 0) return
        emotes.add(FloatEmote(e, 3))
        LudoSfx.play(context, LudoSound.Pop)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        live?.send("lu_emote", JSONObject().put("seat", cur.meSeat).put("e", e))
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(24.dp)
    val glow = rememberInfiniteTransition(label = "ludo-glow")
    val pulse by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(if (reduced) 1_600 else 900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val drift by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "drift")

    Box(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .drawWithCache {
                onDrawBehind {
                    // A wash of the colour on turn, slowly wandering round the screen.
                    val a = drift * 2 * PI.toFloat()
                    val c = Offset(size.width * (0.5f + 0.32f * kotlin.math.cos(a)), size.height * (0.42f + 0.18f * sin(a)))
                    drawCircle(Brush.radialGradient(listOf(turnColor.copy(alpha = 0.20f), Color.Transparent), c, size.maxDimension * 0.65f), size.maxDimension * 0.65f, c)
                    drawCircle(Brush.radialGradient(listOf(turnColor.copy(alpha = 0.10f), Color.Transparent), Offset(size.width * 0.5f, size.height), size.width), size.width, Offset(size.width * 0.5f, size.height))
                }
            },
    ) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(top = topPad)) {
            // ---- Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Ludo", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp))
                    Text(
                        subtitle(g, title, liveUp),
                        style = LockInText.Small.copy(color = LockInColors.Mist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIcon(if (muted) "🔇" else "🔊", if (muted) "Sound off" else "Sound on") {
                    muted = !muted
                    LudoSfx.muted = muted
                    if (muted) LudoSfx.stopShake()
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon("⏻", "Leave the game") { confirmLeave = !confirmLeave }
            }

            AnimatedVisibility(
                visible = confirmLeave,
                enter = slideInVertically(spring(dampingRatio = 0.8f)) { -it },
                exit = slideOutVertically(spring(dampingRatio = 1f)) { -it },
            ) {
                LeaveBar(g, onCancel = { confirmLeave = false }) { end ->
                    confirmLeave = false
                    co.launch {
                        repo.leave(scope, end).onSuccess { share(it) }
                        live?.send("lu_here")
                        onBack()
                    }
                }
            }

            val wide = com.godbless.lockin.ui.isWide()
            if (g == null || g.none) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        error ?: if (loading) "Setting up the board…" else "No game right now.",
                        style = if (error != null) LockInText.Error else LockInText.Sub.copy(color = LockInColors.Mist),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1.25f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        BoardArea(g, anim, turns, pulse, fingers, emotes, reduced, onTap = ::tapBoard, onTouch = ::touch, onSit = { s -> act { repo.sit(scope, s) } })
                    }
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (corner in listOf(0, 1, 3, 2)) {
                            val s = LudoGeo.seatAt(corner, turns)
                            Plate(g, s, anim, now, pulse, false, Modifier.fillMaxWidth(), onRoll = ::roll, onSit = { act { repo.sit(scope, s) } })
                        }
                        Spacer(Modifier.weight(1f))
                        Footer(g, anim, error, onStart = { act { repo.start(scope) } }, onEmote = ::emote)
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Plate(g, LudoGeo.seatAt(0, turns), anim, now, pulse, false, Modifier.weight(1f), onRoll = ::roll, onSit = { act { repo.sit(scope, LudoGeo.seatAt(0, turns)) } })
                        Plate(g, LudoGeo.seatAt(1, turns), anim, now, pulse, true, Modifier.weight(1f), onRoll = ::roll, onSit = { act { repo.sit(scope, LudoGeo.seatAt(1, turns)) } })
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                        BoardArea(g, anim, turns, pulse, fingers, emotes, reduced, onTap = ::tapBoard, onTouch = ::touch, onSit = { s -> act { repo.sit(scope, s) } })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Plate(g, LudoGeo.seatAt(3, turns), anim, now, pulse, false, Modifier.weight(1f), onRoll = ::roll, onSit = { act { repo.sit(scope, LudoGeo.seatAt(3, turns)) } })
                        Plate(g, LudoGeo.seatAt(2, turns), anim, now, pulse, true, Modifier.weight(1f), onRoll = ::roll, onSit = { act { repo.sit(scope, LudoGeo.seatAt(2, turns)) } })
                    }
                    Spacer(Modifier.height(8.dp))
                    Footer(g, anim, error, onStart = { act { repo.start(scope) } }, onEmote = ::emote)
                }
            }
        }

        // ---- The end: a podium rising from the bottom.
        val over = g != null && g.over && !anim.busy && anim.moverT < 0
        AnimatedVisibility(
            visible = over,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 220f)) { it },
            exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 400f)) { it },
        ) {
            if (g != null) Podium(g, onAgain = { act { repo.again(scope) } }, onClose = onBack)
        }
    }
}

private fun subtitle(g: LudoGame?, title: String, liveUp: Boolean): String {
    if (g == null || g.none) return title
    val dot = if (liveUp) "Live · " else ""
    return when {
        g.lobby -> "$dot${g.seated.size} of 4 seats taken"
        g.over -> "${dot}Game over"
        else -> dot + g.seated.filter { !it.left }.joinToString(" vs ") { if (it.seat == g.meSeat) "You" else it.first }
    }
}

@Composable
private fun RoundIcon(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 18.sp) }
}

@Composable
private fun LeaveBar(g: LudoGame?, onCancel: () -> Unit, onLeave: (end: Boolean) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .padding(16.dp),
    ) {
        val crew = g?.scope == "crew"
        Text(
            when {
                !crew -> "Leaving ends this game for both of you."
                g?.playing == true -> "If you leave, your tokens come off the board and the others play on."
                else -> "Get up from the table?"
            },
            style = LockInText.Sub.copy(color = LockInColors.Snow),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Stay", onClick = onCancel, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton(if (crew) "Leave" else "End game", onClick = { onLeave(false) }, modifier = Modifier.weight(1f))
        }
        if (crew && g?.iHost == true) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Close the table for everyone",
                style = LockInText.Label.copy(color = LockInColors.Flare),
                modifier = Modifier.clickable(role = Role.Button) { onLeave(true) }.padding(vertical = 6.dp),
            )
        }
    }
}

/** The board with its tokens, effects, fingers, floating faces and, at a crew table that is filling up, "Sit here" chips. */
@Composable
private fun BoardArea(
    g: LudoGame,
    anim: LudoAnim,
    turns: Int,
    pulse: Float,
    fingers: Map<Int, Pair<P, Long>>,
    emotes: MutableList<FloatEmote>,
    reduced: Boolean,
    onTap: (P) -> Unit,
    onTouch: (P?) -> Unit,
    onSit: (Int) -> Unit,
) {
    val dark = LockInColors.isDark
    val present = BooleanArray(4) { g.seat(it)?.taken == true && g.seat(it)?.left != true }
    val presentKey = present.joinToString()
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).aspectRatio(1f)) {
        val density = LocalDensity.current
        val sidePx = with(density) { maxWidth.toPx() }
        val cell = sidePx / LudoGeo.N
        val shakeX = anim.shake.value
        anim.cellPx = cell
        anim.turns = turns
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = shakeX; translationY = shakeX * 0.4f }
                .then(remember(turns, presentKey, dark) { Modifier.drawWithCache { val c = size.width / LudoGeo.N; onDrawBehind { drawLudoBoard(c, turns, dark, present) } } }),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(turns, cell) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var pos = down.position
                            var moved = 0f
                            fun canon(o: Offset) = LudoGeo.unview(P(o.x / cell, o.y / cell), turns)
                            onTouch(canon(pos))
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                moved += (ch.position - pos).getDistance()
                                pos = ch.position
                                onTouch(canon(pos))
                            }
                            onTouch(null)
                            if (moved < cell * 0.8f) onTap(canon(pos))
                        }
                    },
            ) {
                anim.frame // redraw on every effects frame
                val c = size.width / LudoGeo.N
                // The yard of the player on turn breathes.
                if (g.playing) {
                    val tl = px(LudoGeo.rot(P(0.25f, 0.25f), g.turn), turns, c)
                    val br = px(LudoGeo.rot(P(5.75f, 5.75f), g.turn), turns, c)
                    val l = minOf(tl.x, br.x); val t = minOf(tl.y, br.y)
                    val w = abs(br.x - tl.x); val h = abs(br.y - tl.y)
                    val grow = c * 0.12f * pulse
                    drawRoundRect(
                        LudoPalette.l(g.turn).copy(alpha = 0.55f + 0.35f * pulse),
                        Offset(l - grow, t - grow), Size(w + grow * 2, h + grow * 2),
                        androidx.compose.ui.geometry.CornerRadius(c * 0.8f),
                        style = Stroke(c * 0.12f),
                    )
                }
                // Ghosts where my tokens could land, and rings round the ones that can move.
                val movable = if (g.myTurn && g.phase == "move" && !anim.busy && anim.moverT < 0) LudoGeo.movable(anim.tokens, g.meSeat, g.dice) else emptyList()
                for (k in movable) {
                    val d = LudoGeo.dest(anim.tokens[g.meSeat * 4 + k], g.dice) ?: continue
                    LudoGeo.point(g.meSeat, d, k)?.let { drawGhost(g.meSeat, px(it, turns, c), c, pulse) }
                }
                val skip = HashSet<Int>().apply { if (anim.moverT >= 0) add(anim.moverT); addAll(anim.flying.keys) }
                val placed = layout(anim.tokens, skip).sortedBy { px(it.second, turns, c).y }
                val mySet = movable.map { g.meSeat * 4 + it }.toSet()
                for ((t, p, s) in placed) {
                    val o = px(p, turns, c)
                    if (t in mySet) drawMovableRing(t / 4, o, c, pulse)
                    val bob = if (t in mySet && !reduced) c * 0.12f * pulse else 0f
                    drawPawn(t / 4, o, c, lift = bob, scale = s, dim = g.seat(t / 4)?.left == true)
                }
                for ((t, f) in anim.flying) drawPawn(t / 4, px(f.first, turns, c), c, lift = f.second * c, spin = f.third)
                if (anim.moverT >= 0) drawPawn(anim.moverT / 4, px(anim.moverAt, turns, c), c, lift = anim.moverLift * c, squash = anim.moverSquash)
                // Other people's fingers, as a glow in their colour.
                for ((seat, f) in fingers) {
                    val o = px(f.first, turns, c)
                    drawCircle(Brush.radialGradient(listOf(LudoPalette.c(seat).copy(alpha = 0.55f), Color.Transparent), o, c * 1.1f), c * 1.1f, o)
                    drawCircle(Color.White.copy(alpha = 0.9f), c * 0.16f, o)
                }
                drawFx(anim.fx)
            }

            // Seats to take at a crew table.
            if (g.lobby) {
                for (s in 0..3) {
                    val seat = g.seat(s) ?: continue
                    val centre = px(LudoGeo.yardCentre(s), turns, cell)
                    val label = if (seat.taken) (if (s == g.meSeat) "You" else seat.first) else "Sit here"
                    val chip = Modifier
                        .offset { IntOffset((centre.x - 54.dp.toPx()).roundToInt(), (centre.y - 18.dp.toPx()).roundToInt()) }
                        .width(108.dp)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(if (seat.taken) Color.Black.copy(alpha = 0.55f) else Color.White)
                        .then(if (!seat.taken) Modifier.clickable(role = Role.Button) { onSit(s) } else Modifier)
                    Box(chip, contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = LockInText.Label.copy(color = if (seat.taken) Color.White else LudoPalette.d(s), fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // The banner: what just happened, big, in the middle.
            val b = anim.banner
            AnimatedVisibility(
                visible = b != null,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f), initialScale = 0.4f),
                exit = scaleOut(spring(dampingRatio = 1f, stiffness = 700f), targetScale = 0.6f),
            ) {
                val shown = b ?: Banner("", 0)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.horizontalGradient(listOf(LudoPalette.d(shown.seat), LudoPalette.c(shown.seat))))
                        .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(shown.text, style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 26.sp, color = Color.White), textAlign = TextAlign.Center)
                }
            }
        }

        // Faces floating up from where their sender sits.
        for (e in emotes.toList()) {
            androidx.compose.runtime.key(e.id) {
                FloatingEmote(e, maxWidth.value, maxHeight.value) { emotes.remove(e) }
            }
        }
    }
}

@Composable
private fun FloatingEmote(e: FloatEmote, w: Float, h: Float, onDone: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(1_900, easing = { 1f - (1f - it) * (1f - it) }))
        onDone()
    }
    val x = if (e.corner == 0 || e.corner == 3) w * 0.2f else w * 0.8f
    val y0 = if (e.corner >= 2) h * 0.85f else h * 0.2f
    val t = rise.value
    val wobble = sin(t * 9f) * 14f
    Text(
        e.emoji,
        fontSize = 40.sp,
        modifier = Modifier
            .offset((x + wobble - 24).dp, (y0 - t * h * 0.45f - 24).dp)
            .scale(if (t < 0.15f) 0.4f + t * 6f else 1.3f - t * 1.3f + 0.1f),
    )
}

/** One player's corner: their colour, name, the turn timer round their initial, and the die when it is theirs. */
@Composable
private fun Plate(
    g: LudoGame,
    seat: Int,
    anim: LudoAnim,
    now: Long,
    pulse: Float,
    mirror: Boolean,
    modifier: Modifier,
    onRoll: () -> Unit,
    onSit: () -> Unit,
) {
    val info: LudoSeat? = g.seat(seat)
    val color = LudoPalette.c(seat)
    val onTurn = g.playing && g.turn == seat && info?.taken == true
    val mine = seat == g.meSeat
    val shape = RoundedCornerShape(22.dp)
    val lit by animateFloatAsState(if (onTurn) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "plate-lit")
    val canRoll = mine && onTurn && g.phase == "roll" && !anim.busy && !anim.tumbling
    val sitHere = g.lobby && info?.taken != true
    Row(
        modifier
            .height(76.dp)
            .scale(1f + 0.03f * lit)
            .clip(shape)
            .background(Brush.horizontalGradient(if (mirror) listOf(LockInColors.Slate, color.copy(alpha = 0.18f + 0.22f * lit)) else listOf(color.copy(alpha = 0.18f + 0.22f * lit), LockInColors.Slate)))
            .border((1 + lit * 1.5f).dp, if (onTurn) color else LockInColors.Graphite, shape)
            .then(if (sitHere) Modifier.clickable(role = Role.Button, onClick = onSit) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (mirror) Arrangement.End else Arrangement.Start,
    ) {
        val avatar: @Composable () -> Unit = {
            val frac = if (onTurn) (g.msLeftNow(now) / 30_000f).coerceIn(0f, 1f) else 0f
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(LudoPalette.l(seat), color, LudoPalette.d(seat))), size.minDimension * 0.4f)
                    if (onTurn) {
                        val warn = frac < 0.34f
                        drawArc(
                            if (warn) Color(0xFFFF3B3B).copy(alpha = 0.6f + 0.4f * pulse) else Color.White,
                            -90f, 360f * frac, false,
                            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                            size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    if (info?.taken == true) (if (mine) "You" else (info?.first ?: "").take(1).uppercase()) else "+",
                    style = LockInText.Label.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (mine) 12.sp else 18.sp),
                )
                if (info?.here == true && !mine) {
                    Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(LockInColors.Ink).padding(2.dp).clip(CircleShape).background(Color(0xFF39D98A)))
                }
            }
        }
        val words: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 8.dp).widthIn(max = 110.dp), horizontalAlignment = if (mirror) Alignment.End else Alignment.Start) {
                Text(
                    when {
                        info?.taken != true -> "Empty seat"
                        mine -> "You"
                        else -> info?.first ?: ""
                    },
                    style = LockInText.Label.copy(color = if (info?.taken == true) LockInColors.Snow else LockInColors.Mist, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val home = (0..3).count { anim.tokens[seat * 4 + it] == LudoGeo.HOME }
                Text(
                    when {
                        info?.taken != true && g.lobby -> "Tap to sit"
                        info?.taken != true -> LudoPalette.names[seat]
                        info?.left == true -> "Left the game"
                        info?.place == 1 -> "Winner 👑"
                        (info?.place ?: 0) > 1 -> "${ordinal(info?.place ?: 0)} place"
                        onTurn && mine && g.phase == "roll" -> "Tap the die"
                        onTurn && mine -> "Pick a token"
                        onTurn -> if (g.phase == "roll") "Rolling…" else "Choosing…"
                        g.lobby -> if (g.host == info?.id) "Host" else "Ready"
                        else -> "$home of 4 home" + if ((info?.wins ?: 0) > 0) " · ${info?.wins}🏆" else ""
                    },
                    style = LockInText.Small.copy(color = if (onTurn) LockInColors.Snow else LockInColors.Mist, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
        val die: @Composable () -> Unit = {
            val has = anim.dieSeat == seat
            val ready by animateFloatAsState(if (canRoll) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "die-ready")
            Box(
                Modifier
                    .size(58.dp)
                    .scale(1f + 0.08f * ready * pulse)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (has || canRoll) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
                    .then(if (canRoll) Modifier.clickable(role = Role.Button, onClick = onRoll).semantics { contentDescription = "Roll the die" } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (has || canRoll) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawDie(
                            Offset(size.width / 2, size.height / 2 + 3.dp.toPx()),
                            size.minDimension * 0.46f,
                            anim.rx.value, anim.ry.value, anim.rz.value,
                            color,
                            lift = anim.lift.value * density,
                            glow = if (anim.dieValue == 6 && !anim.tumbling && has) 1f else ready * pulse * 0.7f,
                        )
                    }
                }
            }
        }
        if (mirror) {
            if (g.playing || g.over) die()
            Spacer(Modifier.weight(1f))
            words()
            avatar()
        } else {
            avatar()
            words()
            Spacer(Modifier.weight(1f))
            if (g.playing || g.over) die()
        }
    }
}

private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

/** Under the board: what to do now, the host's Start button at a crew table, the error if any, and reactions. */
@Composable
private fun Footer(g: LudoGame, anim: LudoAnim, error: String?, onStart: () -> Unit, onEmote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val hint = when {
            error != null -> null
            g.lobby && g.meSeat < 0 -> "Pick a seat to join the table."
            g.lobby && g.iHost -> null
            g.lobby -> "Waiting for ${g.seats.firstOrNull { it.id == g.host }?.first ?: "the host"} to start."
            g.over -> null
            g.myTurn && g.phase == "roll" -> if (g.sixes > 0) "Another roll! Tap your die." else "Your turn. Tap your die to roll."
            g.myTurn -> "Tap a glowing token to move it ${g.dice}."
            g.playing -> "${g.seat(g.turn)?.first ?: "They"}'s turn."
            else -> null
        }
        if (error != null) Text(error, style = LockInText.Error, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (hint != null) Text(hint, style = LockInText.Sub.copy(color = LockInColors.Silver), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (g.lobby && g.iHost) {
            val n = g.seated.size
            LockInButton(
                if (n >= 2) "Start with $n players" else "Waiting for players…",
                onClick = onStart,
                enabled = n >= 2 && !anim.busy,
                loading = anim.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (g.meSeat >= 0 && !g.over) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EMOTES.forEach { e ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(LockInColors.Slate)
                            .border(1.dp, LockInColors.Graphite, CircleShape)
                            .clickable(role = Role.Button) { onEmote(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 20.sp) }
                }
            }
        }
    }
}

@Composable
private fun Podium(g: LudoGame, onAgain: () -> Unit, onClose: () -> Unit) {
    val ranked = g.seats.filter { it.taken && it.place > 0 }.sortedBy { it.place }
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(LockInColors.Slate, LockInColors.Ink)))
            .border(1.dp, LockInColors.Graphite, shape)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iWon = ranked.firstOrNull()?.seat == g.meSeat
        Text(
            if (iWon) "You win! 👑" else "${ranked.firstOrNull()?.first ?: "Someone"} wins",
            style = LockInText.Headline.copy(fontSize = 30.sp, lineHeight = 36.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        // Steps: second, first, third, with the winner tallest.
        val order = listOfNotNull(ranked.getOrNull(1), ranked.getOrNull(0), ranked.getOrNull(2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
            order.forEach { s ->
                val tall = when (s.place) { 1 -> 120.dp; 2 -> 86.dp; else -> 64.dp }
                val grow = remember { Animatable(0f) }
                LaunchedEffect(s.seat) {
                    delay(140L * (4 - s.place))
                    grow.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 180f))
                }
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.place == 1) Text("👑", fontSize = 30.sp, modifier = Modifier.scale(grow.value))
                    Box(
                        Modifier.size(50.dp).scale(grow.value).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(LudoPalette.l(s.seat), LudoPalette.c(s.seat), LudoPalette.d(s.seat)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (s.seat == g.meSeat) "You" else s.first.take(1).uppercase(), style = LockInText.Label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    }
                    Text(if (s.seat == g.meSeat) "You" else s.first, style = LockInText.Small.copy(color = LockInColors.Snow), maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(tall * grow.value.coerceIn(0f, 1.2f))
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .background(Brush.verticalGradient(listOf(LudoPalette.c(s.seat), LudoPalette.d(s.seat)))),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(ordinal(s.place), style = LockInText.Title.copy(fontSize = 22.sp, color = Color.White), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton("Close", onClick = onClose, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton(if (g.scope == "crew") "New table" else "Play again", onClick = onAgain, modifier = Modifier.weight(1f))
        }
    }
}

// ---- The director -------------------------------------------------------------------------------------------------

private fun LudoAnim.say(co: kotlinx.coroutines.CoroutineScope, text: String, seat: Int, ms: Long = 1_300) {
    val b = Banner(text, seat)
    banner = b
    co.launch {
        delay(ms)
        if (banner === b) banner = null
    }
}

/** Plays [g]'s last action on the board (if it is new), then shows [g]. */
private suspend fun play(
    g: LudoGame,
    anim: LudoAnim,
    context: android.content.Context,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    reduced: Boolean,
    co: kotlinx.coroutines.CoroutineScope,
) {
    val before = anim.shown
    if (g.none) {
        anim.shown = g
        return
    }
    val fresh = before == null || before.none || g.id != before.id
    if (fresh || g.seq <= anim.seq) {
        // Nothing to animate (first look, or old news): just show it.
        if (fresh || g.seq == anim.seq) {
            anim.tokens = g.tokens
            anim.shown = g
            anim.seq = g.seq
            if (anim.dieSeat < 0 || fresh) { anim.dieSeat = g.turn; if (g.dice in 1..6) { anim.dieValue = g.dice; val (x, y) = dieFace(g.dice); anim.rx.snapTo(x); anim.ry.snapTo(y) } }
            if (fresh && g.myTurn) LudoSfx.play(context, LudoSound.Turn)
        }
        return
    }
    val l = g.last
    val hopMs = if (reduced) 70 else 150
    val wasMyTurn = before?.myTurn == true
    val me = g.meSeat
    if (l != null && l.seq == g.seq) {
        when (l.kind) {
            "roll" -> {
                anim.settle(co, l.seat, l.roll, reduced)
                LudoSfx.stopShake()
                LudoSfx.play(context, LudoSound.Land)
                if (l.roll == 6 && !l.three) LudoSfx.play(context, LudoSound.Six)
                when {
                    l.three -> { anim.say(co, "Three 6s! Turn lost", l.seat); delay(700) }
                    l.none -> { anim.say(co, if (l.seat == me) "No moves this time" else "${g.seat(l.seat)?.first ?: "They"} can't move", l.seat, 1_000); delay(600) }
                }
            }
            "move" -> {
                val t = l.seat * 4 + l.token
                if (l.rolled) {
                    anim.settle(co, l.seat, l.roll, reduced)
                    LudoSfx.stopShake()
                    LudoSfx.play(context, LudoSound.Land)
                    if (l.roll == 6) LudoSfx.play(context, LudoSound.Six)
                    delay(if (reduced) 60 else 260)
                }
                // Start from the board as it was just before this move.
                val pre = g.tokens.toMutableList()
                pre[t] = l.from
                l.caps.forEach { pre[it.t] = it.from }
                anim.tokens = pre
                val path = LudoGeo.path(l.seat, l.token, l.from, l.to)
                var at = LudoGeo.point(l.seat, l.from, l.token) ?: path.firstOrNull() ?: P(7.5f, 7.5f)
                anim.moverT = t
                anim.moverAt = at
                if (l.from == -1) LudoSfx.play(context, LudoSound.Out)
                path.forEachIndexed { i, p ->
                    val big = l.from == -1
                    anim.hop(t, at, p, if (big) 1.6f else 0.7f, if (big) hopMs * 2 else hopMs)
                    at = p
                    LudoSfx.play(context, LudoSound.Hop, rate = 0.9f + i * 0.05f)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (!reduced) co.launch {
                        anim.moverSquash = 0.22f
                        Animatable(0.22f).animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 900f)) { anim.moverSquash = value }
                    }
                }
                val landed = anim.tokens.toMutableList().also { it[t] = l.to }
                anim.tokens = landed
                anim.moverT = -1
                val abs = LudoGeo.abs(l.seat, l.to)
                if (l.caps.isNotEmpty()) {
                    LudoSfx.play(context, LudoSound.Capture)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!reduced) co.launch {
                        anim.shake.snapTo(14f)
                        anim.shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 700f))
                    }
                    val victims = l.caps.map { it.t }
                    anim.say(co,
                        when {
                            l.seat == me -> "Captured ${g.seat(victims.first() / 4)?.first ?: "them"}! 💥"
                            victims.any { it / 4 == me } -> "${g.seat(l.seat)?.first ?: "They"} got you! 😤"
                            else -> "Capture! 💥"
                        },
                        l.seat,
                    )
                    coroutineScope {
                        for (c in l.caps) launch {
                            val from = LudoGeo.point(c.t / 4, c.from, c.t % 4) ?: return@launch
                            val to = LudoGeo.point(c.t / 4, -1, c.t % 4) ?: return@launch
                            if (!reduced) {
                                val o = anim.at(from)
                                val v = c.t / 4
                                anim.fx.burst(o, listOf(LudoPalette.c(v), Color.White, LudoPalette.l(v)), 28, anim.cellPx * 10f, anim.cellPx * 0.13f)
                                anim.fx.ring(o, LudoPalette.c(v), anim.cellPx * 1.8f)
                            }
                            val a = Animatable(0f)
                            anim.flying[c.t] = Triple(from, 0f, 0f)
                            a.animateTo(1f, tween(if (reduced) 250 else 820)) {
                                anim.flying[c.t] = Triple(lerpP(from, to, value), sin(PI.toFloat() * value) * 3.2f, value * 720f)
                            }
                            anim.flying.remove(c.t)
                            anim.tokens = anim.tokens.toMutableList().also { it[c.t] = -1 }
                        }
                    }
                } else if (l.to == LudoGeo.HOME) {
                    LudoSfx.play(context, LudoSound.Home)
                    if (!reduced) {
                        val o = anim.at(P(7.5f, 7.5f))
                        anim.fx.burst(o, listOf(LudoPalette.c(l.seat), LudoPalette.l(l.seat), Color.White, Color(0xFFFFD54A)), 44, anim.cellPx * 12f, anim.cellPx * 0.2f, confetti = true)
                        anim.fx.ring(o, LudoPalette.c(l.seat), anim.cellPx * 2.4f)
                    }
                    anim.say(co, if (l.done) (if (l.seat == me) "All home! 🎉" else "${g.seat(l.seat)?.first ?: "They"} finished!") else "Home! ✨", l.seat)
                } else if (LudoGeo.safe(abs)) {
                    LudoSfx.play(context, LudoSound.Safe)
                    if (!reduced) LudoGeo.point(l.seat, l.to, l.token)?.let { anim.fx.ring(anim.at(it), Color.White, anim.cellPx * 0.9f) }
                }
                if (l.bonus && !l.over && l.seat == me && l.caps.isEmpty() && l.to != LudoGeo.HOME) anim.say(co, "Roll again!", l.seat, 900)
                delay(if (reduced) 80 else 220)
            }
            "start" -> {
                LudoSfx.play(context, LudoSound.Start)
                anim.dieSeat = l.first
                anim.say(co, if (l.first == me) "You go first" else "${g.seat(l.first)?.first ?: "They"} goes first", l.first, 1_500)
                delay(500)
            }
            "left" -> anim.say(co, "Someone left the game", g.turn, 1_200)
            "again" -> anim.say(co, "New table: take your seats", g.turn, 1_200)
        }
    }
    anim.tokens = g.tokens
    anim.seq = g.seq
    anim.shown = g
    if (g.playing && (anim.dieSeat != g.turn)) anim.dieSeat = g.turn
    if (g.over && before?.over != true) {
        val winner = g.seats.firstOrNull { it.place == 1 }
        if (!reduced && anim.cellPx > 0f) anim.fx.rain(anim.cellPx * LudoGeo.N, LudoPalette.base + Color.White, 140, anim.cellPx * 0.32f)
        if (winner?.seat == me) LudoSfx.play(context, LudoSound.Win)
        else if (me >= 0) LudoSfx.play(context, LudoSound.Lose)
    }
    if (g.myTurn && (!wasMyTurn || (l?.bonus == true) || (l?.kind == "roll" && l.roll == 6))) {
        if (!wasMyTurn) {
            LudoSfx.play(context, LudoSound.Turn)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            anim.say(co, "Your turn", me, 900)
        }
    }
}
