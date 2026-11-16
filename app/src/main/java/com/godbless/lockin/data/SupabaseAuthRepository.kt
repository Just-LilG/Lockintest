package com.godbless.lockin.data

import android.content.Context
import android.net.Uri
import com.godbless.lockin.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Talks to Supabase Auth over its plain HTTPS API, so the app needs no extra libraries.
 * Tokens are only written to disk (encrypted) when the person asks to be remembered.
 */
class SupabaseAuthRepository(context: Context) : AuthRepository {
    private val store = SecureStore(context.applicationContext).also { it.warmUp() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: Session? = null
    private val renewLock = Mutex()

    private val linkCounter = MutableStateFlow(0)
    override val linkSignIns: StateFlow<Int> = linkCounter

    private val linkError = MutableStateFlow<String?>(null)
    override val linkErrors: StateFlow<String?> = linkError
    override fun clearLinkError() {
        linkError.value = null
    }

    private class Session(val access: String, val refresh: String, val name: String, val email: String)

    private class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject? = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    // ---- Plumbing ---------------------------------------------------------------------------

    /** Returns null when the phone could not reach Supabase at all. */
    private suspend fun request(method: String, path: String, body: JSONObject? = null, bearer: String? = null): Resp? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 12_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                conn.setRequestProperty("Content-Type", "application/json")
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Resp(code, text)
            } catch (e: IOException) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private val offline = AuthResult.Failure("No connection. Check your internet and try again.")

    private fun failure(r: Resp, forCode: Boolean = false): AuthResult.Failure {
        val j = r.json()
        val code = j?.optString("error_code").orEmpty().ifEmpty { j?.optString("code").orEmpty() }
        val msg = j?.optString("msg").orEmpty()
            .ifEmpty { j?.optString("message").orEmpty() }
            .ifEmpty { j?.optString("error_description").orEmpty() }
        val text = when {
            code == "invalid_credentials" -> "Wrong email or password. Check both and try again."
            code == "user_already_exists" || code == "email_exists" -> "That email already has an account. Sign in instead."
            code == "weak_password" -> "Choose a stronger password."
            code == "otp_expired" || (forCode && r.code in 400..422) -> "That code is wrong or has expired. Request a new one."
            code.contains("rate_limit") || r.code == 429 -> "Too many emails right now. Wait a minute and try again."
            msg.isNotEmpty() -> msg
            else -> "Something went wrong. Try again."
        }
        return AuthResult.Failure(text)
    }

    private fun errorCode(r: Resp): String {
        val j = r.json()
        return j?.optString("error_code").orEmpty().ifEmpty { j?.optString("code").orEmpty() }
    }

    private fun parseSession(j: JSONObject, fallbackEmail: String, fallbackName: String = ""): Session? {
        val access = j.optString("access_token")
        val refresh = j.optString("refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) return null
        val user = j.optJSONObject("user")
        val email = user?.optString("email").orEmpty().ifEmpty { fallbackEmail }
        val meta = user?.optJSONObject("user_metadata")
        val name = meta?.optString("name").orEmpty()
            .ifEmpty { meta?.optString("full_name").orEmpty() }
            .ifEmpty { fallbackName }
            .ifEmpty { email.substringBefore('@') }
        return Session(access, refresh, name, email)
    }

    private fun finish(s: Session, remember: Boolean): AuthResult {
        session = s
        if (remember) {
            store.put(K_REFRESH, s.refresh)
            store.put(K_NAME, s.name)
            store.put(K_EMAIL, s.email)
        } else {
            store.remove(K_REFRESH)
            store.remove(K_NAME)
            store.remove(K_EMAIL)
            store.remove(K_BIO)
        }
        return AuthResult.SignedIn
    }

    // ---- State ------------------------------------------------------------------------------

    override fun hasSession(): Boolean = session != null

    override fun savedUser(): SavedUser? {
        if (store.get(K_REFRESH) == null) return null
        return SavedUser(
            name = store.get(K_NAME).orEmpty(),
            email = store.get(K_EMAIL).orEmpty(),
            biometric = store.get(K_BIO) == "1",
        )
    }

    override fun userName(): String = session?.name ?: store.get(K_NAME).orEmpty()

    override fun setUserName(name: String) {
        session = session?.let { Session(it.access, it.refresh, name, it.email) }
        if (store.get(K_REFRESH) != null) store.put(K_NAME, name)
    }

    override fun userEmail(): String = session?.email ?: store.get(K_EMAIL).orEmpty()

    override fun shouldOfferBiometric(): Boolean =
        store.get(K_REFRESH) != null && store.get(K_BIO_ASKED) == null

    override fun markBiometricOffered() = store.put(K_BIO_ASKED, "1")

    override fun setBiometric(enabled: Boolean) = store.put(K_BIO, if (enabled) "1" else "0")

    override fun userId(): String? {
        val token = session?.access ?: return null
        return try {
            val payload = token.split('.')[1]
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            JSONObject(json).optString("sub").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        val before = session ?: return null
        if (!forceRefresh) return before.access
        return renewLock.withLock {
            val now = session ?: return@withLock null
            // Someone else may have renewed while we waited for the lock.
            if (now.access != before.access) return@withLock now.access
            val r = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", now.refresh))
                ?: return@withLock null
            if (!r.ok) return@withLock null
            val fresh = r.json()?.let { parseSession(it, now.email, now.name) } ?: return@withLock null
            session = fresh
            // Refresh tokens are single use, so a remembered one has to be replaced.
            if (store.get(K_REFRESH) != null) store.put(K_REFRESH, fresh.refresh)
            fresh.access
        }
    }

    override fun signOut() {
        val s = session
        session = null
        store.remove(K_REFRESH)
        store.remove(K_NAME)
        store.remove(K_EMAIL)
        store.remove(K_BIO)
        if (s != null) scope.launch { request("POST", "/auth/v1/logout", null, s.access) }
    }

    // ---- Calls ------------------------------------------------------------------------------

    override suspend fun emailAvailable(email: String): Boolean? {
        val r = request("POST", "/rest/v1/rpc/check_email_available", JSONObject().put("p_email", email.trim())) ?: return null
        if (!r.ok) return null
        return when (r.body.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    override suspend fun signUp(name: String, email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("data", JSONObject().put("name", name))
        val r = request("POST", "/auth/v1/signup?redirect_to=$REDIRECT_ENCODED", body) ?: return offline
        if (!r.ok) return failure(r)
        val j = r.json() ?: return AuthResult.Failure("Something went wrong. Try again.")
        parseSession(j, email, name)?.let { return finish(it, true) }
        // With email confirmation on, an already-confirmed address comes back with no identities.
        val identities = j.optJSONArray("identities") ?: j.optJSONObject("user")?.optJSONArray("identities")
        if (identities != null && identities.length() == 0) {
            return AuthResult.Failure("That email already has an account. Sign in instead.")
        }
        return AuthResult.Success
    }

    override suspend fun signIn(email: String, password: String, remember: Boolean): AuthResult {
        val body = JSONObject().put("email", email).put("password", password)
        val r = request("POST", "/auth/v1/token?grant_type=password", body) ?: return offline
        if (!r.ok) {
            if (errorCode(r) == "email_not_confirmed") return AuthResult.NeedsConfirmation(email)
            return failure(r)
        }
        val s = r.json()?.let { parseSession(it, email) } ?: return AuthResult.Failure("Something went wrong. Try again.")
        return finish(s, remember)
    }

    override suspend fun sendCode(email: String, purpose: CodePurpose): AuthResult {
        val r = when (purpose) {
            CodePurpose.SignUp -> request(
                "POST", "/auth/v1/resend?redirect_to=$REDIRECT_ENCODED",
                JSONObject().put("type", "signup").put("email", email),
            )
            CodePurpose.Login -> request(
                "POST", "/auth/v1/otp?redirect_to=$REDIRECT_ENCODED",
                JSONObject().put("email", email).put("create_user", false),
            )
            CodePurpose.Recovery -> request(
                "POST", "/auth/v1/recover?redirect_to=$REDIRECT_ENCODED",
                JSONObject().put("email", email),
            )
        } ?: return offline
        if (r.ok) return AuthResult.Success
        // For sign-in and reset we do not reveal whether an address has an account.
        if (purpose != CodePurpose.SignUp && r.code in 400..404) return AuthResult.Success
        return failure(r)
    }

    override suspend fun verifyCode(email: String, code: String, purpose: CodePurpose, remember: Boolean): AuthResult {
        // An address that was never confirmed gets a "signup" code, a known one an "email" code.
        // Which one an email holds depends on the account, so the second type is the fallback.
        val types = when (purpose) {
            CodePurpose.SignUp -> listOf("signup", "email")
            CodePurpose.Login -> listOf("email", "signup")
            CodePurpose.Recovery -> listOf("recovery")
        }
        var last: Resp? = null
        for (type in types) {
            val body = JSONObject().put("type", type).put("email", email).put("token", code)
            val r = request("POST", "/auth/v1/verify", body) ?: return offline
            if (r.ok) {
                val s = r.json()?.let { parseSession(it, email) }
                    ?: return AuthResult.Failure("That code is wrong or has expired. Request a new one.")
                // After a reset code the session is only kept once a new password is saved.
                return finish(s, remember && purpose != CodePurpose.Recovery)
            }
            last = r
        }
        return failure(last!!, forCode = true)
    }

    override suspend fun setNewPassword(password: String, remember: Boolean): AuthResult {
        val s = session ?: return AuthResult.Failure("Your reset session ended. Start again from Sign in.")
        val r = request("PUT", "/auth/v1/user", JSONObject().put("password", password), s.access) ?: return offline
        if (!r.ok) return failure(r)
        return finish(s, remember)
    }

    override suspend fun restore(): AuthResult = renewLock.withLock {
        // The background service may have signed back in already. A refresh token works once, so don't use it twice.
        session?.let { return@withLock AuthResult.SignedIn }
        val refresh = store.get(K_REFRESH) ?: return@withLock AuthResult.Failure("Sign in again to continue.")
        val r = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh)) ?: return@withLock offline
        if (!r.ok) {
            store.remove(K_REFRESH)
            return@withLock AuthResult.Failure("Your session ended. Sign in again.")
        }
        val email = store.get(K_EMAIL).orEmpty()
        val s = r.json()?.let { parseSession(it, email, store.get(K_NAME).orEmpty()) }
            ?: return@withLock AuthResult.Failure("Your session ended. Sign in again.")
        finish(s, true)
    }

    /**
     * Google sign-in uses the browser plus PKCE: the app keeps a secret (the verifier), sends only its
     * hash, and gets back a one-time code that is worthless to any other app that sees the link.
     */
    override fun startGoogleSignIn(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val verifier = Base64.encodeToString(bytes, flags)
        store.put(K_PKCE, verifier)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            flags,
        )
        return BuildConfig.SUPABASE_URL + "/auth/v1/authorize?provider=google" +
            "&redirect_to=$REDIRECT_ENCODED&code_challenge=$challenge&code_challenge_method=s256"
    }

    private suspend fun exchangeCode(code: String) {
        val verifier = store.get(K_PKCE)
        store.remove(K_PKCE)
        if (verifier == null) {
            linkError.value = "Google sign-in expired. Try again."
            return
        }
        val r = request(
            "POST", "/auth/v1/token?grant_type=pkce",
            JSONObject().put("auth_code", code).put("code_verifier", verifier),
        )
        if (r == null) {
            linkError.value = offline.message
            return
        }
        val s = if (r.ok) r.json()?.let { parseSession(it, "") } else null
        if (s == null) {
            linkError.value = "Google sign-in didn't finish. Try again."
            return
        }
        finish(s, true)
        linkCounter.value = linkCounter.value + 1
    }

    override fun handleLink(uri: Uri): Boolean {
        if (uri.scheme != "lockin" || uri.host != "auth") return false
        val raw = listOfNotNull(uri.query, uri.fragment).joinToString("&")
        if (raw.isEmpty()) return true
        val params = HashMap<String, String>()
        for (part in raw.split("&")) {
            val i = part.indexOf('=')
            if (i > 0) {
                params[URLDecoder.decode(part.substring(0, i), "UTF-8")] = URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
        }
        if (params["error"] != null || params["error_description"] != null) {
            store.remove(K_PKCE)
            linkError.value = if (params["error"] == "access_denied") "Sign-in was cancelled."
            else params["error_description"] ?: "That link didn't work. Try again."
            return true
        }
        params["code"]?.let { code ->
            scope.launch { exchangeCode(code) }
            return true
        }
        val access = params["access_token"]
        val refresh = params["refresh_token"]
        if (access == null || refresh == null) return true
        scope.launch {
            val j = request("GET", "/auth/v1/user", null, access)?.takeIf { it.ok }?.json()
            val email = j?.optString("email").orEmpty()
            val name = j?.optJSONObject("user_metadata")?.optString("name").orEmpty().ifEmpty { email.substringBefore('@') }
            finish(Session(access, refresh, name, email), true)
            linkCounter.value = linkCounter.value + 1
        }
        return true
    }

    private companion object {
        const val K_REFRESH = "refresh_token"
        const val K_NAME = "user_name"
        const val K_EMAIL = "user_email"
        const val K_BIO = "biometric"
        const val K_BIO_ASKED = "biometric_asked"
        const val K_PKCE = "pkce_verifier"
        const val REDIRECT = "lockin://auth/callback"
        val REDIRECT_ENCODED: String = Uri.encode(REDIRECT)
    }
}
