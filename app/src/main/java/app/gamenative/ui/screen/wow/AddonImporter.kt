package app.gamenative.ui.screen.wow

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Installs add-ons from a downloaded .zip into the game's Interface/AddOns folder, so players don't need a PC to add
 * them. There's no catalog or update checking; players download add-ons themselves.
 */
internal object AddonImporter {
    // Flavor suffixes on .toc names, e.g. "Details_Mainline.toc" belongs to the "Details" folder.
    private val FLAVOR_SUFFIX = Regex("[_-](Mainline|Classic|Vanilla|TBC|BCC|Wrath|WOTLKC|Cata|Mists|Standard)$", RegexOption.IGNORE_CASE)

    /**
     * Extracts every add-on in [zip] into [addonsDir], replacing an existing copy of each, and returns their folder
     * names. An add-on is the shallowest folder holding a .toc file; .toc files deeper inside it (bundled libraries)
     * stay part of it. Zips that wrap the add-on in an extra folder, like GitHub downloads, or have its .toc at the
     * root, are installed under the name of the .toc.
     */
    fun install(zip: File, addonsDir: File): List<String> = ZipFile(zip).use { archive ->
        // Zips made on a Mac also carry "__MACOSX/" folders of "._" metadata files.
        val files = archive.entries().toList().filterNot {
            it.isDirectory || it.name.startsWith("__MACOSX/") || it.name.substringAfterLast('/').startsWith("._")
        }
        val tocs = files.map { it.name }.filter { it.endsWith(".toc", ignoreCase = true) }
        val dirs = tocs.map { it.substringBeforeLast('/', "") }.toSet()
        fun isInside(dir: String, parent: String) = dir != parent && (parent.isEmpty() || dir.startsWith("$parent/"))
        val roots = tocs.associate { toc -> toc.substringBeforeLast('/', "") to addonName(toc) }
            .filterKeys { dir -> dirs.none { isInside(dir, it) } }
        if (roots.isEmpty()) throw IOException("No add-ons found in this file. Add-ons have a .toc file inside.")

        addonsDir.mkdirs()
        val base = addonsDir.canonicalFile
        roots.forEach { (dir, name) ->
            val target = File(base, name).canonicalFile
            if (target.parentFile != base) throw IOException("Invalid add-on name: $name")
            target.deleteRecursively()
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            files.filter { it.name.startsWith(prefix) }.forEach { entry ->
                val dest = File(target, entry.name.removePrefix(prefix)).canonicalFile
                // Rejects entries like "../../x" that would write outside the add-on folder.
                if (!dest.path.startsWith(target.path + File.separator)) throw IOException("Invalid path in zip: ${entry.name}")
                dest.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        roots.values.sorted()
    }

    // WoW only loads an add-on whose folder matches its .toc, so a folder that matches is kept as is.
    private fun addonName(toc: String): String {
        val tocName = toc.substringAfterLast('/').substringBeforeLast('.')
        val folder = toc.substringBeforeLast('/', "").substringAfterLast('/')
        return if (folder.isNotEmpty() && tocName.startsWith(folder, ignoreCase = true)) folder else tocName.replace(FLAVOR_SUFFIX, "")
    }
}
