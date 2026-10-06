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
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import timber.log.Timber

object WowClientDownloader {
    const val FLAVOR_DIR = "_classic_beta_"
    const val EXE_NAME = "WowB-ARM64.exe"
    const val TARGET_PRODUCT = "wow_classic_beta"
    const val BUILD_INFO = ".build.info"
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"
    private const val PATCH_URL = "http://us.patch.battle.net:1119/$TARGET_PRODUCT"
    private const val DEFAULT_CDN_PATH = "tpr/wow"
    private val DEFAULT_CDN_HOSTS = listOf("level3.blizzard.com", "us.cdn.blizzard.com")

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
    )

    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo) }.getOrNull()

    fun checkVersion(gameRoot: File): VersionCheckResult? {
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

        val versions = runCatching { fetchText("$PATCH_URL/versions") }.getOrElse {
            Timber.w(it, "checkVersion: http call failed")
            return null
        } ?: run {
            Timber.w("checkVersion: response body is null or unsuccessful")
            return null
        }
        val remoteRow = parsePsv(versions).firstOrNull()
            ?: run {
                Timber.w("checkVersion: no remote row in $versions")
                return null
            }
        val remoteVersion = remoteRow["VersionsName"] ?: remoteRow["VersionName"] ?: remoteRow["Version"]
            ?: run {
                Timber.w("checkVersion: no remoteVersion in $remoteRow")
                return null
            }
        val remoteBuildKey = remoteRow["BuildConfig"].orEmpty()

        val cdns = runCatching { fetchText("$PATCH_URL/cdns") }.getOrNull()?.let(::parsePsv).orEmpty()
        val cdnsRow = cdns.firstOrNull { it["Name"] == "us" } ?: cdns.firstOrNull()

        val isOutdated = localVersion != remoteVersion || (remoteBuildKey.isNotBlank() && localBuildKey != remoteBuildKey)
        Timber.i("checkVersion: localVersion=$localVersion, remoteVersion=$remoteVersion, isOutdated=$isOutdated")
        return VersionCheckResult(
            localVersion = localVersion,
            remoteVersion = remoteVersion,
            isOutdated = isOutdated,
            remoteBuildConfig = remoteBuildKey,
            remoteCdnConfig = remoteRow["CDNConfig"].orEmpty(),
            remoteCdnHosts = (cdnsRow?.get("Hosts") ?: row["CDN Hosts"])?.let(::splitHosts) ?: DEFAULT_CDN_HOSTS,
            remoteCdnPath = cdnsRow?.get("Path") ?: row["CDN Path"] ?: DEFAULT_CDN_PATH,
        )
    }

    fun updateGame(gameRoot: File, target: VersionCheckResult, onStatus: (String) -> Unit) {
        val cdn = Cdn(
            hosts = target.remoteCdnHosts.ifEmpty { DEFAULT_CDN_HOSTS },
            path = target.remoteCdnPath.ifEmpty { DEFAULT_CDN_PATH },
        )
        onStatus("Fetching build ${target.remoteVersion} manifest...")
        val buildConfigRaw = cdn.require("config", target.remoteBuildConfig)
        val cdnConfigRaw = target.remoteCdnConfig.takeIf { it.isNotBlank() }?.let { cdn.require("config", it) }

        saveConfig(gameRoot, target.remoteBuildConfig, buildConfigRaw)
        cdnConfigRaw?.let { saveConfig(gameRoot, target.remoteCdnConfig, it) }

        val cdnConfig = cdnConfigRaw?.let { parseConfig(String(it)) }
        syncClient(
            gameRoot = gameRoot,
            cdn = cdn,
            buildConfig = parseConfig(String(buildConfigRaw)),
            archives = lazy { cdnConfig?.let { ArchiveLocator(cdn, gameRoot, it.getValue("archives")) } },
            onStatus = onStatus,
        )
        cdnConfig?.let { syncIndices(gameRoot, cdn, it, onStatus) }

        onStatus("Updating build information...")
        updateBuildInfo(gameRoot, target)
    }

    fun download(gameRoot: File, onStatus: (String) -> Unit) {
        val row = activeBuild(File(gameRoot, BUILD_INFO))
        check(row["Product"] == TARGET_PRODUCT) { "Incompatible game product: ${row["Product"]}. Expected $TARGET_PRODUCT." }
        val latest = checkVersion(gameRoot)
        if (latest != null && latest.remoteBuildConfig.isNotBlank()) return updateGame(gameRoot, latest, onStatus)

        val cdn = Cdn(hosts = splitHosts(row.getValue("CDN Hosts")), path = row.getValue("CDN Path"))
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

    private fun saveConfig(gameRoot: File, key: String, bytes: ByteArray) {
        val dir = File(gameRoot, "Data/config/${key.substring(0, 2)}/${key.substring(2, 4)}")
        dir.mkdirs()
        File(dir, key).writeBytes(bytes)
    }

    private fun syncIndices(gameRoot: File, cdn: Cdn, cdnConfig: Map<String, List<String>>, onStatus: (String) -> Unit) {
        val indicesDir = File(gameRoot, "Data/indices")
        indicesDir.mkdirs()
        val required = listOf("archives", "patch-archives").flatMap { cdnConfig[it].orEmpty() } +
            listOf("file-index", "patch-file-index", "archive-group", "patch-archive-group").mapNotNull { cdnConfig[it]?.firstOrNull() }
        val missing = required.distinct().filter { !File(indicesDir, "$it.index").isFile }
        missing.forEachIndexed { index, hash ->
            onStatus("Syncing index archive (${index + 1}/${missing.size})...")
            cdn.fetch("data", "$hash.index")?.let { File(indicesDir, "$hash.index").writeBytes(it) }
        }
    }

    private fun updateBuildInfo(gameRoot: File, target: VersionCheckResult) {
        val buildInfo = File(gameRoot, BUILD_INFO)
        if (!buildInfo.isFile) return
        val lines = buildInfo.readLines()
        if (lines.isEmpty()) return
        val header = psvHeader(lines.first())
        val productIdx = header.indexOf("Product")
        val activeIdx = header.indexOf("Active")
        val updates = mapOf(
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to target.remoteCdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath,
        ).mapNotNull { (column, value) -> header.indexOf(column).takeIf { it >= 0 && value.isNotBlank() }?.to(value) }

        val updated = lines.mapIndexed { idx, line ->
            if (idx == 0 || line.isBlank()) return@mapIndexed line
            val cols = line.split("|").toMutableList()
            val matchesProduct = productIdx < 0 || cols.getOrNull(productIdx) == TARGET_PRODUCT
            val isActive = activeIdx < 0 || cols.getOrNull(activeIdx) == "1"
            if (matchesProduct && isActive) updates.forEach { (i, value) -> if (i in cols.indices) cols[i] = value }
            cols.joinToString("|")
        }
        buildInfo.writeText(updated.joinToString("\n") + "\n")
    }

    private data class InstallEntry(val name: String, val ckey: String, val tags: Set<String>) {
        val relativePath get() = name.replace('\\', '/')

        fun isClientBinary(): Boolean {
            if ("Windows" !in tags || PLATFORM_ARCH !in tags || EXCLUDED_ARCH in tags) return false
            val lower = name.lowercase()
            return !lower.startsWith("utils\\") || "exceptionhandler" in lower
        }
    }

    private class Cdn(val hosts: List<String>, val path: String) {
        fun require(kind: String, key: String) = fetch(kind, key) ?: throw IOException("CDN is missing $kind/$key")

        fun fetch(kind: String, key: String, range: LongRange? = null): ByteArray? {
            var failure: IOException? = null
            var missing = false
            for (host in hosts) {
                val request = Request.Builder()
                    .url("http://$host/$path/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key")
                    .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                    .build()
                try {
                    Net.http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> return response.body!!.bytes()
                            response.code == 403 || response.code == 404 -> missing = true
                            else -> failure = IOException("HTTP ${response.code} from $host")
                        }
                    }
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (missing) return null
            throw failure ?: IOException("No CDN hosts in $BUILD_INFO")
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
                    val fetched = cdn.fetch("data", "$archive.index") ?: continue
                    local.parentFile?.mkdirs()
                    local.writeBytes(fetched)
                    fetched
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
