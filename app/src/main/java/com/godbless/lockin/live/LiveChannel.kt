package com.godbless.lockin.live

import com.godbless.lockin.BuildConfig
import com.godbless.lockin.data.PartnerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Something my partner's phone sent over the live channel: [name] says what, [data] has the details. */
internal class LiveEvent(val name: String, val data: JSONObject)

/**
 * The live line between two partnered phones while a game is open: what the other person is holding, where their finger is,
 * the letters they are typing, and "I just moved" so the answer does not wait for the next refresh.
 *
 * It is Supabase Realtime (Phoenix channels over a WebSocket) on the private topic live:<partnership id>, which only the two
 * of you can join (see the migration 20261125_word_duel.sql). Nothing here is the truth of a game: that stays on the server's
 * functions. If this line can't be made, or drops, the games carry on over their normal refresh; [up] says whether it is there.
 *
 * [acquire] and [release] count the screens that want it, so one connection serves them all and it closes when the last one
 * has gone.
 */
internal class LiveChannel(
    private val topicOf: suspend () -> String?,
    private val tokenOf: suspend () -> String?,
    private val url: String,
) {
    constructor(partners: PartnerRepository) : this(
        { partners.liveTopic() },
        { partners.liveToken() },
        BuildConfig.SUPABASE_URL.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_KEY}&vsn=1.0.0",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val upNow = MutableStateFlow(false)

    /** True while the channel is joined and anything sent will reach my partner's phone, if it is open there. */
    val up: StateFlow<Boolean> = upNow

    private val incoming = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<LiveEvent> = incoming

    private val users = AtomicInteger(0)
    private var job: Job? = null
    private var lingering: Job? = null
    @Volatile private var ws: WebSocketLite? = null
    @Volatile private var topic: String? = null
    private val refs = AtomicInteger(1)

    @Synchronized
    fun acquire() {
        lingering?.cancel()
        lingering = null
        if (users.getAndIncrement() == 0 && job?.isActive != true) job = scope.launch { keepConnected() }
    }

    @Synchronized
    fun release() {
        if (users.decrementAndGet() > 0) return
        users.set(0)
        // A moment's grace: leaving one game screen for another should not drop the line.
        lingering = scope.launch {
            delay(4_000)
            stopNow()
        }
    }

    @Synchronized
    private fun stopNow() {
        if (users.get() > 0) return
        job?.cancel()
        job = null
        ws?.close()
        ws = null
        upNow.value = false
    }

    /** Sends [name] with [data] to my partner. False when the line is not up (nothing is queued: these are only for now). */
    fun send(name: String, data: JSONObject = JSONObject()): Boolean {
        val t = topic ?: return false
        val socket = ws ?: return false
        if (!upNow.value) return false
        val frame = JSONObject()
            .put("topic", "realtime:$t")
            .put("event", "broadcast")
            .put("payload", JSONObject().put("type", "broadcast").put("event", name).put("payload", data))
            .put("ref", refs.incrementAndGet().toString())
        return socket.send(frame.toString())
    }

    private suspend fun keepConnected() {
        var wait = 800L
        while (scope.isActive) {
            val joined = runCatching { oneConnection() }.getOrDefault(false)
            upNow.value = false
            if (joined) wait = 800L
            delay(wait)
            if (!joined) wait = (wait * 2).coerceAtMost(15_000L)
        }
    }

    /** One connection from start to end. True if it got as far as joining. */
    private suspend fun oneConnection(): Boolean {
        val t = topic ?: topicOf().also { topic = it }
        if (t == null) {
            // Not paired (yet): look again later, not every second.
            delay(20_000)
            return false
        }
        val token = tokenOf() ?: return false
        val closed = CompletableDeferred<Unit>()
        var joined = false
        lateinit var socket: WebSocketLite
        socket = WebSocketLite(url, idleMs = 55_000, listener = object : WebSocketLite.Listener {
            override fun onOpen() {
                val join = JSONObject()
                    .put("topic", "realtime:$t")
                    .put("event", "phx_join")
                    .put(
                        "payload",
                        JSONObject()
                            .put(
                                "config",
                                JSONObject()
                                    .put("broadcast", JSONObject().put("self", false).put("ack", false))
                                    .put("presence", JSONObject().put("key", ""))
                                    .put("private", true),
                            )
                            .put("access_token", token),
                    )
                    .put("ref", "1")
                    .put("join_ref", "1")
                socket.send(join.toString())
            }

            override fun onText(text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("event")) {
                    "phx_reply" -> if (m.optString("ref") == "1") {
                        val status = m.optJSONObject("payload")?.optString("status")
                        if (status == "ok") {
                            joined = true
                            ws = socket
                            upNow.value = true
                            incoming.tryEmit(LiveEvent(UP, JSONObject()))
                        } else {
                            socket.close()
                        }
                    }
                    "broadcast" -> {
                        val p = m.optJSONObject("payload") ?: return
                        val name = p.optString("event")
                        if (name.isNotEmpty()) incoming.tryEmit(LiveEvent(name, p.optJSONObject("payload") ?: JSONObject()))
                    }
                    "phx_close", "phx_error" -> socket.close()
                }
            }

            override fun onClosed(reason: String) {
                closed.complete(Unit)
            }
        })
        socket.connect()

        // Keeps the line alive and checks it is still there; the token is renewed by starting over after 45 minutes.
        val beat = scope.launch {
            while (isActive) {
                delay(25_000)
                socket.send(
                    JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject())
                        .put("ref", refs.incrementAndGet().toString()).toString(),
                )
            }
        }
        withTimeoutOrNull(45 * 60_000L) { closed.await() }
        beat.cancel()
        socket.close()
        if (ws === socket) ws = null
        return joined
    }

    companion object {
        /** Not from my partner: made when the line comes up, so a screen can say hello at once. */
        const val UP = "_up"

        /** The same kind of line for my crew (topic live:crew-<crew id>, which only the crew's members can join). */
        fun forCrew(partners: PartnerRepository) = LiveChannel(
            { partners.liveCrewTopic() },
            { partners.liveToken() },
            BuildConfig.SUPABASE_URL.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
                "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_KEY}&vsn=1.0.0",
        )
    }
}
