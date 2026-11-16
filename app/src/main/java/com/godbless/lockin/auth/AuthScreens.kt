@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.AuthRepository
import com.godbless.lockin.data.AuthResult
import com.godbless.lockin.data.CodePurpose
import com.godbless.lockin.ui.ActionRow
import com.godbless.lockin.ui.AuthScaffold
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LocalKeyboardUp
import com.godbless.lockin.ui.ControlShape
import com.godbless.lockin.ui.Collapse
import com.godbless.lockin.ui.FingerprintIcon
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.OtpField
import androidx.compose.ui.platform.LocalClipboardManager
import com.godbless.lockin.ui.ToggleRow
import com.godbless.lockin.ui.PasswordMeter
import com.godbless.lockin.ui.UiGlide
import com.godbless.lockin.ui.rememberFloat
import com.godbless.lockin.ui.rise
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

private fun isEmail(s: String) = Patterns.EMAIL_ADDRESS.matcher(s.trim()).matches()

/** Opens Google sign-in in the browser; the answer comes back to the app through the lockin:// link. */
private fun startGoogle(context: Context, repo: AuthRepository) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repo.startGoogleSignIn()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        toast(context, "Couldn't open the browser for Google sign-in.")
    }
}

// Common typos in the domain part of an email address.
private val DomainTypos = mapOf(
    "gmial.com" to "gmail.com", "gmai.com" to "gmail.com", "gamil.com" to "gmail.com",
    "gnail.com" to "gmail.com", "gmail.con" to "gmail.com", "gmail.co" to "gmail.com",
    "hotmial.com" to "hotmail.com", "hotmai.com" to "hotmail.com", "hotmail.con" to "hotmail.com",
    "yaho.com" to "yahoo.com", "yahooo.com" to "yahoo.com", "yahoo.con" to "yahoo.com",
    "outlok.com" to "outlook.com", "outlook.con" to "outlook.com",
    "icloud.con" to "icloud.com", "icoud.com" to "icloud.com",
)

private fun suggestEmail(email: String): String? {
    val e = email.trim()
    val at = e.lastIndexOf('@')
    if (at <= 0) return null
    val fixed = DomainTypos[e.substring(at + 1).lowercase()] ?: return null
    return e.substring(0, at + 1) + fixed
}

/** "Did you mean ...?" under the email field when the domain looks mistyped. */
@Composable
private fun EmailSuggestion(email: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestion = suggestEmail(email)
    val last = remember { mutableStateOf("") }
    if (suggestion != null) last.value = suggestion
    AnimatedVisibility(
        visible = suggestion != null,
        enter = expandVertically(tween(260, easing = UiGlide)),
        exit = shrinkVertically(tween(200, easing = UiGlide)),
        modifier = modifier,
    ) {
        Row(Modifier.padding(top = 8.dp)) {
            Text("Did you mean ", style = LockInText.Small.copy(fontSize = 14.sp))
            Text(
                last.value,
                style = LockInText.Small.copy(fontSize = 14.sp, color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
                modifier = Modifier.clickable(role = Role.Button) { onPick(last.value) },
            )
            Text("?", style = LockInText.Small.copy(fontSize = 14.sp))
        }
    }
}

@Composable
private fun SwitchLine(question: String, action: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(question, style = LockInText.Sub)
        Spacer(Modifier.size(6.dp))
        Text(
            action,
            style = LockInText.Sub.copy(color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        )
    }
}

/** An error banner that slides open and shut. */
@Composable
private fun BannerSlot(message: String?) {
    val last = remember { mutableStateOf("") }
    if (message != null) last.value = message
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(tween(280, easing = UiGlide)),
        exit = shrinkVertically(tween(200, easing = UiGlide)),
    ) {
        Column {
            ErrorBanner(last.value)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** "Sign in instead" under the email field when that address already has an account. */
@Composable
private fun TakenHint(visible: Boolean, onGoSignIn: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(260, easing = UiGlide)),
        exit = shrinkVertically(tween(200, easing = UiGlide)),
    ) {
        Text(
            "Sign in instead",
            style = LockInText.Sub.copy(color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
            modifier = Modifier.padding(top = 8.dp).clickable(role = Role.Button, onClick = onGoSignIn),
        )
    }
}

/** Sign-up step 1: just the name. */
@Composable
fun ColumnScope.NameStep(
    name: String,
    onName: (String) -> Unit,
    repo: AuthRepository,
    entrance: State<Float>,
    onNext: () -> Unit,
    onGoSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardUp = LocalKeyboardUp.current
    var error by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableIntStateOf(0) }

    fun submit() {
        if (name.trim().length < 2) {
            error = "Enter your name."
            shake += 1
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            onNext()
        }
    }

    LockInField(
        "Your name", name, { onName(it); error = null }, "First name",
        modifier = Modifier.rise(entrance, 1),
        imeAction = ImeAction.Done, error = error, valid = name.trim().length >= 2, shakeSignal = shake,
        autoFocus = true, onDone = { submit() },
        autofillTypes = listOf(AutofillType.PersonFirstName),
    )
    Spacer(Modifier.height(22.dp))
    ActionRow("Continue", onClick = { submit() }, onGoogle = { startGoogle(context, repo) }, modifier = Modifier.rise(entrance, 2))
    Collapse(!keyboardUp, Modifier.rise(entrance, 3)) {
        Column {
            Spacer(Modifier.height(6.dp))
            SwitchLine("Already have an account?", "Sign in", onClick = onGoSignIn)
        }
    }
}

/** Sign-up step 2: the email, checked live against existing accounts. */
@Composable
fun ColumnScope.EmailStep(
    email: String,
    onEmail: (String) -> Unit,
    repo: AuthRepository,
    entrance: State<Float>,
    onNext: () -> Unit,
    onGoSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboardUp = LocalKeyboardUp.current
    var error by remember { mutableStateOf<String?>(null) }
    var taken by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }

    // While the person types, ask whether that address is already registered (after a short pause).
    LaunchedEffect(email) {
        taken = false
        if (isEmail(email)) {
            delay(600)
            taken = repo.emailAvailable(email) == false
        }
    }

    fun fail(message: String) {
        error = message
        shake += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun submit() {
        if (loading) return
        if (!isEmail(email)) return fail("Enter a valid email address.")
        if (taken) return fail("That email already has an account.")
        loading = true
        scope.launch {
            val free = repo.emailAvailable(email.trim())
            loading = false
            if (free == false) {
                taken = true
                fail("That email already has an account.")
            } else {
                onNext()
            }
        }
    }

    Column(Modifier.rise(entrance, 1)) {
        LockInField(
            "Email", email, { onEmail(it); error = null }, "you@example.com",
            keyboardType = KeyboardType.Email, imeAction = ImeAction.Done,
            error = error ?: if (taken) "That email already has an account." else null,
            valid = isEmail(email) && !taken, shakeSignal = shake, autoFocus = true,
            autofillTypes = listOf(AutofillType.EmailAddress), onDone = { submit() },
            onBlur = { if (email.isNotBlank() && !isEmail(email)) error = "Enter a valid email address." },
        )
        TakenHint(taken, onGoSignIn)
        EmailSuggestion(email, onPick = { onEmail(it); error = null })
    }
    Spacer(Modifier.height(22.dp))
    LockInButton("Continue", onClick = { submit() }, modifier = Modifier.rise(entrance, 2), loading = loading)
    Collapse(!keyboardUp, Modifier.rise(entrance, 3)) {
        Column {
            Spacer(Modifier.height(6.dp))
            SwitchLine("Already have an account?", "Sign in", onClick = onGoSignIn)
        }
    }
}

/** Sign-up step 3: choose and confirm a password, then create the account. */
@Composable
fun ColumnScope.PasswordStep(
    name: String,
    email: String,
    password: String,
    onPassword: (String) -> Unit,
    confirm: String,
    onConfirm: (String) -> Unit,
    repo: AuthRepository,
    entrance: State<Float>,
    onCodeSent: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }

    fun fail() {
        shake += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun submit() {
        if (loading || success) return
        passwordError = if (password.length < 8) "Use at least 8 characters." else null
        confirmError = if (passwordError == null && confirm != password) "Passwords don't match." else null
        formError = null
        if (passwordError != null || confirmError != null) return fail()
        loading = true
        scope.launch {
            val result = repo.signUp(name.trim(), email.trim(), password)
            loading = false
            when (result) {
                is AuthResult.SignedIn -> {
                    success = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(650)
                    onSignedIn()
                }
                is AuthResult.Success, is AuthResult.NeedsConfirmation -> {
                    success = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(650)
                    onCodeSent()
                }
                is AuthResult.Failure -> {
                    formError = result.message
                    fail()
                }
            }
        }
    }

    BannerSlot(formError)
    Column(Modifier.rise(entrance, 1)) {
        LockInField(
            "Password", password, { onPassword(it); passwordError = null }, "At least 8 characters",
            keyboardType = KeyboardType.Password, isPassword = true,
            error = passwordError, shakeSignal = shake, autoFocus = true,
            autofillTypes = listOf(AutofillType.NewPassword),
        )
        PasswordMeter(password)
    }
    Spacer(Modifier.height(14.dp))
    LockInField(
        "Confirm password", confirm, { onConfirm(it); confirmError = null }, "Type it again",
        modifier = Modifier.rise(entrance, 2),
        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, isPassword = true,
        error = confirmError, shakeSignal = shake, onDone = { submit() },
        autofillTypes = listOf(AutofillType.NewPassword),
    )
    Spacer(Modifier.height(22.dp))
    LockInButton("Create account", onClick = { submit() }, modifier = Modifier.rise(entrance, 3), loading = loading, success = success)
}

@Composable
fun ColumnScope.SignInForm(
    email: String,
    onEmail: (String) -> Unit,
    repo: AuthRepository,
    entrance: State<Float>,
    rememberPhone: Boolean,
    onRememberPhone: (Boolean) -> Unit,
    onSignedIn: () -> Unit,
    onCodeSent: (CodePurpose) -> Unit,
    onGoSignUp: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }

    fun fail(message: String) {
        error = message
        shake += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun submit() {
        if (email.isBlank() || password.isEmpty()) {
            fail("Wrong email or password. Check both and try again.")
            return
        }
        error = null
        loading = true
        scope.launch {
            val result = repo.signIn(email.trim(), password, rememberPhone)
            loading = false
            when (result) {
                is AuthResult.SignedIn, is AuthResult.Success -> {
                    success = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(750)
                    onSignedIn()
                }
                is AuthResult.NeedsConfirmation -> {
                    // The account exists but was never confirmed: send a fresh code and go straight to it.
                    val sent = repo.sendCode(email.trim(), CodePurpose.SignUp)
                    if (sent is AuthResult.Failure) fail(sent.message) else onCodeSent(CodePurpose.SignUp)
                }
                is AuthResult.Failure -> fail(result.message)
            }
        }
    }

    /** "Forgot password?" needs the email that is already typed. */
    fun requestCode(purpose: CodePurpose) {
        if (!isEmail(email)) {
            fail("Enter your email first.")
            return
        }
        error = null
        sending = true
        scope.launch {
            val r = repo.sendCode(email.trim(), purpose)
            sending = false
            if (r is AuthResult.Failure) fail(r.message) else onCodeSent(purpose)
        }
    }

    val banner = error
    BannerSlot(banner)
    LockInField(
        "Email", email, { onEmail(it); error = null }, "you@example.com",
        modifier = Modifier.rise(entrance, 1),
        keyboardType = KeyboardType.Email, invalid = banner != null, shakeSignal = shake,
        autofillTypes = listOf(AutofillType.EmailAddress, AutofillType.Username),
    )
    Spacer(Modifier.height(14.dp))
    LockInField(
        "Password", password, { password = it; error = null }, "Your password",
        modifier = Modifier.rise(entrance, 2),
        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, isPassword = true,
        invalid = banner != null, shakeSignal = shake, onDone = { submit() },
        autofillTypes = listOf(AutofillType.Password),
    )
    Row(
        Modifier.fillMaxWidth().rise(entrance, 3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleRow("Remember this phone", rememberPhone, onRememberPhone)
        Text(
            if (sending) "Sending code\u2026" else "Forgot password?",
            style = LockInText.Sub.copy(fontSize = 14.sp, color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable(enabled = !sending, role = Role.Button) { requestCode(CodePurpose.Recovery) }.padding(vertical = 12.dp),
        )
    }
    ActionRow(
        "Sign in", onClick = { submit() }, onGoogle = { startGoogle(context, repo) },
        modifier = Modifier.rise(entrance, 4), loading = loading, success = success,
    )
    val keyboardUp = LocalKeyboardUp.current
    Collapse(!keyboardUp, Modifier.rise(entrance, 5)) {
        Column {
            Spacer(Modifier.height(8.dp))
            SwitchLine("New to LockIn?", "Create account", onClick = onGoSignUp)
        }
    }
}

/**
 * Enter the 6-digit code from the email. The same email also holds a link; if that link is broken
 * ("I already tapped the link") the password just typed signs in once the email is confirmed.
 */
@Composable
fun ColumnScope.CodePanel(
    email: String,
    purpose: CodePurpose,
    password: String,
    repo: AuthRepository,
    rememberPhone: Boolean,
    entrance: State<Float>,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboardUp = LocalKeyboardUp.current
    var code by rememberSaveable { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableIntStateOf(0) }
    var resending by remember { mutableStateOf(false) }
    var checkingLink by remember { mutableStateOf(false) }
    // A new email can only be requested every 30 seconds, which also stops accidental spamming.
    var cooldown by remember { mutableIntStateOf(30) }
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }
    val drift = rememberFloat()

    fun fail(message: String) {
        errorText = message
        shake += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun succeed() {
        success = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun verify(entered: String) {
        if (verifying || success) return
        errorText = null
        verifying = true
        scope.launch {
            val r = repo.verifyCode(email, entered, purpose, rememberPhone)
            verifying = false
            if (r is AuthResult.SignedIn) {
                succeed()
                delay(600)
                onVerified()
            } else {
                code = ""
                fail((r as? AuthResult.Failure)?.message ?: "That code is wrong or has expired. Request a new one.")
            }
        }
    }

    /** The link in the email confirms the account even when the page it opens fails to load. */
    fun linkAlreadyTapped() {
        if (checkingLink || success) return
        errorText = null
        checkingLink = true
        scope.launch {
            val r = repo.signIn(email, password, rememberPhone)
            checkingLink = false
            when (r) {
                is AuthResult.SignedIn, is AuthResult.Success -> {
                    succeed()
                    delay(600)
                    onVerified()
                }
                is AuthResult.NeedsConfirmation -> fail("Not confirmed yet. Enter the 6-digit code from the email.")
                is AuthResult.Failure -> fail(r.message)
            }
        }
    }

    Collapse(!keyboardUp, Modifier.rise(entrance, 1)) {
        Box(
            Modifier
                .padding(bottom = 18.dp)
                .graphicsLayer { translationY = drift.value * 4.dp.toPx() }
                .size(64.dp)
                .clip(CircleShape)
                .background(LockInColors.Graphite),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(30.dp)) {
                val k = size.width / 24f
                val style = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawRoundRect(
                    LockInColors.Snow, topLeft = Offset(3f * k, 5f * k), size = Size(18f * k, 14f * k),
                    cornerRadius = CornerRadius(2.5f * k, 2.5f * k), style = style,
                )
                val flap = Path().apply {
                    moveTo(3.5f * k, 7.5f * k)
                    lineTo(12f * k, 13.5f * k)
                    lineTo(20.5f * k, 7.5f * k)
                }
                drawPath(flap, LockInColors.Snow, style = style)
            }
        }
    }
    val intro = when (purpose) {
        CodePurpose.SignUp -> "We emailed a 6-digit code to $email. Enter it here, or tap the link in the same email."
        CodePurpose.Login -> "We emailed a 6-digit code to $email. Enter it to sign in."
        CodePurpose.Recovery -> "We emailed a 6-digit code to $email. Enter it to choose a new password."
    }
    Text(
        intro,
        style = LockInText.Lead,
        modifier = Modifier.rise(entrance, 2).widthIn(max = 340.dp),
    )
    Spacer(Modifier.height(20.dp))
    OtpField(
        value = code,
        onValueChange = { code = it; errorText = null },
        onComplete = { verify(it) },
        modifier = Modifier.rise(entrance, 3),
        hasError = errorText != null,
        shakeSignal = shake,
    )
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().height(40.dp).rise(entrance, 3), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Paste code",
            style = LockInText.Sub.copy(color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
            modifier = Modifier.clickable(role = Role.Button) {
                val digits = clipboard.getText()?.text.orEmpty().filter { it.isDigit() }.take(6)
                if (digits.length == 6) {
                    code = digits
                    errorText = null
                    verify(digits)
                } else {
                    toast(context, "No 6-digit code on the clipboard yet.")
                }
            },
        )
    }
    Collapse(errorText != null) {
        Text(errorText.orEmpty(), style = LockInText.Error, modifier = Modifier.padding(bottom = 6.dp))
    }
    Spacer(Modifier.height(8.dp))
    LockInButton(
        when (purpose) {
            CodePurpose.SignUp -> "Confirm email"
            CodePurpose.Login -> "Sign in"
            CodePurpose.Recovery -> "Continue"
        },
        onClick = { verify(code) },
        modifier = Modifier.rise(entrance, 4),
        loading = verifying,
        success = success,
        enabled = code.length == 6,
    )
    Collapse(!keyboardUp, Modifier.rise(entrance, 5)) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open email app",
                    style = LockInText.Sub.copy(color = LockInColors.Snow, textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(role = Role.Button) { openEmailApp(context) },
                )
                val locked = cooldown > 0 || resending
                Text(
                    when {
                        resending -> "Sending\u2026"
                        cooldown > 0 -> "Email me a new code (${cooldown}s)"
                        else -> "Email me a new code"
                    },
                    style = LockInText.Sub.copy(
                        color = if (locked) LockInColors.Mist else LockInColors.Snow,
                        textDecoration = if (locked) TextDecoration.None else TextDecoration.Underline,
                    ),
                    modifier = Modifier.clickable(enabled = !locked, role = Role.Button) {
                        resending = true
                        scope.launch {
                            val r = repo.sendCode(email, purpose)
                            resending = false
                            if (r is AuthResult.Failure) {
                                toast(context, r.message)
                            } else {
                                cooldown = 30
                                code = ""
                                errorText = null
                                toast(context, "New code sent. Use the 6 digits from the newest email.")
                            }
                        }
                    },
                )
            }
            if (purpose == CodePurpose.SignUp && password.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (checkingLink) "Checking\u2026" else "I already tapped the link",
                        style = LockInText.Sub.copy(color = LockInColors.Body, textDecoration = TextDecoration.Underline),
                        modifier = Modifier.clickable(enabled = !checkingLink, role = Role.Button) { linkAlreadyTapped() },
                    )
                }
            }
        }
    }
}

/** After a reset code: choose the new password. */
@Composable
fun ColumnScope.NewPasswordForm(
    repo: AuthRepository,
    rememberPhone: Boolean,
    entrance: State<Float>,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }

    fun fail() {
        shake += 1
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun submit() {
        if (password.length < 8) {
            error = "Use at least 8 characters."
            fail()
            return
        }
        error = null
        formError = null
        loading = true
        scope.launch {
            val r = repo.setNewPassword(password, rememberPhone)
            loading = false
            if (r is AuthResult.SignedIn) {
                success = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(700)
                onDone()
            } else {
                formError = (r as? AuthResult.Failure)?.message ?: "Something went wrong. Try again."
                fail()
            }
        }
    }

    BannerSlot(formError)
    Column(Modifier.rise(entrance, 1)) {
        LockInField(
            "New password", password, { password = it; error = null }, "At least 8 characters",
            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, isPassword = true,
            error = error, shakeSignal = shake, onDone = { submit() },
            autofillTypes = listOf(AutofillType.NewPassword),
        )
        PasswordMeter(password)
    }
    Spacer(Modifier.height(22.dp))
    LockInButton("Save password", onClick = { submit() }, modifier = Modifier.rise(entrance, 2), loading = loading, success = success)
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** Offered once, right after the first remembered sign-in on a phone that has a fingerprint or screen lock. */
@Composable
fun ColumnScope.BiometricOfferPanel(
    repo: AuthRepository,
    entrance: State<Float>,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val drift = rememberFloat()

    Box(
        Modifier
            .rise(entrance, 1)
            .graphicsLayer { translationY = drift.value * 4.dp.toPx() }
            .size(72.dp)
            .clip(CircleShape)
            .background(LockInColors.Graphite),
        contentAlignment = Alignment.Center,
    ) {
        FingerprintIcon(LockInColors.Snow, 36.dp)
    }
    Text(
        "Open LockIn with your fingerprint or screen lock instead of typing your password.",
        style = LockInText.Lead,
        modifier = Modifier.rise(entrance, 2).padding(top = 18.dp).widthIn(max = 340.dp),
    )
    Spacer(Modifier.height(24.dp))
    LockInButton(
        "Turn on",
        onClick = {
            val activity = context.findFragmentActivity()
            repo.markBiometricOffered()
            if (activity == null) {
                onDone()
            } else {
                Biometrics.prompt(activity, "Turn on quick sign-in", "Confirm it's you") { ok ->
                    if (ok) repo.setBiometric(true)
                    onDone()
                }
            }
        },
        modifier = Modifier.rise(entrance, 3),
    )
    Spacer(Modifier.height(10.dp))
    LockInButton(
        "Not now",
        onClick = {
            repo.markBiometricOffered()
            onDone()
        },
        modifier = Modifier.rise(entrance, 4),
        kind = ButtonKind.Outline,
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, LockInColors.Flare, ControlShape)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = message },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(20.dp).padding(top = 1.dp)) {
            val k = size.width / 24f
            val style = Stroke(width = 2f * k, cap = StrokeCap.Round)
            drawCircle(LockInColors.Flare, radius = 9.5f * k, center = Offset(12f * k, 12f * k), style = style)
            drawLine(LockInColors.Flare, Offset(12f * k, 7.5f * k), Offset(12f * k, 13f * k), strokeWidth = 2f * k, cap = StrokeCap.Round)
            drawCircle(LockInColors.Flare, radius = 1.1f * k, center = Offset(12f * k, 16.5f * k))
        }
        Text(message, style = LockInText.Error.copy(fontSize = 15.sp, lineHeight = 21.sp))
    }
}

private fun openEmailApp(context: Context) {
    try {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        toast(context, "No email app found on this phone.")
    }
}
