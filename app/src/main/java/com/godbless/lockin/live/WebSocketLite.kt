package com.godbless.lockin.live

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small WebSocket client (RFC 6455): text messages only, which is all the live channel needs, so the app does not carry a
 * networking library for it. One thread reads; [send] can be called from any thread. [Listener] methods run on the reading
 * thread, so they should return quickly.
 *
 * It ends (and says why through [Listener.onClosed], exactly once) when the server closes, the connection drops, nothing
 * arrives for [idleMs], or [close] is called.
 */
internal class WebSocketLite(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val idleMs: Int = 50_000,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
    }

    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var closedReported = false
    @Volatile private var open = false
    private val random = SecureRandom()

    val isOpen: Boolean get() = open

    /** Connects on a background thread; the outcome comes through the listener. */
    fun connect() {
        Thread({ run() }, "live-ws").apply { isDaemon = true }.start()
    }

    /** Sockets can't be written to on the main thread, and callers (game taps) are on it: frames go through one writer thread, in order. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "live-ws-write").apply { isDaemon = true } }

    /** Queues [text] to go out. True if it was queued (the line was up); a failed write closes the line. */
    fun send(text: String): Boolean {
        if (!open) return false
        return try {
            writer.execute {
                if (!open) return@execute
                try {
                    writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
                } catch (e: IOException) {
                    shutdown("send failed")
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    fun close() {
        val wasOpen = open
        open = false
        runCatching {
            writer.execute {
                if (wasOpen) runCatching { writeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
                shutdown("closed")
            }
        }.onFailure { shutdown("closed") }
    }

    private fun shutdown(reason: String) {
        open = false
        runCatching { socket?.close() }
        runCatching { writer.shutdown() }
        report(reason)
    }

    private fun report(reason: String) {
        if (closedReported) return
        closedReported = true
        runCatching { listener.onClosed(reason) }
    }

    private fun run() {
        try {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val host = uri.host ?: throw IOException("no host")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val target = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" }.orEmpty())

            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), 12_000)
            val s: Socket = if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) throw IOException("certificate does not match $host")
                ssl
            } else plain
            socket = s
            s.soTimeout = 12_000
            val input = BufferedInputStream(s.getInputStream(), 8192)
            out = s.getOutputStream()

            // The upgrade request.
            val keyBytes = ByteArray(16).also { random.nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val req = StringBuilder()
                .append("GET ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(if (uri.port > 0) ":${uri.port}" else "").append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
            headers.forEach { (k, v) -> req.append(k).append(": ").append(v).append("\r\n") }
            req.append("\r\n")
            synchronized(writeLock) {
                out!!.write(req.toString().toByteArray(Charsets.ISO_8859_1))
                out!!.flush()
            }

            // Its answer: 101, and the key proved back.
            val status = readLine(input)
            if (!status.contains(" 101")) throw IOException("upgrade refused: ${status.take(60)}")
            var accept = ""
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) accept = line.substring(i + 1).trim()
            }
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)),
            )
            if (accept != expected) throw IOException("bad handshake")

            s.soTimeout = idleMs
            open = true
            listener.onOpen()
            readLoop(input)
        } catch (e: SocketTimeoutException) {
            shutdown("timed out")
        } catch (e: Exception) {
            shutdown(e.message ?: e.javaClass.simpleName)
        }
        shutdown("ended")
    }

    private fun readLoop(input: InputStream) {
        val message = ByteArrayOutputStream()
        var messageIsText = false
        while (open) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("closed by the server")
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0F
            val b1 = readByte(input)
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            }
            if (len < 0 || len > MAX_MESSAGE) throw IOException("message too large")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(len.toInt())
            readFully(input, payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                OP_TEXT, OP_BINARY, OP_CONT -> {
                    if (op != OP_CONT) {
                        message.reset()
                        messageIsText = op == OP_TEXT
                    }
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("message too large")
                    if (fin) {
                        if (messageIsText) listener.onText(message.toString(Charsets.UTF_8.name()))
                        message.reset()
                    }
                }
                OP_PING -> runCatching { writeFrame(OP_PONG, payload) }
                OP_PONG -> {}
                OP_CLOSE -> {
                    runCatching { writeFrame(OP_CLOSE, payload.copyOf(minOf(payload.size, 2))) }
                    throw EOFException("closed by the server")
                }
                else -> throw IOException("unknown frame $op")
            }
        }
    }

    private fun writeFrame(op: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or op)
        val n = payload.size
        when {
            n < 126 -> frame.write(0x80 or n)
            n < 65536 -> { frame.write(0x80 or 126); frame.write(n ushr 8); frame.write(n and 0xFF) }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write(((n.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        frame.write(mask)
        val masked = ByteArray(n) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        frame.write(masked)
        synchronized(writeLock) {
            val o = out ?: throw IOException("not connected")
            o.write(frame.toByteArray())
            o.flush()
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("closed during the handshake")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("header too long")
        }
        return sb.toString()
    }

    private fun readByte(input: InputStream): Int {
        val c = input.read()
        if (c < 0) throw EOFException("closed by the server")
        return c
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("closed by the server")
            off += n
        }
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONT = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        const val MAX_MESSAGE = 1_000_000L
    }
}
