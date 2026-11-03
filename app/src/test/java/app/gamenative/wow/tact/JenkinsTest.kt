package app.gamenative.wow.tact

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors from lookup3.c's own self-test (driver5). */
class JenkinsTest {
    private val text = "Four score and seven years ago".toByteArray()

    @Test
    fun hashLittleMatchesReferenceVectors() {
        assertEquals(0xdeadbeef.toInt(), Jenkins.hashLittle(ByteArray(0)))
        assertEquals(0xbd5b7dde.toInt(), Jenkins.hashLittle(ByteArray(0), initval = 0xdeadbeef.toInt()))
        assertEquals(0x17770551, Jenkins.hashLittle(text))
        assertEquals(0xcd628161.toInt(), Jenkins.hashLittle(text, initval = 1))
    }

    @Test
    fun hashLittle2MatchesReferenceVectors() {
        assertEquals(0xdeadbeef.toInt() to 0xdeadbeef.toInt(), Jenkins.hashLittle2(ByteArray(0), 0, 0, 0, 0))
        assertEquals(0xbd5b7dde.toInt() to 0xdeadbeef.toInt(), Jenkins.hashLittle2(ByteArray(0), 0, 0, 0, 0xdeadbeef.toInt()))
        assertEquals(0x9c093ccd.toInt() to 0xbd5b7dde.toInt(), Jenkins.hashLittle2(ByteArray(0), 0, 0, 0xdeadbeef.toInt(), 0xdeadbeef.toInt()))
        assertEquals(0x17770551 to 0xce7226e6.toInt(), Jenkins.hashLittle2(text, 0, text.size, 0, 0))
        assertEquals(0xe3607cae.toInt() to 0xbd371de4.toInt(), Jenkins.hashLittle2(text, 0, text.size, 0, 1))
        assertEquals(0xcd628161.toInt() to 0x6cbea4b3, Jenkins.hashLittle2(text, 0, text.size, 1, 0))
    }

    @Test
    fun hashesASliceInPlace() {
        val padded = byteArrayOf(9, 9) + text + byteArrayOf(9)
        assertEquals(Jenkins.hashLittle(text), Jenkins.hashLittle(padded, 2, text.size))
    }
}
