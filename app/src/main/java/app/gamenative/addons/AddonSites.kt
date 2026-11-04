package app.gamenative.addons

import java.net.URLEncoder

/** Where to send someone who wants an addon's page. [isSearch] is true when we had to fall back to a web search. */
data class SiteLink(val url: String, val isSearch: Boolean) {
    val label get() = if (isSearch) "Search the web" else "Go to site"
}

object AddonSites {
    private val DIGITS = Regex("""\d{1,9}""")

    /**
     * The best page for an addon, so every addon has a button. In order: the page we know it came from, the addon's
     * own `X-Website`, its WoWInterface or CurseForge id from the TOC, and finally a web search for its name.
     */
    fun forAddon(title: String, providerPage: String? = null, website: String? = null, wowiId: String? = null, curseId: String? = null): SiteLink {
        fun web(url: String?) = url?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        web(providerPage)?.let { return SiteLink(it, false) }
        web(website)?.let { return SiteLink(it, false) }
        wowiId?.trim()?.takeIf { DIGITS.matches(it) }?.let { return SiteLink("https://www.wowinterface.com/downloads/info$it", false) }
        curseId?.trim()?.takeIf { DIGITS.matches(it) }?.let { return SiteLink("https://www.curseforge.com/projects/$it", false) }
        return SiteLink("https://duckduckgo.com/?q=" + URLEncoder.encode("$title WoW addon", "UTF-8"), true)
    }
}
