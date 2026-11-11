package app.gamenative.utils

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Minimal single pass editor for single line values (strings, dwords) of a Wine `*.reg` file.
 *
 * The file is handled byte-exact (ISO-8859-1 round trip), only the touched value lines change and
 * nothing is written when every value already matches. Keys are matched as they appear in the file
 * (backslashes doubled, e.g. `[Software\\Microsoft\\Cryptography]`), case-insensitive like the
 * Windows registry. Keys that are not in the file are never created; a missing value in an
 * existing key is added at the end of that key.
 */
object WineRegText {
    class Edit(val key: String, val name: String, val raw: String)

    fun string(key: String, name: String, value: String): Edit =
        Edit(key, name, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")

    fun dword(key: String, name: String, value: Int): Edit = Edit(key, name, "dword:" + "%08x".format(value))

    private fun header(key: String): String = ("[" + key.replace("\\", "\\\\") + "]").lowercase()

    private fun valuePrefix(name: String): String = ("\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"=").lowercase()

    /** Raw value text (`"abc"`, `dword:0000002a`, ...) or null when key or value is missing. */
    fun getRaw(text: String, key: String, name: String): String? {
        val wantHeader = header(key)
        val wantPrefix = valuePrefix(name)
        var inside = false
        for (line in text.split('\n')) {
            if (line.startsWith("[")) {
                inside = line.lowercase().startsWith(wantHeader)
            } else if (inside && line.lowercase().startsWith(wantPrefix)) {
                return line.substring(wantPrefix.length)
            }
        }
        return null
    }

    /** Applies [edits] to [text]; returns the new text and the number of changed or added values. */
    fun apply(text: String, edits: List<Edit>): Pair<String, Int> {
        val byHeader = HashMap<String, MutableList<Edit>>()
        for (edit in edits) byHeader.getOrPut(header(edit.key)) { ArrayList() }.add(edit)

        val lines = text.split('\n').toMutableList()
        var changed = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val sectionEdits = if (line.startsWith("[")) matchHeader(line, byHeader) else null
            if (sectionEdits == null) {
                i++
                continue
            }
            var end = i + 1
            while (end < lines.size && !lines[end].startsWith("[")) end++
            val pending = ArrayList(sectionEdits)
            for (j in i + 1 until end) {
                val current = lines[j]
                val lower = current.lowercase()
                val edit = pending.firstOrNull { lower.startsWith(valuePrefix(it.name)) } ?: continue
                pending.remove(edit)
                val prefixLength = valuePrefix(edit.name).length
                val currentRaw = current.substring(prefixLength)
                // A value continued on the next line (hex lists) is not ours to rewrite.
                if (currentRaw != edit.raw && !currentRaw.endsWith("\\")) {
                    lines[j] = current.substring(0, prefixLength) + edit.raw
                    changed++
                }
            }
            if (pending.isNotEmpty()) {
                var last = end - 1
                while (last > i && lines[last].isEmpty()) last--
                var insertAt = last + 1
                for (edit in pending) {
                    lines.add(insertAt++, "\"" + edit.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"=" + edit.raw)
                    changed++
                }
                end += pending.size
            }
            i = end
        }
        return if (changed == 0) text to 0 else lines.joinToString("\n") to changed
    }

    private fun matchHeader(line: String, byHeader: Map<String, List<Edit>>): List<Edit>? {
        val close = line.indexOf(']')
        if (close < 0) return null
        return byHeader[line.substring(0, close + 1).lowercase()]
    }

    /** Applies [edits] to [file]; writes (temp file + move) only when something changed. */
    fun applyToFile(file: File, edits: List<Edit>): Int {
        val text = String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1)
        val (updated, changed) = apply(text, edits)
        if (changed == 0) return 0
        val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            Files.write(temp.toPath(), updated.toByteArray(StandardCharsets.ISO_8859_1))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        return changed
    }
}
