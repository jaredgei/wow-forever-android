package app.gamenative.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val inlineToken = Regex("""\*\*(.+?)\*\*|`(.+?)`""")

fun renderReleaseNotes(markdown: String): AnnotatedString = buildAnnotatedString {
    val lines = markdown.trim().lines()
    lines.forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val heading = Regex("""^#{1,6}\s+(.*)$""").matchEntire(raw.trimEnd())
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)) {
                appendInline(heading.groupValues[1])
            }
        } else {
            appendInline(normalizeBullet(raw))
        }
    }
}

private fun normalizeBullet(line: String): String {
    val match = Regex("""^(\s*)[-*]\s+(.*)$""").matchEntire(line) ?: return line
    return "${match.groupValues[1]}\u2022 ${match.groupValues[2]}"
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var last = 0
    inlineToken.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
