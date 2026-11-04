package app.gamenative.addons

import java.io.IOException

/** What is about to be installed: where from, and which version when the source says. */
/** [file] is the zip a hand import comes from, so the user can tell two zips of one addon apart. */
data class IncomingAddon(val name: String, val sourceLabel: String, val version: String?, val file: String? = null)

/** Installed folders that one source owns (or nobody, for [record] == null) and that the new install would replace. */
data class OverlapGroup(val record: SourceRecord?, val folders: List<String>) {
    val name get() = record?.name ?: folders.first()
    val sourceLabel get() = record?.provider?.label ?: UNTRACKED_SOURCE
    val version get() = record?.version?.takeIf { it.isNotEmpty() }

    companion object {
        const val UNTRACKED_SOURCE = "Added by hand or imported"
    }
}

/** Everything the user needs to choose between the installed copy and the incoming one. */
data class OverlapPrompt(val incoming: IncomingAddon, val groups: List<OverlapGroup>)

/** Asked, off the main thread, before an install replaces folders that already hold an addon. True means go ahead. */
fun interface OverlapGate {
    fun allow(prompt: OverlapPrompt): Boolean

    companion object {
        val ALWAYS = OverlapGate { true }
    }
}

/** Thrown when the user chose to keep what is installed. */
class OverlapDeclined(val addon: String) : IOException("Kept the installed copy; $addon was not installed")

object AddonOverlaps {
    /**
     * Whether [owner] is the product ([provider], [id]). A GitHub record made before products had their own ids is the
     * repo's plain "owner/repo" with the product in its tag ("spoken/v3.8.0"), so it counts as "owner/repo#spoken".
     */
    internal fun isSameProduct(owner: SourceRecord, provider: Provider, id: String): Boolean {
        if (owner.provider != provider) return false
        if (owner.id == id) return true
        val prefix = id.substringAfter('#', "")
        return provider == Provider.GITHUB && prefix.isNotEmpty() && owner.id == id.substringBefore('#') && GitHubProvider.tagPrefix(owner.version) == prefix
    }

    /**
     * The installed folders [folders] would overwrite, grouped by the source that owns them. Reinstalling or updating the
     * same product ([provider] and [id] match the owner) is not an overlap. [id] is null for an untracked zip import,
     * which is never the same product as anything.
     */
    fun find(existing: Set<String>, records: List<SourceRecord>, provider: Provider?, id: String?, folders: List<String>): List<OverlapGroup> {
        val hits = folders.filter { it in existing }
        val byOwner = LinkedHashMap<SourceRecord?, MutableList<String>>()
        for (folder in hits) {
            val owner = records.firstOrNull { folder in it.folders }
            if (owner != null && provider != null && id != null && isSameProduct(owner, provider, id)) continue
            byOwner.getOrPut(owner) { ArrayList() }.add(folder)
        }
        return byOwner.map { (owner, list) -> OverlapGroup(owner, list) }
    }
}
