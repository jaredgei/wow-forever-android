package app.gamenative.addons

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Turns the BBCode WoWInterface uses, and the markdown everyone else uses, into plain styled text: bold, italics,
 * headings and bullets are kept; links keep their text; images are dropped (see [extractImageUrls]).
 */
object Markup {
    private val BB_TAG = Regex("""^\[(/?)([A-Za-z*]+)(?:=([^\]]*))?]""")
    private val BB_IMG = Regex("""\[IMG[^\]]*](https://[^\[\s]+)\[/IMG]""", RegexOption.IGNORE_CASE)
    private val MD_IMG = Regex("""!\[[^\]]*]\((https://[^)\s]+)[^)]*\)""")
    private val HTML_IMG = Regex("""<img[^>]+src=["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val MD_BULLET = Regex("""^(\s*)[*+-]\s+(.*)$""")
    private val MD_RULE = Regex("""^\s*([-*_]\s*){3,}$""")
    // A linked image (a badge button) comes first so its inner image isn't mistaken for a plain link; it keeps the alt text.
    private val MD_INLINE = Regex("""\[!\[([^\]]*)]\([^)]*\)]\([^)]*\)|!\[[^\]]*]\([^)]*\)|\[([^\]]*)]\([^)]*\)|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])|<[^>]+>""")
    // Badges (shields.io) and SVGs aren't screenshots, and Android can't decode SVG anyway.
    private val NOT_PREVIEWS = Regex("""shields\.io|badge|\.svg(\?|$)""", RegexOption.IGNORE_CASE)
    private val BLANK_RUNS = Regex("""\n{3,}""")

    /** Every https image the text embeds, in order. */
    fun extractImageUrls(raw: String): List<String> =
        (BB_IMG.findAll(raw).map { it.groupValues[1] } + MD_IMG.findAll(raw).map { it.groupValues[1] } + HTML_IMG.findAll(raw).map { it.groupValues[1] })
            .filterNot { NOT_PREVIEWS.containsMatchIn(it) }.distinct().toList()

    fun render(raw: String, markdown: Boolean): AnnotatedString {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = if (markdown) renderMarkdown(text) else renderBbcode(text)
        return trimBlankRuns(out)
    }

    /** Collapses runs of blank lines and trims the ends, keeping every span. */
    private fun trimBlankRuns(s: AnnotatedString): AnnotatedString {
        val text = s.text
        val collapsed = buildAnnotatedString {
            var last = 0
            for (m in BLANK_RUNS.findAll(text)) {
                append(s.subSequence(last, m.range.first))
                append("\n\n")
                last = m.range.last + 1
            }
            append(s.subSequence(last, text.length))
        }
        val start = collapsed.text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return AnnotatedString("")
        val end = collapsed.text.indexOfLast { !it.isWhitespace() } + 1
        return collapsed.subSequence(start, end)
    }

    private val BB_KNOWN = setOf(
        "B", "I", "U", "CODE", "PHP", "HTML", "SIZE", "IMG", "ATTACH", "MEDIA", "YOUTUBE", "*", "LIST", "QUOTE", "CENTER", "LEFT",
        "RIGHT", "INDENT", "COLOR", "FONT", "HIGHLIGHT", "URL", "EMAIL", "SPOILER", "TABLE", "TR", "TD", "TH", "H1", "H2", "H3",
    )

    private fun renderBbcode(text: String): AnnotatedString = buildAnnotatedString {
        var bold = 0
        var italic = 0
        var underline = 0
        var big = 0
        var mono = 0
        fun style() = SpanStyle(
            fontWeight = if (bold > 0 || big > 0) FontWeight.Bold else null,
            fontStyle = if (italic > 0) FontStyle.Italic else null,
            textDecoration = if (underline > 0) TextDecoration.Underline else null,
            fontSize = if (big > 0) 16.sp else TextUnit.Unspecified,
            fontFamily = if (mono > 0) FontFamily.Monospace else null,
        )
        val literal = StringBuilder()
        fun flush() {
            if (literal.isEmpty()) return
            withStyle(style()) { append(literal.toString()) }
            literal.clear()
        }
        var skipUntilClose: String? = null
        var i = 0
        while (i < text.length) {
            val m = if (text[i] == '[') BB_TAG.find(text.substring(i, minOf(text.length, i + 80))) else null
            val name = m?.groupValues?.get(2)?.uppercase()
            if (m == null || name !in BB_KNOWN) {
                if (skipUntilClose == null) literal.append(text[i])
                i++
                continue
            }
            val closing = m.groupValues[1] == "/"
            val arg = m.groupValues[3]
            i += m.value.length
            if (skipUntilClose != null) {
                if (closing && name == skipUntilClose) skipUntilClose = null
                continue
            }
            val delta = if (closing) -1 else 1
            when (name) {
                "B" -> { flush(); bold += delta }
                "I" -> { flush(); italic += delta }
                "U" -> { flush(); underline += delta }
                "CODE", "PHP", "HTML" -> { flush(); mono += delta; literal.append('\n') }
                "SIZE" -> if (closing || (arg.toIntOrNull() ?: 0) >= 4) { flush(); big += delta }
                "H1", "H2", "H3" -> { flush(); big += delta; literal.append('\n') }
                "IMG", "ATTACH", "MEDIA", "YOUTUBE" -> if (!closing) skipUntilClose = name
                "*" -> literal.append("\n• ")
                "LIST", "QUOTE", "CENTER", "LEFT", "RIGHT", "INDENT", "TR" -> literal.append('\n')
                // Colors, fonts, links and the like only decorate their text, which we keep.
                else -> {}
            }
        }
        flush()
    }

    private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
        var inFence = false
        var afterHeading = false
        for (line in text.lines()) {
            // A heading already ends its line; the blank line most sources add after it would double the gap.
            if (afterHeading && line.isBlank()) { afterHeading = false; continue }
            afterHeading = false
            if (line.trimStart().startsWith("```")) { inFence = !inFence; continue }
            if (inFence) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(line) }
                append('\n')
                continue
            }
            val heading = MD_HEADING.find(line)
            val bullet = MD_BULLET.find(line)
            when {
                MD_RULE.matches(line) -> append('\n')
                heading != null -> {
                    val size = when (heading.groupValues[1].length) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) { inline(heading.groupValues[2].trim(), this@buildAnnotatedString) }
                    append('\n')
                    afterHeading = true
                }
                bullet != null -> {
                    val depth = (bullet.groupValues[1].length + 3) / 4 // 2 or 4 spaces per nesting level
                    append("  ".repeat(depth.coerceAtMost(4)) + "• ")
                    inline(bullet.groupValues[2], this)
                    append('\n')
                }
                else -> { inline(line.trim(), this); append('\n') }
            }
        }
    }

    private fun inline(s: String, b: AnnotatedString.Builder) {
        var last = 0
        for (m in MD_INLINE.findAll(s)) {
            b.append(s.substring(last, m.range.first))
            val v = m.value
            when {
                v.startsWith("[![") -> b.append(m.groupValues[1])
                v.startsWith("![") -> {}
                v.startsWith("<") -> {}
                v.startsWith("[") -> b.append(m.groupValues[2])
                v.startsWith("**") -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[3]) }
                v.startsWith("__") -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[4]) }
                v.startsWith("`") -> b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[5]) }
                else -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[6]) }
            }
            last = m.range.last + 1
        }
        b.append(s.substring(last))
    }
}
