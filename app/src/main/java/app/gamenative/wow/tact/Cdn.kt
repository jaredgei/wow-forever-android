package app.gamenative.wow.tact

import app.gamenative.utils.Net
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Blizzard's TACT CDN. Hosts are tried in order: a 403/404 from any host means the file doesn't exist,
 * other failures fall through to the next host.
 */
class Cdn(
    val hosts: List<String>,
    val path: String,
    private val http: OkHttpClient = Net.http,
    private val scheme: String = "http",
) {
    /** Called with each block of bytes as it arrives from the network, for download speed and progress. */
    @Volatile
    var onBytes: ((Long) -> Unit)? = null

    fun url(host: String, kind: String, key: String) = "$scheme://$host/$path/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"

    fun require(kind: String, key: String) = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")

    fun fetch(kind: String, key: String, range: LongRange? = null): ByteArray? =
        withResponse(kind, key, range) { response ->
            ByteArrayOutputStream().also { copyCounting(response.body!!.byteStream(), it) }.toByteArray()
        }

    /** Streams a file to [dest] without holding it in memory. Returns false if the CDN doesn't have it. */
    fun download(kind: String, key: String, dest: File, range: LongRange? = null): Boolean =
        withResponse(kind, key, range) { response ->
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> copyCounting(response.body!!.byteStream(), out) }
            true
        } ?: false

    /**
     * The body from the first host that answers, counted as it's read; the caller closes it. Unlike [fetch],
     * a failure partway through is the caller's to handle, since it may already have used the start.
     * Returns null if the CDN doesn't have the file.
     */
    fun open(kind: String, key: String, range: LongRange? = null): CdnStream? {
        var failure: IOException? = null
        var missing = false
        for (host in hosts) {
            val request = Request.Builder()
                .url(url(host, kind, key))
                .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                .build()
            try {
                val response = http.newCall(request).execute()
                when {
                    response.isSuccessful -> return CdnStream(response)
                    response.code == 403 || response.code == 404 -> missing = true
                    else -> failure = IOException("HTTP ${response.code} from $host")
                }
                response.close()
            } catch (e: IOException) {
                failure = e
            }
        }
        if (missing) return null
        throw failure ?: IOException("No CDN hosts configured")
    }

    inner class CdnStream internal constructor(private val response: Response) : java.io.FilterInputStream(response.body!!.byteStream()) {
        /** The body's length in bytes, or -1 if the server didn't say. */
        val length = response.body!!.contentLength()

        override fun read(): Int = super.read().also { if (it >= 0) onBytes?.invoke(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes?.invoke(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) onBytes?.invoke(it) }

        override fun close() = response.close()
    }

    private fun copyCounting(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            onBytes?.invoke(read.toLong())
        }
    }

    private fun <T> withResponse(kind: String, key: String, range: LongRange?, read: (Response) -> T): T? {
        var failure: IOException? = null
        var missing = false
        for (host in hosts) {
            val request = Request.Builder()
                .url(url(host, kind, key))
                .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return read(response)
                        response.code == 403 || response.code == 404 -> missing = true
                        else -> failure = IOException("HTTP ${response.code} from $host")
                    }
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        if (missing) return null
        throw failure ?: IOException("No CDN hosts configured")
    }
}

/** Looks up encoded keys for content keys in the (large, chunked) encoding file using ranged reads. */
class EncodingLookup(private val cdn: Cdn, private val ekey: String) {
    private data class Chunk(val encodedOffset: Long, val encodedSize: Int, val decodedOffset: Long, val decodedSize: Int)

    private val chunks: List<Chunk> = parseChunkTable(ByteBuffer.wrap(fetchEncoded(0, 8)).getInt(4))

    private fun parseChunkTable(headerSize: Int): List<Chunk> {
        val header = ByteBuffer.wrap(fetchEncoded(0, headerSize))
        val count = header.getInt(8) and 0xFFFFFF
        var encoded = headerSize.toLong()
        var decoded = 0L
        return (0 until count).map { i ->
            val compressedSize = header.getInt(12 + i * 24)
            val decompressedSize = header.getInt(16 + i * 24)
            Chunk(encoded, compressedSize, decoded, decompressedSize).also {
                encoded += compressedSize
                decoded += decompressedSize
            }
        }
    }

    private var cachedChunk: Chunk? = null
    private var cachedData: ByteArray = ByteArray(0)

    private fun read(start: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var written = 0
        for (chunk in chunks) {
            val chunkEnd = chunk.decodedOffset + chunk.decodedSize
            if (chunkEnd <= start || chunk.decodedOffset >= start + length) continue
            val from = maxOf(start, chunk.decodedOffset) - chunk.decodedOffset
            val count = (minOf(start + length, chunkEnd) - chunk.decodedOffset - from).toInt()
            val mode = fetchEncoded(chunk.encodedOffset, 1)[0].toInt().toChar()
            val bytes = if (mode == 'N') {
                fetchEncoded(chunk.encodedOffset + 1 + from, count)
            } else {
                decodedChunk(chunk).copyOfRange(from.toInt(), from.toInt() + count)
            }
            bytes.copyInto(out, written)
            written += count
        }
        return out
    }

    private fun decodedChunk(chunk: Chunk): ByteArray {
        if (cachedChunk != chunk) {
            cachedData = Blte.decodeChunk(fetchEncoded(chunk.encodedOffset, chunk.encodedSize))
            cachedChunk = chunk
        }
        return cachedData
    }

    private fun fetchEncoded(offset: Long, size: Int) =
        cdn.fetch("data", ekey, offset until offset + size) ?: throw IOException("Encoding file missing")

    fun find(wanted: Set<String>): Map<String, String> {
        val header = ByteBuffer.wrap(read(0, 22))
        val ckeySize = header.get(3).toInt()
        val ekeySize = header.get(4).toInt()
        val pageSize = (header.getShort(5).toInt() and 0xFFFF) * 1024
        val pageCount = header.getInt(9)
        val especSize = header.getInt(18)
        val indexStart = 22L + especSize
        val indexEntrySize = ckeySize + 16
        val index = read(indexStart, pageCount * indexEntrySize)
        val firstKeys = (0 until pageCount).map { hex(index, it * indexEntrySize, ckeySize) }
        val pagesStart = indexStart + pageCount.toLong() * indexEntrySize

        val found = mutableMapOf<String, String>()
        wanted.groupBy { ckey -> firstKeys.binarySearch(ckey).let { if (it >= 0) it else -it - 2 } }
            .filterKeys { it >= 0 }
            .forEach { (page, ckeys) ->
                val data = read(pagesStart + page.toLong() * pageSize, pageSize)
                var pos = 0
                while (pos + 6 + ckeySize <= data.size && data[pos].toInt() != 0) {
                    val keyCount = data[pos].toInt() and 0xFF
                    val ckey = hex(data, pos + 6, ckeySize)
                    if (ckey in ckeys) found[ckey] = hex(data, pos + 6 + ckeySize, ekeySize)
                    pos += 6 + ckeySize + keyCount * ekeySize
                }
            }
        return found
    }
}

/** A CDN archive `.index` file: maps encoded keys to (offset, size) inside its archive. */
class ArchiveIndex(private val bytes: ByteArray) {
    fun locate(ekey: String): Pair<Long, Long>? {
        val buffer = ByteBuffer.wrap(bytes)
        for (block in 0 until bytes.size / BLOCK * BLOCK step BLOCK) {
            var pos = block
            while (pos <= block + BLOCK - ENTRY) {
                if (hex(bytes, pos, 16) == ekey) {
                    val size = buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL
                    val offset = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                    return offset to size
                }
                pos += ENTRY
            }
        }
        return null
    }

    /** Every entry of [wanted] found in this index, in one pass. */
    fun locateAll(wanted: Set<String>): Map<String, Pair<Long, Long>> {
        if (wanted.isEmpty()) return emptyMap()
        val buffer = ByteBuffer.wrap(bytes)
        val found = HashMap<String, Pair<Long, Long>>()
        for (block in 0 until bytes.size / BLOCK * BLOCK step BLOCK) {
            var pos = block
            while (pos <= block + BLOCK - ENTRY) {
                val key = hex(bytes, pos, 16)
                if (key in wanted) {
                    found[key] = (buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL) to (buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL)
                }
                pos += ENTRY
            }
        }
        return found
    }

    private companion object {
        const val BLOCK = 4096
        const val ENTRY = 24
    }
}
