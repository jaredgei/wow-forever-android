package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import app.gamenative.wow.product.BuildInfo
import app.gamenative.wow.product.GameRegions
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.product.detectProduct
import app.gamenative.wow.tact.ArchiveIndex
import app.gamenative.wow.tact.Blte
import app.gamenative.wow.tact.Cdn
import app.gamenative.wow.tact.EncodingLookup
import app.gamenative.wow.tact.InstallEntry
import app.gamenative.wow.tact.InstallManifest
import app.gamenative.wow.tact.Psv
import app.gamenative.wow.tact.TactConfig
import app.gamenative.wow.tact.hex
import app.gamenative.wow.tact.md5Hex
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import timber.log.Timber

object WowClientDownloader {
    const val BUILD_INFO = BuildInfo.FILE_NAME
    private const val PLATFORM_ARCH = "arm64"
    private const val EXCLUDED_ARCH = "x86_64"
    private fun patchUrl(product: WowProduct) = "http://us.patch.battle.net:1119/${product.code}"
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

    /** True when the patch service lists a build of [product]. */
    fun isProductLive(product: WowProduct): Boolean {
        val versions = runCatching { fetchText("${patchUrl(product)}/versions") }.getOrNull() ?: return false
        return Psv.parse(versions).any { !it["BuildConfig"].isNullOrBlank() }
    }

    fun readBuildInfo(buildInfo: File): Map<String, String>? =
        runCatching { activeBuild(buildInfo) }.getOrNull()

    fun checkVersion(gameRoot: File, product: WowProduct? = detectProduct(gameRoot)): VersionCheckResult? {
        if (product == null) {
            Timber.w("checkVersion: no supported product in $gameRoot")
            return null
        }
        val row = runCatching { activeBuild(File(gameRoot, BUILD_INFO), product) }.getOrElse {
            Timber.w(it, "checkVersion: no readable $BUILD_INFO in $gameRoot")
            return null
        }
        if (row["Product"] != product.code) {
            Timber.w("checkVersion: wrong product '${row["Product"]}', expected '${product.code}'")
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

        val versions = runCatching { fetchText("${patchUrl(product)}/versions") }.getOrElse {
            Timber.w(it, "checkVersion: http call failed")
            return null
        } ?: run {
            Timber.w("checkVersion: response body is null or unsuccessful")
            return null
        }
        // Regions patch on different days, so compare with the region the player logs into, not the first (US) row (#36).
        val region = GameRegions.effective(row["Branch"]).code
        val remoteRow = regionRow(Psv.parse(versions), "Region", region)
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

        val cdns = runCatching { fetchText("${patchUrl(product)}/cdns") }.getOrNull()?.let(Psv::parse).orEmpty()
        val cdnsRow = regionRow(cdns, "Name", region)

        val isOutdated = localVersion != remoteVersion || (remoteBuildKey.isNotBlank() && localBuildKey != remoteBuildKey)
        Timber.i("checkVersion: region=$region, localVersion=$localVersion, remoteVersion=$remoteVersion, isOutdated=$isOutdated")
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

    /** The patch service row for [region] in [column], else the first row if that region isn't listed. */
    internal fun regionRow(rows: List<Map<String, String>>, column: String, region: String): Map<String, String>? =
        rows.firstOrNull { it[column].equals(region, ignoreCase = true) } ?: rows.firstOrNull()

    fun updateGame(gameRoot: File, product: WowProduct, target: VersionCheckResult, onStatus: (String) -> Unit) {
        val cdn = Cdn(
            hosts = target.remoteCdnHosts.ifEmpty { DEFAULT_CDN_HOSTS },
            path = target.remoteCdnPath.ifEmpty { DEFAULT_CDN_PATH },
        )
        onStatus("Fetching build ${target.remoteVersion} manifest...")
        val buildConfigRaw = cdn.require("config", target.remoteBuildConfig)
        val cdnConfigRaw = target.remoteCdnConfig.takeIf { it.isNotBlank() }?.let { cdn.require("config", it) }

        saveConfig(gameRoot, target.remoteBuildConfig, buildConfigRaw)
        cdnConfigRaw?.let { saveConfig(gameRoot, target.remoteCdnConfig, it) }

        val cdnConfig = cdnConfigRaw?.let { TactConfig.parse(String(it)) }
        syncClient(
            gameRoot = gameRoot,
            product = product,
            cdn = cdn,
            buildConfig = TactConfig.parse(String(buildConfigRaw)),
            archives = lazy { cdnConfig?.let { ArchiveLocator(cdn, gameRoot, it.getValue("archives")) } },
            onStatus = onStatus,
        )
        cdnConfig?.let { syncIndices(gameRoot, cdn, it, onStatus) }

        onStatus("Updating build information...")
        updateBuildInfo(gameRoot, product, target)
    }

    fun download(gameRoot: File, product: WowProduct, onStatus: (String) -> Unit) {
        val row = activeBuild(File(gameRoot, BUILD_INFO), product)
        check(row["Product"] == product.code) { "Incompatible game product: ${row["Product"]}. Expected ${product.code}." }
        val latest = checkVersion(gameRoot, product)
        if (latest != null && latest.remoteBuildConfig.isNotBlank()) return updateGame(gameRoot, product, latest, onStatus)

        val cdn = Cdn(hosts = splitHosts(row.getValue("CDN Hosts")), path = row.getValue("CDN Path"))
        onStatus("Reading build ${row["Version"].orEmpty()} manifest...")
        syncClient(
            gameRoot = gameRoot,
            product = product,
            cdn = cdn,
            buildConfig = TactConfig.parse(String(cdn.require("config", row.getValue("Build Key")))),
            archives = lazy {
                ArchiveLocator(cdn, gameRoot, TactConfig.parse(String(cdn.require("config", row.getValue("CDN Key")))).getValue("archives"))
            },
            onStatus = onStatus,
        )
    }

    private fun syncClient(
        gameRoot: File,
        product: WowProduct,
        cdn: Cdn,
        buildConfig: Map<String, List<String>>,
        archives: Lazy<ArchiveLocator?>,
        onStatus: (String) -> Unit,
    ) {
        val files = InstallManifest.parse(Blte.decode(cdn.require("data", buildConfig.getValue("install")[1]))).filter { it.isClientBinary() }
        check(files.isNotEmpty()) { "No Windows ARM64 client files in this build" }

        val clientDir = File(gameRoot, product.flavorDir)
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
        DigestOutputStream(temp.outputStream().buffered(), digest).use { Blte.decode(encoded, it) }
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

    private fun updateBuildInfo(gameRoot: File, product: WowProduct, target: VersionCheckResult) {
        val buildInfo = File(gameRoot, BUILD_INFO)
        val info = BuildInfo.read(gameRoot) ?: return
        val updates = mapOf(
            "Version" to target.remoteVersion,
            "Build Key" to target.remoteBuildConfig,
            "CDN Key" to target.remoteCdnConfig,
            "CDN Hosts" to target.remoteCdnHosts.joinToString(" "),
            "CDN Path" to target.remoteCdnPath,
        ).filterValues { it.isNotBlank() }
        val temp = File(gameRoot, "$BUILD_INFO.tmp")
        temp.writeText(info.withUpdatedActive(product, updates).serialize())
        check(temp.renameTo(buildInfo)) { "Could not update $BUILD_INFO" }
    }

    private fun InstallEntry.isClientBinary(): Boolean {
        if ("Windows" !in tags || PLATFORM_ARCH !in tags || EXCLUDED_ARCH in tags) return false
        val lower = name.lowercase()
        return !lower.startsWith("utils\\") || "exceptionhandler" in lower
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
                val (offset, size) = ArchiveIndex(index).locate(ekey) ?: continue
                return cdn.fetch("data", archive, offset until offset + size)
            }
            return null
        }
    }

    private fun splitHosts(hosts: String) = hosts.split(" ").filter { it.isNotBlank() }

    private fun fetchText(url: String): String? =
        patchHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }

    private fun activeBuild(buildInfo: File, product: WowProduct? = null): Map<String, String> {
        check(buildInfo.isFile) { "Missing $BUILD_INFO in ${buildInfo.parent}" }
        val info = BuildInfo.parse(buildInfo.readText())
        return (product ?: info.products().firstOrNull())?.let { info.active(it) }
            ?: info.active()
            ?: throw IllegalStateException("No build in $BUILD_INFO")
    }
}
