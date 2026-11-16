package com.godbless.lockin.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.PartnerUsage
import com.godbless.lockin.data.UsageRepository
import com.godbless.lockin.lock.LockGuard
import com.godbless.lockin.usage.Guard
import com.godbless.lockin.usage.Limit
import com.godbless.lockin.usage.LimitStore
import com.godbless.lockin.usage.UsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App times and limits between you and your partner: what they share, what you share, the limits on
 * your own phone, and the switches. It also tells the background guard what to do.
 */
internal class UsageState(
    private val repo: UsageRepository,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** Your partner's app times and the limits you set on them. Null until the first answer. */
    var partner by mutableStateOf<PartnerUsage?>(null)
        private set

    /** Whether you share your own app times. On until you switch it off. */
    @set:JvmName("assignSharing") // keeps this setter from clashing with setSharing(on) below
    var sharing by mutableStateOf(true)
        private set

    /** Whether LockIn may read app times on this phone (the "usage access" switch in Settings). */
    var access by mutableStateOf(UsageAccess.granted(context))
        private set

    /** Whether LockIn may show its block screen over other apps. */
    var overlay by mutableStateOf(LockGuard(context).canOverlay())
        private set

    /** Whether LockIn is device owner here, so it can switch a limited app off completely. */
    val owner: Boolean get() = LockGuard(context).isOwner()

    /** Limits your partner set on this phone. */
    var limitsOnMe by mutableStateOf<List<Limit>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private val store = LimitStore(context)
    private var paired = false
    private var toldServer: Boolean? = null

    /** Re-reads the permission, which the person may have just changed in Settings, and tells the server. */
    suspend fun checkAccess() {
        val now = UsageAccess.granted(context)
        access = now
        overlay = LockGuard(context).canOverlay()
        if (toldServer != now) repo.setAccess(now).onSuccess { toldServer = now }
        applyGuard()
    }

    /** Fetches everything. Called when the app opens and every half minute while it is on screen. */
    suspend fun refresh(isPaired: Boolean) {
        paired = isPaired
        if (!isPaired) {
            partner = null
            limitsOnMe = emptyList()
            store.save(emptyList())
            applyGuard()
            return
        }
        repo.mine().onSuccess { sharing = it.sharing }
        repo.partner().onSuccess { partner = it }
        repo.myLimits().onSuccess {
            limitsOnMe = it
            store.save(it)
        }
        checkAccess()
    }

    /** Tells the background service what it has to do now: report app times, hold limits, or nothing. */
    private fun applyGuard() {
        store.reporting = paired && sharing
        Guard.sync(context)
    }

    fun setSharing(on: Boolean) {
        val before = sharing
        sharing = on
        applyGuard()
        scope.launch {
            repo.setSharing(on).onFailure {
                sharing = before
                error = it.message
                applyGuard()
            }
        }
    }

    fun setLimit(pkg: String, seconds: Int, done: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            repo.setLimit(pkg, seconds)
                .onSuccess {
                    repo.partner().onSuccess { partner = it }
                    done()
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun removeLimit(pkg: String, done: () -> Unit = {}) {
        busy = true
        error = null
        scope.launch {
            repo.removeLimit(pkg)
                .onSuccess {
                    repo.partner().onSuccess { partner = it }
                    done()
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun limitOf(pkg: String) = partner?.limits?.firstOrNull { it.pkg == pkg }
}
