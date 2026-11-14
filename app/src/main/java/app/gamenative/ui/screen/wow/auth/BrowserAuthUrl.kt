package app.gamenative.ui.screen.wow.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** URL boundary for Battle.net's browser challenge. Never include a rejected URL in an error. */
internal object BrowserAuthUrl {
    private const val MAX_URL_LENGTH = 16_384
    private const val MAX_TICKET_LENGTH = 2_048
    private val navigationDomains = listOf("battle.net", "blizzard.com", "battlenet.com.cn", "blizzard.cn")

    fun isInitialUrl(value: String): Boolean = httpsHost(value)?.let { belongsTo(it, "battle.net") } == true

    fun isNavigationUrl(value: String): Boolean = httpsHost(value)?.let { host ->
        navigationDomains.any { belongsTo(host, it) }
    } == true

    /** Only the main-frame callback may call this. Other URLs, including lookalike hosts, return null. */
    fun ticket(value: String): ByteArray? {
        val uri = parse(value) ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) || !uri.host.equals("localhost", ignoreCase = true) ||
            uri.port != 0 || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")
        ) return null
        val query = uri.rawQuery ?: return null
        // A single, exact parameter avoids duplicate-key and alternate-decoding ambiguity.
        if (!query.startsWith("ST=") || '&' in query || ';' in query) return null
        val decoded = try {
            URLDecoder.decode(query.substring(3), StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty() || decoded.length > MAX_TICKET_LENGTH || decoded.any { it.code !in 33..126 }) return null
        return decoded.toByteArray(StandardCharsets.US_ASCII)
    }

    /** The dialog shows the site's origin only. The path and query can contain sign-in credentials. */
    fun displayOrigin(value: String): String? = httpsHost(value)?.let { "https://$it" }

    private fun httpsHost(value: String): String? {
        val uri = parse(value) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        return uri.host?.lowercase(Locale.ROOT)
    }

    private fun belongsTo(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    private fun parse(value: String): URI? {
        if (value.length > MAX_URL_LENGTH || value.any { it.code <= 32 || it.code >= 127 }) return null
        return try {
            URI(value).takeUnless { it.isOpaque }
        } catch (_: Exception) {
            null
        }
    }
}
