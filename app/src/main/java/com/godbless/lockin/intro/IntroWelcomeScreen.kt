package com.godbless.lockin.intro

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.handoffSwap
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.godbless.lockin.auth.AuthSheet
import com.godbless.lockin.auth.AuthStage
import com.godbless.lockin.auth.Biometrics
import com.godbless.lockin.data.AuthResult
import com.godbless.lockin.data.CodePurpose
import com.godbless.lockin.data.SavedUser
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import android.content.Context
import android.content.ContextWrapper
import com.godbless.lockin.data.AuthRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.godbless.lockin.home.HomePartnerSpec
import com.godbless.lockin.home.HomeYouSpec
import com.godbless.lockin.home.SceneHandoff
import com.godbless.lockin.home.progressAtDistance
import com.godbless.lockin.R
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.Fonts
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText

/** Where the scene and wordmark sit on the opening screen (px) and where they settle on the welcome screen. */
private class IntroLayout(
    val s0: Float, val ox0: Float, val oy0: Float, val wm0: Offset,
    val s1: Float, val ox1: Float, val oy1: Float, val wm1: Offset, val wmScale1: Float,
)

private fun computeLayout(
    w: Float, h: Float, d: Float, topInset: Float, bottomInset: Float, wmW: Float, wmH: Float,
): IntroLayout {
    // Opening: scene centred, wordmark underneath.
    val sceneW0 = 0.80f * w
    val s0 = sceneW0 / SCENE_W
    val h0 = SCENE_H * s0
    val gap = 22f * d
    val top0 = (h - (h0 + gap + wmH)) / 2f - 0.02f * h
    val ox0 = (w - sceneW0) / 2f

    // Welcome: the whole mountain stays on screen, a little larger, sitting between the top bar and the headline.
    val contentH = 360f * d + bottomInset
    val regionTop = topInset + 64f * d
    val regionBottom = h - contentH - 8f * d
    var s1 = 0.94f * w / SCENE_W
    var h1 = SCENE_H * s1
    if (h1 > regionBottom - regionTop) {
        s1 = (regionBottom - regionTop) / SCENE_H
        h1 = SCENE_H * s1
    }
    val ox1 = (w - SCENE_W * s1) / 2f
    val oy1 = regionTop + maxOf(0f, regionBottom - regionTop - h1) * 0.45f

    return IntroLayout(
        s0 = s0, ox0 = ox0, oy0 = top0, wm0 = Offset((w - wmW) / 2f, top0 + h0 + gap),
        s1 = s1, ox1 = ox1, oy1 = oy1, wm1 = Offset(28f * d, topInset + 20f * d), wmScale1 = 20f / 34f,
    )
}

/**
 * The opening and the welcome screen are one continuous scene. A single clock drives it:
 * contours draw on, the mountain rises, the flag goes up, two climbers walk to their places,
 * "LockIn" locks in, and then the same scene glides into the welcome layout while the headline and
 * buttons slide in from below. Nothing fades.
 */
@Composable
fun IntroWelcomeScreen(
    skipIntro: Boolean,
    onIntroDone: () -> Unit,
    repo: AuthRepository,
    onSignedIn: () -> Unit,
    /**
     * 0 to 1, read while drawing: how far this page has shrunk into Home's mountain card. The headline
     * and buttons drop away, the page closes in around the mountain, and the climbers walk to where
     * today's focus puts them. Run backwards when signing out.
     */
    exit: () -> Float = { 0f },
) {
    val density = LocalDensity.current
    val clock = remember { mutableFloatStateOf(if (skipIntro) Timeline.END else 0f) }
    val mountain: ImageBitmap = ImageBitmap.imageResource(if (LockInColors.isDark) R.drawable.mountain else R.drawable.mountain_light)
    val contours = remember { ContourField() }
    val flag = remember { FlagMesh() }
    val measurer = rememberTextMeasurer()
    val wordStyle = remember(LockInColors.palette) {
        TextStyle(fontFamily = Fonts.Wordmark, fontSize = 34.sp, color = LockInColors.Snow)
    }
    val wordmark = remember(measurer) { Wordmark(measurer, wordStyle) }
    val left = remember { ClimberSpec(135f, 1f, 5, 290f, Timeline.WALK_START, Timeline.WALK_DUR) }
    val right = remember {
        ClimberSpec(1115f, -1f, 5, 971f, Timeline.WALK_START + Timeline.WALK_LAG_RIGHT, Timeline.WALK_DUR, female = true)
    }
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()

    // The account sheet lives on this page. `open` is how far it has risen (0 to 1) and drives
    // the sheet, the headline block leaving, and the mountain making room above it.
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }
    var sheetComposed by remember { mutableStateOf(false) }
    // While the keyboard is up over the open sheet, the scene behind it is mostly hidden, so it rests.
    val ime = WindowInsets.ime
    val keyboardUp by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val idlePaused by rememberUpdatedState(sheetComposed && keyboardUp)
    // Someone coming back doesn't need the whole opening before they can continue: it plays faster for them, and a
    // tap anywhere on the empty scene skips to the end for everyone.
    var introSkipped by remember { mutableStateOf(false) }
    val comingBack = remember { repo.savedUser() != null }
    val introSpeed by rememberUpdatedState(if (introSkipped) 8f else if (comingBack) 1.6f else 1f)
    LaunchedEffect(Unit) {
        var t = clock.floatValue
        var last = withFrameNanos { it }
        var announced = false
        var lastWrite = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                // The speed-up applies to the opening only; the idle flag after it waves at its own pace.
                t = if (com.godbless.lockin.ui.MotionState.reduced) Timeline.END else t + dt * (if (t < Timeline.END) introSpeed else 1f)
                if (t < Timeline.END) {
                    clock.floatValue = t
                } else if (!announced) {
                    announced = true
                    clock.floatValue = t
                    lastWrite = now
                    onIntroDone()
                } else if (!idlePaused && !com.godbless.lockin.ui.MotionState.reduced && now - lastWrite >= 33_000_000L) {
                    // After the opening only the flag moves, so 30 frames a second is plenty and
                    // leaves the phone free for the keyboard and typing.
                    clock.floatValue = t
                    lastWrite = now
                }
            }
        }
    }
    var stage by rememberSaveable { mutableStateOf(AuthStage.Name) }
    var email by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf(CodePurpose.SignUp) }
    var rememberPhone by rememberSaveable { mutableStateOf(true) }
    // Someone who ticked "remember this phone" before gets a one-tap way back in.
    var saved by remember { mutableStateOf<SavedUser?>(repo.savedUser()) }
    var continuing by remember { mutableStateOf(false) }
    var askSwitch by remember { mutableStateOf(false) }
    var continueNote by remember { mutableStateOf<String?>(null) }
    // What the wait is for, said in words: signing back in can take a few seconds on a slow connection.
    var slowWait by remember { mutableStateOf(false) }
    LaunchedEffect(continuing) {
        slowWait = false
        if (continuing) {
            delay(3500)
            slowWait = true
        }
    }
    val context = LocalContext.current
    val sheetPx = remember { mutableFloatStateOf(0f) }
    val dragFrom = remember { floatArrayOf(Float.NaN) }

    fun openSheet(target: AuthStage) {
        stage = target
        sheetComposed = true
        scope.launch { open.animateTo(1f, tween(560, easing = Glide)) }
    }

    fun closeSheet() {
        scope.launch {
            open.animateTo(0f, tween(400, easing = Glide))
            sheetComposed = false
        }
    }

    BackHandler(enabled = sheetComposed) { closeSheet() }

    fun continueAsSaved() {
        val user = saved ?: return
        if (continuing) return
        continueNote = null
        fun go() {
            continuing = true
            scope.launch {
                val r = repo.restore()
                if (r is AuthResult.SignedIn) {
                    // Stay in "getting your partner's news" until Home is ready and the page moves; letting go here
                    // flashed the button and the old line back for the moments Home spends loading.
                    onSignedIn()
                } else {
                    continuing = false
                    continueNote = (r as? AuthResult.Failure)?.message
                        ?: "Couldn't sign you back in. Use a different account."
                }
            }
        }
        val activity = context.findFragmentActivity()
        if (user.biometric && activity != null) {
            Biometrics.prompt(activity, "Unlock LockIn", "Confirm it's you") { ok -> if (ok) go() }
        } else {
            go()
        }
    }

    // Where Home's mountain box is. Until Home has measured it, a good guess keeps the move going.
    val dens = density.density
    fun landing(w: Float): Rect = SceneHandoff.box ?: Rect(10f * dens, topInset + 100f * dens, w - 10f * dens, topInset + 390f * dens)
    // The headline, buttons, curtain and sheet all fall away in the first half of the move.
    fun textOut(): Float = easeInCubic(seg(exit(), 0f, 0.55f))
    val startYou = remember(left) { progressAtDistance(HomeYouSpec, left.dTotal) }
    val startPartner = remember(right) { progressAtDistance(HomePartnerSpec, right.dTotal) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // A tap on the scene while the opening plays skips to the end of it.
            .pointerInput(Unit) { detectTapGestures { if (clock.floatValue < Timeline.END) introSkipped = true } }
            .graphicsLayer {
                val x = exit()
                if (x > 0f) {
                    val b = landing(size.width)
                    val inset = SceneHandoff.border
                    shape = MorphShape(x, b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, maxOf(0f, SceneHandoff.corner - inset))
                    clip = true
                } else {
                    shape = androidx.compose.ui.graphics.RectangleShape
                    clip = false
                }
            }
            .drawBehind { drawRect(lerpColor(LockInColors.Ink, LockInColors.Slate, exit())) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val d = density.density
        val layout = remember(w, h, topInset, bottomInset, wordmark, d) {
            computeLayout(w, h, d, topInset, bottomInset, wordmark.width, wordmark.height)
        }

        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "LockIn" }) {
            val t = clock.floatValue
            SceneHandoff.clock = t
            val x = exit()
            val e = easeInOutCubic(seg(t, Timeline.TRANS_START, Timeline.TRANS_DUR))
            var s = mix(layout.s0, layout.s1, e)
            var ox = mix(layout.ox0, layout.ox1, e)
            var oy = mix(layout.oy0, layout.oy1, e)

            // With the sheet up, the mountain shrinks and shifts to fill the space above it.
            val p = open.value
            if (p > 0f) {
                val regionTop = topInset + 56f * d
                val regionBottom = h - sheetPx.floatValue - 10f * d
                val avail = regionBottom - regionTop
                var s2 = layout.s1
                if (SCENE_H * s2 > avail) s2 = maxOf(0.45f * layout.s1, avail / SCENE_H)
                val ox2 = (w - SCENE_W * s2) / 2f
                val oy2 = regionTop + maxOf(0f, avail - SCENE_H * s2) * 0.5f
                s = mix(s, s2, p)
                ox = mix(ox, ox2, p)
                oy = mix(oy, oy2, p)
            }

            // On the way into Home the scene settles into the box Home keeps its mountain in.
            val b = landing(size.width)
            if (x > 0f) {
                val sh = b.width / SCENE_W
                s = mix(s, sh, x)
                ox = mix(ox, b.left, x)
                oy = mix(oy, b.bottom - (BASELINE - SCENE_Y) * sh, x)
                // The contour lines close in with the page, so they match the card's own when it lands.
                translate(mix(0f, b.left, x), mix(0f, b.top, x)) {
                    contours.draw(this, t, mix(size.width, b.width, x), mix(size.height, b.height, x))
                }
            } else {
                contours.draw(this, t, size.width, size.height)
            }

            withTransform({
                translate(ox - SCENE_X * s, oy - SCENE_Y * s)
                scale(s, s, Offset.Zero)
            }) {
                // Home's climbers have the same stride as these two, so at the first frame of the move
                // they stand exactly where these stand, then walk to today's place on the mountain.
                val homeMode = x > 0f
                val ls = if (homeMode) HomeYouSpec else left
                val rs = if (homeMode) HomePartnerSpec else right
                val pl = if (homeMode) Rig.pose(ls, mix(startYou, SceneHandoff.reachYou, x), t) else Rig.pose(left, t, t)
                val pr = if (homeMode) Rig.pose(rs, mix(startPartner, SceneHandoff.reachPartner, x), t) else Rig.pose(right, t, t)
                val rise = (1f - easeOutExpo(seg(t, Timeline.RISE_START, Timeline.RISE_DUR))) * 460f
                clipRect(left = -300f, top = -300f, right = 1600f, bottom = BASELINE) {
                    translate(top = rise) {
                        drawMountain(mountain)
                        if (SHOW_ROPES) {
                            val grow = easeOutCubic(seg(t, Timeline.ROPE_START, Timeline.ROPE_DUR))
                            val sag = mix(34f + 46f * (1f - easeInOutSine(seg(t, Timeline.WALK_START, Timeline.WALK_DUR))), 74f, x)
                            val la = ClimberShapes.ropeAnchor(ls, pl)
                            val ra = ClimberShapes.ropeAnchor(rs, pr)
                            drawRope(Offset(la.x, la.y), sag, grow)
                            drawRope(Offset(ra.x, ra.y), sag, grow)
                        }
                        drawClimber(ls, pl)
                        drawClimber(rs, pr)
                        // The flag climbs out of the summit together with the mountain.
                        flag.draw(this, t)
                    }
                }
            }

            // The foot of the mountain melts into the card, as it does on Home.
            if (x > 0f) {
                val fill = lerpColor(LockInColors.Ink, LockInColors.Slate, x)
                val bottom = mix(size.height, b.bottom, x)
                val mist = 46.dp.toPx() * x
                drawRect(
                    Brush.verticalGradient(listOf(fill.copy(alpha = 0f), fill), startY = bottom - mist, endY = bottom),
                    topLeft = Offset(0f, bottom - mist),
                    size = Size(size.width, mist),
                )
            }

            // The big wordmark drops out of its mask as the scene moves; a small one rises at the top left.
            drawWordmark(
                wordmark, layout.wm0, 1f,
                rise = t - Timeline.WORD_START,
                exit = t - Timeline.TRANS_START,
                click = t - Timeline.CLICK,
            )
            drawWordmark(
                wordmark,
                Offset(layout.wm1.x, layout.wm1.y - easeInCubic(seg(x, 0f, 0.5f)) * (layout.wm1.y + 80f * d)),
                layout.wmScale1,
                rise = t - Timeline.SMALL_WORD_START,
                exit = -1f,
                click = null,
            )
        }

        // A curtain of the ground colour rises behind the text, calming the contours below the mountain.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(380.dp)
                .graphicsLayer {
                    translationY = (1f - easeOutCubic(seg(clock.floatValue, Timeline.CURTAIN_START, 0.8f))) * size.height +
                        textOut() * size.height
                }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.30f to LockInColors.Ink,
                        1f to LockInColors.Ink,
                    ),
                ),
        )

        // The headline block drops away as the sheet rises to replace it.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = easeInOutCubic(open.value) * (size.height + bottomInset + 48f * d) +
                        textOut() * (size.height + bottomInset + 48f * d)
                }
                .navigationBarsPadding()
                .readable(Widths.Sheet)
                .padding(start = 28.dp, end = 28.dp, bottom = 40.dp),
        ) {
            val firstName = saved?.name?.trim()?.substringBefore(' ').orEmpty()
            val returning = saved != null && firstName.isNotEmpty()
            Reveal(clock, Timeline.HEAD_1) { Text(if (returning) "Welcome back," else "Your partner", style = LockInText.Headline) }
            Reveal(clock, Timeline.HEAD_2) { Text(if (returning) "$firstName." else "holds the key.", style = LockInText.Headline) }
            Spacer(Modifier.height(16.dp))
            Reveal(clock, Timeline.PARA, dur = 0.8f) {
                // While signing back in, the line says what is happening. It slides, it doesn't fade.
                AnimatedContent(
                    targetState = if (continuing) (if (slowWait) 2 else 1) else 0,
                    transitionSpec = { handoffSwap() },
                    label = "welcome-line",
                ) { phase ->
                    Text(
                        when {
                            phase == 1 -> "Signing you in and getting your partner\u2019s news\u2026"
                            phase == 2 -> "Still working. The connection looks slow, hang on\u2026"
                            returning -> "Pick up where you left off. Your partner is waiting."
                            else -> "Your study partner locks your phone while you work, and unlocks it when you\u2019re done."
                        },
                        style = LockInText.Lead,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(if (continueNote != null) 14.dp else 28.dp))
            continueNote?.let { Text(it, style = LockInText.Error, modifier = Modifier.padding(bottom = 12.dp)) }
            if (returning) {
                RiseUp(clock, Timeline.BUTTONS) {
                    LockInButton("Continue as $firstName", onClick = { continueAsSaved() }, loading = continuing)
                }
                Spacer(Modifier.height(12.dp))
                RiseUp(clock, Timeline.BUTTONS + 0.08f) {
                    LockInButton(
                        "Use a different account",
                        onClick = {
                            when {
                                // Switching account would drop the lock this phone is holding.
                                com.godbless.lockin.lock.LocalLock(context).active ->
                                    continueNote = "You can't switch account while your phone is locked."
                                // While paired, your partner has to say yes first.
                                com.godbless.lockin.lock.Protection.required(context, com.godbless.lockin.lock.Protection.SignOut) ->
                                    askSwitch = true
                                else -> {
                                    com.godbless.lockin.home.Identity.load(context)
                                    com.godbless.lockin.home.Identity.reset()
                                    repo.signOut()
                                    saved = null
                                    continueNote = null
                                }
                            }
                        },
                        kind = ButtonKind.Outline,
                    )
                }
            } else {
                RiseUp(clock, Timeline.BUTTONS) { LockInButton("Create account", onClick = { openSheet(AuthStage.Name) }) }
                Spacer(Modifier.height(12.dp))
                RiseUp(clock, Timeline.BUTTONS + 0.08f) {
                    LockInButton("Sign in", onClick = { openSheet(AuthStage.SignIn) }, kind = ButtonKind.Outline)
                }
            }
        }

        if (askSwitch) {
            com.godbless.lockin.lock.ProtectAskSheet(
                kind = com.godbless.lockin.lock.Protection.SignOut,
                onAllowed = {
                    askSwitch = false
                    com.godbless.lockin.lock.Protection.clear(context)
                    com.godbless.lockin.home.Identity.load(context)
                    com.godbless.lockin.home.Identity.reset()
                    repo.signOut()
                    saved = null
                    continueNote = null
                },
                onClose = { askSwitch = false },
            )
        }

        if (sheetComposed) {
            // Tapping the mountain area closes the sheet.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { closeSheet() } },
            )
            val topInsetDp = with(density) { topInset.toDp() }
            AuthSheet(
                stage = stage,
                email = email,
                onEmail = { email = it },
                repo = repo,
                purpose = purpose,
                onPurpose = { purpose = it },
                rememberPhone = rememberPhone,
                onRememberPhone = { rememberPhone = it },
                onStage = { stage = it },
                onClose = { closeSheet() },
                onSignedIn = onSignedIn,
                onDrag = { dy ->
                    // Dragging the grab area moves the sheet itself: openness follows the finger.
                    // The running position is kept here, not read back from the animation, because
                    // several touch events can arrive before the animation has caught up.
                    val full = maxOf(sheetPx.floatValue, 1f)
                    val from = if (dragFrom[0].isNaN()) open.value else dragFrom[0]
                    val next = (from - dy / full).coerceIn(0f, 1f)
                    dragFrom[0] = next
                    scope.launch { open.snapTo(next) }
                },
                onDragEnd = {
                    val at = if (dragFrom[0].isNaN()) open.value else dragFrom[0]
                    dragFrom[0] = Float.NaN
                    if (at < 0.65f) closeSheet()
                    else scope.launch { open.animateTo(1f, tween(260, easing = Glide)) }
                },
                maxContentHeight = maxHeight - topInsetDp - 72.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        translationY = (1f - open.value) * (size.height + 24f * d) + textOut() * (size.height + 24f * d)
                    }
                    .onSizeChanged { sheetPx.floatValue = it.height.toFloat() },
            )
        }
    }
}

private val Glide = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * The page's outline while it closes in on Home's mountain card: the whole screen at 0, the card's top
 * part (round at the top, square below where the card carries on) at 1.
 */
private data class MorphShape(
    val k: Float,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val radius: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val round = CornerRadius(radius * k, radius * k)
        return Outline.Rounded(
            RoundRect(
                left = l * k,
                top = t * k,
                right = size.width + k * (r - size.width),
                bottom = size.height + k * (b - size.height),
                topLeftCornerRadius = round,
                topRightCornerRadius = round,
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius.Zero,
            ),
        )
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** Text that slides up out of a mask line. */
@Composable
private fun Reveal(clock: MutableFloatState, start: Float, dur: Float = 0.7f, content: @Composable () -> Unit) {
    Box(Modifier.clipToBounds()) {
        Box(
            Modifier.graphicsLayer {
                translationY = (1f - easeOutCubic(seg(clock.floatValue, start, dur))) * size.height * 1.15f
            },
        ) { content() }
    }
}

/** Content that rises from below the screen edge with a small spring. */
@Composable
private fun RiseUp(clock: MutableFloatState, start: Float, content: @Composable () -> Unit) {
    Box(
        Modifier.graphicsLayer {
            translationY = (1f - easeOutBack(seg(clock.floatValue, start, 0.75f), 1.15f)) * 360.dp.toPx()
        },
    ) { content() }
}
