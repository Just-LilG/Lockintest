package com.godbless.lockin.auth

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import com.godbless.lockin.ui.StepBar
import com.godbless.lockin.ui.LocalKeyboardUp
import com.godbless.lockin.ui.Collapse
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.AuthRepository
import com.godbless.lockin.data.CodePurpose
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.rememberEntrance
import com.godbless.lockin.ui.rise

/** The faces of the sheet, in the order they slide (a higher one slides in from the right). */
enum class AuthStage { SignIn, Name, Email, Password, Code, NewPassword, BiometricOffer }

private val Glide = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val SWITCH_MS = 380

/**
 * The account sheet that rises over the welcome page. It only draws itself; the welcome screen
 * owns where it is (so the mountain can make room above it) and when it opens or closes.
 */
@Composable
fun AuthSheet(
    stage: AuthStage,
    email: String,
    onEmail: (String) -> Unit,
    repo: AuthRepository,
    purpose: CodePurpose,
    onPurpose: (CodePurpose) -> Unit,
    rememberPhone: Boolean,
    onRememberPhone: (Boolean) -> Unit,
    onStage: (AuthStage) -> Unit,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    maxContentHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val context = LocalContext.current
    val density = LocalDensity.current
    // Only the yes/no is read here, so the sheet does not rebuild on every frame of the keyboard sliding.
    val ime = WindowInsets.ime
    val keyboardUp by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    // The sign-up answers live here so they survive sliding between steps.
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    // After any successful sign-in: offer fingerprint once (only when remembered), else go in.
    val afterSignIn = {
        if (rememberPhone && Biometrics.available(context) && repo.shouldOfferBiometric()) {
            onStage(AuthStage.BiometricOffer)
        } else {
            onSignedIn()
        }
    }
    val inSignUp = stage == AuthStage.Name || stage == AuthStage.Email || stage == AuthStage.Password ||
        (stage == AuthStage.Code && purpose == CodePurpose.SignUp)
    val barStep = remember { intArrayOf(0) }
    if (inSignUp) {
        barStep[0] = when (stage) {
            AuthStage.Name -> 0
            AuthStage.Email -> 1
            AuthStage.Password -> 2
            else -> 3
        }
    }

    // imePadding sits inside the caller's modifier, so the sheet rides on top of the keyboard.
    CompositionLocalProvider(LocalKeyboardUp provides keyboardUp) {
    Column(modifier.readable(Widths.Sheet).imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.dp, LockInColors.Graphite, shape),
        ) {
            // Grab area: drag down to close.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        ) { change, dy ->
                            change.consume()
                            onDrag(dy)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LockInColors.Edge),
                )
            }
            Collapse(inSignUp) {
                Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 14.dp)) {
                    StepBar(total = 4, current = barStep[0])
                }
            }
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(SWITCH_MS, easing = Glide)) { dir * it } togetherWith
                        slideOutHorizontally(tween(SWITCH_MS, easing = Glide)) { -dir * it }) using
                        SizeTransform(clip = true) { _, _ -> tween(SWITCH_MS, easing = Glide) }
                },
                label = "auth-stage",
            ) { st ->
                val entrance = rememberEntrance()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                ) {
                    when (st) {
                        AuthStage.Name -> {
                            SheetHeader("What's your name?", "Your study partner will see it.", onClose, Modifier.rise(entrance, 0))
                            NameStep(
                                name = name,
                                onName = { name = it },
                                repo = repo,
                                entrance = entrance,
                                onNext = { onStage(AuthStage.Email) },
                                onGoSignIn = { onStage(AuthStage.SignIn) },
                            )
                        }
                        AuthStage.Email -> {
                            SheetHeader(
                                "What's your email?", "We'll send a 6-digit code to confirm it.",
                                onClose, Modifier.rise(entrance, 0), onBack = { onStage(AuthStage.Name) },
                            )
                            EmailStep(
                                email = email,
                                onEmail = onEmail,
                                repo = repo,
                                entrance = entrance,
                                onNext = { onStage(AuthStage.Password) },
                                onGoSignIn = { onStage(AuthStage.SignIn) },
                            )
                        }
                        AuthStage.Password -> {
                            SheetHeader(
                                "Create a password", "Use at least 8 characters.",
                                onClose, Modifier.rise(entrance, 0), onBack = { onStage(AuthStage.Email) },
                            )
                            PasswordStep(
                                name = name,
                                email = email,
                                password = password,
                                onPassword = { password = it },
                                confirm = confirm,
                                onConfirm = { confirm = it },
                                repo = repo,
                                entrance = entrance,
                                onCodeSent = { onPurpose(CodePurpose.SignUp); onStage(AuthStage.Code) },
                                onSignedIn = afterSignIn,
                            )
                        }
                        AuthStage.SignIn -> {
                            SheetHeader("Welcome back", "Sign in to pick up where you left off.", onClose, Modifier.rise(entrance, 0))
                            SignInForm(
                                email = email,
                                onEmail = onEmail,
                                repo = repo,
                                entrance = entrance,
                                rememberPhone = rememberPhone,
                                onRememberPhone = onRememberPhone,
                                onSignedIn = afterSignIn,
                                onCodeSent = { p -> onPurpose(p); onStage(AuthStage.Code) },
                                onGoSignUp = { onStage(AuthStage.Name) },
                            )
                        }
                        AuthStage.Code -> {
                            SheetHeader(
                                if (purpose == CodePurpose.SignUp) "Confirm your email" else "Check your email",
                                null, onClose, Modifier.rise(entrance, 0),
                                onBack = { onStage(if (purpose == CodePurpose.SignUp) AuthStage.Email else AuthStage.SignIn) },
                            )
                            CodePanel(
                                email = email.trim(),
                                purpose = purpose,
                                password = password,
                                repo = repo,
                                rememberPhone = rememberPhone,
                                entrance = entrance,
                                onVerified = {
                                    if (purpose == CodePurpose.Recovery) onStage(AuthStage.NewPassword) else afterSignIn()
                                },
                            )
                        }
                        AuthStage.NewPassword -> {
                            SheetHeader("Choose a new password", "Use at least 8 characters.", onClose, Modifier.rise(entrance, 0))
                            NewPasswordForm(
                                repo = repo,
                                rememberPhone = rememberPhone,
                                entrance = entrance,
                                onDone = afterSignIn,
                            )
                        }
                        AuthStage.BiometricOffer -> {
                            SheetHeader("Sign in faster", null, onClose, Modifier.rise(entrance, 0))
                            BiometricOfferPanel(repo = repo, entrance = entrance, onDone = onSignedIn)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    sub: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val keyboardUp = LocalKeyboardUp.current
    Row(
        modifier.fillMaxWidth().padding(bottom = if (sub != null && !keyboardUp) 20.dp else 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(LockInColors.Graphite)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.625f, size.height * 0.208f)
                        lineTo(size.width * 0.333f, size.height * 0.5f)
                        lineTo(size.width * 0.625f, size.height * 0.792f)
                    }
                    drawPath(p, LockInColors.Snow, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LockInText.Title.copy(fontSize = 26.sp))
            if (sub != null) {
                Collapse(!keyboardUp) {
                    Text(sub, style = LockInText.Sub, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(LockInColors.Graphite)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val w = 2.dp.toPx()
                drawLine(LockInColors.Snow, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(LockInColors.Snow, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}
