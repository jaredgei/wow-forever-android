package app.gamenative.wow.tact

import java.io.IOException
import java.nio.ByteBuffer

/**
 * The decoded `download` manifest: every encoded file the game can stream, with its size and tags. Keys
 * stay in one flat array (16 bytes each) because a full WoW build lists over a million entries.
 */
class DownloadManifest private constructor(
    val keySize: Int,
    private val keys: ByteArray,
    private val sizes: LongArray,
    private val tags: Map<String, Pair<Int, ByteArray>>,
) {
    val count get() = sizes.size

    fun key(i: Int): ByteArray = keys.copyOfRange(i * keySize, (i + 1) * keySize)

    fun size(i: Int) = sizes[i]

    private fun has(tag: String, i: Int) = tags[tag]?.second?.let { it[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 } ?: false

    /**
     * Standard TACT selection, as [InstallManifest.select]: for every tag type holding one of [wanted], an
     * entry needs one wanted tag of that type. Entries carrying any [excluded] tag are left out.
     */
    fun select(wanted: Set<String>, excluded: Set<String> = emptySet()): IntArray {
        val required = tags.entries.groupBy({ it.value.first }, { it.key })
            .values.map { names -> names.toSet() intersect wanted }
            .filter { it.isNotEmpty() }
        val skip = excluded.filter { it in tags }
        return (0 until count).filter { i -> required.all { group -> group.any { has(it, i) } } && skip.none { has(it, i) } }.toIntArray()
    }

    companion object {
        /** Tags for optional content a normal install leaves out: high-resolution textures and alternate assets. */
        val OPTIONAL_TAGS = setOf("HighRes", "Alternate")

        fun parse(data: ByteArray): DownloadManifest {
            if (data.size < 11 || data[0] != 'D'.code.toByte() || data[1] != 'L'.code.toByte()) throw IOException("Bad download manifest")
            val buffer = ByteBuffer.wrap(data)
            val version = data[2].toInt()
            val keySize = data[3].toInt()
            val hasChecksum = data[4].toInt() != 0
            val count = buffer.getInt(5)
            val tagCount = buffer.getShort(9).toInt() and 0xFFFF
            var flagBytes = 0
            var pos = 11
            if (version >= 2) {
                flagBytes = data[11].toInt() and 0xFF
                pos = 12
                // Version 3 adds a base priority and three reserved bytes.
                if (version >= 3) pos += 4
            }
            val entrySize = keySize + 5 + 1 + (if (hasChecksum) 4 else 0) + flagBytes
            if (pos.toLong() + count.toLong() * entrySize > data.size) throw IOException("Download manifest is truncated")

            val keys = ByteArray(count * keySize)
            val sizes = LongArray(count)
            for (i in 0 until count) {
                System.arraycopy(data, pos, keys, i * keySize, keySize)
                var size = 0L
                for (b in 0 until 5) size = (size shl 8) or (data[pos + keySize + b].toLong() and 0xFF)
                sizes[i] = size
                pos += entrySize
            }

            val maskSize = (count + 7) / 8
            val tags = HashMap<String, Pair<Int, ByteArray>>()
            repeat(tagCount) {
                val end = (pos until data.size).firstOrNull { data[it].toInt() == 0 } ?: throw IOException("Download manifest is truncated")
                val name = String(data, pos, end - pos)
                pos = end + 1
                val type = buffer.getShort(pos).toInt() and 0xFFFF
                pos += 2
                tags[name] = type to data.copyOfRange(pos, pos + maskSize)
                pos += maskSize
            }
            return DownloadManifest(keySize, keys, sizes, tags)
        }
    }
}
