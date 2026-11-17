package com.godbless.lockin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Holds Home's entrance animations back while Home is built behind the welcome page.
 *
 * Signing in builds Home under the welcome page so it can load (who your partner is, today's minutes) while the
 * page says "getting your partner's news". Without a hold, Home's rows, greeting, number and bars all started
 * the moment Home was built: they played out hidden, or half-played by the time the welcome page moved away, and
 * the layout then jumped again when the data arrived. With it, every intro waits here and they all start on the
 * same frame as the move, so the welcome page and Home arrive as one piece.
 */
object EntranceGate {
    /** True from the start of a sign-in until the welcome page begins its move onto Home. */
    var held by mutableStateOf(false)

    /** Returns at once when nothing is held, else when the hold is let go. */
    suspend fun open() {
        if (held) snapshotFlow { held }.first { !it }
    }
}
