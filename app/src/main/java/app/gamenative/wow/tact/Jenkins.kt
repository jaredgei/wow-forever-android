package app.gamenative.wow.tact

/**
 * Bob Jenkins' lookup3 hashes (`hashlittle`, `hashlittle2`), which CASC local storage uses to guard its
 * index files and entry headers. Byte-wise reads, so the result doesn't depend on alignment.
 */
object Jenkins {
    fun hashLittle(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, initval: Int = 0): Int {
        val state = IntArray(3) { -0x21524111 + length + initval }
        mixBlocks(data, offset, length, state)
        return state[2]
    }

    /** Returns `(c, b)`: `c` is the primary hash, and both feed the next call when hashing a chain of entries. */
    fun hashLittle2(data: ByteArray, offset: Int, length: Int, pc: Int, pb: Int): Pair<Int, Int> {
        val state = IntArray(3) { -0x21524111 + length + pc }
        state[2] += pb
        mixBlocks(data, offset, length, state)
        return state[2] to state[1]
    }

    /** Runs lookup3 over the input into [s] (a, b, c). Empty input leaves the state as it was, unfinalized. */
    private fun mixBlocks(k: ByteArray, offset: Int, length: Int, s: IntArray) {
        var a = s[0]
        var b = s[1]
        var c = s[2]
        var pos = offset
        var left = length
        while (left > 12) {
            a += word(k, pos, 4)
            b += word(k, pos + 4, 4)
            c += word(k, pos + 8, 4)
            a -= c; a = a xor Integer.rotateLeft(c, 4); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 6); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 8); b += a
            a -= c; a = a xor Integer.rotateLeft(c, 16); c += b
            b -= a; b = b xor Integer.rotateLeft(a, 19); a += c
            c -= b; c = c xor Integer.rotateLeft(b, 4); b += a
            left -= 12
            pos += 12
        }
        if (left == 0) {
            s[0] = a; s[1] = b; s[2] = c
            return
        }
        a += word(k, pos, minOf(left, 4))
        if (left > 4) b += word(k, pos + 4, minOf(left - 4, 4))
        if (left > 8) c += word(k, pos + 8, left - 8)
        c = c xor b; c -= Integer.rotateLeft(b, 14)
        a = a xor c; a -= Integer.rotateLeft(c, 11)
        b = b xor a; b -= Integer.rotateLeft(a, 25)
        c = c xor b; c -= Integer.rotateLeft(b, 16)
        a = a xor c; a -= Integer.rotateLeft(c, 4)
        b = b xor a; b -= Integer.rotateLeft(a, 14)
        c = c xor b; c -= Integer.rotateLeft(b, 24)
        s[0] = a; s[1] = b; s[2] = c
    }

    /** Up to four bytes as a little-endian word; missing high bytes count as zero. */
    private fun word(k: ByteArray, pos: Int, count: Int): Int {
        var v = 0
        for (i in 0 until count) v = v or ((k[pos + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
}
