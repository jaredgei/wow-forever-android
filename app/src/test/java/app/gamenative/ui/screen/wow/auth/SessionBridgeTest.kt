package app.gamenative.ui.screen.wow.auth

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionBridgeTest {
    private fun connect(bridge: SessionBridge): Socket {
        val config = bridge.config.lines()
        val nonce = config[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Socket("127.0.0.1", config[0].toInt()).apply {
            soTimeout = 2000
            getOutputStream().write(MessageDigest.getInstance("SHA-256").digest("WOW-SESSION-CLIENT-1".toByteArray() + nonce))
            val echo = ByteArray(32)
            DataInputStream(getInputStream()).readFully(echo)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("WOW-SESSION-SERVER-1".toByteArray() + nonce), echo)
        }
    }

    @Test fun authenticatesBothSidesAndHandsOffOneCredential() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SessionBridge().use { bridge ->
                val peer = executor.submit<SessionBridge.Peer> { bridge.accept() }
                connect(bridge).use { child ->
                    val output = DataOutputStream(child.getOutputStream())
                    val device = "{\"RGKY\":123}".toByteArray()
                    output.writeInt(device.size)
                    output.write(device)
                    val accepted = peer.get(2, TimeUnit.SECONDS)
                    assertArrayEquals(device, accepted.device())
                    val launch = executor.submit { accepted.launch("test-game-ticket".toByteArray()) }
                    val input = DataInputStream(child.getInputStream())
                    val token = ByteArray(input.readInt()).also(input::readFully)
                    assertEquals("test-game-ticket", token.toString(Charsets.US_ASCII))
                    output.writeInt(7)
                    output.writeBytes("STARTED")
                    launch.get(2, TimeUnit.SECONDS)
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun unauthenticatedClientCannotReceiveCapabilityOrConsumeTheLaunch() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SessionBridge().use { bridge ->
                val port = bridge.config.lineSequence().first().toInt()
                val peer = executor.submit<SessionBridge.Peer> { bridge.accept() }
                Socket("127.0.0.1", port).use { intruder ->
                    intruder.soTimeout = 2000
                    intruder.getOutputStream().write(ByteArray(32))
                    assertEquals(-1, intruder.getInputStream().read())
                }
                connect(bridge).use { peer.get(2, TimeUnit.SECONDS).close() }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun rejectsNegativeEmptyAndOversizedFramesBeforeAllocation() {
        for (size in listOf(-1, 0, 4097, Int.MAX_VALUE)) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                SessionBridge().use { bridge ->
                    val peer = executor.submit<SessionBridge.Peer> { bridge.accept() }
                    connect(bridge).use { child ->
                        DataOutputStream(child.getOutputStream()).writeInt(size)
                        assertThrows(IOException::class.java) { peer.get(2, TimeUnit.SECONDS).device() }
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun rejectsMalformedCredentialsBeforeSendingThem() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SessionBridge().use { bridge ->
                val peer = executor.submit<SessionBridge.Peer> { bridge.accept() }
                connect(bridge).use {
                    val accepted = peer.get(2, TimeUnit.SECONDS)
                    for (bad in listOf(byteArrayOf(), byteArrayOf(0), ByteArray(2049) { 65 }, byteArrayOf(-1))) {
                        assertThrows(IllegalArgumentException::class.java) { accepted.launch(bad) }
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun peerErrorPayloadCannotLeakIntoException() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SessionBridge().use { bridge ->
                val peer = executor.submit<SessionBridge.Peer> { bridge.accept() }
                connect(bridge).use { child ->
                    val accepted = peer.get(2, TimeUnit.SECONDS)
                    val launch = executor.submit<Throwable> {
                        assertThrows(IOException::class.java) { accepted.launch("ticket".toByteArray()) }
                    }
                    val input = DataInputStream(child.getInputStream())
                    input.readFully(ByteArray(input.readInt()))
                    DataOutputStream(child.getOutputStream()).apply {
                        writeInt(16)
                        writeBytes("SECRET-ERROR-DATA")
                    }
                    child.shutdownOutput()
                    assertFalse(launch.get(2, TimeUnit.SECONDS).toString().contains("SECRET"))
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationInterruptsAnUnconnectedListener() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val bridge = SessionBridge()
            val waiting = executor.submit<Boolean> {
                try { bridge.accept(); false } catch (_: IOException) { true }
            }
            bridge.close()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
