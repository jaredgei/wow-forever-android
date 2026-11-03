package app.gamenative.wow.casc

import app.gamenative.wow.tact.TactFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StorageBuilderTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("storage").toFile()
    }

    /** A chunked BLTE file and its key: the MD5 of its header. */
    private fun chunked(seed: Int): Pair<ByteArray, ByteArray> {
        val blte = TactFixtures.blte(listOf(ByteArray(3000) { (it * seed).toByte() }, ByteArray(500) { seed.toByte() }), 'Z')
        val headerSize = 12 + 24 * 2
        return MessageDigest.getInstance("MD5").digest(blte.copyOf(headerSize)) to blte
    }

    /** A single-chunk BLTE file without a chunk table: its key is the MD5 of the whole file. */
    private fun headerless(seed: Int): Pair<ByteArray, ByteArray> {
        val blte = TactFixtures.blte(listOf(ByteArray(200) { (seed + it).toByte() }), 'N', headerless = true)
        return MessageDigest.getInstance("MD5").digest(blte) to blte
    }

    @Test
    fun appendsHeaderThenDataAndIndexesEverything() {
        val files = listOf(chunked(3), headerless(4), chunked(5))
        val builder = StorageBuilder(dir)
        builder.openSegment().use { segment ->
            files.forEach { (key, blte) -> assertTrue(segment.append(key, blte.size, ByteArrayInputStream(blte))) }
        }
        builder.finish(7)

        val data = File(dir, "data.000").readBytes()
        assertEquals(files.sumOf { it.second.size + LocalStorage.HEADER_SIZE }, data.size)
        assertArrayEquals(LocalStorage.entryHeader(files[0].first, files[0].second.size, 0), data.copyOf(30))

        val indexed = (0 until 16).flatMap { b -> LocalStorage.readIndex(File(dir, LocalStorage.indexFileName(b, 7)).readBytes(), b)!! }
        assertEquals(3, indexed.size)
        for ((key, blte) in files) {
            val entry = indexed.single { it.key.contentEquals(key.copyOf(9)) }
            assertEquals(blte.size + 30, entry.size)
            val start = entry.offset.toInt() + 30
            assertArrayEquals(blte, data.copyOfRange(start, start + blte.size))
        }
    }

    @Test
    fun refusesDataThatDoesNotMatchItsKey() {
        val (key, blte) = chunked(3)
        val damaged = blte.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val wrongHeader = blte.copyOf().also { it[20] = (it[20] + 1).toByte() }
        val builder = StorageBuilder(dir)
        builder.openSegment().use { segment ->
            // A damaged chunk body still matches a chunked key (the client checks chunks); a damaged header doesn't.
            assertTrue(segment.append(key, damaged.size, ByteArrayInputStream(damaged)))
            assertFalse(segment.append(key, wrongHeader.size, ByteArrayInputStream(wrongHeader)))
        }
        builder.commit()
        assertEquals(1, builder.count)
        assertEquals((blte.size + 30).toLong(), File(dir, "data.000").length())
    }

    @Test
    fun resumesFromTheJournalAndCutsOffTheRest() {
        val files = listOf(chunked(3), headerless(4), chunked(5))
        val first = StorageBuilder(dir)
        val segment = first.openSegment()
        segment.append(files[0].first, files[0].second.size, ByteArrayInputStream(files[0].second))
        segment.append(files[1].first, files[1].second.size, ByteArrayInputStream(files[1].second))
        first.commit()
        // Written but never journaled, as when the app is killed between commits.
        segment.append(files[2].first, files[2].second.size, ByteArrayInputStream(files[2].second))

        val second = StorageBuilder(dir)
        assertEquals(2, second.count)
        val kept = files.take(2).sumOf { it.second.size + 30 }.toLong()
        assertEquals(kept, File(dir, "data.000").length())
        // The partly filled segment is reused, continuing where the journal ends.
        second.openSegment().use { assertEquals(0, it.number) }
    }

    @Test
    fun aReadErrorLeavesNothingBehind() {
        val (key, blte) = chunked(3)
        val builder = StorageBuilder(dir)
        val failing = object : InputStream() {
            var left = 100
            override fun read() = if (left-- > 0) 1 else throw IOException("connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw IOException("connection reset")
                val n = minOf(len, left)
                left -= n
                return n
            }
        }
        builder.openSegment().use { segment ->
            runCatching { segment.append(key, blte.size, failing) }
            assertTrue(segment.append(key, blte.size, ByteArrayInputStream(blte)))
        }
        builder.commit()
        assertEquals((blte.size + 30).toLong(), File(dir, "data.000").length())
    }

    @Test
    fun startsANewSegmentWhenAFileDoesNotFit() {
        val builder = StorageBuilder(dir)
        val a = builder.openSegment()
        assertTrue(a.fits(LocalStorage.SEGMENT_SIZE - 30))
        assertFalse(a.fits(LocalStorage.SEGMENT_SIZE))
        val b = builder.openSegment()
        assertEquals(listOf(0, 1), listOf(a.number, b.number))
        a.close()
        b.close()
    }
}
