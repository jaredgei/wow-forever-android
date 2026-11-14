package app.gamenative.ui.screen.wow.auth

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/** A private launch capability authenticates both ends before any credential crosses loopback. */
internal class SessionBridge : Closeable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 180_000
    }
    private val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val config: String get() = "${listener.localPort}\n${nonce.joinToString("") { "%02x".format(it) }}\n"
    @Volatile private var candidate: Socket? = null
    @Volatile private var closed = false

    fun accept(): Peer {
        // Unauthenticated peers cannot hold the listener indefinitely or receive the nonce.
        repeat(16) {
            val socket = listener.accept()
            candidate = socket
            if (closed) { socket.close(); throw IOException("Bridge closed") }
            socket.soTimeout = 2_000
            try {
                val input = DataInputStream(socket.getInputStream())
                val received = ByteArray(32)
                input.readFully(received)
                val accepted = MessageDigest.isEqual(proof("CLIENT"), received)
                received.fill(0)
                if (!accepted) throw IOException("Bridge authentication failed")
                socket.getOutputStream().apply { write(proof("SERVER")); flush() }
                nonce.fill(0)
                listener.close()
                socket.soTimeout = 30_000
                return Peer(socket)
            } catch (error: IOException) {
                socket.close()
                if (listener.isClosed) throw error
            }
        }
        throw IOException("Too many unauthenticated bridge connections")
    }

    override fun close() {
        closed = true
        listener.close()
        candidate?.close()
        nonce.fill(0)
    }

    private fun proof(role: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("WOW-SESSION-$role-1".toByteArray(Charsets.US_ASCII) + nonce)

    class Peer(private val socket: Socket) : Closeable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun device(): ByteArray = readFrame(4096)

        fun launch(token: ByteArray) {
            require(token.size in 1..2048 && token.all { (it.toInt() and 255) in 32..126 }) {
                "Invalid game credential"
            }
            output.writeInt(token.size)
            output.write(token)
            output.flush()
            val status = readFrame(128).toString(Charsets.US_ASCII)
            if (status != "STARTED") {
                // Do not echo a peer-provided payload into a log or dialog.
                throw IOException("Wine could not hand the session to the game")
            }
        }

        private fun readFrame(limit: Int): ByteArray {
            val length = input.readInt()
            if (length !in 1..limit) throw IOException("Invalid bridge frame length")
            return ByteArray(length).also(input::readFully)
        }

        override fun close() = socket.close()
    }
}
