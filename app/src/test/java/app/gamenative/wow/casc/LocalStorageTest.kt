package app.gamenative.wow.casc

import app.gamenative.wow.tact.TactFixtures.unhex
import app.gamenative.wow.tact.hex
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expected values come from a separate Python port of CascLib's code (lookup3, VerifyHeaderSpan and
 * CaptureGuardedBlock2), so the format is checked against CascLib rather than against this implementation.
 */
class LocalStorageTest {
    @Test
    fun entryHeaderMatchesAgent() {
        val header = LocalStorage.entryHeader(ByteArray(16) { it.toByte() }, 1000, 0x1234)
        assertEquals("0f0e0d0c0b0a0908070605040302010006040000000035a30b52574d9528", hex(header))
    }

    @Test
    fun entriesHashChainsPerEntry() {
        val entries = ByteArray(54) { (it * 7 + 3).toByte() }
        assertEquals(0x141c9f9b, LocalStorage.entriesHash(entries))
    }

    @Test
    fun indexHeaderMatchesRealFiles() {
        val dir = Files.createTempDirectory("idx").toFile()
        val file = File(dir, LocalStorage.indexFileName(5, 1))
        LocalStorage.writeIndex(file, 5, emptyList())
        val bytes = file.readBytes()
        assertEquals("0500000001.idx", file.name)
        assertEquals("10000000" + "dcbb1772" + "07000500" + "0405091e" + "0000000040000000", hex(bytes, 0, 24))
        // The client appends its update journal on the 64 KB boundary, so that space is there, zeroed.
        assertEquals(0x10000 + 0x7800, bytes.size)
    }

    @Test
    fun indexRoundTripsSortedWithPackedLocations() {
        val dir = Files.createTempDirectory("idx").toFile()
        val file = File(dir, LocalStorage.indexFileName(3, 2))
        val a = LocalStorage.Entry(unhex("ff0102030405060708"), 5, 0x12345, 4030)
        val b = LocalStorage.Entry(unhex("000102030405060708"), 1023, LocalStorage.SEGMENT_SIZE - 100, 99)
        LocalStorage.writeIndex(file, 3, listOf(a, b))
        val bytes = file.readBytes()
        // Second entry on disk is segment 5: high byte 5 >> 2, then (5 & 3) << 30 | offset, big-endian.
        assertEquals("ff0102030405060708" + "01" + "40012345" + "be0f0000", hex(bytes, 0x28 + 18, 18))

        val read = LocalStorage.readIndex(bytes, 3)!!
        assertEquals(listOf("000102030405060708", "ff0102030405060708"), read.map { hex(it.key) })
        assertEquals(listOf(1023, 5), read.map { it.segment })
        assertEquals(listOf(LocalStorage.SEGMENT_SIZE - 100, 0x12345L), read.map { it.offset })
        assertEquals(listOf(99, 4030), read.map { it.size })
        assertNull("wrong bucket", LocalStorage.readIndex(bytes, 4))
        bytes[0x30] = (bytes[0x30] + 1).toByte()
        assertNull("damaged entries", LocalStorage.readIndex(bytes, 3))
    }

    @Test
    fun bucketFoldsTheFirstNineBytes() {
        assertEquals(0, LocalStorage.bucket(ByteArray(16)))
        // XOR of the first nine bytes is 0xa5, then 0x5 ^ 0xa.
        assertEquals(0x0f, LocalStorage.bucket(unhex("a5000000000000000077777777777777")))
        val key = ByteArray(16) { (it * 37).toByte() }
        assertEquals(LocalStorage.bucket(key), LocalStorage.bucket(byteArrayOf(1, 2) + key, 2))
    }

    @Test
    fun highestIndexVersion() {
        val dir = Files.createTempDirectory("idx").toFile()
        assertEquals(0, LocalStorage.highestIndexVersion(dir))
        listOf("0000000003.idx", "0f00000011.idx", "data.000", "shmem").forEach { File(dir, it).writeText("") }
        assertEquals(0x11, LocalStorage.highestIndexVersion(dir))
    }
}
