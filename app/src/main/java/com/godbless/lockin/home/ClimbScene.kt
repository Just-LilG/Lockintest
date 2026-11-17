package com.godbless.lockin.home

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.godbless.lockin.intro.ClimberSpec
import com.godbless.lockin.intro.FxPrim
import com.godbless.lockin.intro.Ground
import com.godbless.lockin.intro.Life
import com.godbless.lockin.intro.Pose
import com.godbless.lockin.intro.ROPE_TIE_X
import com.godbless.lockin.intro.ROPE_TIE_Y
import com.godbless.lockin.intro.RopeSim
import com.godbless.lockin.intro.SceneFx
import com.godbless.lockin.intro.SkyClip
import com.godbless.lockin.intro.Weather
import com.godbless.lockin.intro.drawFx
import com.godbless.lockin.intro.drawRopePoints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.sp
import com.godbless.lockin.R
import com.godbless.lockin.intro.BASELINE
import com.godbless.lockin.intro.ClimberShapes
import com.godbless.lockin.intro.ContourField
import com.godbless.lockin.intro.FlagMesh
import com.godbless.lockin.intro.Rig
import com.godbless.lockin.intro.SCENE_W
import com.godbless.lockin.intro.SCENE_X
import com.godbless.lockin.intro.SCENE_Y
import com.godbless.lockin.intro.drawClimber
import com.godbless.lockin.intro.drawMountain
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.UiGlide

// The same two climbers as the welcome page, but here how far each has climbed is today's focus.
private val YouSpec = HomeYouSpec
private val PartnerSpec = HomePartnerSpec

/** How far up the mountain (0 to 1) a day's focus puts someone. */
internal fun reach(seconds: Int, goalSeconds: Int): Float = 0.04f + 0.9f * (seconds / goalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

/**
 * The mountain with the two roped climbers and the waving flag. It is the progress bar on Home and the
 * backdrop of the lock screens. Climbers walk up to where [doneSeconds] and [partnerSeconds] put them.
 *
 * Around them the mountain lives (see SceneFx): the sky follows the real time of day (sun, moon, stars),
 * the weather follows how today is going, a long streak brings the northern lights, camps light up as
 * they're passed, footprints trail behind, and cairns at the foot count the streak. The climbers have
 * moods: they cheer and the summit bursts when the goal is reached, stand and wait at the top, wave down
 * at whoever is still climbing, tire after a long day, stumble when a lock is broken ([slips]), and at
 * night, off the clock, they pitch a tent and sleep. The ropes are real ropes that hang, swing and rest
 * on the snow.
 *
 * Tap your climber to wave, tap your partner's to nudge them ([onPartnerTap]), hold either for today's time.
 *
 * [taut] (0 slack, 1 tight, read while drawing) pulls the ropes. [ground] is the colour the scene sits on,
 * so its foot can melt into it. [frameMillis] 0 draws every frame; a larger number saves battery on long screens.
 */
@Composable
internal fun ClimbScene(
    doneSeconds: Int,
    partnerSeconds: Int,
    taut: () -> Float,
    ground: Color,
    modifier: Modifier = Modifier,
    frameMillis: Long = 0L,
    /** 0 to 1: how far the name tags have popped in. They arrive after the welcome page hands the scene over. */
    tagScale: () -> Float = { 1f },
    /** 0 to 1 while a nudge plays (read while drawing). 0 when there is none. */
    nudge: () -> Float = { 0f },
    /** Set to make your partner's climber tappable (Home uses it to nudge them). */
    onPartnerTap: (() -> Unit)? = null,
    /** You're in a session right now (climbing for real): no sleeping, and a long one tires you. */
    youActive: Boolean = false,
    /** Your partner is in a session right now. */
    partnerActive: Boolean = false,
    /** Goes up each time you leave the lock screen during a lock: your climber stumbles. */
    slips: Int = 0,
    /** The same for your partner, during a lock you set on them. */
    partnerSlips: Int = 0,
    /** When above 0, the summit is this many seconds of [doneSeconds] away instead of today's goal (a lock climbs to its end). */
    goalOverrideSeconds: Int = 0,
    /** Replaces "You · 12m today" when you tap yourself. */
    youLabel: String? = null,
    /** Home is covered by a full page: stop the animation loops until it is uncovered. */
    paused: Boolean = false,
    /** Home's own scene: the walk-up and the grow-in play once per run of the app, then it comes back settled. */
    settleOnReturn: Boolean = false,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Dark theme: white snow faces on the dark page. Light theme: the same peak with its rock filled in (baked by
    // tools/scene-preview/make_light_mountain.py), since the dark one's see-through gaps would show the pale page.
    val mountain: ImageBitmap = ImageBitmap.imageResource(if (LockInColors.isDark) R.drawable.mountain else R.drawable.mountain_light)
    val contours = remember { ContourField() }
    val flag = remember { FlagMesh() }
    val measurer = rememberTextMeasurer()
    val tagStyle = remember(LockInColors.palette) {
        TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = LockInColors.Ink)
    }
    val youTag = remember(measurer, tagStyle) { measurer.measure(AnnotatedString("You"), tagStyle) }
    val partnerName = LocalPartnerName.current
    // First name only, cut short, so a long name never pushes the tag out of the card.
    val tagName = remember(partnerName) {
        // A short full name ("Lil G") fits as it is; only longer ones drop to the first name.
        val full = partnerName.trim()
        val first = full.substringBefore(' ').ifEmpty { partnerName }
        if (full.length <= 10) full else if (first.length > 12) first.take(11) + "…" else first
    }
    val partnerTag = remember(measurer, tagName, tagStyle) { measurer.measure(AnnotatedString(tagName), tagStyle) }
    val nudgedTag = remember(measurer, tagStyle) { measurer.measure(AnnotatedString("Nudged!"), tagStyle) }
    // Where each head was last drawn, in pixels, so taps can be told apart: [you x, you y, them x, them y].
    val heads = remember { floatArrayOf(-1f, -1f, -1f, -1f) }

    val goalSeconds = if (goalOverrideSeconds > 0) goalOverrideSeconds else LocalGoalMinutes.current * 60
    val partnerGoalSeconds = LocalPartnerGoalMinutes.current * 60
    val streak = LocalStats.current.streak

    // Reduced motion (battery saver, animations off in the phone's settings, or chosen in Settings): the scene holds still.
    val motionOff = com.godbless.lockin.ui.MotionState.reduced
    val frameGap = frameMillis

    // Climbers walk up to where they are when the scene appears, then creep on as the seconds add up.
    // Already seen in this run of the app (you came back from another tab): they start where they are, no walk-up.
    val seenBefore = remember { settleOnReturn && HomeIntro.sceneSeen && !SceneHandoff.flying }
    val you = remember { Animatable(if (seenBefore) reach(doneSeconds, goalSeconds) else 0f) }
    val partner = remember { Animatable(if (seenBefore) reach(partnerSeconds, partnerGoalSeconds) else 0f) }
    // While the welcome page is still handing the scene over they simply stand where it puts them.
    LaunchedEffect(doneSeconds, goalSeconds) {
        val to = reach(doneSeconds, goalSeconds)
        if (SceneHandoff.sceneHidden) you.snapTo(to) else you.animateTo(to, tween(2400, easing = UiGlide))
    }
    LaunchedEffect(partnerSeconds, partnerGoalSeconds) {
        val to = reach(partnerSeconds, partnerGoalSeconds)
        if (SceneHandoff.sceneHidden) partner.snapTo(to) else partner.animateTo(to, tween(2700, easing = UiGlide))
    }

    // The flag climbs out of the summit when the scene appears, then keeps waving. After a handover from
    // the welcome page it carries on from the welcome page's clock, so the wave never skips.
    val clock = remember { mutableFloatStateOf(if (SceneHandoff.flying) SceneHandoff.clock else if (seenBefore) maxOf(HomeIntro.sceneClock, 3.2f) else 0.2f) }
    LaunchedEffect(frameGap, motionOff, paused) {
        if (paused) return@LaunchedEffect
        if (motionOff) {
            // Held still, but not at the very start: the flag only climbs out of the summit as the clock runs, so
            // park the clock where it is fully up and caught in a gentle wave.
            if (clock.floatValue < 3f) clock.floatValue = 3.2f
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        var base = clock.floatValue
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val elapsed = (now - start) / 1_000_000_000f
                if (SceneHandoff.flying) base = SceneHandoff.clock - elapsed
                if (frameGap <= 0L || now - last >= frameGap * 1_000_000L) {
                    clock.floatValue = base + elapsed
                    if (settleOnReturn) HomeIntro.sceneClock = clock.floatValue
                    last = now
                }
            }
        }
    }

    // The real time of day, checked every half minute.
    var hour by remember { mutableFloatStateOf(nowHour()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); hour = nowHour() } }
    val night = hour >= 22.5f || hour < 5.5f

    // The sky, weather, trail and cairns grow in once the scene is on screen (nothing fades in).
    val bloom = remember { Animatable(if (motionOff || seenBefore) 1f else 0f) }
    LaunchedEffect(SceneHandoff.sceneHidden) {
        if (!SceneHandoff.sceneHidden) {
            // From here on, coming back to Home shows the scene settled instead of growing in again.
            if (settleOnReturn) HomeIntro.sceneSeen = true
            if (bloom.value < 1f) bloom.animateTo(1f, tween(1600, delayMillis = 350, easing = UiGlide))
        }
    }

    // ----- Moods, each a spring so the climbers ease from one into the next -----
    val youReached = goalSeconds > 0 && doneSeconds >= goalSeconds
    val themReached = partnerGoalSeconds > 0 && partnerSeconds >= partnerGoalSeconds
    val youLife = remember { LifeSprings() }
    val themLife = remember { LifeSprings() }
    // Walking is only for when the climber is really covering ground. Between the once-a-second creeps of a
    // running session they stand and rest instead of freezing mid-stride; the gait picks up again as soon as
    // they have somewhere to go (the walk-up when the scene opens, a jump in progress).
    LaunchedEffect(motionOff, paused) {
        if (paused) return@LaunchedEffect
        var lastYou = you.value
        var lastThem = partner.value
        var lastNs = 0L
        while (true) {
            val ns = androidx.compose.runtime.withFrameNanos { it }
            if (lastNs != 0L) {
                val dt = ((ns - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                val yv = you.value
                val tv = partner.value
                // Units per second along the trail; a single-frame leap (a snap) isn't a walk.
                val sy = kotlin.math.abs(yv - lastYou) * YouSpec.dTotal / dt
                val st = kotlin.math.abs(tv - lastThem) * PartnerSpec.dTotal / dt
                youLife.follow(sy, dt)
                themLife.follow(st, dt)
                lastYou = yv
                lastThem = tv
            }
            lastNs = ns
        }
    }
    // Reaching the goal while watching: a cheer, a burst from the summit and a buzz. Already there when the
    // scene opens: they just stand at the top.
    var burstAt by remember { mutableFloatStateOf(-10f) }
    var seenYouReached by remember { mutableStateOf<Boolean?>(null) }
    var seenThemReached by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(youReached) {
        val before = seenYouReached
        seenYouReached = youReached
        if (youReached && before == false && !SceneHandoff.sceneHidden) {
            burstAt = clock.floatValue
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            youLife.cheerFor(this, 4200)
        }
    }
    LaunchedEffect(themReached) {
        val before = seenThemReached
        seenThemReached = themReached
        if (themReached && before == false && !SceneHandoff.sceneHidden) {
            burstAt = clock.floatValue
            themLife.cheerFor(this, 4200)
        }
    }
    val youTired = if (youActive) ((doneSeconds - 7200) / 3600f).coerceIn(0f, 1f) else 0f
    val themTired = if (partnerActive) ((partnerSeconds - 7200) / 3600f).coerceIn(0f, 1f) else 0f
    LaunchedEffect(youReached, night, youActive, youTired) { youLife.settle(stand = youReached, sleep = night && !youActive, tired = youTired) }
    LaunchedEffect(themReached, night, partnerActive, themTired) { themLife.settle(stand = themReached, sleep = night && !partnerActive, tired = themTired) }
    // A stumble each time a lock is broken.
    var lastSlips by remember { mutableIntStateOf(slips) }
    // Read and cleared while drawing, so it's a plain holder rather than state.
    val kicks = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(slips) {
        if (slips > lastSlips) { kicks[0] = 5f; youLife.stumble() }
        lastSlips = slips
    }
    var lastPartnerSlips by remember { mutableIntStateOf(partnerSlips) }
    LaunchedEffect(partnerSlips) {
        if (partnerSlips > lastPartnerSlips) { kicks[1] = 5f; themLife.stumble() }
        lastPartnerSlips = partnerSlips
    }
    // Tapping your own climber makes them wave.
    val youWaveTap = remember { Animatable(0f) }
    // Holding a climber shows today's time over their head: 1 you, 2 them.
    var bubbleFor by remember { mutableIntStateOf(0) }
    val bubble = remember { Animatable(0f) }
    fun showBubble(which: Int) {
        bubbleFor = which
        scope.launch {
            bubble.snapTo(0f)
            bubble.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
            delay(2600)
            bubble.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
        }
    }

    // The tap handler is read fresh on every tap, so a new lambda from the screen never restarts the gesture.
    val partnerTap by androidx.compose.runtime.rememberUpdatedState(onPartnerTap)
    val tapModifier = Modifier.pointerInput(Unit) {
        // Generous around the head and down the body: a thumb doesn't aim at a head.
        fun near(pos: Offset, x: Float, y: Float, r: Float = 64.dp.toPx()): Boolean {
            if (x < 0f) return false
            val dx = pos.x - x
            val dy = pos.y - (y + 6.dp.toPx())
            return dx * dx + dy * dy <= r * r
        }
        detectTapGestures(
            onTap = { pos ->
                when {
                    near(pos, heads[2], heads[3], 76.dp.toPx()) && partnerTap != null -> partnerTap?.invoke()
                    near(pos, heads[0], heads[1]) && !youWaveTap.isRunning -> scope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        youWaveTap.animateTo(1f, tween(1300, easing = UiGlide))
                        youWaveTap.snapTo(0f)
                    }
                }
            },
            onLongPress = { pos ->
                when {
                    near(pos, heads[0], heads[1]) -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showBubble(1) }
                    near(pos, heads[2], heads[3]) -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showBubble(2) }
                }
            },
        )
    }

    val bubbleStyle = remember(LockInColors.palette) {
        TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = LockInColors.Snow)
    }
    val bubbleText = when (bubbleFor) {
        1 -> (youLabel ?: "You · ${formatMinutes(doneSeconds / 60)} today") + when {
            youActive -> " · climbing"
            youReached -> " · at the top"
            night -> " · asleep"
            else -> ""
        }
        2 -> "$partnerName · ${formatMinutes(partnerSeconds / 60)} today" + when {
            partnerActive -> " · climbing"
            themReached -> " · at the top"
            night -> " · asleep"
            else -> ""
        }
        else -> ""
    }
    val bubbleLayout = remember(measurer, bubbleText, bubbleStyle) { if (bubbleText.isEmpty()) null else measurer.measure(AnnotatedString(bubbleText), bubbleStyle) }

    // The two ropes, simulated. Laid out on the old curve first, so a hand-over from the welcome page never jumps.
    val ropes = remember { arrayOf(RopeSim(), RopeSim()) }
    val ropeClock = remember { floatArrayOf(-1f, 0f) }
    val front = remember { ArrayList<FxPrim>(300) }
    val ground2 = remember { ArrayList<FxPrim>(200) }

    // The topographic lines never change here, so they live on their own layer, drawn once into a texture and
    // simply laid under the scene, instead of being stroked again on every frame of the animation above them.
    androidx.compose.foundation.layout.Box(modifier) {
    Canvas(
        androidx.compose.ui.Modifier
            .matchParentSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) { contours.draw(this, 10f, size.width, size.height) }
    Canvas(androidx.compose.ui.Modifier.matchParentSize().then(tapModifier)) {
        val t = clock.floatValue
        val n = nudge()
        val env = bloom.value
        // The nudge, in three beats: a spark flies from you to them (0 to 0.42), they hop and the rope
        // twangs (0.42 on), and rings spread out from them while their tag reads "Nudged!".
        val arrive = 0.42f
        val after = if (n > arrive) ((n - arrive) / (1f - arrive)).coerceIn(0f, 1f) else 0f
        val hop = if (n > arrive) {
            val k = ((n - arrive) / 0.32f).coerceIn(0f, 1f)
            kotlin.math.sin(k * Math.PI).toFloat() * (1f - 0.35f * k)
        } else 0f
        // The scene's own colours follow the theme (accent flags and sparks; grey snow on the light theme).
        SceneFx.accent = LockInColors.Lime.toArgb()
        SceneFx.light = !LockInColors.isDark
        val s = size.width / SCENE_W
        val oy = size.height - (BASELINE - SCENE_Y) * s
        val weather = SceneFx.weatherFor(doneSeconds, goalSeconds, hour)

        // Waves: during a nudge you flick your arm up and they wave back; a tap on you waves; whoever is at the
        // top waves down now and then at the one still climbing.
        fun waitWave(phase: Float): Float {
            val f = ((t + phase) % 9f) / 1.6f
            return if (f in 0f..1f) kotlin.math.sin(f * Math.PI).toFloat() else 0f
        }
        val youWave = maxOf(
            if (n > 0f && n < arrive) kotlin.math.sin((n / arrive) * Math.PI).toFloat() * 0.8f else 0f,
            kotlin.math.sin(youWaveTap.value * Math.PI).toFloat(),
            if (youReached && !themReached && youLife.cheer.value < 0.1f) waitWave(0f) * 0.9f else 0f,
        )
        val themWave = maxOf(
            if (after > 0f) kotlin.math.sin(after * Math.PI).toFloat() else 0f,
            if (themReached && !youReached && themLife.cheer.value < 0.1f) waitWave(4.5f) * 0.9f else 0f,
        )
        val pl = Rig.pose(YouSpec, you.value, t, youWave, youLife.life())
        val pr = Rig.pose(PartnerSpec, partner.value, t, themWave, themLife.life())
        val youTent = youLife.tent.value
        val themTent = themLife.tent.value

        withTransform({
            translate(-SCENE_X * s, oy - SCENE_Y * s)
            scale(s, s, Offset.Zero)
        }) {
            clipRect(left = -300f, top = -300f, right = 1600f, bottom = BASELINE) {
                // Behind the mountain: the sky for the hour and the weather.
                if (env > 0f) clipPath(SkyClip) { drawFx(SceneFx.back(t, hour, weather, aurora = streak >= 7, bloom = env)) }
                drawMountain(mountain)

                // On the mountain: cairns, footprints, the trail and camps.
                ground2.clear()
                if (env > 0f) {
                    SceneFx.cairns(streak, env, ground2)
                    SceneFx.ground(YouSpec, you.value, env, t, ground2)
                    SceneFx.ground(PartnerSpec, partner.value, env, t + 0.7f, ground2)
                    drawFx(ground2)
                }

                // The ropes: real ones, from each harness (or tent door) to the foot of the flag.
                val dt = if (ropeClock[0] < 0f) 0f else (t - ropeClock[0]).coerceIn(0f, 0.05f)
                val sag = 74f - 52f * taut()
                val slack = 0.02f + 0.13f * (1f - taut())
                val prevN = ropeClock[1]
                // The rope twangs when a nudge lands, and when you stumble.
                val twang = if (prevN < arrive && n >= arrive) 6f else 0f
                ropeClock[1] = n
                for ((i, pair) in listOf(Triple(YouSpec, pl, youTent), Triple(PartnerSpec, pr, themTent)).withIndex()) {
                    val (spec, pose, tent) = pair
                    val a = ClimberShapes.ropeAnchor(spec, pose)
                    val door = spec.worldX(pose.hip.x)
                    val ax = a.x + (door - a.x) * tent
                    val ay = a.y + (Ground.y(door) - 6f - a.y) * tent
                    // With motion off the rope simply hangs on its curve.
                    if (ropeClock[0] < 0f || motionOff) ropes[i].reset(ax, ay, ROPE_TIE_X, ROPE_TIE_Y, sag)
                    val kick = kicks[i] + if (i == 1) twang else 0f
                    ropes[i].step(dt, ax, ay, ROPE_TIE_X, ROPE_TIE_Y, slack, kick)
                    drawRopePoints(ropes[i].points())
                }
                kicks[0] = 0f
                kicks[1] = 0f
                ropeClock[0] = t

                // The climbers, each on a soft shadow; asleep, they're tucked into a tent with a lamp on.
                front.clear()
                fun climber(spec: ClimberSpec, pose: Pose, tent: Float, lift: Float, phase: Float) {
                    val gx = spec.worldX(pose.hip.x)
                    val gy = Ground.y(gx)
                    if (tent > 0.01f) {
                        val l = ArrayList<FxPrim>(12)
                        SceneFx.tent(gx, 34f * tent, true, t + phase, l, lamp = tent)
                        drawFx(l)
                        if (tent > 0.9f) SceneFx.zzz(gx + 12f, gy - 42f, t + phase, front)
                    }
                    if (tent >= 0.99f) return
                    val k = 1f - tent
                    withTransform({
                        translate(top = -lift)
                        scale(k, k, Offset(gx, gy))
                    }) {
                        val sh = ArrayList<FxPrim>(1)
                        SceneFx.shadow(spec, pose.hip.x, 1f, sh)
                        drawFx(sh)
                        drawClimber(spec, pose)
                    }
                    // Breath in the cold: high up, at night, or in the snow.
                    if (tent < 0.1f && (pose.hip.x > spec.dTotal * 0.35f || night || weather == Weather.Snow)) {
                        val up = atan2(pose.head.x - pose.shoulder.x, -(pose.head.y - pose.shoulder.y))
                        val fx = cos(up) * spec.dir
                        val fy = sin(up)
                        SceneFx.breath(spec.worldX(pose.head.x) + fx * 14f, pose.head.y + fy * 14f - lift, fx, fy - 0.25f, t, phase, front)
                    }
                }
                climber(YouSpec, pl, youTent, 0f, 0f)
                climber(PartnerSpec, pr, themTent, hop * 34f, 1.6f)
                flag.draw(this, t)

                // In front: snow and mist, the summit burst, and a high five when both made it.
                if (env > 0f) SceneFx.front(t, weather, env, front)
                SceneFx.burst(t - burstAt, front)
                if (youLife.cheer.value > 0.5f && themLife.cheer.value > 0.5f) {
                    SceneFx.highFive(ROPE_TIE_X, minOf(pl.handA.y, pr.handA.y) - 6f, t, front)
                }
                drawFx(front)
            }
        }
        // The foot of the mountain melts into the ground instead of ending on a hard line.
        val mist = 46.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground), startY = size.height - mist, endY = size.height),
            topLeft = Offset(0f, size.height - mist),
            size = Size(size.width, mist),
        )
        // Name tags float above each climber's head (or above the tent).
        val padX = 8.dp.toPx()
        val padY = 3.dp.toPx()
        val hopPx = hop * 34f * s
        fun headPx(spec: ClimberSpec, pose: Pose, tent: Float, lift: Float): Offset {
            val gx = spec.worldX(pose.hip.x)
            val hx = spec.worldX(pose.head.x)
            val hy = pose.head.y + (Ground.y(gx) - 40f - pose.head.y) * tent
            return Offset((hx + (gx - hx) * tent - SCENE_X) * s, oy + (hy - SCENE_Y) * s - lift)
        }
        val youHead = headPx(YouSpec, pl, youTent, 0f)
        val themHead = headPx(PartnerSpec, pr, themTent, hopPx)
        heads[0] = youHead.x; heads[1] = youHead.y
        heads[2] = themHead.x; heads[3] = themHead.y

        if (n > 0f && n < 1f) {
            // The spark arcs over the summit from your head to theirs, with a short tail behind it.
            if (n < arrive) {
                val ctrl = Offset((youHead.x + themHead.x) / 2f, minOf(youHead.y, themHead.y) - 90.dp.toPx())
                fun along(f: Float): Offset {
                    val u = f.coerceIn(0f, 1f)
                    val a = (1 - u) * (1 - u)
                    val b = 2 * (1 - u) * u
                    val c = u * u
                    return Offset(a * youHead.x + b * ctrl.x + c * themHead.x, a * youHead.y + b * ctrl.y + c * themHead.y)
                }
                val f = n / arrive
                val eased = 1f - (1f - f) * (1f - f)
                for (k in 4 downTo 1) {
                    drawCircle(LockInColors.Lime, radius = (5f - k).dp.toPx() * 0.7f, center = along(eased - k * 0.05f))
                }
                drawCircle(LockInColors.Lime, radius = 6.dp.toPx(), center = along(eased))
                drawCircle(LockInColors.Ink, radius = 2.dp.toPx(), center = along(eased))
            } else {
                // Two rings spread out from them and thin away to nothing (no fading, the stroke shrinks).
                for (ring in 0..1) {
                    val r = ((after - ring * 0.18f) / 0.82f).coerceIn(0f, 1f)
                    if (r <= 0f || r >= 1f) continue
                    drawCircle(
                        LockInColors.Lime,
                        radius = 10.dp.toPx() + 54.dp.toPx() * r,
                        center = themHead,
                        style = androidx.compose.ui.graphics.drawscope.Stroke((1f - r) * 4.dp.toPx()),
                    )
                }
            }
        }

        val pop = tagScale()
        if (pop > 0f) {
            val tags = listOf(Triple(youTag, youHead, false), Triple(partnerTag, themHead, true))
            for ((baseLayout, head, isThem) in tags) {
                val nudged = isThem && n > arrive && n < 0.97f
                val layout = if (nudged) nudgedTag else baseLayout
                val w = layout.size.width + padX * 2
                val h = layout.size.height + padY * 2
                // Kept inside the card: a climber near an edge never carries half a tag off the screen.
                val edge = 6.dp.toPx()
                val cx = if (size.width > w + edge * 2) head.x.coerceIn(w / 2f + edge, size.width - w / 2f - edge) else head.x
                val cy = head.y - 24.dp.toPx()
                // Each tag grows out of the climber's head; theirs bounces when the nudge lands.
                val bounce = if (nudged) 1f + 0.22f * kotlin.math.sin(((n - arrive) / 0.2f).coerceIn(0f, 1f) * Math.PI).toFloat() else 1f
                val sc = pop * bounce
                withTransform({ scale(sc, sc, Offset(cx, cy + h / 2f)) }) {
                    drawRoundRect(
                        if (nudged) LockInColors.Lime else LockInColors.Snow,
                        topLeft = Offset(cx - w / 2f, cy - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f, h / 2f),
                    )
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
                }
            }
        }

        // Held: today's time in a dark bubble over that climber, springing out of their tag.
        val b = bubble.value
        val bl = bubbleLayout
        if (b > 0.01f && bl != null && bubbleFor != 0) {
            val head = if (bubbleFor == 1) youHead else themHead
            val w = bl.size.width + 12.dp.toPx() * 2
            val h = bl.size.height + 7.dp.toPx() * 2
            val cx = head.x.coerceIn(w / 2f + 6.dp.toPx(), size.width - w / 2f - 6.dp.toPx())
            val cy = head.y - 58.dp.toPx()
            val pivotY = head.y - 30.dp.toPx()
            withTransform({ scale(b, b, Offset(head.x, pivotY)) }) {
                drawRoundRect(LockInColors.Lime, topLeft = Offset(cx - w / 2f - 1.5f, cy - h / 2f - 1.5f), size = Size(w + 3f, h + 3f), cornerRadius = CornerRadius(h / 2f, h / 2f))
                drawRoundRect(LockInColors.Ink, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f))
                drawText(bl, topLeft = Offset(cx - bl.size.width / 2f, cy - bl.size.height / 2f))
            }
        }
    }    }
}

private fun nowHour(): Float = java.time.LocalTime.now().let { it.hour + it.minute / 60f }

/** A climber's moods as springs, so they ease from climbing into standing, cheering, tiring or a tent. */
private class LifeSprings {
    val stand = Animatable(0f)
    val cheer = Animatable(0f)
    val tired = Animatable(0f)
    val slip = Animatable(0f)
    val tent = Animatable(0f)
    private val soft = spring<Float>(dampingRatio = 0.8f, stiffness = 140f)

    /** 1 while really walking, easing to 0 when stopped. Starts at 1 so a hand-over from the welcome page doesn't pop. */
    var walk by mutableFloatStateOf(1f)
        private set

    /** Moves [walk] towards walking or resting from the speed along the trail ([speed] in units/s over [dt] seconds). */
    fun follow(speed: Float, dt: Float) {
        val moving = speed in 12f..1500f
        val target = if (moving) 1f else 0f
        val k = if (moving) 7f else 3.2f
        walk += (target - walk) * (1f - kotlin.math.exp(-k * dt))
    }

    fun life(): Life = Life(stand = stand.value, cheer = cheer.value, tired = tired.value, slip = slip.value, walk = walk)

    suspend fun settle(stand: Boolean, sleep: Boolean, tired: Float) = kotlinx.coroutines.coroutineScope {
        launch { this@LifeSprings.stand.animateTo(if (stand && !sleep) 1f else 0f, soft) }
        launch { this@LifeSprings.tired.animateTo(tired, soft) }
        // A tent goes up with a little overshoot, like it's been pitched with a snap.
        launch { tent.animateTo(if (sleep) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 120f)) }
    }

    fun cheerFor(scope: kotlinx.coroutines.CoroutineScope, millis: Long) {
        scope.launch {
            cheer.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f))
            delay(millis)
            cheer.animateTo(0f, soft)
        }
    }

    suspend fun stumble() {
        slip.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 900f))
        slip.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 160f))
    }
}
