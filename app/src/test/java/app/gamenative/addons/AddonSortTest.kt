package app.gamenative.addons

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonSortTest {
    private fun addon(id: String, name: String, provider: Provider = Provider.WOWINTERFACE, monthly: Long = 0, total: Long = 0, updated: Long = 0) =
        CatalogAddon(provider, id, name, "", "", emptyList(), monthly, total, emptyList(), null, null, updated = updated)

    private val a = addon("10", "Alpha", monthly = 5, total = 900, updated = 100)
    private val b = addon("30", "Beta", monthly = 50, total = 100, updated = 300)
    private val c = addon("20", "Gamma Alpha", monthly = 20, total = 500, updated = 200)
    private val all = listOf(a, b, c)

    private fun order(sort: AddonSort, q: String = "") = all.sortedWith(sort.comparator(q)).map { it.name }

    @Test fun downloadsUsesTotal() = assertEquals(listOf("Alpha", "Gamma Alpha", "Beta"), order(AddonSort.DOWNLOADS))
    @Test fun trendingUsesMonthly() = assertEquals(listOf("Beta", "Gamma Alpha", "Alpha"), order(AddonSort.TRENDING))
    @Test fun updatedIsNewestFirst() = assertEquals(listOf("Beta", "Gamma Alpha", "Alpha"), order(AddonSort.UPDATED))
    @Test fun createdIsHighestIdFirst() = assertEquals(listOf("Beta", "Gamma Alpha", "Alpha"), order(AddonSort.CREATED))
    @Test fun relevanceRanksPrefixThenContains() = assertEquals(listOf("Alpha", "Gamma Alpha", "Beta"), order(AddonSort.RELEVANCE, "alpha"))
    @Test fun unknownKeyFallsBackToRelevance() = assertEquals(AddonSort.RELEVANCE, AddonSort.fromKey("nope"))

    // Tukui publishes no download counts, so ElvUI would otherwise rank as "0 downloads" and fall off the 100-row list.
    private val elvui = addon("elvui", "ElvUI", Provider.TUKUI, monthly = 0, total = 0, updated = 50)
    private val withTukui = listOf(a, b, c, elvui)
    private fun orderWithTukui(sort: AddonSort, q: String = "") = withTukui.sortedWith(sort.comparator(q)).map { it.name }

    @Test fun tukuiStaysOnTopOfDownloadsAndTrendingWhenNotSearching() {
        assertEquals("ElvUI", orderWithTukui(AddonSort.DOWNLOADS).first())
        assertEquals("ElvUI", orderWithTukui(AddonSort.TRENDING).first())
        assertEquals(listOf("ElvUI", "Alpha", "Gamma Alpha", "Beta"), orderWithTukui(AddonSort.DOWNLOADS))
    }

    @Test fun tukuiIsNotPinnedWhileSearching() {
        // With a query the user is looking for something specific, so the order is purely by the chosen field.
        assertEquals(listOf("Alpha", "Gamma Alpha", "Beta", "ElvUI"), orderWithTukui(AddonSort.DOWNLOADS, "x"))
        assertEquals(listOf("Beta", "Gamma Alpha", "Alpha", "ElvUI"), orderWithTukui(AddonSort.TRENDING, "x"))
    }

    @Test fun updatedAndCreatedStillUseRealDataForTukui() {
        assertEquals(listOf("Beta", "Gamma Alpha", "Alpha", "ElvUI"), orderWithTukui(AddonSort.UPDATED))
        assertEquals("ElvUI", orderWithTukui(AddonSort.CREATED).last())
    }
}
