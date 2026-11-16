package com.godbless.lockin.data

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthResult {
    /** The step worked (for example a code was sent), but nobody is signed in yet. */
    data object Success : AuthResult

    /** A session now exists. */
    data object SignedIn : AuthResult

    /** The account exists but its email is not confirmed yet. */
    data class NeedsConfirmation(val email: String) : AuthResult

    data class Failure(val message: String) : AuthResult
}

/** Why a 6-digit code was requested. */
enum class CodePurpose { SignUp, Login, Recovery }

/** Who was signed in on this phone last time, if they asked to be remembered. */
data class SavedUser(val name: String, val email: String, val biometric: Boolean)

/** The screens talk to this and nothing else. */
interface AuthRepository {
    /** Counts sign-ins that arrived through the link in an email. Goes up by one each time. */
    val linkSignIns: StateFlow<Int>

    /** A message for when an email link or the Google sign-in came back with a problem. */
    val linkErrors: StateFlow<String?>
    fun clearLinkError()

    /** Starts Google sign-in. Returns the page to open in the browser; the result comes back through [handleLink]. */
    fun startGoogleSignIn(): String

    fun hasSession(): Boolean
    fun savedUser(): SavedUser?

    /** The signed-in person's name, or an empty string. */
    fun userName(): String

    /** Keeps a new name on this phone after it was saved on the server. */
    fun setUserName(name: String)

    /** The signed-in person's email, or an empty string. */
    fun userEmail(): String

    /** True if nobody has confirmed this email yet, false if it is taken, null if the check failed. */
    suspend fun emailAvailable(email: String): Boolean?

    suspend fun signUp(name: String, email: String, password: String): AuthResult
    suspend fun signIn(email: String, password: String, remember: Boolean): AuthResult
    suspend fun sendCode(email: String, purpose: CodePurpose): AuthResult
    suspend fun verifyCode(email: String, code: String, purpose: CodePurpose, remember: Boolean): AuthResult
    suspend fun setNewPassword(password: String, remember: Boolean): AuthResult

    /** Signs back in with the refresh token kept on this phone. */
    suspend fun restore(): AuthResult

    fun shouldOfferBiometric(): Boolean
    fun markBiometricOffered()
    fun setBiometric(enabled: Boolean)

    /** Handles the link from a confirmation email. Returns true if the link was ours. */
    fun handleLink(uri: Uri): Boolean

    /** The signed-in person's id, or null when nobody is signed in. */
    fun userId(): String?

    /**
     * A token for calling the backend as this person. Pass [forceRefresh] after the server said the
     * old one had expired. Returns null when nobody is signed in or the session could not be renewed.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    fun signOut()
}
