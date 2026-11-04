package app.gamenative.addons

/**
 * How the Get Addons list is ordered. Neither source publishes a creation date or a real trending
 * score, so [CREATED] uses WoWInterface's sequential ids (newer addons get higher ids) and
 * [TRENDING] uses downloads over the last month.
 */
enum class AddonSort(val key: String, val label: String) {
    RELEVANCE("relevance", "Relevance"),
    DOWNLOADS("downloads", "Downloads"),
    UPDATED("updated", "Updated"),
    CREATED("created", "Created"),
    TRENDING("trending", "Trending");

    fun comparator(query: String): Comparator<CatalogAddon> {
        val byName = compareBy<CatalogAddon> { it.name.lowercase() }
        // Tukui publishes no download counts, so ElvUI and Tukui would rank as "0 downloads" and fall off the end of
        // the list. Browsing, keep them on top, as the default order does; a search is ordered purely by the field.
        val tukuiFirst = compareByDescending<CatalogAddon> { it.provider == Provider.TUKUI && query.isEmpty() }
        return when (this) {
            RELEVANCE -> compareBy<CatalogAddon> { matchRank(it, query) }
                .thenByDescending { it.provider == Provider.TUKUI && query.isEmpty() }
                .thenByDescending { it.downloadsMonthly }
                .then(byName)
            DOWNLOADS -> tukuiFirst.thenByDescending { it.downloadsTotal }.thenByDescending { it.downloadsMonthly }.then(byName)
            UPDATED -> compareByDescending<CatalogAddon> { it.updated }.then(byName)
            CREATED -> compareByDescending<CatalogAddon> { createdRank(it) }.then(byName)
            TRENDING -> tukuiFirst.thenByDescending { it.downloadsMonthly }.thenByDescending { it.downloadsTotal }.then(byName)
        }
    }

    companion object {
        fun fromKey(key: String?): AddonSort = entries.firstOrNull { it.key == key } ?: RELEVANCE

        /** 0 = name starts with the query, 1 = name contains it, 2 = anything else (author or folder match, or no query). */
        internal fun matchRank(addon: CatalogAddon, query: String): Int = when {
            query.isEmpty() -> 2
            addon.name.startsWith(query, true) -> 0
            addon.name.contains(query, true) -> 1
            else -> 2
        }

        internal fun createdRank(addon: CatalogAddon): Long =
            if (addon.provider == Provider.WOWINTERFACE) addon.id.toLongOrNull() ?: 0 else 0
    }
}
