package app.gamenative.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProvidersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val wowiList = """[
        {"UID":"3826","UICATID":"19","UIVersion":"11.0.2.3","UIName":"CTMod","UIAuthorName":"DahkCeles",
         "UIFileInfoURL":"https:\/\/www.wowinterface.com\/downloads\/info3826-CTMod.html","UIDownloadTotal":"598624","UIDownloadMonthly":"41",
         "UICompatibility":[{"version":"11.0.2","name":"The War Within"},{"version":"1.15.2","name":"Classic"}],"UIDir":["CT_Core","CT_Library"]},
        {"UID":"9","UIVersion":"2.0","UIName":"Midnight Thing","UIAuthorName":"Someone","UIDownloadTotal":"5","UIDownloadMonthly":"x",
         "UICompatibility":[{"version":"12.0.0","name":"Midnight"}],"UIDir":null},
        {"UIName":"No id"}
    ]"""

    @Test
    fun wowInterface_parsesCatalogAndFlagsMidnight() {
        val list = WowInterfaceProvider.parseFileList(wowiList)
        assertEquals(2, list.size)
        val ct = list[0]
        assertEquals("wowinterface:3826", ct.key)
        assertEquals(listOf("CT_Core", "CT_Library"), ct.folders)
        assertEquals(41L, ct.downloadsMonthly)
        assertFalse(ct.isMidnight)
        assertTrue(list[1].isMidnight)
        assertEquals(0L, list[1].downloadsMonthly)
        assertTrue(list[1].folders.isEmpty())
    }

    @Test
    fun wowInterface_parsesFileDetails() {
        val file = WowInterfaceProvider.parseFileDetails("""[{"UID":"3826","UIVersion":"11.0.2.3","UIMD5":"801d2daf73ad195c02c58d50be7f1b22","UIDownload":"https:\/\/cdn.wowinterface.com\/downloads\/getfile.php?id=3826&d=1&minion"}]""")
        assertEquals("https://cdn.wowinterface.com/downloads/getfile.php?id=3826&d=1&minion", file.url)
        assertEquals("801d2daf73ad195c02c58d50be7f1b22", file.md5)
        assertEquals("11.0.2.3", file.version)
        try {
            WowInterfaceProvider.parseFileDetails("[]")
            fail("expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun tukui_parsesAddonsWithHttpsDownloads() {
        val list = TukuiProvider.parse("""[
            {"id":-2,"slug":"elvui","author":"Elv","name":"ElvUI","url":"https://api.tukui.org/v1/download/elvui/abc","version":"16.00","patch":["12.1.0","1.15.9"],"web_url":"https://tukui.org/elvui","small_desc":"A UI"},
            {"slug":"insecure","name":"X","url":"http://example.com/x.zip","version":"1"}
        ]""")
        assertEquals(1, list.size)
        assertEquals("tukui:elvui", list[0].key)
        assertTrue(list[0].isMidnight)
        assertEquals("https://api.tukui.org/v1/download/elvui/abc", list[0].downloadUrl)
    }

    @Test
    fun gitHub_parsesRepoUrls() {
        assertEquals(GitHubRepo("Stanzilla", "AdvancedInterfaceOptions"), GitHubProvider.parseRepo("https://github.com/Stanzilla/AdvancedInterfaceOptions"))
        assertEquals(GitHubRepo("a", "b"), GitHubProvider.parseRepo("github.com/a/b/releases/tag/v1"))
        assertEquals(GitHubRepo("a", "b"), GitHubProvider.parseRepo(" https://github.com/a/b.git "))
        assertNull(GitHubProvider.parseRepo("https://example.com/a/b.zip"))
        assertNull(GitHubProvider.parseRepo("https://github.com/onlyowner"))
    }

    @Test
    fun gitHub_prefersTheFlavorNeutralZip() {
        val json = """{"tag_name":"2.1.14","assets":[
            {"name":"Foo-2.1.14-cata.zip","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14-cata.zip"},
            {"name":"Foo-2.1.14.zip","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14.zip"},
            {"name":"release.json","browser_download_url":"https://github.com/a/b/releases/download/2.1.14/release.json"}]}"""
        val file = GitHubProvider.parseLatest(json)
        assertEquals("https://github.com/a/b/releases/download/2.1.14/Foo-2.1.14.zip", file.url)
        assertEquals("2.1.14", file.version)
        assertNull(file.md5)
    }

    @Test
    fun gitHub_fallsBackToAnyZip_andRejectsNone() {
        val onlyClassic = """{"tag_name":"v1","assets":[{"name":"Foo-classic.zip","browser_download_url":"https://x/Foo-classic.zip"}]}"""
        assertEquals("https://x/Foo-classic.zip", GitHubProvider.parseLatest(onlyClassic).url)
        try {
            GitHubProvider.parseLatest("""{"tag_name":"v1","assets":[{"name":"src.tar.gz","browser_download_url":"https://x/src.tar.gz"}]}""")
            fail("expected failure")
        } catch (_: Exception) {
        }
    }

    private fun rel(tag: String, vararg zips: String, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"assets":[${zips.joinToString(",") { """{"name":"$it","size":72200000,"browser_download_url":"https://x/$tag/$it"}""" }}]}"""

    // Newest first, as GitHub lists them: a voice pack and a desktop manager were published after Spoken 3.8.0.
    private val spokenReleases = "[" + listOf(
        rel("manager/v0.1.10", "Spoken-Manager-0.1.10-linux.deb"),
        rel("quests-audio/v2.5.0", "SpokenQuestsAudioAll-2.5.0.zip"),
        rel("spoken/v3.9.0", "Spoken-3.9.0.zip", prerelease = true),
        rel("spoken/v3.8.0", "Spoken-3.8.0.zip"),
        rel("quests-audio/v2.4.0", "SpokenQuestsAudioAll-2.4.0.zip"),
    ).joinToString(",") + "]"

    @Test
    fun gitHub_monorepoFreshInstallTakesTheProductNamedLikeTheRepo() {
        val file = GitHubProvider.parseReleases(spokenReleases, "spoken-wow")
        assertEquals("spoken/v3.8.0", file.version)
        assertEquals("Spoken-3.8.0.zip", file.assetName)
    }

    @Test
    fun gitHub_monorepoUpdateStaysWithinTheInstalledProduct() {
        assertEquals("quests-audio/v2.5.0", GitHubProvider.parseReleases(spokenReleases, "spoken-wow", installedTag = "quests-audio/v2.4.0").version)
        assertEquals("spoken/v3.8.0", GitHubProvider.parseReleases(spokenReleases, "spoken-wow", installedTag = "spoken/v3.7.0").version)
    }

    @Test
    fun gitHub_plainRepoStillTakesTheNewestRelease() {
        val json = "[" + rel("v1.3", "Foo-1.3.zip") + "," + rel("v1.2", "Foo-1.2.zip") + "]"
        assertEquals("v1.3", GitHubProvider.parseReleases(json, "foo").version)
        assertEquals("v1.3", GitHubProvider.parseReleases(json, "foo", installedTag = "v1.2").version)
    }

    @Test
    fun gitHub_unrelatedMonorepoFallsBackToNewestZipRelease_andFailsWithoutOne() {
        val json = "[" + rel("tools/v2", "a.exe") + "," + rel("alpha/v1", "A.zip") + "]"
        assertEquals("alpha/v1", GitHubProvider.parseReleases(json, "widgets").version)
        try {
            GitHubProvider.parseReleases(spokenReleases, "spoken-wow", installedTag = "launcher/v1")
            fail("expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun gitHub_productsListsOneEntryPerPrefix_defaultFirst() {
        val products = GitHubProvider.parseProducts(spokenReleases, "spoken-wow")
        // manager/v0.1.10 has no zip, so it is not offered
        assertEquals(listOf("spoken", "quests-audio"), products.map { it.prefix })
        assertEquals(listOf(true, false), products.map { it.isDefault })
        assertEquals("spoken/v3.8.0", products[0].tag)
        assertEquals("Spoken-3.8.0.zip", products[0].assetName)
        assertEquals("quests-audio/v2.5.0", products[1].tag)
    }

    @Test
    fun gitHub_productsForAPlainRepoIsASingleEntry() {
        val json = "[" + rel("v1.3", "Foo-1.3.zip") + "," + rel("v1.2", "Foo-1.2.zip") + "]"
        val products = GitHubProvider.parseProducts(json, "foo")
        assertEquals(1, products.size)
        assertEquals("v1.3", products[0].tag)
    }

    @Test
    fun gitHub_productSizesReadAsMbAndGb_andFlagLargeDownloads() {
        fun p(bytes: Long) = GitHubProduct("x", "x/v1", "x.zip", bytes, false)
        assertEquals("69 MB", p(72_200_000).sizeLabel)
        assertEquals("1.4 GB", p(1_500_000_000).sizeLabel)
        assertEquals("", p(0).sizeLabel)
        assertEquals(false, p(72_200_000).isLarge)
        assertEquals(true, p(1_500_000_000).isLarge)
    }

    @Test
    fun gitHub_desktopAppBuildsAreNotAddons() {
        val json = "[" + rel("manager/v0.1.9", "Spoken-Manager-0.1.9-mac-arm64.zip", "Spoken-Manager-0.1.9-linux.zip") + "," +
            rel("spoken/v3.8.0", "Spoken-3.8.0.zip") + "," + rel("macro/v1", "MacroToolkit-1.zip") + "]"
        assertEquals(listOf("spoken", "macro"), GitHubProvider.parseProducts(json, "spoken-wow").map { it.prefix })
        assertEquals("spoken/v3.8.0", GitHubProvider.parseReleases(json, "spoken-wow").version)
    }

    @Test
    fun gitHub_productLocaleComesFromTheSuffix() {
        fun p(prefix: String) = GitHubProduct(prefix, "$prefix/v1", "x.zip", 1, false)
        assertEquals("deDE", p("quests-audio-deDE").locale)
        assertNull(p("quests-audio").locale)
        assertNull(p("quests-audio-acted").locale)
        assertNull(p("spoken").locale)
    }

    @Test
    fun gitHub_recordIdsKeepProductsApartAndLeadBackToTheRepo() {
        val repo = GitHubRepo("rusty-key", "spoken-wow")
        val spoken = GitHubProduct("spoken", "spoken/v3.8.0", "Spoken-3.8.0.zip", 1, true)
        val quests = GitHubProduct("quests-audio", "quests-audio/v2.5.0", "q.zip", 1, false)
        assertEquals("rusty-key/spoken-wow", GitHubProvider.recordId(repo))
        assertEquals("rusty-key/spoken-wow#spoken", GitHubProvider.recordId(repo, spoken))
        assertEquals("rusty-key/spoken-wow#quests-audio", GitHubProvider.recordId(repo, quests))
        assertEquals(repo, GitHubProvider.repoOfRecord("rusty-key/spoken-wow#quests-audio"))
        assertEquals(repo, GitHubProvider.repoOfRecord("rusty-key/spoken-wow"))
    }

    private fun overlaps(existing: Set<String>, owners: List<SourceRecord>, id: String, folders: List<String>) =
        AddonOverlaps.find(existing, owners, Provider.GITHUB, id, folders)

    @Test
    fun install_overlapsAreFoldersThatExistAndBelongToSomethingElse() {
        val spoken = SourceRecord(Provider.GITHUB, "o/r#spoken", "r: spoken", "spoken/v1", listOf("Spoken", "Spoken_Quests"))
        val elvui = SourceRecord(Provider.TUKUI, "elvui", "ElvUI", "16", listOf("ElvUI"))
        val owners = listOf(spoken, elvui)
        val existing = setOf("Spoken", "Spoken_Quests", "ElvUI", "HealBot")
        // another product of the same repo wants Spoken_Quests; ElvUI is Tukui's; HealBot was added by hand; New does not exist yet
        val found = overlaps(existing, owners, "o/r#quests", listOf("Spoken_Quests", "ElvUI", "HealBot", "New"))
        assertEquals(listOf(OverlapGroup(spoken, listOf("Spoken_Quests")), OverlapGroup(elvui, listOf("ElvUI")), OverlapGroup(null, listOf("HealBot"))), found)
        // reinstalling or updating the same product replaces its own folders: no overlap
        assertEquals(emptyList<OverlapGroup>(), overlaps(existing, owners, "o/r#spoken", listOf("Spoken", "Spoken_Quests")))
    }

    @Test
    fun install_aRecordMadeBeforeProductIdsIsTheSameProduct() {
        val legacy = SourceRecord(Provider.GITHUB, "o/r", "r", "spoken/v3.8.0", listOf("Spoken", "Spoken_Quests"))
        val existing = setOf("Spoken", "Spoken_Quests")
        // the plain-id record holds the spoken product, so ticking spoken again is a reinstall...
        assertEquals(emptyList<OverlapGroup>(), overlaps(existing, listOf(legacy), "o/r#spoken", listOf("Spoken", "Spoken_Quests")))
        // ...but another product wanting the same folders still overlaps, and so does a different source
        assertEquals(listOf("Spoken", "Spoken_Quests"), overlaps(existing, listOf(legacy), "o/r#quests", listOf("Spoken", "Spoken_Quests")).single().folders)
        assertEquals(listOf("Spoken", "Spoken_Quests"), overlaps(existing, listOf(legacy.copy(id = "other/r")), "o/r#spoken", listOf("Spoken", "Spoken_Quests")).single().folders)
        // a plain id with no product in its tag ("v1.2") is only the same as the plain id
        val plain = SourceRecord(Provider.GITHUB, "o/r", "r", "v1.2", listOf("Foo"))
        assertEquals(1, overlaps(setOf("Foo"), listOf(plain), "o/r#foo", listOf("Foo")).size)
    }

    @Test
    fun install_selectionWarnsAboutRoomAndSharedFolders() {
        val gb = 1L shl 30
        fun p(prefix: String, bytes: Long) = GitHubProduct(prefix, "$prefix/v1", "$prefix.zip", bytes, false)
        assertEquals(emptyList<String>(), selectionWarnings(emptyList(), 0, 0))
        // one small download with plenty of room: nothing to say
        assertEquals(emptyList<String>(), selectionWarnings(listOf(p("a", 70L shl 20)), 10 * gb, 10 * gb))
        // two downloads: the replace-check note
        val two = selectionWarnings(listOf(p("a", 70L shl 20), p("b", 70L shl 20)), 10 * gb, 10 * gb)
        assertEquals(1, two.size)
        assertTrue(two[0].text.contains("asked first"))
        assertFalse(two[0].serious)
        // not enough room on the game drive, and none to download the biggest file
        val tight = selectionWarnings(listOf(p("a", 2 * gb), p("b", 1 * gb)), 1 * gb, 1 * gb)
        assertEquals(3, tight.size)
        assertTrue(tight[0].text.contains("3.0 GB") && tight[0].text.contains("1.0 GB"))
        assertTrue(tight[1].text.contains("2.0 GB"))
        // running out of room is what stays pinned on screen; the replace-check note can scroll
        assertEquals(listOf(true, true, false), tight.map { it.serious })
    }

    @Test
    fun sources_recordReplacesPriorOwnerOfTheSameFolders() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.WOWINTERFACE, "1", "Old", "1.0", listOf("Foo", "Bar")))
        AddonSources.record(root, SourceRecord(Provider.TUKUI, "foo", "New", "2.0", listOf("Foo")))
        val all = AddonSources.load(root)
        assertEquals(setOf("tukui:foo", "wowinterface:1"), all.map { "${it.provider.key}:${it.id}" }.toSet())
        assertEquals(listOf("Bar"), all.first { it.provider == Provider.WOWINTERFACE }.folders)
    }

    @Test
    fun sources_pruneDropsRemovedAddons() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.WOWINTERFACE, "1", "A", "1", listOf("A1", "A2")))
        AddonSources.record(root, SourceRecord(Provider.TUKUI, "b", "B", "1", listOf("B1")))
        AddonSources.prune(root, setOf("A1"))
        val all = AddonSources.load(root)
        assertEquals(1, all.size)
        assertEquals(listOf("A1"), all[0].folders)
    }

    @Test
    fun sources_loadSurvivesGarbage() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        File(root, "_forever_/Interface/.addon-sources.json").writeText("not json")
        assertTrue(AddonSources.load(root).isEmpty())
    }

    @Test
    fun md5_matchesKnownValue() {
        val f = File(tmp.root, "x").also { it.writeText("hello") }
        assertEquals("5d41402abc4b2a76b9719d911017c592", AddonInstaller.md5(f))
    }

    @Test
    fun wowInterface_parsesDetailsWithImagesFromEverywhere() {
        val d = WowInterfaceProvider.parseDetails(
            """[{"UIDescription":"Nice [B]addon[/B] [IMG]https://cdn.test/inline.png[/IMG]","UIChangeLog":"# Changelog\r\n- fixed","UIIMGs":["https://cdn.test/a.png","http://insecure.test/b.png"]}]""",
            listOf("https://cdn.test/a.png", "https://cdn.test/fallback.png"), "https://www.wowinterface.com/x",
        )
        assertEquals(listOf("https://cdn.test/a.png", "https://cdn.test/fallback.png", "https://cdn.test/inline.png"), d.images)
        assertTrue(d.changelog!!.contains("fixed"))
        assertEquals("https://www.wowinterface.com/x", d.homepage)
        assertFalse(d.markdown)
    }

    @Test
    fun tukui_parsesDetailsAndTruncatesHugeChangelogsAtALineBreak() {
        val json = """{"desc":"## About\n**ElvUI** rocks","small_desc":"short","screenshot_url":"https://s.test/1.jpg","gallery_url":["https://s.test/2.jpg"],"web_url":"https://tukui.org/elvui"}"""
        val long = (1..2000).joinToString("\n") { "* change number $it" }
        val d = TukuiProvider.parseDetails(json, long)
        assertEquals(listOf("https://s.test/1.jpg", "https://s.test/2.jpg"), d.images)
        assertTrue(d.markdown)
        assertTrue(d.changelog!!.length < 8100 && d.changelog!!.endsWith("…"))
        assertEquals("short", TukuiProvider.parseDetails("""{"small_desc":"short"}""", null).description)
    }

    @Test
    fun gitHub_parsesDetails() {
        val d = GitHubProvider.parseDetails(
            """{"description":"Restores options","homepage":"https://www.curseforge.com/wow/addons/x","html_url":"https://github.com/a/b"}""",
            """{"tag_name":"2.1.14","body":"- fixed ![s](https://i.test/s.png)"}""",
        )
        assertEquals("Restores options", d.description)
        assertEquals("## 2.1.14\n\n- fixed ![s](https://i.test/s.png)", d.changelog)
        assertEquals(listOf("https://i.test/s.png"), d.images)
        assertEquals("https://www.curseforge.com/wow/addons/x", d.homepage)
        assertEquals("https://github.com/a/b", GitHubProvider.parseDetails("""{"html_url":"https://github.com/a/b","homepage":""}""", null).homepage)
    }

    @Test
    fun gitHub_prefersTheSameKindOfAssetAsLastTime() {
        val json = """{"tag_name":"3.0.0","assets":[
            {"name":"Foo-3.0.0.zip","browser_download_url":"https://x/Foo-3.0.0.zip"},
            {"name":"Foo-3.0.0-mainline.zip","browser_download_url":"https://x/Foo-3.0.0-mainline.zip"}]}"""
        assertEquals("Foo-3.0.0.zip", GitHubProvider.parseLatest(json).assetName)
        assertEquals("Foo-3.0.0-mainline.zip", GitHubProvider.parseLatest(json, preferredAsset = "Foo-2.9.1-mainline.zip").assetName)
        assertEquals("Foo-3.0.0.zip", GitHubProvider.parseLatest(json, preferredAsset = "Gone-1.0.zip").assetName)
    }

    @Test
    fun fingerprint_prefersEtagThenModifiedThenSize() {
        assertEquals("etag:\"abc\"", AddonInstaller.fingerprintOf("\"abc\"", "Mon", 5))
        assertEquals("modified:Mon", AddonInstaller.fingerprintOf(null, "Mon", 5))
        assertEquals("size:5", AddonInstaller.fingerprintOf("", null, 5))
        assertNull(AddonInstaller.fingerprintOf(null, null, 0))
    }

    @Test
    fun sources_rememberTheAssetAndTrackUrlRecords() {
        val root = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }
        AddonSources.record(root, SourceRecord(Provider.GITHUB, "a/b", "b", "1.0", listOf("B"), "B-1.0.zip"))
        AddonSources.record(root, SourceRecord(Provider.URL, "https://x.test/addon.zip", "Addon", "etag:\"1\"", listOf("Addon")))
        val all = AddonSources.load(root)
        assertEquals("B-1.0.zip", all.first { it.provider == Provider.GITHUB }.assetName)
        assertNull(all.first { it.provider == Provider.URL }.assetName)
        assertEquals(Provider.URL, Provider.of("url"))
    }
}
