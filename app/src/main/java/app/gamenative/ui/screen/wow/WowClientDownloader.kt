package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import timber.log.Timber

object WowClientDownloader {
    const val FLAVOR_DIR = "_forever_"
    const val EXE_NAME = "Wow-ARM64.exe"
    const val TARGET_PRODUCT = "wowf"
    const val BUILD_INFO = ".build.info"

    // The pre-release beta. Its Data/ archives are shared with the full release, so beta installs upgrade in place.
    const val LEGACY_FLAVOR_DIR = "_classic_beta_"
    const val LEGACY_PRODUCT = "wow_classic_beta"
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"
    // Lists every region's build, like the plain-HTTP patch server on port 1119 it replaces.
    private const val PATCH_URL = "https://us.version.battle.net/v2/products/$TARGET_PRODUCT"
    private const val DEFAULT_CDN_PATH = "tpr/wow"
    private val DEFAULT_CDN_HOSTS = listOf("level3.blizzard.com", "us.cdn.blizzard.com")
    private const val INDEX_DOWNLOAD_THREADS = 8
    private val BUILD_INFO_HEADER = listOf(
        "Branch!STRING:0", "Active!DEC:1", "Build Key!HEX:16", "CDN Key!HEX:16", "Install Key!HEX:16", "IM Size!DEC:4",
        "CDN Path!STRING:0", "CDN Hosts!STRING:0", "CDN Servers!STRING:0", "Tags!STRING:0", "Armadillo!STRING:0",
        "Last Activated!STRING:0", "Version!STRING:0", "KeyRing!HEX:16", "Product!STRING:0",
    ).joinToString("|")
    private val EU_COUNTRIES = setOf(
        "AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR", "HU", "IE", "IS", "IT",
        "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO", "RU", "SE", "SI", "SK", "UA", "TR",
    )

    private val patchHttp by lazy {
        Net.http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    data class VersionCheckResult(
        val localVersion: String,
        val remoteVersion: String,
        val isOutdated: Boolean,
        val remoteBuildConfig: String = "",
        val remoteCdnConfig: String = "",
        val remoteCdnHosts: List<String> = emptyList(),
        val remoteCdnPath: String = DEFAULT_CDN_PATH,
        val remoteCdnServers: List<String> = emptyList(),
        val region: String = "",
    )

    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo) }.getOrNull()

    fun installedProducts(buildInfo: File): Set<String> =
        runCatching { parsePsv(buildInfo.readText()).mapNotNull { it["Product"] }.toSet() }.getOrDefault(emptySet())

    /** Compares the install against the live build for [region] (e.g. "us"), since regions can be on different builds. */
    fun checkVersion(gameRoot: File, region: String): VersionCheckResult? {
        val row = runCatching { activeBuild(File(gameRoot, BUILD_INFO)) }.getOrElse {
            Timber.w(it, "checkVersion: no readable $BUILD_INFO in $gameRoot")
            return null
        }
        val product = row["Product"]?.takeIf { it.isNotBlank() } ?: TARGET_PRODUCT
        if (product != TARGET_PRODUCT) {
            Timber.w("checkVersion: wrong product '$product', expected '$TARGET_PRODUCT'")
            return null
        }
        val localVersion = row["Version"] ?: run {
            Timber.w("checkVersion: row has no Version")
            return null
        }
        val localBuildKey = row["Build Key"] ?: run {
            Timber.w("checkVersion: row has no Build Key")
            return null
        }

        val remote = fetchRemote(region, row) ?: return null
        val isOutdated = localVersion != remote.remoteVersion ||
            (remote.remoteBuildConfig.isNotBlank() && localBuildKey != remote.remoteBuildConfig)
        Timber.i("checkVersion: localVersion=$localVersion, remoteVersion=${remote.remoteVersion}, isOutdated=$isOutdated")
        return remote.copy(localVersion = localVersion, isOutdated = isOutdated)
    }

    private fun fetchRemote(region: String, localRow: Map<String, String>? = null): VersionCheckResult? {
        val versions = runCatching { fetchText("$PATCH_URL/versions") }.getOrElse {
            Timber.w(it, "fetchRemote: http call failed")
            return null
        } ?: run {
            Timber.w("fetchRemote: response body is null or unsuccessful")
            return null
        }
        val remoteRow = regionRow(parsePsv(versions), "Region", region)
            ?: run {
                Timber.w("fetchRemote: no remote row in $versions")
                return null
            }
        val remoteVersion = remoteRow["VersionsName"] ?: remoteRow["VersionName"] ?: remoteRow["Version"]
            ?: run {
                Timber.w("fetchRemote: no remoteVersion in $remoteRow")
                return null
            }

        val cdns = runCatching { fetchText("$PATCH_URL/cdns") }.getOrNull()?.let(::parsePsv).orEmpty()
        val cdnsRow = regionRow(cdns, "Name", region)

        return VersionCheckResult(
            localVersion = "",
            remoteVersion = remoteVersion,
            isOutdated = true,
            remoteBuildConfig = remoteRow["BuildConfig"].orEmpty(),
            remoteCdnConfig = remoteRow["CDNConfig"].orEmpty(),
            remoteCdnHosts = (cdnsRow?.get("Hosts") ?: localRow?.get("CDN Hosts"))?.let(::splitHosts) ?: DEFAULT_CDN_HOSTS,
            remoteCdnPath = cdnsRow?.get("Path") ?: localRow?.get("CDN Path") ?: DEFAULT_CDN_PATH,
            remoteCdnServers = (cdnsRow?.get("Servers") ?: localRow?.get("CDN Servers"))?.let(::splitHosts).orEmpty(),
            region = remoteRow["Region"].orEmpty(),
        )
    }

    // Realms only accept their own region's build, which can lag behind or run ahead of the US one after a patch.
    internal fun regionRow(rows: List<Map<String, String>>, column: String, region: String): Map<String, String>? =
        rows.firstOrNull { it[column].equals(region, ignoreCase = true) }
            ?: rows.firstOrNull { it[column].equals("us", ignoreCase = true) }
            ?: rows.firstOrNull()

    /**
     * Sets up the full-release client in [gameRoot]: an empty folder for a new install, or a beta install that is upgraded
     * in place. Downloads the build configs, archive indexes and ARM64 client, then adds the [TARGET_PRODUCT] row to
     * [BUILD_INFO] last, so an interrupted install is simply resumed the next time. Game assets that aren't on disk yet
     * are streamed from the CDN by the game client while playing.
     */
    fun install(gameRoot: File, region: String, onStatus: (String) -> Unit) {
        onStatus("Checking the latest World of Warcraft build...")
        val target = fetchRemote(region) ?: throw IOException("Couldn't reach Blizzard's patch server. Check your connection.")
        check(target.remoteBuildConfig.isNotBlank()) { "Blizzard's patch server returned no build" }
        listOf("Data/data", "Data/indices", "Data/config").forEach { File(gameRoot, it).mkdirs() }
        seedResidency(gameRoot)
        updateGame(gameRoot, target, onStatus)
    }

    // The Battle.net app tracks which shared archives each product uses in Data/<product>/. A beta install has the same
    // archives as the full release, so its record is a better starting point than none.
    private fun seedResidency(gameRoot: File) {
        val residency = File(gameRoot, "Data/$TARGET_PRODUCT")
        val legacy = File(gameRoot, "Data/$LEGACY_PRODUCT")
        if (residency.exists() || !legacy.isDirectory) return
        runCatching { legacy.copyRecursively(residency) }.onFailure { Timber.w(it, "Couldn't copy the beta residency data") }
    }

    fun updateGame(gameRoot: File, target: VersionCheckResult, onStatus: (String) -> Unit) {
        val cdn = Cdn(
            servers = httpsServers(target.remoteCdnServers, target.remoteCdnHosts),
            path = target.remoteCdnPath.ifEmpty { DEFAULT_CDN_PATH },
        )
        onStatus("Fetching build ${target.remoteVersion} manifest...")
        val buildConfigRaw = cdn.require("config", target.remoteBuildConfig)
        val cdnConfigRaw = target.remoteCdnConfig.takeIf { it.isNotBlank() }?.let { cdn.require("config", it) }

        saveConfig(gameRoot, target.remoteBuildConfig, buildConfigRaw)
        cdnConfigRaw?.let { saveConfig(gameRoot, target.remoteCdnConfig, it) }

        val cdnConfig = cdnConfigRaw?.let { parseConfig(String(it)) }
        cdnConfig?.let { syncIndices(gameRoot, cdn, it, onStatus) }
        syncClient(
            gameRoot = gameRoot,
            cdn = cdn,
            buildConfig = parseConfig(String(buildConfigRaw)),
            archives = lazy { cdnConfig?.let { ArchiveLocator(cdn, gameRoot, it.getValue("archives")) } },
            onStatus = onStatus,
        )

        onStatus("Updating build information...")
        upsertBuildInfo(File(gameRoot, BUILD_INFO), target)
    }

    fun download(gameRoot: File, region: String, onStatus: (String) -> Unit) {
        val row = activeBuild(File(gameRoot, BUILD_INFO))
        check(row["Product"] == TARGET_PRODUCT) { "Incompatible game product: ${row["Product"]}. Expected $TARGET_PRODUCT." }
        val latest = checkVersion(gameRoot, region)
        if (latest != null && latest.remoteBuildConfig.isNotBlank()) return updateGame(gameRoot, latest, onStatus)

        val cdn = Cdn(
            servers = httpsServers(row["CDN Servers"].orEmpty().let(::splitHosts), row["CDN Hosts"].orEmpty().let(::splitHosts)),
            path = row.getValue("CDN Path"),
        )
        onStatus("Reading build ${row["Version"].orEmpty()} manifest...")
        syncClient(
            gameRoot = gameRoot,
            cdn = cdn,
            buildConfig = parseConfig(String(cdn.require("config", row.getValue("Build Key")))),
            archives = lazy {
                ArchiveLocator(cdn, gameRoot, parseConfig(String(cdn.require("config", row.getValue("CDN Key")))).getValue("archives"))
            },
            onStatus = onStatus,
        )
    }

    private fun syncClient(
        gameRoot: File,
        cdn: Cdn,
        buildConfig: Map<String, List<String>>,
        archives: Lazy<ArchiveLocator?>,
        onStatus: (String) -> Unit,
    ) {
        val files = parseInstall(blteDecode(cdn.require("data", buildConfig.getValue("install")[1]))).filter { it.isClientBinary() }
        check(files.isNotEmpty()) { "No Windows ARM64 client files in this build" }

        val clientDir = File(gameRoot, FLAVOR_DIR)
        val pending = files.filter { md5Hex(File(clientDir, it.relativePath)) != it.ckey }
        if (pending.isEmpty()) return

        onStatus("Looking up client files...")
        val ekeys = EncodingLookup(cdn, buildConfig.getValue("encoding")[1]).find(pending.map { it.ckey }.toSet())
        pending.forEachIndexed { index, entry ->
            onStatus("Downloading ${entry.name} (${index + 1}/${pending.size})...")
            val ekey = ekeys[entry.ckey] ?: throw IOException("${entry.name} is missing from the encoding table")
            val encoded = cdn.fetch("data", ekey) ?: archives.value?.read(ekey) ?: throw IOException("${entry.name} not found on the CDN")
            writeVerified(File(clientDir, entry.relativePath), entry, encoded)
        }
    }

    private fun writeVerified(dest: File, entry: InstallEntry, encoded: ByteArray) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        val digest = MessageDigest.getInstance("MD5")
        DigestOutputStream(temp.outputStream().buffered(), digest).use { blteDecode(encoded, it) }
        if (hex(digest.digest(), 0, 16) != entry.ckey) {
            temp.delete()
            throw IOException("Checksum mismatch for ${entry.name}")
        }
        dest.delete()
        check(temp.renameTo(dest)) { "Could not write ${dest.path}" }
    }

    private fun saveConfig(gameRoot: File, key: String, bytes: ByteArray) =
        writeAtomically(File(gameRoot, "Data/config/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"), bytes)

    // Patch archives and archive groups aren't needed to play: the former live under patch/, and the client builds the latter.
    private fun syncIndices(gameRoot: File, cdn: Cdn, cdnConfig: Map<String, List<String>>, onStatus: (String) -> Unit) {
        val required = cdnConfig["archives"].orEmpty() + cdnConfig["file-index"].orEmpty()
        val missing = required.distinct().filter { !File(gameRoot, "Data/indices/$it.index").isFile }
        if (missing.isEmpty()) return
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(INDEX_DOWNLOAD_THREADS)
        try {
            missing.map { hash ->
                pool.submit {
                    cdn.fetch("data", "$hash.index")?.let { writeAtomically(File(gameRoot, "Data/indices/$hash.index"), it) }
                    onStatus("Syncing archive indexes (${done.incrementAndGet()}/${missing.size})...")
                }
            }.forEach { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }

    private fun writeAtomically(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.download")
        temp.writeBytes(bytes)
        dest.delete()
        check(temp.renameTo(dest)) { "Could not write ${dest.path}" }
    }

    /**
     * Points the [TARGET_PRODUCT] row of [buildInfo] at [target], adding the row (and the file) when it's missing.
     * The row takes the region the build was picked for; a new row without one takes that of an existing beta row. The game streams
     * missing assets from the "CDN Servers" URLs and crashes on startup when there are none.
     */
    internal fun upsertBuildInfo(buildInfo: File, target: VersionCheckResult) {
        val lines = buildInfo.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(BUILD_INFO_HEADER)
        val header = psvHeader(lines.first())
        val updates = mapOf(
            "Active" to "1",
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to target.remoteCdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath,
            "CDN Servers" to target.remoteCdnServers.joinToString(" "),
            "Product" to TARGET_PRODUCT,
        ).filterValues { it.isNotBlank() }

        val rows = lines.drop(1).map { line -> header.zip(line.split("|")).toMap() }
        val existing = rows.indexOfFirst { it["Product"] == TARGET_PRODUCT }
        val updated = if (existing >= 0) {
            rows.mapIndexed { idx, row ->
                when {
                    idx != existing -> row
                    target.region.isBlank() -> row + updates
                    else -> row + regionColumns(target.region, row["Tags"]) + updates
                }
            }
        } else {
            val branch = target.region.ifBlank { null }
                ?: rows.firstOrNull { it["Product"] == LEGACY_PRODUCT }?.get("Branch")?.takeIf { it.isNotBlank() }
                ?: defaultBranch()
            rows + (regionColumns(branch, null) + updates)
        }
        val text = (listOf(lines.first()) + updated.map { row -> header.joinToString("|") { row[it].orEmpty() } }).joinToString("\n") + "\n"
        writeAtomically(buildInfo, text.toByteArray())
    }

    // The region as the Battle.net app records it: as the Branch, and as the region tag (e.g. "US?") in Tags.
    private fun regionColumns(branch: String, tags: String?): Map<String, String> {
        val region = branch.uppercase(Locale.ROOT)
        val regionTags = tags?.takeIf { it.isNotBlank() }?.replace(REGION_TAG, "$region?")
            ?: listOf("speech", "text").joinToString(":") { "Windows $PLATFORM_ARCH $region? enUS $it?" }
        return mapOf("Branch" to branch, "Tags" to regionTags)
    }

    private val REGION_TAG = Regex("""\b[A-Z]{2}\?""")

    // Best guess at the region the Battle.net app would record, for installs that have no beta row to copy it from.
    internal fun defaultBranch() = when (Locale.getDefault().country.uppercase(Locale.ROOT)) {
        "KR" -> "kr"
        "TW", "HK" -> "tw"
        in EU_COUNTRIES -> "eu"
        else -> "us"
    }

    private data class InstallEntry(val name: String, val ckey: String, val tags: Set<String>) {
        val relativePath get() = name.replace('\\', '/')

        fun isClientBinary(): Boolean {
            if ("Windows" !in tags || PLATFORM_ARCH !in tags || EXCLUDED_ARCH in tags) return false
            val lower = name.lowercase()
            return !lower.startsWith("utils\\") || "exceptionhandler" in lower
        }
    }

    /** [servers] are HTTPS base URLs, e.g. "https://level3.ssl.blizzard.com". */
    private class Cdn(val servers: List<String>, val path: String) {
        fun require(kind: String, key: String): ByteArray {
            val bytes = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")
            // Configs are named by their MD5, so they can be checked before anything is parsed from them. Data files
            // are checked once decoded, against the install manifest.
            if (kind == "config") verifyConfigDigest(key, bytes)
            return bytes
        }

        fun fetch(kind: String, key: String, range: LongRange? = null): ByteArray? {
            var failure: IOException? = null
            var missing = false
            for (server in servers) {
                val request = Request.Builder()
                    .url("$server/$path/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key")
                    .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                    .build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            // A server that ignores Range answers 200 with the whole file, which isn't the bytes asked for.
                            response.code == (if (range != null) 206 else 200) -> return response.body!!.bytes()
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $server")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            // Only report a file as missing when every server that answered said so.
            if (missing && failure == null) return null
            throw failure ?: IOException("No CDN servers for $path")
        }
    }

    internal fun verifyConfigDigest(key: String, bytes: ByteArray) {
        if (!hex(MessageDigest.getInstance("MD5").digest(bytes), 0, 16).equals(key, ignoreCase = true)) {
            throw IOException("Checksum mismatch for config $key")
        }
    }

    /**
     * The HTTPS servers among the patch server's "Servers" URLs (e.g. "https://level3.ssl.blizzard.com/?maxhosts=4"),
     * else the "Hosts" over HTTPS. Level 3 serves HTTPS from a separate host name.
     */
    internal fun httpsServers(servers: List<String>, hosts: List<String>): List<String> {
        val https = servers.filter { it.startsWith("https://", ignoreCase = true) }.map { it.substringBefore("?").trimEnd('/') }
        return https.distinct().ifEmpty {
            hosts.ifEmpty { DEFAULT_CDN_HOSTS }.map { host ->
                "https://" + if (host.equals("level3.blizzard.com", ignoreCase = true)) "level3.ssl.blizzard.com" else host
            }
        }
    }

    private class EncodingLookup(private val cdn: Cdn, private val ekey: String) {
        private data class Chunk(val encodedOffset: Long, val encodedSize: Int, val decodedOffset: Long, val decodedSize: Int)

        private val chunks: List<Chunk> = parseChunkTable(ByteBuffer.wrap(fetchEncoded(0, 8)).getInt(4))

        private fun parseChunkTable(headerSize: Int): List<Chunk> {
            val header = ByteBuffer.wrap(fetchEncoded(0, headerSize))
            val count = header.getInt(8) and 0xFFFFFF
            var encoded = headerSize.toLong()
            var decoded = 0L
            return (0 until count).map { i ->
                val compressedSize = header.getInt(12 + i * 24)
                val decompressedSize = header.getInt(16 + i * 24)
                Chunk(encoded, compressedSize, decoded, decompressedSize).also {
                    encoded += compressedSize
                    decoded += decompressedSize
                }
            }
        }

        private var cachedChunk: Chunk? = null
        private var cachedData: ByteArray = ByteArray(0)

        private fun read(start: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var written = 0
            for (chunk in chunks) {
                val chunkEnd = chunk.decodedOffset + chunk.decodedSize
                if (chunkEnd <= start || chunk.decodedOffset >= start + length) continue
                val from = maxOf(start, chunk.decodedOffset) - chunk.decodedOffset
                val count = (minOf(start + length, chunkEnd) - chunk.decodedOffset - from).toInt()
                val mode = fetchEncoded(chunk.encodedOffset, 1)[0].toInt().toChar()
                val bytes = if (mode == 'N') {
                    fetchEncoded(chunk.encodedOffset + 1 + from, count)
                } else {
                    decodedChunk(chunk).copyOfRange(from.toInt(), from.toInt() + count)
                }
                bytes.copyInto(out, written)
                written += count
            }
            return out
        }

        private fun decodedChunk(chunk: Chunk): ByteArray {
            if (cachedChunk != chunk) {
                cachedData = decodeChunk(fetchEncoded(chunk.encodedOffset, chunk.encodedSize))
                cachedChunk = chunk
            }
            return cachedData
        }

        private fun fetchEncoded(offset: Long, size: Int) =
            cdn.fetch("data", ekey, offset until offset + size) ?: throw IOException("Encoding file missing")

        fun find(wanted: Set<String>): Map<String, String> {
            val header = ByteBuffer.wrap(read(0, 22))
            val ckeySize = header.get(3).toInt()
            val ekeySize = header.get(4).toInt()
            val pageSize = (header.getShort(5).toInt() and 0xFFFF) * 1024
            val pageCount = header.getInt(9)
            val especSize = header.getInt(18)
            val indexStart = 22L + especSize
            val indexEntrySize = ckeySize + 16
            val index = read(indexStart, pageCount * indexEntrySize)
            val firstKeys = (0 until pageCount).map { hex(index, it * indexEntrySize, ckeySize) }
            val pagesStart = indexStart + pageCount.toLong() * indexEntrySize

            val found = mutableMapOf<String, String>()
            wanted.groupBy { ckey -> firstKeys.binarySearch(ckey).let { if (it >= 0) it else -it - 2 } }
                .filterKeys { it >= 0 }
                .forEach { (page, ckeys) ->
                    val data = read(pagesStart + page.toLong() * pageSize, pageSize)
                    var pos = 0
                    while (pos + 6 + ckeySize <= data.size && data[pos].toInt() != 0) {
                        val keyCount = data[pos].toInt() and 0xFF
                        val ckey = hex(data, pos + 6, ckeySize)
                        if (ckey in ckeys) found[ckey] = hex(data, pos + 6 + ckeySize, ekeySize)
                        pos += 6 + ckeySize + keyCount * ekeySize
                    }
                }
            return found
        }
    }

    private class ArchiveLocator(private val cdn: Cdn, private val gameRoot: File, private val archives: List<String>) {
        fun read(ekey: String): ByteArray? {
            for (archive in archives) {
                val local = File(gameRoot, "Data/indices/$archive.index")
                val index = if (local.isFile) {
                    local.readBytes()
                } else {
                    cdn.fetch("data", "$archive.index")?.also { writeAtomically(local, it) } ?: continue
                }
                val buffer = ByteBuffer.wrap(index)
                for (block in 0 until index.size / 4096 * 4096 step 4096) {
                    var pos = block
                    while (pos <= block + 4096 - 24) {
                        if (hex(index, pos, 16) == ekey) {
                            val size = buffer.getInt(pos + 16).toLong() and 0xFFFFFFFFL
                            val offset = buffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                            return cdn.fetch("data", archive, offset until offset + size)
                        }
                        pos += 24
                    }
                }
            }
            return null
        }
    }

    private fun fetchText(url: String): String? =
        patchHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }

    private fun activeBuild(buildInfo: File): Map<String, String> {
        check(buildInfo.isFile) { "Missing $BUILD_INFO in ${buildInfo.parent}" }
        val rows = parsePsv(buildInfo.readText())
        return rows.firstOrNull { it["Product"] == TARGET_PRODUCT && it["Active"] == "1" }
            ?: rows.firstOrNull { it["Product"] == TARGET_PRODUCT }
            ?: rows.firstOrNull { it["Active"] == "1" }
            ?: rows.firstOrNull()
            ?: throw IllegalStateException("No build in $BUILD_INFO")
    }

    private fun parsePsv(text: String): List<Map<String, String>> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = psvHeader(lines.firstOrNull() ?: return emptyList())
        return lines.drop(1).map { header.zip(it.split("|")).toMap() }
    }

    private fun psvHeader(line: String) = line.split("|").map { it.substringBefore("!") }

    private fun splitHosts(hosts: String) = hosts.split(" ").filter { it.isNotBlank() }

    private fun parseConfig(text: String): Map<String, List<String>> =
        text.lines().filter { "=" in it && !it.startsWith("#") }.associate { line ->
            line.substringBefore("=").trim() to line.substringAfter("=").trim().split(" ").filter { it.isNotEmpty() }
        }

    private fun parseInstall(data: ByteArray): List<InstallEntry> {
        check(data[0] == 'I'.code.toByte() && data[1] == 'N'.code.toByte()) { "Bad install manifest" }
        val buffer = ByteBuffer.wrap(data)
        val hashSize = data[3].toInt()
        val tagCount = buffer.getShort(4).toInt() and 0xFFFF
        val entryCount = buffer.getInt(6)
        val maskSize = (entryCount + 7) / 8
        var pos = 10

        fun readString(): String {
            val end = (pos until data.size).first { data[it].toInt() == 0 }
            return String(data, pos, end - pos).also { pos = end + 1 }
        }

        val tags = (0 until tagCount).map {
            val name = readString()
            pos += 2
            (name to data.copyOfRange(pos, pos + maskSize)).also { pos += maskSize }
        }
        return (0 until entryCount).map { i ->
            val name = readString()
            val ckey = hex(data, pos, hashSize)
            pos += hashSize + 4
            val entryTags = tags.filter { (_, mask) -> mask[i / 8].toInt() and (0x80 ushr (i % 8)) != 0 }.map { it.first }.toSet()
            InstallEntry(name, ckey, entryTags)
        }
    }

    private fun blteDecode(data: ByteArray) = ByteArrayOutputStream(data.size).also { blteDecode(data, it) }.toByteArray()

    private fun blteDecode(data: ByteArray, out: OutputStream) {
        check(String(data, 0, 4) == "BLTE") { "Not BLTE data" }
        val buffer = ByteBuffer.wrap(data)
        val headerSize = buffer.getInt(4)
        if (headerSize == 0) {
            out.write(decodeChunk(data.copyOfRange(8, data.size)))
            return
        }
        val count = buffer.getInt(8) and 0xFFFFFF
        var offset = headerSize
        for (i in 0 until count) {
            val compressedSize = buffer.getInt(12 + i * 24)
            out.write(decodeChunk(data.copyOfRange(offset, offset + compressedSize)))
            offset += compressedSize
        }
    }

    private fun decodeChunk(chunk: ByteArray): ByteArray {
        val body = chunk.copyOfRange(1, chunk.size)
        return when (chunk[0].toInt().toChar()) {
            'N' -> body
            'Z' -> inflate(body)
            'F' -> blteDecode(body)
            else -> throw IOException("Unsupported BLTE chunk mode ${chunk[0].toInt().toChar()}")
        }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 2)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Truncated zlib chunk")
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun md5Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest(), 0, 16)
    }

    private fun hex(data: ByteArray, offset: Int, size: Int): String {
        val chars = CharArray(size * 2)
        for (i in 0 until size) {
            val v = data[offset + i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0xF]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
