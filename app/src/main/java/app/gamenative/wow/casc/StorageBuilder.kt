package app.gamenative.wow.casc

import app.gamenative.wow.casc.LocalStorage.HEADER_SIZE
import app.gamenative.wow.casc.LocalStorage.SEGMENT_SIZE
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Builds a complete local storage in [dir], one file at a time from several downloads at once. Each
 * download appends to its own segment. Finished files are listed in a journal, written only after their
 * data is synced to disk, so an interrupted build resumes where it stopped: anything past the journal is cut
 * off and fetched again.
 */
class StorageBuilder(val dir: File) {
    private val journalFile = File(dir, JOURNAL)
    private val records = RecordList()
    private val pending = RecordList()
    private val dirty = HashSet<Segment>()
    private val reusable = ArrayDeque<Pair<Int, Long>>()
    private var nextSegment = 0

    init {
        dir.mkdirs()
        loadJournal()
        recoverSegments()
    }

    /** Files already in storage. */
    val count get() = records.size

    fun forEachKey(action: (ByteArray, Int) -> Unit) = records.forEachKey(action)

    /** A segment with room for a file of [size] bytes: a partly filled one if one fits, else a new one. */
    @Synchronized
    fun openSegment(size: Long = 0): Segment {
        reusable.firstOrNull { it.second + HEADER_SIZE + size <= SEGMENT_SIZE }?.let {
            reusable.remove(it)
            return Segment(it.first, it.second)
        }
        if (nextSegment >= LocalStorage.MAX_SEGMENTS) throw IOException("Game storage is full")
        return Segment(nextSegment++, 0)
    }

    /** Syncs every segment written since the last call, then journals the files they now hold. */
    @Synchronized
    fun commit() {
        if (pending.size == 0) return
        dirty.forEach { it.sync() }
        dirty.clear()
        FileOutputStream(journalFile, true).use { out ->
            out.write(pending.toJournal())
            out.fd.sync()
        }
        records.addAll(pending)
        pending.clear()
    }

    /**
     * Writes the sixteen index files as [version]. The journal stays until the caller has put the storage
     * in place, so a crash before then still resumes instead of starting over. Two files whose keys share their
     * first 9 bytes can't both be indexed; the first one wins and the client fetches the other if it ever
     * needs it.
     */
    @Synchronized
    fun finish(version: Int) {
        commit()
        // One bucket at a time keeps only a sixteenth of the entries in memory.
        for (b in 0 until LocalStorage.BUCKETS) {
            val entries = ArrayList<LocalStorage.Entry>()
            records.forEach { key, segment, offset, size ->
                if (LocalStorage.bucket(key) == b) entries += LocalStorage.Entry(key.copyOf(LocalStorage.KEY_BYTES), segment, offset, size)
            }
            entries.sortWith { x, y -> LocalStorage.compareKeys(x.key, y.key) }
            val unique = entries.filterIndexed { i, e -> i == 0 || LocalStorage.compareKeys(entries[i - 1].key, e.key) != 0 }
            LocalStorage.writeIndex(File(dir, LocalStorage.indexFileName(b, version)), b, unique)
        }
    }

    private fun loadJournal() {
        if (!journalFile.isFile) return
        val bytes = journalFile.readBytes()
        val whole = bytes.size / RecordList.RECORD_SIZE * RecordList.RECORD_SIZE
        records.addJournal(bytes, whole)
        // A record cut short by a crash is dropped along with the bytes it described.
        if (whole != bytes.size) RandomAccessFile(journalFile, "rw").use { it.setLength(whole.toLong()) }
    }

    /** Cuts each segment back to its last journaled file and remembers the ones with room left. */
    private fun recoverSegments() {
        val ends = HashMap<Int, Long>()
        records.forEach { _, segment, offset, size -> ends[segment] = maxOf(ends[segment] ?: 0, offset + size) }
        val files = dir.listFiles { f -> SEGMENT_NAME.matches(f.name) }.orEmpty()
        for (f in files) {
            val n = f.name.substringAfter('.').toInt()
            val end = ends[n] ?: 0
            if (end == 0L) {
                f.delete()
                continue
            }
            if (f.length() > end) RandomAccessFile(f, "rw").use { it.setLength(end) }
            if (end < SEGMENT_SIZE * 9 / 10) reusable += n to end
        }
        nextSegment = maxOf(files.maxOfOrNull { it.name.substringAfter('.').toInt() + 1 } ?: 0, (ends.keys.maxOrNull() ?: -1) + 1)
    }

    /** One `data.NNN` file, appended to by a single download at a time. */
    inner class Segment internal constructor(val number: Int, private var end: Long) : Closeable {
        private val file = RandomAccessFile(File(dir, "data.%03d".format(number)), "rw").apply { setLength(end) }
        private val buffer = ByteArray(256 * 1024)

        fun fits(size: Long) = end + HEADER_SIZE + size <= SEGMENT_SIZE

        /**
         * Appends the [size] bytes of BLTE data at [input] under [ekey]. Returns false, keeping nothing, when
         * the data doesn't hash to its key. On a read error the partial entry is cut off before rethrowing.
         */
        fun append(ekey: ByteArray, size: Int, input: InputStream, cancelled: () -> Boolean = { false }): Boolean {
            val start = end
            try {
                file.seek(start)
                file.write(LocalStorage.entryHeader(ekey, size, start))
                val check = KeyCheck()
                var left = size
                while (left > 0) {
                    if (cancelled()) throw CancellationException()
                    val n = input.read(buffer, 0, minOf(buffer.size, left))
                    if (n < 0) throw EOFException("Download ended early")
                    file.write(buffer, 0, n)
                    check.update(buffer, n)
                    left -= n
                }
                if (!check.matches(ekey)) {
                    file.setLength(start)
                    return false
                }
            } catch (e: Throwable) {
                runCatching { file.setLength(start) }
                throw e
            }
            end = start + HEADER_SIZE + size
            synchronized(this@StorageBuilder) {
                pending.add(ekey, number, start, HEADER_SIZE + size)
                dirty += this
            }
            return true
        }

        internal fun sync() = file.fd.sync()

        override fun close() {
            synchronized(this@StorageBuilder) {
                if (this in dirty) {
                    sync()
                    dirty -= this
                }
                if (end in 1 until SEGMENT_SIZE * 9 / 10) reusable += number to end
            }
            file.close()
        }
    }

    /**
     * An encoded key is the MD5 of the BLTE header when the file is split into chunks, or of the whole file
     * when it isn't. Both are tracked while the bytes stream past.
     */
    private class KeyCheck {
        private val whole = MessageDigest.getInstance("MD5")
        private val head = ByteArrayOutputStream()
        private var headerSize = -1

        fun update(bytes: ByteArray, n: Int) {
            whole.update(bytes, 0, n)
            var i = 0
            while (i < n) {
                val wanted = if (headerSize < 0) 8 else headerSize
                if (head.size() >= wanted) break
                val take = minOf(n - i, wanted - head.size())
                head.write(bytes, i, take)
                i += take
                if (headerSize < 0 && head.size() >= 8) {
                    val size = ByteBuffer.wrap(head.toByteArray(), 4, 4).int
                    headerSize = if (size in 8..MAX_BLTE_HEADER) size else 0
                }
            }
        }

        fun matches(ekey: ByteArray): Boolean =
            whole.digest().contentEquals(ekey) ||
                (headerSize > 0 && head.size() == headerSize && MessageDigest.getInstance("MD5").digest(head.toByteArray()).contentEquals(ekey))
    }

    companion object {
        const val JOURNAL = "journal.bin"
        private const val MAX_BLTE_HEADER = 4 * 1024 * 1024
        private val SEGMENT_NAME = Regex("data\\.\\d{3}")
    }
}

/** Journal records in flat arrays: a full build holds about a million of them. */
internal class RecordList {
    private var keys = ByteArray(16 * 1024)
    private var segments = IntArray(1024)
    private var offsets = LongArray(1024)
    private var sizes = IntArray(1024)
    var size = 0
        private set

    fun add(key: ByteArray, segment: Int, offset: Long, length: Int) {
        if (size == sizes.size) grow()
        System.arraycopy(key, 0, keys, size * 16, 16)
        segments[size] = segment
        offsets[size] = offset
        sizes[size] = length
        size++
    }

    fun addAll(other: RecordList) = other.forEach { k, s, o, l -> add(k, s, o, l) }

    fun clear() {
        size = 0
    }

    fun forEach(action: (ByteArray, Int, Long, Int) -> Unit) {
        for (i in 0 until size) action(keys.copyOfRange(i * 16, i * 16 + 16), segments[i], offsets[i], sizes[i])
    }

    /** Hands each key as the shared backing array and its offset, without copying. */
    fun forEachKey(action: (ByteArray, Int) -> Unit) {
        for (i in 0 until size) action(keys, i * 16)
    }

    fun toJournal(): ByteArray {
        val out = ByteBuffer.allocate(size * RECORD_SIZE)
        for (i in 0 until size) out.put(keys, i * 16, 16).putShort(segments[i].toShort()).putInt(offsets[i].toInt()).putInt(sizes[i])
        return out.array()
    }

    fun addJournal(bytes: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(bytes)
        val key = ByteArray(16)
        for (at in 0 until length step RECORD_SIZE) {
            buffer.position(at)
            buffer.get(key)
            add(key, buffer.short.toInt() and 0xFFFF, buffer.int.toLong() and 0xFFFFFFFFL, buffer.int)
        }
    }

    private fun grow() {
        val n = sizes.size * 2
        keys = keys.copyOf(n * 16)
        segments = segments.copyOf(n)
        offsets = offsets.copyOf(n)
        sizes = sizes.copyOf(n)
    }

    companion object {
        const val RECORD_SIZE = 26
    }
}
