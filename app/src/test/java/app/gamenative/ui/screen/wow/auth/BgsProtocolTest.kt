package app.gamenative.ui.screen.wow.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BgsProtocolTest {
    @Test fun encodesKnownProtobufVectors() {
        assertArrayEquals(byteArrayOf(0x08, 0xac.toByte(), 0x02), BgsProtocol.integer(1, 300))
        assertArrayEquals(byteArrayOf(0x12, 0x03, 0x61, 0x62, 0x63), BgsProtocol.text(2, "abc"))
        assertArrayEquals(byteArrayOf(0x5d, 0x16, 0x82.toByte(), 0x2f, 0xc0.toByte()),
            BgsProtocol.fixed32(11, BgsClient.AUTHENTICATION_SERVICE))
    }

    @Test fun readsKnownVectorsAndUnknownFields() {
        val parsed = BgsProtocol.parse(byteArrayOf(0x08, 0xac.toByte(), 0x02, 0x12, 0x01, 0x78,
            0x1d, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x21, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(300L, parsed.integer(1))
        assertArrayEquals(byteArrayOf(0x78), parsed.bytes(2))
        assertEquals(0xffffffffL, parsed.fixed32(3))
        assertEquals(4, parsed.fields.size)
    }

    @Test fun rejectsTruncatedAndOverflowingFields() {
        val malformed = listOf(
            byteArrayOf(0x08, 0x80.toByte()),
            byteArrayOf(0x12, 0x02, 0x01),
            byteArrayOf(0x1d, 0, 0, 0),
            byteArrayOf(0x21, 0, 0, 0),
            byteArrayOf(0x00),
            byteArrayOf(0x0b), // Groups are not part of this protocol.
            byteArrayOf(0x08) + ByteArray(10) { 0xff.toByte() },
            byteArrayOf(0x12) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(0x01),
            byteArrayOf(0x12) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x40),
        )
        malformed.forEach { bytes -> assertThrows(BgsException::class.java) { BgsProtocol.parse(bytes) } }
    }

    @Test fun rejectsAmbiguousSingularFieldsAndWrongWireTypes() {
        val duplicate = BgsProtocol.parse(BgsProtocol.integer(1, 0) + BgsProtocol.integer(1, 5))
        assertThrows(BgsException::class.java) { duplicate.integer(1) }
        val wrong = BgsProtocol.parse(BgsProtocol.text(1, "secret must never be included in errors"))
        val error = assertThrows(BgsException::class.java) { wrong.integer(1) }
        assertEquals("Battle.net returned an invalid protocol message.", error.message)
    }

    @Test fun boundsInputBytesAndFieldCount() {
        assertThrows(BgsException::class.java) { BgsProtocol.parse(ByteArray(BgsProtocol.MAX_MESSAGE_BYTES + 1)) }
        assertThrows(BgsException::class.java) {
            BgsProtocol.parse(ByteArray(8193 * 2) { if (it % 2 == 0) 8 else 0 })
        }
    }

    @Test fun preservesUnsigned64BitWireValues() {
        assertEquals(-1L, BgsProtocol.parse(BgsProtocol.integer(1, -1L)).integer(1))
    }
}
