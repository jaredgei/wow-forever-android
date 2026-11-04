package app.gamenative.addons

import app.gamenative.ui.screen.wow.WowClientDownloader.FLAVOR_DIR
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class InstalledAddon(
    val folder: String,
    val title: String,
    val version: String?,
    val interfaceVersion: String?,
    val author: String?,
    val notes: String?,
    val website: String? = null,
    val wowiId: String? = null,
    val curseId: String? = null,
)

data class TocInfo(
    val title: String?,
    val version: String?,
    val interfaceVersion: String?,
    val author: String?,
    val notes: String?,
    val website: String? = null,
    val wowiId: String? = null,
    val curseId: String? = null,
)

/** A zip that holds at least one addon, with the addon folders it would install. */
data class ImportCandidate(val file: File, val folders: List<String>)

data class ImportScan(val candidates: List<ImportCandidate>, val skipped: List<File>)

data class ImportResult(val file: File, val folders: List<String>, val error: String?, val deleted: Boolean) {
    val ok get() = error == null
}

/** Reads, installs and removes addons under the game's `Interface/AddOns` folder. */
object AddonManager {
    private const val STAGING_DIR = ".addon-staging"
    // Installs share one staging folder, so they run one at a time.
    private val installLock = Any()

    /** Test seam: called after each file is extracted, so a test can hold one install partway through. */
    @Volatile internal var afterFileExtracted: (() -> Unit)? = null
    private const val MAX_ENTRIES = 50_000
    private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
    private val COLOR_CODES = Regex("""\|c[0-9a-fA-F]{8}|\|r|\|T[^|]*\|t|\|A[^|]*\|a""")
    private val LINE_BREAKS = Regex("""\|[nN]""")
    private val LOAD_TAGS = Regex("""\s*\[(?:Allow|Exclude)LoadGameType[^\]]*]""")
    private val FOREVER_TAG = Regex("""\[AllowLoadGameType[^\]]*\bcamelot\b[^\]]*]""", RegexOption.IGNORE_CASE)

    fun addonsDir(gameRoot: File) = File(gameRoot, "$FLAVOR_DIR/Interface/AddOns")

    /**
     * Modern TOCs repeat a field once per game type, each tagged like `[AllowLoadGameType camelot]` ("camelot" is
     * WoW Forever's game type). Pick the Forever line, else an untagged one, else the first, and drop the tags.
     */
    fun parseToc(text: String): TocInfo {
        val fields = HashMap<String, MutableList<String>>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("##")) continue
            val body = line.removePrefix("##").trim()
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val value = body.substring(colon + 1).trim()
            if (value.isNotEmpty()) fields.getOrPut(body.substring(0, colon).trim().lowercase()) { ArrayList() }.add(value)
        }
        fun clean(key: String): String? {
            val values = fields[key] ?: return null
            val chosen = values.firstOrNull { FOREVER_TAG.containsMatchIn(it) }
                ?: values.firstOrNull { !LOAD_TAGS.containsMatchIn(it) }
                ?: values.first()
            return chosen.replace(LOAD_TAGS, "").replace(LINE_BREAKS, " ").replace(COLOR_CODES, "").trim().takeIf { it.isNotEmpty() }
        }
        return TocInfo(
            clean("title"), clean("version"), clean("interface"), clean("author"), clean("notes"),
            website = clean("x-website"), wowiId = clean("x-wowi-id"), curseId = clean("x-curse-project-id"),
        )
    }

    fun listInstalled(gameRoot: File): List<InstalledAddon> {
        val dirs = addonsDir(gameRoot).listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val toc = findToc(dir) ?: return@mapNotNull null
            val info = runCatching { parseToc(toc.readText()) }.getOrNull() ?: TocInfo(null, null, null, null, null)
            InstalledAddon(dir.name, info.title ?: dir.name, info.version, info.interfaceVersion, info.author, info.notes, info.website, info.wowiId, info.curseId)
        }.sortedBy { it.title.lowercase() }
    }

    fun remove(gameRoot: File, folder: String): Boolean {
        require(isSafeName(folder)) { "Bad addon folder name: $folder" }
        val dir = File(addonsDir(gameRoot), folder)
        return dir.isDirectory && dir.deleteRecursively()
    }

    /** The addon folders a zip would install, or empty when it holds no addon. */
    fun inspectZip(zip: File): List<String> = try {
        ZipFile(zip).use { addonRoots(it).folders }
    } catch (_: Exception) {
        emptyList()
    }

    /** Extracts every addon in [zip] into [addonsDir], replacing any copy that is already installed. */
    fun installZip(zip: File, addonsDir: File): List<String> = synchronized(installLock) {
        ZipFile(zip).use { zf ->
            val roots = addonRoots(zf)
            if (roots.folders.isEmpty()) throw IOException("No addon found in ${zip.name}")
            val staging = File(addonsDir.parentFile, STAGING_DIR).also { it.deleteRecursively(); it.mkdirs() }
            try {
                extract(zf, roots, staging)
                addonsDir.mkdirs()
                for (folder in roots.folders) {
                    val target = File(addonsDir, folder)
                    if (target.exists() && !target.deleteRecursively()) throw IOException("Couldn't replace $folder")
                    if (!File(staging, folder).renameTo(target)) throw IOException("Couldn't install $folder")
                }
            } finally {
                staging.deleteRecursively()
            }
            roots.folders
        }
    }

    fun scanFolder(dir: File): ImportScan {
        val zips = dir.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: return ImportScan(emptyList(), emptyList())
        val candidates = ArrayList<ImportCandidate>()
        val skipped = ArrayList<File>()
        for (zip in zips) {
            val folders = inspectZip(zip)
            if (folders.isEmpty()) skipped.add(zip) else candidates.add(ImportCandidate(zip, folders))
        }
        return ImportScan(candidates, skipped)
    }

    /** Installs each file; a file is deleted only when [deleteAfter] is set and it installed cleanly. */
    fun importFiles(gameRoot: File, files: List<File>, deleteAfter: Boolean, gate: OverlapGate = OverlapGate.ALWAYS): List<ImportResult> {
        val addons = addonsDir(gameRoot)
        return files.map { file ->
            try {
                val incoming = inspectZip(file)
                AddonInstaller.checkOverlap(gameRoot, null, null, IncomingAddon(incoming.firstOrNull().orEmpty() + if (incoming.size > 1) " +${incoming.size - 1} more" else "", "Imported zip", null, file.name), incoming, gate)
                val folders = installZip(file, addons)
                // Imports aren't tracked, so folders they replaced no longer belong to their old source.
                AddonSources.release(gameRoot, folders)
                val deleted = deleteAfter && file.delete()
                ImportResult(file, folders, null, deleted)
            } catch (e: Exception) {
                ImportResult(file, emptyList(), e.message ?: "Import failed", false)
            }
        }
    }

    private class Roots(val folders: List<String>, val prefix: String)

    private fun normalized(entry: ZipEntry) = entry.name.replace('\\', '/').trimStart('/')

    private fun isSafeName(name: String) =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.startsWith(".")

    private fun isTocFor(folder: String, file: String): Boolean {
        if (!file.endsWith(".toc", ignoreCase = true)) return false
        val base = file.dropLast(4)
        return base.equals(folder, ignoreCase = true) ||
            base.startsWith("$folder-", ignoreCase = true) ||
            base.startsWith("${folder}_", ignoreCase = true)
    }

    private fun findToc(dir: File): File? {
        val files = (dir.listFiles { f -> f.isFile } ?: return null).sortedBy { it.name }
        // An addon often ships one TOC per game flavor; the Mainline one is the closest to what WoW Forever loads.
        return files.firstOrNull { it.name.equals("${dir.name}.toc", ignoreCase = true) }
            ?: files.firstOrNull { it.name.contains("mainline", ignoreCase = true) && isTocFor(dir.name, it.name) }
            ?: files.firstOrNull { isTocFor(dir.name, it.name) }
    }

    /** Finds the addon folders at the top of the zip, or one folder down when the zip wraps them (e.g. `Foo-1.2/Foo/`). */
    private fun addonRoots(zf: ZipFile): Roots {
        val names = zf.entries().asSequence().map { normalized(it) }.filter { it.isNotEmpty() && !it.startsWith("__MACOSX/") }.toList()
        if (names.size > MAX_ENTRIES) throw IOException("Zip has too many files")
        fun foldersAt(prefix: String): List<String> {
            val tops = names.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            return tops.filter { it.count { c -> c == '/' } == 1 && !it.endsWith("/") }
                .map { it.substringBefore('/') to it.substringAfter('/') }
                .filter { (folder, file) -> isSafeName(folder) && isTocFor(folder, file) }
                .map { it.first }.distinct()
        }
        val top = foldersAt("")
        if (top.isNotEmpty()) return Roots(top, "")
        val wrappers = names.map { it.substringBefore('/') }.filter { names.any { n -> n.startsWith("$it/") } }.distinct()
        if (wrappers.size == 1 && isSafeName(wrappers[0])) {
            val inner = foldersAt("${wrappers[0]}/")
            if (inner.isNotEmpty()) return Roots(inner, "${wrappers[0]}/")
        }
        return Roots(emptyList(), "")
    }

    private fun extract(zf: ZipFile, roots: Roots, staging: File) {
        val base = staging.canonicalFile
        var total = 0L
        for (entry in zf.entries()) {
            val name = normalized(entry)
            if (!name.startsWith(roots.prefix)) continue
            val rel = name.removePrefix(roots.prefix)
            if (rel.isEmpty() || roots.folders.none { rel == it || rel.startsWith("$it/") }) continue
            val out = File(base, rel).canonicalFile
            if (out.path != base.path && !out.path.startsWith(base.path + File.separator)) throw IOException("Unsafe path in zip: $name")
            if (entry.isDirectory) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            zf.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNCOMPRESSED_BYTES) throw IOException("Zip is too large to import")
                        output.write(buffer, 0, n)
                    }
                }
            }
            afterFileExtracted?.invoke()
        }
    }
}
