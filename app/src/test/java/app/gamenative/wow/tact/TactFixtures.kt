package app.gamenative.wow.tact

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Deflater

/** Builders for synthetic TACT files, so tests can exercise the parsers without Blizzard's CDN. */
object TactFixtures {
    fun md5(data: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(data))

    fun chunk(data: ByteArray, mode: Char): ByteArray = when (mode) {
        'N' -> byteArrayOf('N'.code.toByte()) + data
        'Z' -> {
            val deflater = Deflater().apply {
                setInput(data)
                finish()
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            byteArrayOf('Z'.code.toByte()) + out.toByteArray()
        }
        else -> error("mode $mode")
    }

    /** A BLTE file. With one chunk and [headerless], uses the short form (header size 0). */
    fun blte(chunks: List<ByteArray>, mode: Char = 'N', headerless: Boolean = false): ByteArray {
        val encoded = chunks.map { chunk(it, mode) }
        if (headerless) {
            require(encoded.size == 1)
            return "BLTE".toByteArray() + ByteArray(4) + encoded[0]
        }
        val headerSize = 12 + 24 * encoded.size
        val header = ByteBuffer.allocate(headerSize)
        header.put("BLTE".toByteArray()).putInt(headerSize).putInt((0x0F shl 24) or encoded.size)
        encoded.forEachIndexed { i, e ->
            header.putInt(e.size).putInt(chunks[i].size).put(MessageDigest.getInstance("MD5").digest(e))
        }
        return header.array() + encoded.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    data class FileSpec(val name: String, val content: ByteArray, val tags: Set<String>)

    /** A decoded install manifest listing [files] with their tag memberships. */
    fun installManifest(files: List<FileSpec>): ByteArray {
        val tagNames = files.flatMap { it.tags }.distinct()
        val maskSize = (files.size + 7) / 8
        val out = ByteArrayOutputStream()
        out.write("IN".toByteArray())
        out.write(1)
        out.write(16)
        out.write(ByteBuffer.allocate(6).putShort(tagNames.size.toShort()).putInt(files.size).array())
        for (tag in tagNames) {
            out.write(tag.toByteArray())
            out.write(0)
            out.write(byteArrayOf(0, tagType(tag).toByte()))
            val mask = ByteArray(maskSize)
            files.forEachIndexed { i, f -> if (tag in f.tags) mask[i / 8] = (mask[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
            out.write(mask)
        }
        for (f in files) {
            out.write(f.name.toByteArray())
            out.write(0)
            out.write(MessageDigest.getInstance("MD5").digest(f.content))
            out.write(ByteBuffer.allocate(4).putInt(f.content.size).array())
        }
        return out.toByteArray()
    }

    /**
     * A decoded single-page encoding file mapping each ckey to an ekey. Keys must be hex strings of 16 bytes.
     */
    fun encodingFile(entries: Map<String, String>, pageSizeKb: Int = 4): ByteArray {
        val sorted = entries.toSortedMap()
        val pageSize = pageSizeKb * 1024
        val page = ByteBuffer.allocate(pageSize)
        for ((ckey, ekey) in sorted) {
            page.put(1).put(ByteArray(5)).put(unhex(ckey)).put(unhex(ekey))
        }
        val header = ByteBuffer.allocate(22)
        header.put("EN".toByteArray()).put(1).put(16).put(16).putShort(pageSizeKb.toShort()).putShort(pageSizeKb.toShort())
            .putInt(1).putInt(0).put(0).putInt(0)
        val index = ByteBuffer.allocate(32).put(unhex(sorted.firstKey())).put(ByteArray(16))
        return header.array() + index.array() + page.array()
    }

    /** A CDN archive index with one 4096-byte block holding [entries] of ekey to (offset, size). */
    fun archiveIndex(entries: Map<String, Pair<Int, Int>>): ByteArray {
        val block = ByteBuffer.allocate(4096)
        for ((ekey, loc) in entries.toSortedMap()) block.put(unhex(ekey)).putInt(loc.second).putInt(loc.first)
        return block.array() + ByteArray(64)
    }

    data class DownloadSpec(val ekey: String, val size: Long, val tags: Set<String>)

    /** A decoded download manifest in [version] 1, 2 or 3, listing [entries] with their tag memberships. */
    fun downloadManifest(entries: List<DownloadSpec>, version: Int = 1): ByteArray {
        val tagNames = entries.flatMap { it.tags }.distinct()
        val maskSize = (entries.size + 7) / 8
        val out = ByteArrayOutputStream()
        out.write("DL".toByteArray())
        out.write(byteArrayOf(version.toByte(), 16, 0))
        out.write(ByteBuffer.allocate(6).putInt(entries.size).putShort(tagNames.size.toShort()).array())
        if (version >= 2) out.write(1)
        if (version >= 3) out.write(ByteArray(4))
        for (e in entries) {
            out.write(unhex(e.ekey))
            out.write(ByteBuffer.allocate(8).putLong(e.size).array(), 3, 5)
            out.write(0)
            if (version >= 2) out.write(0)
        }
        for (tag in tagNames) {
            out.write(tag.toByteArray())
            out.write(0)
            out.write(byteArrayOf(0, tagType(tag).toByte()))
            val mask = ByteArray(maskSize)
            entries.forEachIndexed { i, e -> if (tag in e.tags) mask[i / 8] = (mask[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
            out.write(mask)
        }
        return out.toByteArray()
    }

    /** Tag types as Blizzard groups them: 1 platform, 2 architecture, 3 locale, 4 region, 5 other. */
    fun tagType(tag: String) = when {
        tag in setOf("Windows", "OSX", "Android") -> 1
        tag in setOf("arm64", "x86_64", "x86_32") -> 2
        Regex("[a-z]{2}[A-Z]{2}").matches(tag) -> 3
        tag in setOf("US", "EU", "KR", "TW", "CN") -> 4
        else -> 5
    }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
