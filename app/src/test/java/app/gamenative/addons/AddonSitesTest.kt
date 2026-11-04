package app.gamenative.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSitesTest {
    @Test
    fun providerPageWinsOverEverythingElse() {
        val link = AddonSites.forAddon("TomTom", providerPage = "https://www.wowinterface.com/downloads/info7032-TomTom.html", website = "https://example.test", wowiId = "7032", curseId = "18808")
        assertEquals("https://www.wowinterface.com/downloads/info7032-TomTom.html", link.url)
        assertFalse(link.isSearch)
        assertEquals("Go to site", link.label)
    }

    @Test
    fun fallsThroughWebsiteThenWowiThenCurseThenSearch() {
        assertEquals("https://example.test/a", AddonSites.forAddon("X", website = " https://example.test/a ", wowiId = "1", curseId = "2").url)
        assertEquals("https://www.wowinterface.com/downloads/info7032", AddonSites.forAddon("X", wowiId = "7032", curseId = "18808").url)
        assertEquals("https://www.curseforge.com/projects/18808", AddonSites.forAddon("X", curseId = "18808").url)
        val search = AddonSites.forAddon("Advanced Interface Options")
        assertTrue(search.isSearch)
        assertEquals("https://duckduckgo.com/?q=Advanced+Interface+Options+WoW+addon", search.url)
        assertEquals("Search the web", search.label)
    }

    @Test
    fun rejectsUnsafeOrMalformedValues() {
        // A TOC is untrusted text: only http(s) links and plain numeric ids may become a link.
        assertTrue(AddonSites.forAddon("X", website = "javascript:alert(1)").isSearch)
        assertTrue(AddonSites.forAddon("X", website = "file:///etc/passwd").isSearch)
        assertTrue(AddonSites.forAddon("X", wowiId = "7032/../../evil", curseId = "abc").isSearch)
        assertTrue(AddonSites.forAddon("X", providerPage = "intent://x", website = "").isSearch)
    }

    @Test
    fun searchEncodesTheTitle() {
        assertEquals("https://duckduckgo.com/?q=A%26B+%22Quote%22+WoW+addon", AddonSites.forAddon("A&B \"Quote\"").url)
    }

    @Test
    fun parseToc_readsTheSiteFieldsFromARealToc() {
        val toc = AddonManager.parseToc("## Title: TomTom\r\n## X-Curse-Project-ID: 18808\r\n## X-WoWI-ID: 7032\r\n## X-Wago-ID: Rn6VaONd\r\n## X-Website: https://www.curseforge.com/wow/addons/tomtom\r\n")
        assertEquals("18808", toc.curseId)
        assertEquals("7032", toc.wowiId)
        assertEquals("https://www.curseforge.com/wow/addons/tomtom", toc.website)
        assertEquals(null, AddonManager.parseToc("## Title: Bare").website)
    }
}
