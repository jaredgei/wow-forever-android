package app.gamenative.wow.install

import app.gamenative.utils.Net
import app.gamenative.wow.product.BuildInfo
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.tact.ArchiveIndex
import app.gamenative.wow.tact.Blte
import app.gamenative.wow.tact.Cdn
import app.gamenative.wow.tact.EncodingLookup
import app.gamenative.wow.tact.InstallManifest
import app.gamenative.wow.tact.Psv
import app.gamenative.wow.tact.TactConfig
import app.gamenative.wow.tact.hex
import app.gamenative.wow.tact.md5Hex
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class InstallRequest(
    val root: File,
    val product: WowProduct,
    val locale: String = "enUS",
    val arch: String = "arm64",
    val region: String = "us",
    /** Download every file the game would otherwise stream, into its own storage, instead of installing. */
    val full: Boolean = false,
)

/**
 * [doneBytes] counts every file handled, including ones already on disk; [fetchedBytes] only what came
 * from the network during this run, which is what the download speed is measured on.
 */
data class InstallProgress(
    val phase: Phase,
    val doneBytes: Long,
    val totalBytes: Long,
    val currentFile: String?,
    val fetchedBytes: Long = 0,
    /** Replaces the file name as the step line, for work that isn't one file at a time. */
    val detail: String? = null,
) {
    enum class Phase { RESOLVING, DOWNLOADING, FINALIZING, DONE }

    val fraction get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    /** "120 MB of 472 MB", switching to GB from 1 GB up. */
    val sizeText get() = "${bytesText(doneBytes)} of ${bytesText(totalBytes)}"

    /** What's being downloaded, in words: the stage for configs and indexes, the file name otherwise. */
    val stepText: String? get() = detail ?: currentFile?.let {
        when {
            it.startsWith("Data/indices/") -> "Archive indexes"
            it.startsWith("Data/config/") -> "Build configuration"
            else -> it.substringAfterLast('/')
        }
    }

    companion object {
        fun bytesText(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
            else -> "%.0f MB".format(bytes / 1e6)
        }
    }
}

class MissingOnCdnException(path: String) : IOException("Blizzard's CDN is missing $path")

class InsufficientSpaceException(val needed: Long, val available: Long) :
    IOException("Not enough space: needs ${needed / 1_000_000_000} GB, ${available / 1_000_000_000} GB free")

/**
 * One file to fetch. [kind] is the CDN folder (`config` or `data`); [key] the CDN key. Client files
 * ([ckey] set) are BLTE-decoded and checked against their content hash; config files are stored raw and
 * checked against their key; index files are stored raw.
 */
@Serializable
data class PlanItem(
    val id: String,
    val kind: String,
    val key: String,
    val dest: String,
    val size: Long,
    val ckey: String? = null,
    val verifyRaw: Boolean = false,
    /** The CDN doesn't always carry every listed index; a missing optional item is skipped, not fatal. */
    val optional: Boolean = false,
)

data class InstallPlan(
    val version: String,
    val buildConfig: String,
    val cdnConfig: String,
    val keyRing: String,
    val installKey: String,
    val installSize: Long,
    val cdn: Cdn,
    val items: List<PlanItem>,
) {
    val totalBytes get() = items.sumOf { it.size }
}

/**
 * Installs or repairs a WoW Forever client from Blizzard's CDN. Interrupted runs resume: every finished
 * item is recorded in `.foreverywhere-install.json`, and files already on disk are re-verified instead of
 * downloaded again. `.build.info` and `.flavor.info` are written last, so a partial install is never
 * mistaken for a complete one.
 */
class GameInstaller(
    private val patchBase: String = "http://us.patch.battle.net:1119",
    private val http: OkHttpClient = Net.http,
    private val cdnScheme: String = "http",
    private val usableSpace: (File) -> Long = { generateSequence(it) { f -> f.parentFile }.first { f -> f.exists() }.usableSpace },
) {
    suspend fun plan(request: InstallRequest): InstallPlan = withContext(Dispatchers.IO) {
        val req = request.keepingInstallRegion()
        val versions = Psv.parse(fetchText("$patchBase/${req.product.code}/versions"))
        val row = versions.firstOrNull { it["Region"] == req.region } ?: versions.firstOrNull()
            ?: throw IOException("Blizzard's patch service has no build for ${req.product.code}")
        val cdns = Psv.parse(fetchText("$patchBase/${req.product.code}/cdns"))
        val cdnRow = cdns.firstOrNull { it["Name"] == req.region } ?: cdns.firstOrNull() ?: throw IOException("No CDN listed")
        val cdn = Cdn(cdnRow.getValue("Hosts").split(" ").filter { it.isNotBlank() }, cdnRow.getValue("Path"), http, cdnScheme)

        val buildConfigKey = row.getValue("BuildConfig")
        val cdnConfigKey = row.getValue("CDNConfig")
        val buildConfig = TactConfig.parse(String(cdn.require("config", buildConfigKey)))
        val cdnConfig = TactConfig.parse(String(cdn.require("config", cdnConfigKey)))

        val items = mutableListOf<PlanItem>()
        for (key in listOf(buildConfigKey, cdnConfigKey)) {
            items += PlanItem("config:$key", "config", key, configPath(key), 0, verifyRaw = true)
        }
        row["KeyRing"]?.takeIf { it.isNotBlank() }?.let { key ->
            items += PlanItem("config:$key", "config", key, configPath(key), 0, verifyRaw = true, optional = true)
        }
        // Pre-seeds the archive indexes. The client fetches any that are missing (patch-archive indexes
        // live elsewhere on the CDN and archive-group indexes are built locally), so these are optional.
        val indices = cdnConfig["archives"].orEmpty() + listOfNotNull(cdnConfig["file-index"]?.firstOrNull())
        val indexSizes = cdnConfig["archives-index-size"].orEmpty()
        indices.distinct().forEachIndexed { i, hash ->
            items += PlanItem("index:$hash", "data", "$hash.index", "Data/indices/$hash.index", indexSizes.getOrNull(i)?.toLongOrNull() ?: 0, optional = true)
        }

        val installEkey = buildConfig.getValue("install").getOrElse(1) { throw IOException("Build config has no install manifest key") }
        val (manifest, tagTypes) = InstallManifest.parseWithTagTypes(Blte.decode(cdn.require("data", installEkey)))
        val wanted = InstallManifest.select(manifest, tagTypes, setOf("Windows", req.arch, req.locale, req.region.uppercase()))
        val paths = CaseFolder()
        val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(wanted.map { it.ckey }.toSet())
        for (entry in wanted) {
            val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
            // Ids carry the flavor folder: versions sharing one root share the resume state too.
            items += PlanItem("file:${req.product.flavorDir}/${entry.name}", "data", ekey, "${req.product.flavorDir}/${paths.fold(entry.relativePath)}", entry.size, ckey = entry.ckey)
        }

        val plan = InstallPlan(
            version = row["VersionsName"].orEmpty(),
            buildConfig = buildConfigKey,
            cdnConfig = cdnConfigKey,
            keyRing = row["KeyRing"].orEmpty(),
            installKey = installEkey,
            installSize = buildConfig["install-size"]?.getOrNull(1)?.toLongOrNull() ?: 0,
            cdn = cdn,
            items = items,
        )
        val needed = (plan.totalBytes * SPACE_MARGIN).toLong()
        val available = usableSpace(req.root)
        if (available < needed) throw InsufficientSpaceException(needed, available)
        plan
    }

    suspend fun run(request: InstallRequest, plan: InstallPlan? = null, onProgress: (InstallProgress) -> Unit) = withContext(Dispatchers.IO) {
        val req = request.keepingInstallRegion()
        onProgress(InstallProgress(InstallProgress.Phase.RESOLVING, 0, 0, null))
        val p = plan ?: plan(req)
        req.root.mkdirs()
        if (!req.root.canWrite()) throw IOException("Can't write to ${req.root}")
        val stateFile = File(req.root, STATE_FILE)
        val done = readState(stateFile).toMutableSet()
        val archives = ArchiveSource(p, req.root)
        var doneBytes = 0L
        var fetched = 0L
        var itemFetched = 0L
        var current: PlanItem? = null
        var lastReport = 0L
        // While a large file streams in, report a few times a second instead of only when it finishes.
        p.cdn.onBytes = { n ->
            fetched += n
            itemFetched += n
            val now = System.nanoTime() / 1_000_000
            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                lastReport = now
                val partial = current?.let { itemFetched.coerceAtMost(it.size) } ?: 0
                onProgress(InstallProgress(InstallProgress.Phase.DOWNLOADING, doneBytes + partial, p.totalBytes, current?.dest, fetched))
            }
        }
        try {
            for (item in p.items) {
                ensureActive()
                val dest = File(req.root, item.dest)
                val fresh = item.id in done && dest.isFile
                if (!fresh && !isValid(item, dest)) {
                    current = item
                    itemFetched = 0
                    onProgress(InstallProgress(InstallProgress.Phase.DOWNLOADING, doneBytes, p.totalBytes, item.dest, fetched))
                    try {
                        fetch(p.cdn, item, dest, archives)
                    } catch (e: MissingOnCdnException) {
                        if (!item.optional) throw e
                    }
                    current = null
                }
                done += item.id
                writeState(stateFile, done)
                doneBytes += item.size
                onProgress(InstallProgress(InstallProgress.Phase.DOWNLOADING, doneBytes, p.totalBytes, item.dest, fetched))
            }
        } finally {
            p.cdn.onBytes = null
        }

        onProgress(InstallProgress(InstallProgress.Phase.FINALIZING, doneBytes, p.totalBytes, null, fetched))
        val flavor = File(req.root, req.product.flavorDir).apply { mkdirs() }
        File(flavor, ".flavor.info").writeText("Product Flavor!STRING:0\n${req.product.code}\n")
        writeBuildInfo(req, p)
        stateFile.delete()
        onProgress(InstallProgress(InstallProgress.Phase.DONE, doneBytes, p.totalBytes, null, fetched))
    }

    private fun isValid(item: PlanItem, dest: File): Boolean = when {
        !dest.isFile -> false
        item.ckey != null -> md5Hex(dest) == item.ckey
        item.verifyRaw -> md5Hex(dest) == item.key
        else -> dest.length() > 0
    }

    private fun fetch(cdn: Cdn, item: PlanItem, dest: File, archives: ArchiveSource) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        if (item.ckey == null) {
            if (!cdn.download(item.kind, item.key, temp)) throw MissingOnCdnException(item.dest)
            if (item.verifyRaw && md5Hex(temp) != item.key) {
                temp.delete()
                throw IOException("Checksum mismatch for ${item.dest}")
            }
        } else {
            val encoded = cdn.fetch("data", item.key) ?: archives.read(item.key) ?: throw IOException("${item.dest} isn't on Blizzard's CDN")
            val digest = MessageDigest.getInstance("MD5")
            DigestOutputStream(temp.outputStream().buffered(), digest).use { Blte.decode(encoded, it) }
            if (hex(digest.digest()) != item.ckey) {
                temp.delete()
                throw IOException("Checksum mismatch for ${item.dest}")
            }
        }
        dest.delete()
        if (!temp.renameTo(dest)) throw IOException("Couldn't write ${dest.path}")
    }

    /**
     * Finds client files that are packed inside CDN archives. On first use it reads every downloaded index
     * once and remembers where the plan's files are, instead of rescanning ~1500 indexes per file.
     */
    private class ArchiveSource(private val plan: InstallPlan, private val root: File) {
        private val locations by lazy {
            val wanted = plan.items.mapNotNull { item -> item.key.takeIf { item.ckey != null } }.toSet()
            val found = HashMap<String, Triple<String, Long, Long>>()
            for (item in plan.items.filter { it.id.startsWith("index:") }) {
                val archive = item.key.removeSuffix(".index")
                val index = File(root, item.dest).takeIf { it.isFile } ?: continue
                ArchiveIndex(index.readBytes()).locateAll(wanted - found.keys).forEach { (ekey, loc) ->
                    found[ekey] = Triple(archive, loc.first, loc.second)
                }
                if (found.size == wanted.size) break
            }
            found
        }

        fun read(ekey: String): ByteArray? {
            val (archive, offset, size) = locations[ekey] ?: return null
            return plan.cdn.fetch("data", archive, offset until offset + size)
        }
    }

    private fun writeBuildInfo(req: InstallRequest, p: InstallPlan) {
        val existing = BuildInfo.read(req.root)
        val values = mapOf(
            "Branch" to req.region,
            "Active" to "1",
            "Build Key" to p.buildConfig,
            "CDN Key" to p.cdnConfig,
            "Install Key" to p.installKey,
            "IM Size" to p.installSize.toString(),
            "CDN Path" to p.cdn.path,
            "CDN Hosts" to p.cdn.hosts.joinToString(" "),
            "CDN Servers" to p.cdn.hosts.joinToString(" ") { "$cdnScheme://$it/?maxhosts=4" },
            "Tags" to tags(req),
            "Armadillo" to "",
            "Last Activated" to Instant.now().toString(),
            "Version" to p.version,
            "KeyRing" to p.keyRing,
            "Product" to req.product.code,
        )
        val info = when {
            existing == null -> BuildInfo(BUILD_INFO_HEADER, listOf(values))
            // Repair or update: only the build columns change; Branch and Tags (region, language) stay.
            existing.rows.any { it["Product"] == req.product.code } -> existing.withUpdatedActive(req.product, values - KEEP_ON_UPDATE)
            else -> existing.copy(rows = existing.rows + values)
        }
        val temp = File(req.root, "${BuildInfo.FILE_NAME}.tmp")
        temp.writeText(info.serialize())
        if (!temp.renameTo(File(req.root, BuildInfo.FILE_NAME))) throw IOException("Couldn't write ${BuildInfo.FILE_NAME}")
    }

    private fun tags(req: InstallRequest): String {
        val base = "Windows ${req.arch} ${req.region.uppercase()}? acct-${req.region.uppercase()}A? geoip-${req.region.uppercase()}? ${req.locale}"
        return "$base speech?:$base text?"
    }

    private fun fetchText(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            response.body?.string() ?: throw IOException("Empty response from $url")
        }

    private fun readState(file: File): Set<String> =
        runCatching { json.decodeFromString<List<String>>(file.readText()).toSet() }.getOrDefault(emptySet())

    private fun writeState(file: File, done: Set<String>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(done.toList()))
        temp.renameTo(file)
    }

    companion object {
        const val STATE_FILE = ".foreverywhere-install.json"
        private const val PROGRESS_INTERVAL_MS = 250L
        private val KEEP_ON_UPDATE = setOf("Branch", "Tags", "Armadillo")
        private const val SPACE_MARGIN = 1.15
        private val json = Json { ignoreUnknownKeys = true }

        val BUILD_INFO_HEADER = listOf(
            "Branch!STRING:0", "Active!DEC:1", "Build Key!HEX:16", "CDN Key!HEX:16", "Install Key!HEX:16", "IM Size!DEC:4",
            "CDN Path!STRING:0", "CDN Hosts!STRING:0", "CDN Servers!STRING:0", "Tags!STRING:0", "Armadillo!STRING:0",
            "Last Activated!STRING:0", "Version!STRING:0", "KeyRing!HEX:16", "Product!STRING:0",
        )

        fun configPath(key: String) = "Data/config/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"
    }
}

/** A repair or update keeps the region and language the install was made with. */
internal fun InstallRequest.keepingInstallRegion(): InstallRequest {
    val row = BuildInfo.read(root)?.active(product)?.takeIf { it["Product"] == product.code } ?: return this
    val tags = row["Tags"].orEmpty().split(" ", ":")
    val locale = tags.firstOrNull { Regex("[a-z]{2}[A-Z]{2}").matches(it) } ?: locale
    val region = row["Branch"]?.takeIf { it.length == 2 } ?: region
    return copy(locale = locale, region = region)
}

/**
 * Install-manifest names mix case (`UTILS\` and `Utils\`). Keeps the first casing seen for each folder
 * so case-sensitive storage doesn't end up with two copies of the same directory.
 */
internal class CaseFolder {
    private val dirs = mutableMapOf<String, String>()

    fun fold(path: String): String {
        val parts = path.split('/').toMutableList()
        for (i in 0 until parts.size - 1) {
            val key = parts.subList(0, i + 1).joinToString("/").lowercase()
            parts[i] = dirs.getOrPut(key) { parts[i] }
        }
        return parts.joinToString("/")
    }
}
