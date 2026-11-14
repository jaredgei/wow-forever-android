package app.gamenative.ui.screen.wow.auth

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * One browser-authentication attempt. Run blocking operations off the UI thread; [close] cancels IO.
 *
 * Protocol adapted from Scuffle (MIT, Copyright 2026 nyrda), auth/bgs.py at
 * https://github.com/nyrda/scuffle/tree/5efccb44777bba7e28e70ef782564b713e7373b5
 * A fresh game credential is issued only after a successful LogonComplete notification. This client
 * does not persist tickets, replay a consumed ticket, or fall back to password authentication.
 */
class BgsClient internal constructor(
    private val transport: BgsTransport,
    private val platform: String = "Wn64",
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    constructor(host: String, platform: String = "Wn64") : this(TlsBgsTransport(host), platform)

    private enum class State { NEW, CONNECTED, CHALLENGE, AUTHENTICATED, FINISHED, CLOSED }
    @Volatile private var state = State.NEW
    private var token = 0L
    private var clientId: ByteArray? = null
    private val pending = ArrayDeque<Packet>()
    private var queueStarted: Long? = null

    init {
        require(platform == "Wn64" || platform == "WinA") { "Unsupported Battle.net platform." }
    }

    fun connect() = safe {
        checkState(State.NEW)
        transport.connect()
        val version = BgsProtocol.integer(1, 8) + BgsProtocol.integer(2, 1) +
            BgsProtocol.integer(3, 0) + BgsProtocol.text(4, "8.1.0")
        val protocol = BgsProtocol.integer(1, 3) + BgsProtocol.integer(2, 18) +
            BgsProtocol.integer(3, 2) + BgsProtocol.text(4, "3.18.2-dd0d4dc339")
        val sdk = BgsProtocol.text(1, "BGS C++ SDK") + BgsProtocol.bytes(2, version) + BgsProtocol.bytes(3, protocol)
        val response = call(CONNECTION_SERVICE, 1, BgsProtocol.integer(3, 1) + BgsProtocol.bytes(5, sdk))
        clientId = response.bytes(9)?.also { if (it.size > 4096) protocolError() }
        state = State.CONNECTED
    }

    /** Obtain the service's sign-in URL. Close this connection while the person uses the browser. */
    fun webChallenge(device: ByteArray, build: Int): String = safe {
        checkState(State.CONNECTED)
        if (device.isEmpty() || device.size > 8192 || build <= 0) {
            throw BgsException("The installed client or device identity is unavailable.")
        }
        val options = BgsProtocol.bytes(3, device)
        val request = BgsProtocol.integer(1, WOW_TITLE) + BgsProtocol.text(2, platform) +
            BgsProtocol.text(3, "enUS") + BgsProtocol.integer(4, build.toLong()) + BgsProtocol.bytes(10, options)
        call(AUTHENTICATION_SERVICE, 1, request)
        var deadline = afterSeconds(30)
        repeat(MAX_NOTIFICATIONS) {
            val packet = notification(deadline)
            if (isQueue(packet)) {
                deadline = queueDeadline()
            } else when (packet.method) {
                4L -> {
                    if (!packet.body.bytes(2).contentEquals("web_auth_url".toByteArray())) {
                        throw BgsException("Battle.net requires an unsupported sign-in challenge.")
                    }
                    val raw = packet.body.bytes(3) ?: protocolError()
                    if (raw.size > 8192) protocolError()
                    val url = raw.toString(Charsets.UTF_8)
                    if (!isTrustedSignInUrl(url)) throw BgsException("Battle.net returned an unsupported sign-in address.")
                    state = State.CHALLENGE
                    return@safe url
                }
                1L -> throwLogonFailure(packet.body)
                else -> protocolError()
            }
        }
        protocolError()
    }

    /**
     * On a newly connected client call [webChallenge] again, then pass the browser's ST ticket here.
     * The ticket is consumed once. The returned credential is for this game launch only.
     */
    fun verifyAndGenerateGameToken(ticket: ByteArray): ByteArray = safe {
        checkState(State.CHALLENGE)
        if (ticket.isEmpty() || ticket.size > 2048) throw BgsException("The browser sign-in ticket is invalid.")
        call(AUTHENTICATION_SERVICE, 2, BgsProtocol.bytes(1, ticket))
        var deadline = afterSeconds(30)
        var authenticated = false
        for (index in 0 until MAX_NOTIFICATIONS) {
            val packet = notification(deadline)
            if (isQueue(packet)) {
                deadline = queueDeadline()
                continue
            }
            if (packet.method == 4L) throw BgsException("Battle.net did not accept the sign-in. Sign in again.")
            if (packet.method != 1L) protocolError()
            val status = packet.body.integer(1)
            if (status != 0L) reject(status)
            val record = packet.body.bytes(2)
            if (record == null || record.isEmpty()) protocolError()
            BgsProtocol.parse(record)
            authenticated = true
            break
        }
        if (!authenticated) protocolError()
        state = State.AUTHENTICATED
        val credential = call(AUTHENTICATION_SERVICE, 3, BgsProtocol.integer(1, WOW_TITLE)).bytes(1)
        if (credential == null || credential.isEmpty() || credential.size > 2048) protocolError()
        state = State.FINISHED
        credential
    }

    override fun close() {
        state = State.CLOSED
        // Do not lock: cancellation must interrupt a blocked read/handshake from another thread.
        transport.close()
    }

    private fun checkState(expected: State) {
        if (state != expected) throw BgsException("The Battle.net sign-in attempt is no longer active.")
    }

    private inline fun <T> safe(action: () -> T): T = try {
        action()
    } catch (error: BgsException) {
        close()
        throw error
    } catch (_: IOException) {
        close()
        // In particular do not surface TLS/IO response text or secret-bearing request contents.
        throw BgsException("The Battle.net connection ended. Check your connection and try again.")
    }

    private fun call(service: Long, method: Long, body: ByteArray): BgsProtocol.Message {
        val requestToken = ++token
        var header = BgsProtocol.integer(1, 0) + BgsProtocol.integer(2, method) +
            BgsProtocol.integer(3, requestToken) + BgsProtocol.integer(5, body.size.toLong()) +
            BgsProtocol.fixed32(11, service)
        clientId?.let { header += BgsProtocol.bytes(13, it) }
        val output = transport.output
        output.write(header.size ushr 8)
        output.write(header.size and 0xff)
        output.write(header)
        output.write(body)
        output.flush()
        val deadline = afterSeconds(30)
        repeat(64) {
            val packet = readWire(deadline)
            if (packet.serviceId == RESPONSE_SERVICE) {
                if (packet.token != requestToken || (packet.method != 0L && packet.method != method) ||
                    (packet.serviceHash != 0L && packet.serviceHash != service)
                ) protocolError()
                if (packet.status != 0L) reject(packet.status)
                return packet.body
            }
            if (packet.serviceHash == AUTHENTICATION_LISTENER) {
                if (pending.size >= 64) protocolError()
                pending.addLast(packet)
            } else if (packet.serviceHash == CONNECTION_SERVICE && packet.method == 4L) {
                throw BgsException("Battle.net closed the sign-in connection. Try again.")
            }
            // Unrelated listener events cannot complete an authentication RPC or change its state.
        }
        protocolError()
    }

    private fun notification(deadline: Long): Packet {
        repeat(64) {
            val packet = if (pending.isNotEmpty()) pending.removeFirst() else readWire(deadline)
            if (packet.serviceId == RESPONSE_SERVICE) protocolError()
            if (packet.serviceHash == AUTHENTICATION_LISTENER) return packet
            if (packet.serviceHash == CONNECTION_SERVICE && packet.method == 4L) {
                throw BgsException("Battle.net closed the sign-in connection. Try again.")
            }
        }
        protocolError()
    }

    private fun isQueue(packet: Packet): Boolean = packet.method == 2L || packet.method == 3L

    private fun queueDeadline(): Long {
        val now = nanoTime()
        if (queueStarted == null) queueStarted = now
        val end = queueStarted!! + 30L * 60 * 1_000_000_000
        if (now >= end) throw BgsException("Battle.net's login queue took too long. Try again later.")
        return min(end, now + 120L * 1_000_000_000)
    }

    private fun afterSeconds(seconds: Int): Long = nanoTime() + seconds.toLong() * 1_000_000_000

    private fun readWire(deadline: Long): Packet {
        fun exact(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val remaining = deadline - nanoTime()
                if (remaining <= 0) throw BgsException("Battle.net did not respond in time. Try again.")
                transport.setReadTimeout(min(120_000L, (remaining / 1_000_000).coerceAtLeast(1)).toInt())
                val count = transport.input.read(bytes, offset, size - offset)
                if (count <= 0) throw IOException("Connection ended")
                offset += count
            }
            return bytes
        }
        val prefix = exact(2)
        val headerSize = ((prefix[0].toInt() and 0xff) shl 8) or (prefix[1].toInt() and 0xff)
        if (headerSize !in 1..8192) protocolError()
        val header = BgsProtocol.parse(exact(headerSize))
        val bodySize = header.integer(5)
        if (bodySize < 0 || bodySize > BgsProtocol.MAX_MESSAGE_BYTES) protocolError()
        return Packet(
            header.integer(1), header.fixed32(11), header.integer(2), header.integer(3), header.integer(6),
            BgsProtocol.parse(exact(bodySize.toInt())),
        )
    }

    private fun throwLogonFailure(body: BgsProtocol.Message): Nothing {
        val status = body.integer(1)
        if (status != 0L) reject(status)
        throw BgsException("Battle.net did not offer a browser sign-in.")
    }

    private fun reject(status: Long): Nothing {
        if (status == 33L) throw BgsException("Battle.net is not accepting this game build yet. Try again later.", status)
        throw BgsException("Battle.net rejected the sign-in (status $status).", status)
    }

    private fun protocolError(): Nothing = throw BgsException("Battle.net returned an invalid protocol message.")

    private data class Packet(
        val serviceId: Long, val serviceHash: Long, val method: Long, val token: Long, val status: Long,
        val body: BgsProtocol.Message,
    )

    companion object {
        internal const val CONNECTION_SERVICE = 0x65446991L
        internal const val AUTHENTICATION_SERVICE = 0xc02f8216L
        // FNV-1a of bnet.protocol.authentication.v2.client.AuthenticationListener.
        internal const val AUTHENTICATION_LISTENER = 0x9da8116bL
        internal const val RESPONSE_SERVICE = 254L
        internal const val WOW_TITLE = 0x576f57L
        private const val MAX_NOTIFICATIONS = 4096

        fun isTrustedSignInUrl(url: String): Boolean = try {
            val parsed = URI(url)
            val host = parsed.host?.lowercase(Locale.ROOT)
            parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.port in listOf(-1, 443) &&
                host != null && (host == "battle.net" || host.endsWith(".battle.net")) &&
                parsed.rawFragment == null
        } catch (_: Exception) {
            false
        }
    }
}

internal interface BgsTransport : Closeable {
    val input: InputStream
    val output: OutputStream
    fun connect()
    fun setReadTimeout(milliseconds: Int)
}

private class TlsBgsTransport(private val host: String) : BgsTransport {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    init {
        require(Regex("[A-Za-z0-9.-]+\\.battle\\.net").matches(host)) { "Unsupported Battle.net endpoint." }
    }

    override fun connect() {
        if (closed) throw IOException("Closed")
        val raw = Socket()
        socket = raw
        if (closed) { raw.close(); throw IOException("Closed") }
        raw.connect(InetSocketAddress(host, 1119), 10_000)
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 1119, true) as SSLSocket
        socket = tls
        if (closed) { tls.close(); throw IOException("Closed") }
        tls.soTimeout = 15_000
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
    }

    override val input: InputStream get() = socket?.getInputStream() ?: throw IOException("Not connected")
    override val output: OutputStream get() = socket?.getOutputStream() ?: throw IOException("Not connected")
    override fun setReadTimeout(milliseconds: Int) { socket?.soTimeout = milliseconds }
    override fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) { }
    }
}
