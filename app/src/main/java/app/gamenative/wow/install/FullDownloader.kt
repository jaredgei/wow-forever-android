package app.gamenative.wow.install

import app.gamenative.utils.Net
import app.gamenative.wow.casc.LocalStorage
import app.gamenative.wow.casc.StorageBuilder
import app.gamenative.wow.product.BuildInfo
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.tact.Blte
import app.gamenative.wow.tact.Cdn
import app.gamenative.wow.tact.DownloadManifest
import app.gamenative.wow.tact.TactConfig
import app.gamenative.wow.tact.hex
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Downloads everything the game would otherwise stream while it runs, about 50 GB for WoW Forever, straight
 * into the game's own storage. The files come from Blizzard's CDN archives in ranged requests, several at
 * once, into a separate folder; the finished storage replaces `Data/data` only once complete, so the game
 * keeps working (and can even be played) until then. Interrupted runs resume from the builder's journal.
 */
class FullDownloader(
    private val http: OkHttpClient = Net.http,
    private val cdnScheme: String = "http",
    private val workers: Int = 4,
    private val usableSpace: (File) -> Long = { generateSequence(it) { f -> f.parentFile }.first { f -> f.exists() }.usableSpace },
    /** The game must be closed while its storage is swapped. */
    private val gameRunning: () -> Boolean = { false },
    private val retryDelayMs: (Int) -> Long = { attempt -> minOf(60_000L, 2_000L shl attempt) },
    private val gamePollMs: Long = 5_000,
) {
    suspend fun run(request: InstallRequest, onProgress: (InstallProgress) -> Unit) = withContext(Dispatchers.IO) {
        val req = request.keepingInstallRegion()
        onProgress(InstallProgress(InstallProgress.Phase.RESOLVING, 0, 0, null))
        val data = File(req.root, "Data")
        recoverSwap(data)
        val row = BuildInfo.read(req.root)?.active(req.product)?.takeIf { it["Product"] == req.product.code }
            ?: throw IOException("Install ${req.product.displayName} before downloading its game data")
        val buildKey = row.getValue("Build Key")
        val cdn = Cdn(row["CDN Hosts"].orEmpty().split(" ").filter { it.isNotBlank() }, row["CDN Path"].orEmpty(), http, cdnScheme)
        val buildConfig = TactConfig.parse(String(config(req.root, cdn, buildKey)))
        val cdnConfig = TactConfig.parse(String(config(req.root, cdn, row.getValue("CDN Key"))))

        val downloadKey = buildConfig["download"]?.getOrNull(1) ?: throw IOException("This build has no download manifest")
        val manifest = DownloadManifest.parse(Blte.decode(cdn.require("data", downloadKey)))
        val selected = manifest.select(setOf("Windows", req.arch, req.locale, req.region.uppercase()), DownloadManifest.OPTIONAL_TAGS)
        val work = Work(manifest, selected)
        val builder = StorageBuilder(File(data, STAGING))
        builder.forEachKey { key, offset -> work.find(key, offset).takeIf { it >= 0 }?.let { work.markDone(it) } }
        val previous = work.doneBytes.get()

        val needed = ((work.totalBytes - previous) * SPACE_MARGIN).toLong() + SPACE_RESERVE
        val available = usableSpace(req.root)
        if (available < needed) throw InsufficientSpaceException(needed, available)

        val archives = cdnConfig["archives"].orEmpty()
        locate(work, archives, cdn, data, onProgress)
        val queue = ConcurrentLinkedQueue(work.tasks())

        val fetched = AtomicLong()
        cdn.onBytes = { fetched.addAndGet(it) }
        fun report() = onProgress(
            InstallProgress(
                InstallProgress.Phase.DOWNLOADING,
                work.doneBytes.get(),
                work.totalBytes,
                null,
                // Earlier runs' bytes came from the network too, so the time left stays honest after a resume.
                previous + fetched.get(),
                detail = "Game data · %,d of %,d files".format(work.doneCount.get(), work.size),
            ),
        )
        try {
            coroutineScope {
                val ticker = launch {
                    var ticks = 0
                    while (isActive) {
                        report()
                        if (++ticks % COMMIT_TICKS == 0) builder.commit()
                        delay(TICK_MS)
                    }
                }
                List(workers) { launch { worker(queue, work, archives, cdn, builder) } }.joinAll()
                ticker.cancel()
            }
        } finally {
            cdn.onBytes = null
            builder.commit()
        }
        report()
        if (work.skipped.get() > 0) Timber.w("Full download skipped ${work.skipped.get()} files the game will stream instead")

        onProgress(InstallProgress(InstallProgress.Phase.FINALIZING, work.totalBytes, work.totalBytes, null, detail = "Writing the game's index files"))
        val live = File(data, "data")
        builder.finish(LocalStorage.highestIndexVersion(live) + 1)
        while (gameRunning()) {
            onProgress(InstallProgress(InstallProgress.Phase.FINALIZING, work.totalBytes, work.totalBytes, null, detail = "Waiting for the game to close"))
            delay(gamePollMs)
        }
        swap(data)
        File(req.root, MARKER).writeText(buildKey)
        onProgress(InstallProgress(InstallProgress.Phase.DONE, work.totalBytes, work.totalBytes, null))
    }

    /** Takes tasks until none are left. A failed task goes back on the queue for another worker, a few times. */
    private suspend fun worker(queue: ConcurrentLinkedQueue<Task>, work: Work, archives: List<String>, cdn: Cdn, builder: StorageBuilder) {
        val job = kotlinx.coroutines.currentCoroutineContext().job
        var segment: StorageBuilder.Segment? = null
        fun segmentFor(size: Int): StorageBuilder.Segment {
            segment?.takeIf { it.fits(size.toLong()) }?.let { return it }
            segment?.close()
            return builder.openSegment(size.toLong()).also { segment = it }
        }
        try {
            while (true) {
                job.ensureActive()
                val task = queue.poll() ?: break
                try {
                    run(task, work, archives, cdn, ::segmentFor) { !job.isActive }
                } catch (e: IOException) {
                    val rest = task.entries.copyOfRange(task.finished, task.entries.size)
                    if (task.attempt + 1 >= MAX_ATTEMPTS) throw e
                    Timber.w(e, "Full download: retrying ${rest.size} files")
                    delay(retryDelayMs(task.attempt))
                    queue += Task(task.archive, rest, task.attempt + 1)
                }
            }
        } finally {
            segment?.close()
        }
    }

    /**
     * Streams one task's files into storage: a range of an archive, or a single loose file. [Task.finished]
     * counts the entries dealt with, so after an exception the rest can be tried again.
     */
    private fun run(task: Task, work: Work, archives: List<String>, cdn: Cdn, segmentFor: (Int) -> StorageBuilder.Segment, cancelled: () -> Boolean) {
        val first = task.entries.first()
        if (task.archive < 0) {
            val stream = cdn.open("data", hex(work.key(first)))
            if (stream == null) {
                work.skip(first)
            } else {
                stream.use {
                    val size = if (it.length > 0) it.length.toInt() else work.manifestSize(first).toInt()
                    store(work, first, size, it, segmentFor, cancelled)
                }
            }
            task.finished = 1
            return
        }
        val last = task.entries.last()
        val start = work.offset(first)
        val stream = cdn.open("data", archives[task.archive], start until work.offset(last) + work.length(last))
        if (stream == null) {
            task.entries.forEach(work::skip)
            task.finished = task.entries.size
            return
        }
        stream.use {
            var position = start
            for (j in task.entries) {
                skipFully(it, work.offset(j) - position)
                store(work, j, work.length(j), it, segmentFor, cancelled)
                position = work.offset(j) + work.length(j)
                task.finished++
            }
        }
    }

    private fun store(work: Work, j: Int, size: Int, input: InputStream, segmentFor: (Int) -> StorageBuilder.Segment, cancelled: () -> Boolean) {
        if (segmentFor(size).append(work.key(j), size, input, cancelled)) work.markDone(j) else work.skip(j)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) {
                left -= n
            } else if (input.read() < 0) {
                throw EOFException("Download ended early")
            } else {
                left--
            }
        }
    }

    /** Finds which archive holds each file still needed, fetching any archive index the install doesn't have yet. */
    private fun locate(work: Work, archives: List<String>, cdn: Cdn, data: File, onProgress: (InstallProgress) -> Unit) {
        val indices = File(data, "indices").apply { mkdirs() }
        archives.forEachIndexed { i, archive ->
            onProgress(
                InstallProgress(InstallProgress.Phase.RESOLVING, 0, 0, null, detail = "Archive indexes · ${i + 1} of ${archives.size}"),
            )
            val file = File(indices, "$archive.index")
            if (!file.isFile || file.length() == 0L) {
                val temp = File(indices, "$archive.index.download")
                if (!cdn.download("data", "$archive.index", temp)) return@forEachIndexed
                temp.renameTo(file)
            }
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes)
            for (block in 0 until bytes.size / INDEX_BLOCK * INDEX_BLOCK step INDEX_BLOCK) {
                var pos = block
                while (pos <= block + INDEX_BLOCK - INDEX_ENTRY) {
                    val j = work.find(bytes, pos)
                    if (j >= 0) work.locate(j, i, buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL, buffer.getInt(pos + 16))
                    pos += INDEX_ENTRY
                }
            }
        }
    }

    private fun config(root: File, cdn: Cdn, key: String): ByteArray =
        File(root, GameInstaller.configPath(key)).takeIf { it.isFile }?.readBytes() ?: cdn.require("config", key)

    /** Finishes or undoes a swap that a crash interrupted. */
    private fun recoverSwap(data: File) {
        val live = File(data, "data")
        val old = File(data, OLD)
        if (old.exists()) {
            if (live.exists()) old.deleteRecursively() else old.renameTo(live)
        }
        File(live, StorageBuilder.JOURNAL).takeIf { !File(data, STAGING).exists() }?.delete()
    }

    private fun swap(data: File) {
        val live = File(data, "data")
        val staging = File(data, STAGING)
        val old = File(data, OLD)
        if (live.exists() && !live.renameTo(old)) throw IOException("Couldn't move the old game data aside")
        if (!staging.renameTo(live)) {
            old.renameTo(live)
            throw IOException("Couldn't put the downloaded game data in place")
        }
        File(live, StorageBuilder.JOURNAL).delete()
        old.deleteRecursively()
    }

    /** One request's worth of files: a run of entries close together in one archive, or a loose file ([archive] -1). */
    private class Task(val archive: Int, val entries: IntArray, val attempt: Int = 0) {
        var finished = 0
    }

    /**
     * The files to download, by position in the selection: key, sizes, where each lives and whether it's done.
     * Keys are found through an open-addressing table on their first 8 bytes.
     */
    private class Work(private val manifest: DownloadManifest, private val selected: IntArray) {
        val size = selected.size
        private val keys = ByteArray(size * 16)
        private val archive = IntArray(size) { -1 }
        private val offsets = LongArray(size)
        private val lengths = IntArray(size)
        private val done = BooleanArray(size)
        private val slots = IntArray(Integer.highestOneBit(maxOf(size, 1)) * 4) { -1 }
        val totalBytes = selected.sumOf { manifest.size(it) }
        val doneBytes = AtomicLong()
        val doneCount = AtomicInteger()
        val skipped = AtomicInteger()

        init {
            selected.forEachIndexed { j, i ->
                System.arraycopy(manifest.key(i), 0, keys, j * 16, 16)
                var slot = slotOf(keys, j * 16)
                while (slots[slot] >= 0) slot = (slot + 1) and (slots.size - 1)
                slots[slot] = j
            }
        }

        fun key(j: Int) = keys.copyOfRange(j * 16, j * 16 + 16)

        fun manifestSize(j: Int) = manifest.size(selected[j])

        fun offset(j: Int) = offsets[j]

        fun length(j: Int) = lengths[j]

        /** The position of the 16-byte key at [bytes] + [at], or -1 if it isn't wanted. */
        fun find(bytes: ByteArray, at: Int): Int {
            var slot = slotOf(bytes, at)
            while (true) {
                val j = slots[slot]
                if (j < 0) return -1
                if ((0 until 16).all { keys[j * 16 + it] == bytes[at + it] }) return j
                slot = (slot + 1) and (slots.size - 1)
            }
        }

        fun locate(j: Int, archiveIndex: Int, offset: Long, length: Int) {
            if (done[j] || archive[j] >= 0) return
            archive[j] = archiveIndex
            offsets[j] = offset
            lengths[j] = length
        }

        @Synchronized
        fun markDone(j: Int) {
            if (done[j]) return
            done[j] = true
            doneBytes.addAndGet(manifestSize(j))
            doneCount.incrementAndGet()
        }

        /** A file that couldn't be stored counts as handled: the game streams it if it ever needs it. */
        fun skip(j: Int) {
            skipped.incrementAndGet()
            markDone(j)
        }

        /**
         * Archive files grouped into ranged requests: entries in offset order, merged while the gap between
         * them stays under [MERGE_GAP] and the request under [MAX_RANGE]. Files in no archive are fetched loose.
         */
        fun tasks(): List<Task> {
            val tasks = ArrayList<Task>()
            val byArchive = HashMap<Int, MutableList<Long>>()
            for (j in 0 until size) {
                if (done[j]) continue
                if (archive[j] < 0) {
                    tasks += Task(-1, intArrayOf(j))
                } else {
                    // Offsets fit in 32 bits and positions in 21, so one long sorts by offset and still carries j.
                    byArchive.getOrPut(archive[j]) { ArrayList() } += (offsets[j] shl 21) or j.toLong()
                }
            }
            for ((a, packed) in byArchive) {
                val order = packed.toLongArray().apply { sort() }.map { (it and 0x1FFFFF).toInt() }
                var run = ArrayList<Int>()
                for (j in order) {
                    val start = run.firstOrNull()?.let { offsets[it] }
                    val end = run.lastOrNull()?.let { offsets[it] + lengths[it] }
                    if (start != null && end != null && (offsets[j] - end > MERGE_GAP || offsets[j] + lengths[j] - start > MAX_RANGE)) {
                        tasks += Task(a, run.toIntArray())
                        run = ArrayList()
                    }
                    run += j
                }
                if (run.isNotEmpty()) tasks += Task(a, run.toIntArray())
            }
            return tasks
        }

        private fun slotOf(bytes: ByteArray, at: Int): Int {
            var h = 0L
            for (i in 0 until 8) h = (h shl 8) or (bytes[at + i].toLong() and 0xFF)
            return ((h xor (h ushr 29)) * -0x61c8864680b583ebL ushr 32).toInt() and (slots.size - 1)
        }
    }

    companion object {
        /** The folder the storage is built in, next to the game's own `Data/data`. */
        const val STAGING = "foreverywhere-download"
        private const val OLD = "foreverywhere-replaced"

        /** Holds the build key of the last full download, next to `.build.info`. */
        const val MARKER = ".foreverywhere-full"

        private const val INDEX_BLOCK = 4096
        private const val INDEX_ENTRY = 24
        private const val MERGE_GAP = 64 * 1024L
        private const val MAX_RANGE = 8L * 1024 * 1024
        private const val MAX_ATTEMPTS = 10
        private const val TICK_MS = 500L
        private const val COMMIT_TICKS = 4
        private const val SPACE_MARGIN = 1.05
        private const val SPACE_RESERVE = 1_000_000_000L

        /** Whether everything for the installed build is downloaded. A game update brings new files to stream. */
        fun isComplete(root: File, product: WowProduct): Boolean {
            val build = BuildInfo.read(root)?.active(product)?.get("Build Key") ?: return false
            return runCatching { File(root, MARKER).readText().trim() == build }.getOrDefault(false)
        }
    }
}
