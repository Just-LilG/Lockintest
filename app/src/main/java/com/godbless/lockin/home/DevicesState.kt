package com.godbless.lockin.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.godbless.lockin.data.Device
import com.godbless.lockin.data.DeviceRepository
import com.godbless.lockin.data.ThisDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Your partner's devices (and how each holds a lock you set) and your own. */
internal class DevicesState(private val repo: DeviceRepository, private val context: Context, private val scope: CoroutineScope) {
    var partner by mutableStateOf<List<Device>>(emptyList())
        private set
    var mine by mutableStateOf<List<Device>>(emptyList())
        private set
    val thisId: String = ThisDevice.id(context)
    private var checkedInAt = 0L

    /** Checks this phone in (at most every 5 minutes) and fetches both lists. */
    suspend fun refresh(paired: Boolean) {
        val now = System.currentTimeMillis()
        if (now - checkedInAt > 5 * 60_000L) {
            repo.checkIn(context, com.godbless.lockin.BuildConfig.VERSION_NAME).onSuccess { checkedInAt = now }
        }
        repo.mine().onSuccess { mine = it }
        if (paired) repo.partner().onSuccess { partner = it } else partner = emptyList()
    }

    /** Removes one of your other devices from the list (it comes back if it opens LockIn again). */
    fun forget(id: String) {
        scope.launch {
            repo.forget(id)
            repo.mine().onSuccess { mine = it }
        }
    }
}
