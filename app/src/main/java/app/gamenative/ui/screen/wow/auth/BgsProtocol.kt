package app.gamenative.ui.screen.wow.auth

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Bounded protobuf support for the small BGS authentication surface used by [BgsClient]. */
internal object BgsProtocol {
    const val MAX_MESSAGE_BYTES = 1_048_576
    private const val MAX_FIELDS = 8192
    private const val MAX_FIELD_NUMBER = 0x1fffffff

    // Protocol layout follows Scuffle's auth/protobuf.py (MIT, Copyright 2026 nyrda).
    // https://github.com/nyrda/scuffle/tree/5efccb44777bba7e28e70ef782564b713e7373b5
    data class Field(val number: Int, val wire: Int, val integer: Long = 0, val bytes: ByteArray? = null)

    class Message internal constructor(val fields: List<Field>) {
        private fun single(number: Int): Field? {
            val matching = fields.filter { it.number == number }
            if (matching.size > 1) fail()
            return matching.firstOrNull()
        }

        fun integer(number: Int, default: Long = 0): Long = single(number)?.let {
            if (it.wire != 0) fail()
            it.integer
        } ?: default

        fun fixed32(number: Int, default: Long = 0): Long = single(number)?.let {
            if (it.wire != 5) fail()
            it.integer
        } ?: default

        fun bytes(number: Int): ByteArray? = single(number)?.let {
            if (it.wire != 2) fail()
            it.bytes
        }
    }

    fun parse(data: ByteArray): Message {
        if (data.size > MAX_MESSAGE_BYTES) fail()
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                if (offset == data.size) fail()
                val byte = data[offset++].toInt() and 0xff
                // Only one bit may be set in the tenth byte of a uint64.
                if (i == 9 && byte > 1) fail()
                value = value or ((byte and 0x7f).toLong() shl (i * 7))
                if (byte and 0x80 == 0) return value
            }
            fail()
        }
        fun take(size: Int): ByteArray {
            if (size < 0 || size > data.size - offset) fail()
            return data.copyOfRange(offset, offset + size).also { offset += size }
        }
        val fields = ArrayList<Field>()
        while (offset < data.size) {
            if (fields.size == MAX_FIELDS) fail()
            val tag = varint()
            if (tag < 0 || tag ushr 3 !in 1L..MAX_FIELD_NUMBER.toLong()) fail()
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            fields += when (wire) {
                0 -> Field(number, wire, integer = varint())
                1 -> Field(number, wire, bytes = take(8))
                2 -> {
                    val size = varint()
                    if (size < 0 || size > MAX_MESSAGE_BYTES) fail()
                    Field(number, wire, bytes = take(size.toInt()))
                }
                5 -> {
                    val bytes = take(4)
                    var value = 0L
                    bytes.forEachIndexed { i, b -> value = value or ((b.toLong() and 0xff) shl (8 * i)) }
                    Field(number, wire, integer = value)
                }
                else -> fail()
            }
        }
        return Message(fields)
    }

    private fun varint(value: Long): ByteArray {
        val result = ByteArrayOutputStream(10)
        var remaining = value
        while (remaining and -128L != 0L) {
            result.write((remaining.toInt() and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        result.write(remaining.toInt())
        return result.toByteArray()
    }

    private fun tag(number: Int, wire: Int): ByteArray {
        require(number in 1..MAX_FIELD_NUMBER)
        return varint((number.toLong() shl 3) or wire.toLong())
    }

    fun integer(number: Int, value: Long): ByteArray = tag(number, 0) + varint(value)
    fun bytes(number: Int, value: ByteArray): ByteArray {
        require(value.size <= MAX_MESSAGE_BYTES)
        return tag(number, 2) + varint(value.size.toLong()) + value
    }
    fun text(number: Int, value: String): ByteArray = bytes(number, value.toByteArray(Charsets.UTF_8))
    fun fixed32(number: Int, value: Long): ByteArray = tag(number, 5) +
        ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun fail(): Nothing = throw BgsException("Battle.net returned an invalid protocol message.")
}

/** Contains only fixed descriptions and numeric status codes, never remote text or credentials. */
class BgsException(message: String, val status: Long? = null) : IOException(message)
