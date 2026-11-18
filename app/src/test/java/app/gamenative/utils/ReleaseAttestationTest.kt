package app.gamenative.utils

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** GitHub stores attestations in the Snappy format; these tests cover the decoder that [ReleaseAttestation] uses for it. */
class ReleaseAttestationTest {
    @Test
    fun snappy_decodesLiteralsAndCopies() {
        // Length 12, a 3-byte literal "abc" (tag 8), then a 9-byte copy from 3 bytes back (tag 21), which overlaps.
        val encoded = bytes(12, 8, 'a'.code, 'b'.code, 'c'.code, 21, 3)
        assertArrayEquals("abcabcabcabc".toByteArray(), ReleaseAttestation.snappyDecode(encoded))
    }

    @Test
    fun snappy_rejectsBrokenInput() {
        val broken = listOf(
            bytes(), // no length
            bytes(5, 8, 1, 2, 3), // shorter than its declared length
            bytes(3, 16, 1, 2, 3, 4, 5), // longer than its declared length
            bytes(8, 0, 1, 1, 9), // a copy from before the start
            bytes(0xff, 0xff, 0xff, 0xff, 0x7f), // a declared length far above the cap
        )
        broken.forEach { input -> assertThrows(IOException::class.java) { ReleaseAttestation.snappyDecode(input) } }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
