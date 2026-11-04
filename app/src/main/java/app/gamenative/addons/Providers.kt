package app.gamenative.addons

import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class Provider(val key: String, val label: String) {
    WOWINTERFACE("wowinterface", "WoWInterface"),
    TUKUI("tukui", "Tukui"),
    GITHUB("github", "GitHub"),
    URL("url", "Web link"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class CatalogAddon(
    val provider: Provider,
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val folders: List<String>,
    val downloadsMonthly: Long,
    val downloadsTotal: Long,
    val gameVersions: List<String>,
    val infoUrl: String?,
    val summary: String?,
    val images: List<String> = emptyList(),
    /** Set only for catalogs that list the file in the index (Tukui); WoWInterface resolves it on demand. */
    val downloadUrl: String? = null,
    /** Last update, epoch millis; 0 when the source doesn't say. */
    val updated: Long = 0,
) {
    val key get() = "${provider.key}:$id"

    /** Retail 12.x is Midnight and later, which the community reports WoW Forever shares its addon API with. */
    val isMidnight get() = gameVersions.any { it.startsWith("12.") }
}

/** A remote file ready to download. */
data class RemoteFile(val url: String, val md5: String?, val version: String, val assetName: String? = null)

/** What the detail view shows. [markdown] is false for WoWInterface descriptions, which are BBCode. */
data class AddonDetails(
    val description: String?,
    val changelog: String?,
    val images: List<String>,
    val homepage: String?,
    val markdown: Boolean,
)

data class GitHubRepo(val owner: String, val repo: String)

/** "69 MB", "1.4 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "${Math.round(bytes / (1024.0 * 1024))} MB"
}

/** Something to tell the user before a batch install. [serious] ones (no room) stay in view; the rest can scroll with the list. */
data class SelectionWarning(val text: String, val serious: Boolean)

/**
 * Things worth knowing before installing [chosen] together: whether there is room for it, and that folders shared
 * between downloads are checked as each one is unpacked. [gameFree] is the space where addons are unpacked;
 * [cacheFree] is where each zip is downloaded first, so only the largest has to fit.
 */
fun selectionWarnings(chosen: List<GitHubProduct>, gameFree: Long, cacheFree: Long): List<SelectionWarning> {
    if (chosen.isEmpty()) return emptyList()
    val total = chosen.sumOf { it.sizeBytes }
    val largest = chosen.maxOf { it.sizeBytes }
    val out = ArrayList<SelectionWarning>()
    if (total > 0 && gameFree < total) {
        out += SelectionWarning("Not enough room: these need about ${formatBytes(total)} and the game drive has ${formatBytes(gameFree)} free.", true)
    }
    if (largest > 0 && cacheFree < largest) {
        out += SelectionWarning("Not enough room to download the biggest file (${formatBytes(largest)}); ${formatBytes(cacheFree)} is free on this device.", true)
    }
    if (chosen.size > 1) {
        out += SelectionWarning("Each download is checked before it is unpacked. If one would replace folders that another addon already has, you will be asked first.", false)
    }
    return out
}

/** One installable product of a GitHub repo: the newest release under [prefix] and the zip that would be installed. */
data class GitHubProduct(val prefix: String, val tag: String, val assetName: String, val sizeBytes: Long, val isDefault: Boolean) {
    /** "69 MB", "1.4 GB"; empty when GitHub gave no size. */
    val sizeLabel: String get() = if (sizeBytes <= 0) "" else formatBytes(sizeBytes)
    val isLarge: Boolean get() = sizeBytes >= 200L * 1024 * 1024

    /** "deDE" for "quests-audio-deDE": a translation of another product, which the chooser tucks away by default. */
    val locale: String? get() = LOCALE_SUFFIX.find(prefix)?.groupValues?.get(1)

    private companion object {
        val LOCALE_SUFFIX = Regex("""-([a-z]{2}[A-Z]{2})$""")
    }
}

object WowInterfaceProvider {
    private const val BASE = "https://api.mmoui.com/v3/game/WOW"
    const val LIST_URL = "$BASE/filelist.json"
    private const val DETAILS_URL = "$BASE/filedetails/"
    private const val MAX_CACHE_AGE_MS = 12L * 60 * 60 * 1000

    fun parseFileList(json: String): List<CatalogAddon> {
        val array = JSONArray(json)
        val out = ArrayList<CatalogAddon>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("UID").takeIf { it.isNotEmpty() } ?: continue
            val compat = o.optJSONArray("UICompatibility")
            out.add(
                CatalogAddon(
                    provider = Provider.WOWINTERFACE,
                    id = id,
                    name = o.optString("UIName"),
                    author = o.optString("UIAuthorName"),
                    version = o.optString("UIVersion"),
                    folders = o.optJSONArray("UIDir").strings(),
                    downloadsMonthly = o.optString("UIDownloadMonthly").toLongOrNull() ?: 0,
                    downloadsTotal = o.optString("UIDownloadTotal").toLongOrNull() ?: 0,
                    gameVersions = (0 until (compat?.length() ?: 0)).mapNotNull { compat?.optJSONObject(it)?.optString("version")?.takeIf { v -> v.isNotEmpty() } },
                    infoUrl = o.optString("UIFileInfoURL").takeIf { it.isNotEmpty() },
                    summary = null,
                    images = o.optJSONArray("UIIMGs").strings().filter { it.startsWith("https://") },
                    updated = o.optLong("UIDate", 0),
                ),
            )
        }
        return out
    }

    fun parseFileDetails(json: String): RemoteFile {
        val o = JSONArray(json).optJSONObject(0) ?: throw IOException("WoWInterface returned no file details")
        val url = o.optString("UIDownload").takeIf { it.isNotEmpty() } ?: throw IOException("WoWInterface has no download for this addon")
        return RemoteFile(url, o.optString("UIMD5").takeIf { it.isNotEmpty() }, o.optString("UIVersion"))
    }

    /** The catalog, from a cache up to 12 hours old, else from the network (falling back to a stale cache if offline). */
    fun catalog(cacheDir: File): List<CatalogAddon> {
        val cache = File(cacheDir, "wowinterface-filelist.json")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_CACHE_AGE_MS
        if (!fresh) {
            try {
                val text = httpText(LIST_URL)
                cache.writeText(text)
                return parseFileList(text)
            } catch (e: Exception) {
                if (!cache.isFile) throw e
            }
        }
        return parseFileList(cache.readText())
    }

    fun resolve(id: String): RemoteFile = parseFileDetails(httpText("$DETAILS_URL$id.json"))

    fun parseDetails(json: String, fallbackImages: List<String>, infoUrl: String?): AddonDetails {
        val o = JSONArray(json).optJSONObject(0) ?: throw IOException("WoWInterface returned no details")
        val description = o.optString("UIDescription").takeIf { it.isNotBlank() }
        val images = (o.optJSONArray("UIIMGs").strings() + fallbackImages + Markup.extractImageUrls(description.orEmpty()))
            .filter { it.startsWith("https://") }.distinct()
        return AddonDetails(
            description = description,
            changelog = o.optString("UIChangeLog").takeIf { it.isNotBlank() },
            images = images,
            homepage = infoUrl,
            markdown = false,
        )
    }

    fun details(id: String, fallbackImages: List<String> = emptyList(), infoUrl: String? = null): AddonDetails =
        parseDetails(httpText("$DETAILS_URL$id.json"), fallbackImages, infoUrl)

    fun details(addon: CatalogAddon): AddonDetails = details(addon.id, addon.images, addon.infoUrl)
}

object TukuiProvider {
    private const val URL = "https://api.tukui.org/v1/addons"

    fun parse(json: String): List<CatalogAddon> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            CatalogAddon(
                provider = Provider.TUKUI,
                id = slug,
                name = o.optString("name", slug),
                author = o.optString("author"),
                version = o.optString("version"),
                folders = emptyList(),
                downloadsMonthly = 0,
                downloadsTotal = 0,
                gameVersions = o.optJSONArray("patch").strings(),
                infoUrl = o.optString("web_url").takeIf { it.isNotEmpty() },
                summary = o.optString("small_desc").takeIf { it.isNotEmpty() },
                downloadUrl = url,
                updated = runCatching { java.time.LocalDate.parse(o.optString("last_update")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0),
            )
        }
    }

    fun catalog(): List<CatalogAddon> = parse(httpText(URL))

    private const val MAX_CHANGELOG_CHARS = 8000

    /** Tukui's changelog files can run to hundreds of KB; the recent releases are all anyone reads. */
    fun parseDetails(json: String, changelog: String?): AddonDetails {
        val o = JSONObject(json)
        val description = o.optString("desc").takeIf { it.isNotBlank() } ?: o.optString("small_desc").takeIf { it.isNotBlank() }
        val images = (listOf(o.optString("screenshot_url")) + o.optJSONArray("gallery_url").strings() + Markup.extractImageUrls(description.orEmpty()))
            .filter { it.startsWith("https://") }.distinct()
        return AddonDetails(
            description = description,
            changelog = changelog?.takeIf { it.isNotBlank() }?.let { if (it.length > MAX_CHANGELOG_CHARS) it.take(MAX_CHANGELOG_CHARS).substringBeforeLast('\n') + "\n\n…" else it },
            images = images,
            homepage = o.optString("web_url").takeIf { it.isNotEmpty() },
            markdown = true,
        )
    }

    fun details(slug: String): AddonDetails {
        val json = httpText("https://api.tukui.org/v1/addon/$slug")
        val changelogUrl = JSONObject(json).optString("changelog_url").takeIf { it.startsWith("https://") }
        return parseDetails(json, changelogUrl?.let { runCatching { httpText(it) }.getOrNull() })
    }
}

object GitHubProvider {
    private val EXCLUDED_FLAVORS = Regex("""classic|cata|wrath|wotlk|bcc|tbc|vanilla|\bera\b|mists|mop|-nolib|nolib""", RegexOption.IGNORE_CASE)
    /** Desktop-app builds that share a release with addons ("Foo-mac-arm64.zip"); a word on its own, so "Macro" is fine. */
    private val PLATFORM_BUILD = Regex("""(?<![a-z])(mac|macos|darwin|osx|linux|windows|win32|win64|appimage)(?![a-z])""", RegexOption.IGNORE_CASE)
    private val REPO_URL = Regex("""^(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?:/.*)?$""")

    /** The id a GitHub install is recorded under: "owner/repo", or "owner/repo#prefix" for one product of a multi-product repo. */
    fun recordId(repo: GitHubRepo, product: GitHubProduct? = null): String =
        "${repo.owner}/${repo.repo}" + (product?.prefix?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: "")

    /** The repository behind a recorded GitHub id. */
    fun repoOfRecord(id: String): GitHubRepo? = parseRepo("github.com/${id.substringBefore('#')}")

    fun parseRepo(text: String): GitHubRepo? =
        REPO_URL.matchEntire(text.trim())?.destructured?.let { (owner, repo) -> GitHubRepo(owner, repo) }

    /** Version numbers and separators stripped, so "Foo-2.1.14.zip" and "Foo-2.2.0.zip" compare equal. */
    internal fun assetShape(name: String) = name.lowercase().replace(Regex("""[\d._\-v]+"""), "")

    /**
     * A release zip. An asset shaped like [preferredAsset] (the one installed last time) wins, as in Obtainium;
     * otherwise one that isn't built for another game flavor.
     */
    fun parseLatest(json: String, preferredAsset: String? = null): RemoteFile {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: throw IOException("This release has no files")
        val zips = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { isAddonZip(it) }
        val pick = preferredAsset?.let { shape -> zips.firstOrNull { assetShape(it.optString("name")) == assetShape(shape) } }
            ?: zips.firstOrNull { !EXCLUDED_FLAVORS.containsMatchIn(it.optString("name")) } ?: zips.firstOrNull()
            ?: throw IOException("This release has no zip file")
        return RemoteFile(pick.getString("browser_download_url"), null, o.optString("tag_name"), pick.optString("name"))
    }

    private fun isAddonZip(asset: JSONObject) = asset.optString("name").let { it.endsWith(".zip", ignoreCase = true) && !PLATFORM_BUILD.containsMatchIn(it) } &&
        asset.optString("browser_download_url").startsWith("https://")

    /** "spoken/v3.8.0" -> "spoken". A tag with no slash has the empty prefix. */
    internal fun tagPrefix(tag: String) = tag.substringBeforeLast('/', "")

    /**
     * Monorepos publish several products as tags like "spoken/v3.8.0" and "quests-audio/v2.5.0", and
     * `/releases/latest` is whichever was published last, of any product. So this reads the release list
     * and stays within one tag prefix: [installedTag]'s when updating, otherwise the prefix the repo's
     * name starts with ("spoken-wow" -> "spoken"), otherwise the newest release with a zip.
     */
    fun parseReleases(json: String, repoName: String, preferredAsset: String? = null, installedTag: String? = null): RemoteFile {
        val list = JSONArray(json)
        val releases = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").isNotEmpty() }
            .filter { r ->
                val assets = r.optJSONArray("assets") ?: return@filter false
                (0 until assets.length()).any { i -> assets.optJSONObject(i)?.let(::isAddonZip) == true }
            }
        if (releases.isEmpty()) throw IOException("This repository has no release with a zip file")
        val prefix = if (installedTag != null) tagPrefix(installedTag) else defaultPrefix(releases.map { tagPrefix(it.getString("tag_name")) }, repoName)
        val pick = if (prefix == null) releases.first() else releases.firstOrNull { tagPrefix(it.getString("tag_name")) == prefix }
            ?: throw IOException("No release under \"$prefix\" has a zip file")
        return parseLatest(pick.toString(), preferredAsset)
    }

    /** The product a fresh install takes: the longest prefix the repo's name starts with ("spoken-wow" -> "spoken"). */
    internal fun defaultPrefix(prefixes: List<String>, repoName: String): String? {
        val name = repoName.lowercase()
        return prefixes.distinct().filter { it.isNotEmpty() && name.startsWith(it.lowercase()) }.maxByOrNull { it.length }
    }

    /**
     * What a repo offers to install: its newest zip release for each tag prefix, the default first and the
     * rest in the order GitHub lists them (newest first). One entry for an ordinary repo, several for a monorepo.
     */
    fun parseProducts(json: String, repoName: String): List<GitHubProduct> {
        val list = JSONArray(json)
        val newestPerPrefix = LinkedHashMap<String, Pair<JSONObject, String>>()
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease") || r.optString("tag_name").isEmpty()) continue
            val asset = runCatching { parseLatest(r.toString()) }.getOrNull()?.assetName ?: continue
            newestPerPrefix.getOrPut(tagPrefix(r.getString("tag_name"))) { r to asset }
        }
        val default = defaultPrefix(newestPerPrefix.keys.toList(), repoName)
        val products = newestPerPrefix.map { (prefix, picked) ->
            val (r, asset) = picked
            val assets = r.getJSONArray("assets")
            val size = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull { it.optString("name") == asset }?.optLong("size") ?: 0L
            GitHubProduct(prefix, r.getString("tag_name"), asset, size, prefix == default)
        }
        return products.sortedByDescending { it.isDefault }
    }

    fun products(repo: GitHubRepo): List<GitHubProduct> =
        parseProducts(httpText("https://api.github.com/repos/${repo.owner}/${repo.repo}/releases?per_page=100", github = true), repo.repo)

    /** [installedTag] is the version recorded at install; it keeps an update inside the same product of a monorepo. */
    fun latest(repo: GitHubRepo, preferredAsset: String? = null, installedTag: String? = null): RemoteFile =
        parseReleases(httpText("https://api.github.com/repos/${repo.owner}/${repo.repo}/releases?per_page=100", github = true), repo.repo, preferredAsset, installedTag)

    fun parseDetails(repoJson: String, releaseJson: String?): AddonDetails {
        val repo = JSONObject(repoJson)
        val release = releaseJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val notes = release?.optString("body")?.takeIf { it.isNotBlank() }
        val tag = release?.optString("tag_name")?.takeIf { it.isNotEmpty() }
        return AddonDetails(
            description = repo.optString("description").takeIf { it.isNotBlank() },
            changelog = notes?.let { n -> if (tag != null) "## $tag\n\n$n" else n },
            images = Markup.extractImageUrls(notes.orEmpty()),
            homepage = repo.optString("homepage").takeIf { it.startsWith("https://") } ?: repo.optString("html_url").takeIf { it.isNotEmpty() },
            markdown = true,
        )
    }

    fun details(repo: GitHubRepo): AddonDetails {
        val base = "https://api.github.com/repos/${repo.owner}/${repo.repo}"
        return parseDetails(httpText(base, github = true), runCatching { httpText("$base/releases/latest", github = true) }.getOrNull())
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

internal fun httpText(url: String, github: Boolean = false): String {
    val request = Request.Builder().url(url).header("User-Agent", "WoW-Forever-Android")
        .apply { if (github) header("Accept", "application/vnd.github+json") }.build()
    Net.http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("${response.request.url.host} answered ${response.code}")
        return response.body?.string() ?: throw IOException("${response.request.url.host} sent nothing")
    }
}
