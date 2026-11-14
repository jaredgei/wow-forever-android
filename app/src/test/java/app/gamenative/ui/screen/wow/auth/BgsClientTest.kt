package app.gamenative.ui.screen.wow.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BgsClientTest {
    @Test fun onlyAcceptsHttpsBattleNetSignInUrls() {
        listOf("https://eu.battle.net/login/en/?x=1", "https://battle.net:443/login").forEach {
            assertTrue(BgsClient.isTrustedSignInUrl(it))
        }
        listOf("http://eu.battle.net/login", "https://battle.net.evil.example/login",
            "https://evilbattle.net/", "https://battle.net@evil.example/", "https://user@battle.net/",
            "https://battle.net:444/", "https://battle.net/#secret", "file:///battle.net",
            "https://battle.net\\@evil.example/", "https://battle.net%00.evil.example/", "not a URL").forEach {
            assertFalse(it, BgsClient.isTrustedSignInUrl(it))
        }
    }

    @Test fun acceptsChallengeArrivingBeforeLogonRpcResponse() {
        val transport = FakeTransport(connectReply() + challenge() + response(2))
        BgsClient(transport).use { client ->
            client.connect()
            assertEquals(URL, client.webChallenge(DEVICE, BUILD))
        }
        assertTrue(transport.closed)
        val requests = transport.requests()
        assertEquals(listOf(1L, 1L), requests.map { it.first.integer(2) })
        assertEquals(BgsClient.CONNECTION_SERVICE, requests[0].first.fixed32(11))
        assertEquals(BgsClient.AUTHENTICATION_SERVICE, requests[1].first.fixed32(11))
        assertArrayEquals(byteArrayOf(8, 7), requests[1].first.bytes(13))
        val logon = requests[1].second
        assertEquals(BgsClient.WOW_TITLE, logon.integer(1))
        assertEquals("Wn64", logon.bytes(2)!!.toString(Charsets.UTF_8))
        assertEquals(BUILD.toLong(), logon.integer(4))
        assertArrayEquals(DEVICE, BgsProtocol.parse(logon.bytes(10)!!).bytes(3))
    }

    @Test fun generatesOnlyAfterSuccessfulLogonAndReturnsSeparateCredential() {
        val gameToken = "fresh-game-credential".toByteArray()
        val transport = FakeTransport(connectReply() + response(2) + challenge() +
            logonComplete() + response(3) + response(4, BgsProtocol.bytes(1, gameToken)))
        BgsClient(transport).use { client ->
            client.connect()
            client.webChallenge(DEVICE, BUILD)
            assertArrayEquals(gameToken, client.verifyAndGenerateGameToken(TICKET))
            assertThrows(BgsException::class.java) { client.verifyAndGenerateGameToken(TICKET) }
        }
        val requests = transport.requests()
        assertEquals(listOf(1L, 1L, 2L, 3L), requests.map { it.first.integer(2) })
        assertArrayEquals(TICKET, requests[2].second.bytes(1))
        assertEquals(BgsClient.WOW_TITLE, requests[3].second.integer(1))
    }

    @Test fun failedLogonNeverGeneratesTokenEvenWhenVerifyRpcSucceeds() {
        val transport = FakeTransport(connectReply() + response(2) + challenge() + response(3) + logonComplete(14400))
        val client = BgsClient(transport)
        client.connect()
        client.webChallenge(DEVICE, BUILD)
        val error = assertThrows(BgsException::class.java) { client.verifyAndGenerateGameToken(TICKET) }
        assertEquals(14400L, error.status)
        assertEquals(3, transport.requests().size)
        assertTrue(transport.closed)
    }

    @Test fun successfulStatusWithoutLogonRecordCannotAuthorizeTokenGeneration() {
        val transport = FakeTransport(connectReply() + response(2) + challenge() + response(3) +
            notification(1, BgsProtocol.integer(1, 0)))
        val client = BgsClient(transport)
        client.connect()
        client.webChallenge(DEVICE, BUILD)
        assertThrows(BgsException::class.java) { client.verifyAndGenerateGameToken(TICKET) }
        assertEquals(3, transport.requests().size)
    }

    @Test fun unrelatedListenerCannotAuthenticateTheSession() {
        val bogusSuccess = notification(1, BgsProtocol.integer(1, 0) + BgsProtocol.bytes(2, BgsProtocol.integer(1, 42)),
            service = 0x11111111)
        val transport = FakeTransport(connectReply() + response(2) + challenge() + response(3) + bogusSuccess + logonComplete(14400))
        val client = BgsClient(transport)
        client.connect()
        client.webChallenge(DEVICE, BUILD)
        assertEquals(14400L, assertThrows(BgsException::class.java) { client.verifyAndGenerateGameToken(TICKET) }.status)
        assertEquals(3, transport.requests().size)
    }

    @Test fun reissuedChallengeRejectsTicketWithoutGeneratingToken() {
        val transport = FakeTransport(connectReply() + response(2) + challenge() + response(3) + challenge())
        val client = BgsClient(transport)
        client.connect()
        client.webChallenge(DEVICE, BUILD)
        assertThrows(BgsException::class.java) { client.verifyAndGenerateGameToken(TICKET) }
        assertEquals(3, transport.requests().size)
    }

    @Test fun mismatchedResponseTokenMethodOrServiceIsRejected() {
        listOf(response(99), response(1, method = 8), response(1, service = BgsClient.AUTHENTICATION_SERVICE)).forEach { reply ->
            val transport = FakeTransport(reply)
            assertThrows(BgsException::class.java) { BgsClient(transport).connect() }
            assertTrue(transport.closed)
        }
    }

    @Test fun reportsBuildRefusalWithoutTryingAnotherBuildOrAuthMethod() {
        val transport = FakeTransport(connectReply() + response(2, status = 33))
        val client = BgsClient(transport)
        client.connect()
        val error = assertThrows(BgsException::class.java) { client.webChallenge(DEVICE, BUILD) }
        assertEquals(33L, error.status)
        assertEquals(2, transport.requests().size)
    }

    @Test fun queueNotificationsDoNotCountAsSuccessfulLoginOrEndTheWait() {
        val transport = FakeTransport(connectReply() + response(2) + notification(2) + notification(3) + challenge() +
            response(3) + notification(2) + notification(3) + logonComplete() + response(4, BgsProtocol.text(1, "new")))
        BgsClient(transport).use {
            it.connect()
            it.webChallenge(DEVICE, BUILD)
            assertEquals("new", it.verifyAndGenerateGameToken(TICKET).toString(Charsets.UTF_8))
        }
        assertTrue(transport.timeouts.all { it in 1..120_000 })
    }

    @Test fun queueHasAnAbsoluteTimeLimit() {
        var clock = 0L
        val queued = ByteArrayOutputStream().apply { repeat(100) { write(notification(2)) } }.toByteArray()
        val transport = FakeTransport(connectReply() + response(2) + queued + challenge())
        val client = BgsClient(transport, nanoTime = { clock })
        client.connect()
        // Queue traffic stays well within the 120-second idle timeout, but cannot extend the total
        // wait indefinitely. Each frame has a prefix and a header; advance ten seconds per read.
        var queuedWait = false
        transport.onTimeout = {
            if (it > 30_000) queuedWait = true
            if (queuedWait) clock += 10L * 1_000_000_000
        }
        val error = assertThrows(BgsException::class.java) { client.webChallenge(DEVICE, BUILD) }
        assertTrue(error.message!!.contains("queue took too long"))
        assertEquals(30L * 60 * 1_000_000_000, clock)
        assertTrue(transport.closed)
    }

    @Test fun rejectsOversizeFrameBeforeReadingBody() {
        val hugeHeader = BgsProtocol.integer(1, 254) + BgsProtocol.integer(3, 1) +
            BgsProtocol.integer(5, BgsProtocol.MAX_MESSAGE_BYTES.toLong() + 1)
        val transport = FakeTransport(prefix(hugeHeader.size) + hugeHeader)
        assertThrows(BgsException::class.java) { BgsClient(transport).connect() }
        assertTrue(transport.closed)
    }

    @Test fun refusesHugeOrTruncatedHeadersAndBodies() {
        listOf(byteArrayOf(0x20, 0x01), byteArrayOf(0, 1), byteArrayOf(0, 0),
            prefix(6) + byteArrayOf(8, 0xfe.toByte(), 1, 0x28, 2, 0)).forEach { bytes ->
            assertThrows(BgsException::class.java) { BgsClient(FakeTransport(bytes)).connect() }
        }
    }

    @Test fun serverUrlsAndTransportErrorsDoNotLeakTheirContents() {
        val transport = FakeTransport(connectReply() + response(2) + challenge("https://evil.example/private-secret"))
        val client = BgsClient(transport)
        client.connect()
        val error = assertThrows(BgsException::class.java) { client.webChallenge(DEVICE, BUILD) }
        assertFalse(error.toString().contains("private-secret"))
        val broken = FakeTransport(byteArrayOf()).apply { connectError = IOException("private-secret") }
        val connectionError = assertThrows(BgsException::class.java) { BgsClient(broken).connect() }
        assertFalse(connectionError.toString().contains("private-secret"))
        assertEquals(null, connectionError.cause)
    }

    @Test fun closePreventsFurtherOperations() {
        val transport = FakeTransport(connectReply())
        val client = BgsClient(transport)
        client.close()
        assertThrows(BgsException::class.java) { client.connect() }
        assertEquals(0, transport.output.size())
    }

    private class FakeTransport(bytes: ByteArray) : BgsTransport {
        override val input = ByteArrayInputStream(bytes)
        override val output = ByteArrayOutputStream()
        var closed = false
        var connectError: IOException? = null
        var onTimeout: ((Int) -> Unit)? = null
        val timeouts = mutableListOf<Int>()
        override fun connect() { connectError?.let { throw it } }
        override fun close() { closed = true }
        override fun setReadTimeout(milliseconds: Int) { timeouts += milliseconds; onTimeout?.invoke(milliseconds) }
        fun requests(): List<Pair<BgsProtocol.Message, BgsProtocol.Message>> {
            val bytes = ByteArrayInputStream(output.toByteArray())
            val result = mutableListOf<Pair<BgsProtocol.Message, BgsProtocol.Message>>()
            while (bytes.available() > 0) {
                val size = (bytes.read() shl 8) or bytes.read()
                val headerBytes = ByteArray(size)
                assertEquals(size, bytes.read(headerBytes))
                val header = BgsProtocol.parse(headerBytes)
                val body = ByteArray(header.integer(5).toInt())
                if (body.isNotEmpty()) assertEquals(body.size, bytes.read(body))
                result += header to BgsProtocol.parse(body)
            }
            return result
        }
    }

    companion object {
        private const val URL = "https://eu.battle.net/login/en/"
        private const val BUILD = 70938
        private val DEVICE = "{\"RGKY\":123}".toByteArray()
        private val TICKET = "EU-test-ticket".toByteArray()
        private fun prefix(size: Int) = byteArrayOf((size ushr 8).toByte(), size.toByte())
        private fun frame(header: ByteArray, body: ByteArray): ByteArray {
            val sized = header + BgsProtocol.integer(5, body.size.toLong())
            return prefix(sized.size) + sized + body
        }
        private fun response(token: Long, body: ByteArray = byteArrayOf(), status: Long = 0,
            method: Long = 0, service: Long = 0): ByteArray = frame(
            BgsProtocol.integer(1, 254) + BgsProtocol.integer(2, method) + BgsProtocol.integer(3, token) +
                BgsProtocol.integer(6, status) + BgsProtocol.fixed32(11, service), body)
        private fun notification(method: Long, body: ByteArray = byteArrayOf(),
            service: Long = BgsClient.AUTHENTICATION_LISTENER): ByteArray = frame(
            BgsProtocol.integer(1, 0) + BgsProtocol.integer(2, method) + BgsProtocol.fixed32(11, service), body)
        private fun connectReply() = response(1, BgsProtocol.bytes(9, byteArrayOf(8, 7)))
        private fun challenge(url: String = URL) = notification(4,
            BgsProtocol.text(2, "web_auth_url") + BgsProtocol.text(3, url))
        private fun logonComplete(status: Long = 0) = notification(1,
            BgsProtocol.integer(1, status) + BgsProtocol.bytes(2, BgsProtocol.integer(1, 42)))
    }
}
