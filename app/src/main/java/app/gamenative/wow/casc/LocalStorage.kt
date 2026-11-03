package app.gamenative.wow.casc

import app.gamenative.wow.tact.Jenkins
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The game's own storage in `Data/data`: `data.NNN` segments holding BLTE files, each behind a 30-byte
 * header, and sixteen `.idx` files mapping the first 9 bytes of each encoded key to where it lives.
 * Layouts follow CascLib and Blizzard's Agent (see CascLib's CascIndexFiles.cpp and CascReadFile.cpp).
 */
object LocalStorage {
    const val HEADER_SIZE = 0x1E
    const val KEY_BYTES = 9
    const val ENTRY_SIZE = 18
    const val SEGMENT_BITS = 30
    const val SEGMENT_SIZE = 1L shl SEGMENT_BITS
    const val MAX_SEGMENTS = 1024
    const val BUCKETS = 16

    /** Agent's seed for the entry header's Jenkins hash. */
    private const val HEADER_SEED = 0x3D6BE971

    /** Agent's table for scrambling the entry offset into the header checksum. */
    private val OFFSET_TABLE = intArrayOf(
        0x049396B8, 0x72A82A9B, 0xEE626CCA.toInt(), 0x9917754F.toInt(),
        0x15DE40B1, 0xF5A8A9B6.toInt(), 0x421EAC7E, 0xA9D55C9A.toInt(),
        0x317FD40C, 0x04FAF80D, 0x3D6BE971, 0x52933CFD,
        0x27F64B7D, 0xC6F5C11B.toInt(), 0xD5757E3A.toInt(), 0x6C388745,
    )

    /** Update journal entries start on a 64 KB boundary after the sorted entries; the client appends there. */
    private const val UPDATE_ALIGNMENT = 0x10000
    private const val UPDATE_SECTION_SIZE = 0x7800

    /** Which of the sixteen index files holds [key]: the first 9 bytes XORed together, then the two nibbles. */
    fun bucket(key: ByteArray, offset: Int = 0): Int {
        var h = 0
        for (i in 0 until KEY_BYTES) h = h xor (key[offset + i].toInt() and 0xFF)
        return (h and 0x0F) xor (h shr 4)
    }

    fun indexFileName(bucket: Int, version: Int) = "%02x%08x.idx".format(bucket, version)

    /** The header written before a file of [blteSize] bytes whose header starts [headerOffset] bytes into its segment. */
    fun entryHeader(ekey: ByteArray, blteSize: Int, headerOffset: Long): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        for (i in 0 until 16) header[i] = ekey[15 - i]
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x10, blteSize + HEADER_SIZE)
        buffer.putInt(0x16, Jenkins.hashLittle(header, 0, 0x16, HEADER_SEED))

        val folded = ByteArray(4)
        for (i in 0 until 0x1A) folded[i and 3] = (folded[i and 3].toInt() xor header[i].toInt()).toByte()
        val position = (headerOffset + HEADER_SIZE).toInt()
        val scrambled = OFFSET_TABLE[position and 0x0F] xor position
        for (j in 0 until 4) {
            val k = (0x1A + j) and 3
            header[0x1A + j] = (folded[k].toInt() xor (scrambled ushr (8 * k))).toByte()
        }
        return header
    }

    /** One index entry: [size] counts the 30-byte header, and [offset] points at it. */
    class Entry(val key: ByteArray, val segment: Int, val offset: Long, val size: Int)

    /** Writes a version 7 index for [bucket], with an empty update journal for the client to append to. */
    fun writeIndex(file: File, bucket: Int, entries: List<Entry>) {
        val sorted = entries.sortedWith { x, y -> compareKeys(x.key, y.key) }
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(7).put(bucket.toByte()).put(0).put(4).put(5).put(KEY_BYTES.toByte()).put(SEGMENT_BITS.toByte())
            // Real files hold 00 00 00 00 40 00 00 00 here.
            .putLong(0x4000000000L)
            .array()
        val body = ByteArray(sorted.size * ENTRY_SIZE)
        sorted.forEachIndexed { i, e ->
            val at = i * ENTRY_SIZE
            System.arraycopy(e.key, 0, body, at, KEY_BYTES)
            val packed = ((e.segment.toLong() and 3) shl SEGMENT_BITS) or e.offset
            body[at + 9] = (e.segment shr 2).toByte()
            ByteBuffer.wrap(body, at + 10, 4).putInt(packed.toInt())
            ByteBuffer.wrap(body, at + 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(e.size)
        }

        val sortedEnd = 8 + header.size + 8 + 8 + body.size
        val total = (sortedEnd + UPDATE_ALIGNMENT - 1) / UPDATE_ALIGNMENT * UPDATE_ALIGNMENT + UPDATE_SECTION_SIZE
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(header.size).putInt(Jenkins.hashLittle(header)).put(header)
        out.position(out.position() + 8)
        out.putInt(body.size).putInt(entriesHash(body)).put(body)

        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(out.array())
        if (!temp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
    }

    /** Reads the sorted entries of an index written by [writeIndex], checking both guards. Null if either fails. */
    fun readIndex(data: ByteArray, bucket: Int): List<Entry>? {
        if (data.size < 0x28) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != 16 || buffer.getInt(4) != Jenkins.hashLittle(data, 8, 16)) return null
        if (buffer.getShort(8).toInt() != 7 || data[10].toInt() != bucket) return null
        val size = buffer.getInt(0x20)
        if (0x28L + size > data.size || buffer.getInt(0x24) != entriesHash(data.copyOfRange(0x28, 0x28 + size))) return null
        return (0 until size / ENTRY_SIZE).map { i ->
            val at = 0x28 + i * ENTRY_SIZE
            val high = data[at + 9].toInt() and 0xFF
            val packed = ByteBuffer.wrap(data, at + 10, 4).int.toLong() and 0xFFFFFFFFL
            Entry(
                data.copyOfRange(at, at + KEY_BYTES),
                (high shl 2) or (packed ushr SEGMENT_BITS).toInt(),
                packed and (SEGMENT_SIZE - 1),
                buffer.getInt(at + 14),
            )
        }
    }

    /** The highest index version in [dir], or 0 if it has none. */
    fun highestIndexVersion(dir: File): Int =
        dir.list().orEmpty().mapNotNull { name ->
            Regex("[0-9a-f]{2}([0-9a-f]{8})\\.idx").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
        }.maxOrNull() ?: 0

    /** Agent's entries hash: hashlittle2 chained entry by entry, keeping the primary value. */
    internal fun entriesHash(body: ByteArray): Int {
        var pc = 0
        var pb = 0
        for (at in body.indices step ENTRY_SIZE) {
            val (c, b) = Jenkins.hashLittle2(body, at, ENTRY_SIZE, pc, pb)
            pc = c
            pb = b
        }
        return pc
    }

    fun compareKeys(x: ByteArray, y: ByteArray): Int {
        for (i in 0 until KEY_BYTES) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}
