package app.gamenative.wow.install

import app.gamenative.wow.product.BuildInfo
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.product.detectProduct
import app.gamenative.wow.tact.TactFixtures
import app.gamenative.wow.tact.TactFixtures.FileSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GameInstallerTest {

    private val server = MockWebServer()
    private val requested = CopyOnWriteArrayList<String>()
    private var failPath: String? = null
    private lateinit var root: File

    private val exe = ByteArray(5000) { (it % 251).toByte() }
    private val dll = "loader dll".toByteArray()
    private val x64 = ByteArray(300) { 7 }
    private val exeEkey = "a1".repeat(16)
    private val dllEkey = "b2".repeat(16)
    private val x64Ekey = "c3".repeat(16)
    private val archive = "d4".repeat(16)
    private val installEkey = "e5".repeat(16)
    private val encodingEkey = "f6".repeat(16)

    private val files = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("install").toFile()
        val manifest = TactFixtures.installManifest(
            listOf(
                FileSpec("Wow-arm64.exe", exe, setOf("Windows", "arm64", "enUS", "deDE", "US", "EU")),
                FileSpec("Utils\\loader.dll", dll, setOf("Windows", "arm64", "enUS", "US", "EU")),
                FileSpec("Wow.exe", x64, setOf("Windows", "x86_64", "enUS", "deDE", "US", "EU")),
            ),
        )
        val encoding = TactFixtures.encodingFile(
            mapOf(TactFixtures.md5(exe) to exeEkey, TactFixtures.md5(dll) to dllEkey, TactFixtures.md5(x64) to x64Ekey),
        )
        val dllBlte = TactFixtures.blte(listOf(dll), 'Z')
        val archiveBytes = ByteArray(100) + dllBlte
        val buildConfig = "# Build Configuration\ninstall = ${"00".repeat(16)} $installEkey\nencoding = ${"11".repeat(16)} $encodingEkey\ninstall-size = 0 777\n"
        val cdnConfig = "# CDN Configuration\narchives = $archive\narchives-index-size = 4160\n"
        val buildKey = TactFixtures.md5(buildConfig.toByteArray())
        val cdnKey = TactFixtures.md5(cdnConfig.toByteArray())

        files["/wowf/versions"] = ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n" +
            "## seqn = 1\nus|$buildKey|$cdnKey||70245|1.60.1.70245|\n").toByteArray()
        files[cdnPath("config", buildKey)] = buildConfig.toByteArray()
        files[cdnPath("config", cdnKey)] = cdnConfig.toByteArray()
        files[cdnPath("data", installEkey)] = TactFixtures.blte(listOf(manifest), 'Z')
        files[cdnPath("data", encodingEkey)] = TactFixtures.blte(listOf(encoding), 'N')
        files[cdnPath("data", exeEkey)] = TactFixtures.blte(listOf(exe.copyOfRange(0, 2000), exe.copyOfRange(2000, 5000)), 'Z')
        files[cdnPath("data", x64Ekey)] = TactFixtures.blte(listOf(x64), 'N')
        files[cdnPath("data", archive)] = archiveBytes
        files[cdnPath("data", "$archive.index")] = TactFixtures.archiveIndex(mapOf(dllEkey to (100 to dllBlte.size)))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                requested += path
                if (path == failPath) return MockResponse().setResponseCode(500)
                val body = files[path] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split("-")?.map { it.toInt() }
                val slice = if (range != null) body.copyOfRange(range[0], minOf(range[1] + 1, body.size)) else body
                return MockResponse().setBody(Buffer().write(slice))
            }
        }
        server.start()
        files["/wowf/cdns"] = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\nus|tpr/wow|${server.hostName}:${server.port}||tpr/configs/data\n".toByteArray()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun cdnPath(kind: String, key: String) = "/tpr/wow/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"

    private fun installer(space: Long = Long.MAX_VALUE) =
        GameInstaller(patchBase = server.url("/").toString().trimEnd('/'), http = OkHttpClient(), usableSpace = { space })

    private val req get() = InstallRequest(root, WowProduct.FOREVER)

    @Test
    fun installsOnlyMatchingTagsAndWritesInstallInfo() = runBlocking {
        installer().run(req) {}
        assertArrayEquals(exe, File(root, "_forever_/Wow-arm64.exe").readBytes())
        assertArrayEquals(dll, File(root, "_forever_/Utils/loader.dll").readBytes())
        assertFalse(File(root, "_forever_/Wow.exe").exists())
        assertTrue(File(root, "Data/indices/$archive.index").isFile)
        assertEquals(WowProduct.FOREVER, detectProduct(root))
        val row = BuildInfo.read(root)!!.active(WowProduct.FOREVER)!!
        assertEquals("1.60.1.70245", row["Version"])
        assertEquals(installEkey, row["Install Key"])
        assertEquals("Product Flavor!STRING:0\nwowf\n", File(root, "_forever_/.flavor.info").readText())
        assertFalse(File(root, GameInstaller.STATE_FILE).exists())
    }

    @Test
    fun writesBuildInfoLast() = runBlocking {
        failPath = cdnPath("data", archive)
        try {
            installer().run(req) {}
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertNull(BuildInfo.read(root))
        assertNull(detectProduct(root))
        assertTrue(File(root, GameInstaller.STATE_FILE).exists())
    }

    @Test
    fun resumesAfterInterruptedFile() = runBlocking {
        failPath = cdnPath("data", archive)
        runCatching { installer().run(req) {} }
        assertTrue(File(root, "_forever_/Wow-arm64.exe").isFile)
        failPath = null
        requested.clear()
        installer().run(req) {}
        assertFalse("exe re-downloaded", requested.contains(cdnPath("data", exeEkey)))
        assertArrayEquals(dll, File(root, "_forever_/Utils/loader.dll").readBytes())
        assertEquals(WowProduct.FOREVER, detectProduct(root))
    }

    @Test
    fun resumeStateOfOneVersionDoesNotVouchForAnother() = runBlocking {
        files["/wow_classic_era/versions"] = files["/wowf/versions"]!!
        files["/wow_classic_era/cdns"] = files["/wowf/cdns"]!!
        failPath = cdnPath("data", archive)
        runCatching { installer().run(req) {} }
        failPath = null
        val eraExe = File(root, "_classic_era_/Wow-arm64.exe").apply { parentFile!!.mkdirs(); writeText("damaged") }
        installer().run(InstallRequest(root, WowProduct.CLASSIC_ERA)) {}
        assertArrayEquals(exe, eraExe.readBytes())
    }

    @Test
    fun missingIndexIsSkipped() = runBlocking {
        val cdnKey = files.keys.first { it.startsWith("/tpr/wow/config/") && String(files.getValue(it)).startsWith("# CDN") }
        val extra = "99".repeat(16)
        val config = String(files.getValue(cdnKey)).replace("archives = $archive", "archives = $archive $extra")
        files.remove(cdnKey)
        val newKey = TactFixtures.md5(config.toByteArray())
        files[cdnPath("config", newKey)] = config.toByteArray()
        files["/wowf/versions"] = String(files.getValue("/wowf/versions")).replace(cdnKey.substringAfterLast('/'), newKey).toByteArray()
        installer().run(req) {}
        assertEquals(WowProduct.FOREVER, detectProduct(root))
        assertFalse(File(root, "Data/indices/$extra.index").exists())
    }

    @Test
    fun progressTextIsReadable() {
        val p = InstallProgress(InstallProgress.Phase.DOWNLOADING, 48_000_000, 472_000_000, "Data/indices/abc.index")
        assertEquals("48 MB of 472 MB", p.sizeText)
        assertEquals("Archive indexes", p.stepText)
        assertEquals("Wow-arm64.exe", p.copy(currentFile = "_forever_/Wow-arm64.exe").stepText)
        assertEquals("25.4 GB of 67.2 GB", p.copy(doneBytes = 25_400_000_000, totalBytes = 67_200_000_000).sizeText)
    }

    @Test
    fun caseFolderKeepsFirstDirectoryCasing() {
        val folder = CaseFolder()
        assertEquals("Utils/a.dll", folder.fold("Utils/a.dll"))
        assertEquals("Utils/B.DLL", folder.fold("UTILS/B.DLL"))
        assertEquals("Utils/SUB/c", folder.fold("utils/SUB/c"))
        assertEquals("Utils/SUB/d", folder.fold("UTILS/sub/d"))
    }

    @Test
    fun repairKeepsRegionAndLanguage() = runBlocking {
        val tags = "Windows arm64 EU? acct-EUA? geoip-EU? deDE speech?:Windows arm64 EU? deDE text?"
        File(root, ".build.info").writeText(
            BuildInfo(GameInstaller.BUILD_INFO_HEADER, listOf(mapOf("Branch" to "eu", "Active" to "1", "Tags" to tags, "Version" to "old", "Product" to "wowf"))).serialize(),
        )
        installer().run(req) {}
        val row = BuildInfo.read(root)!!.active(WowProduct.FOREVER)!!
        assertEquals("eu", row["Branch"])
        assertEquals(tags, row["Tags"])
        assertEquals("1.60.1.70245", row["Version"])
        assertTrue(File(root, "_forever_/Wow-arm64.exe").isFile)
        assertFalse("enUS-only file skipped for a deDE install", File(root, "_forever_/Utils/loader.dll").exists())
    }

    @Test
    fun updateFollowsTheRegionPickedInSettings() {
        val tags = "Windows arm64 US? acct-USA? geoip-US? enUS speech?:Windows arm64 US? enUS text?"
        File(root, ".build.info").writeText(
            BuildInfo(GameInstaller.BUILD_INFO_HEADER, listOf(mapOf("Branch" to "us", "Active" to "1", "Tags" to tags, "Version" to "old", "Product" to "wowf"))).serialize(),
        )
        val request = InstallRequest(root, WowProduct.FOREVER)
        assertEquals("us", request.keepingInstallRegion().region)
        try {
            app.gamenative.wow.product.GameRegions.chosen = app.gamenative.wow.product.GameRegion.EU
            assertEquals("eu", request.keepingInstallRegion().region)
            assertEquals("enUS", request.keepingInstallRegion().locale)
        } finally {
            app.gamenative.wow.product.GameRegions.chosen = null
        }
    }

    @Test
    fun repairReplacesDamagedFile() = runBlocking {
        installer().run(req) {}
        File(root, "_forever_/Wow-arm64.exe").writeText("damaged")
        installer().run(req) {}
        assertArrayEquals(exe, File(root, "_forever_/Wow-arm64.exe").readBytes())
    }

    @Test
    fun rejectsChecksumMismatch() = runBlocking {
        files[cdnPath("data", exeEkey)] = TactFixtures.blte(listOf("not the exe".toByteArray()), 'N')
        try {
            installer().run(req) {}
            fail("expected checksum failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Checksum"))
        }
        assertFalse(File(root, "_forever_/Wow-arm64.exe").exists())
        assertNull(BuildInfo.read(root))
    }

    @Test
    fun failsCleanlyWhenTargetUnwritable() = runBlocking {
        val keep = File(root, "keep.txt").apply { writeText("mine") }
        root.setWritable(false)
        try {
            installer().run(req) {}
            fail("expected failure")
        } catch (_: IOException) {
        } finally {
            root.setWritable(true)
        }
        assertEquals("mine", keep.readText())
        assertNull(BuildInfo.read(root))
    }

    @Test
    fun insufficientSpaceThrows() = runBlocking {
        try {
            installer(space = 10).plan(req)
            fail("expected space failure")
        } catch (e: InsufficientSpaceException) {
            assertTrue(e.needed > 10)
        }
    }

    @Test
    fun addsProductRowToExistingSharedInstall() = runBlocking {
        File(root, ".build.info").writeText(
            BuildInfo(GameInstaller.BUILD_INFO_HEADER, listOf(mapOf("Product" to "wow_classic_beta", "Active" to "1", "Version" to "1.60.1.70450"))).serialize(),
        )
        installer().run(req) {}
        val info = BuildInfo.read(root)!!
        assertEquals(listOf(WowProduct.FOREVER, WowProduct.BETA), info.products(app.gamenative.wow.product.ForeverTrack.LAUNCH))
        assertEquals("1.60.1.70450", info.active(WowProduct.BETA)!!["Version"])
    }

    @Test
    fun installerKeepsOtherProductRows() = runBlocking {
        files["/wow_classic_era/versions"] = files["/wowf/versions"]!!
        files["/wow_classic_era/cdns"] = files["/wowf/cdns"]!!
        installer().run(req) {}
        val foreverRow = BuildInfo.read(root)!!.active(WowProduct.FOREVER)!!
        installer().run(InstallRequest(root, WowProduct.CLASSIC_ERA)) {}
        val info = BuildInfo.read(root)!!
        assertEquals(foreverRow, info.active(WowProduct.FOREVER))
        assertEquals("wow_classic_era", info.active(WowProduct.CLASSIC_ERA)!!["Product"])
        assertEquals("Product Flavor!STRING:0\nwow_classic_era\n", File(root, "_classic_era_/.flavor.info").readText())
        assertEquals("Product Flavor!STRING:0\nwowf\n", File(root, "_forever_/.flavor.info").readText())
    }

    @Test
    fun freshInstallRecordsTheChosenRegion() = runBlocking {
        installer().run(InstallRequest(root, WowProduct.FOREVER, region = "eu")) {}
        val row = BuildInfo.read(root)!!.active(WowProduct.FOREVER)!!
        assertEquals("eu", row["Branch"])
        assertTrue(row["Tags"]!!.contains("EU?"))
    }

    @Test
    fun fetchedBytesCountOnlyWhatComesFromTheNetwork() = runBlocking {
        var first: InstallProgress? = null
        installer().run(req) { first = it }
        assertTrue("first install fetched ${first!!.fetchedBytes}", first!!.fetchedBytes >= exe.size)
        var repair: InstallProgress? = null
        installer().run(req) { repair = it }
        assertEquals(0L, repair!!.fetchedBytes)
        assertEquals(repair!!.totalBytes, repair!!.doneBytes)
    }

    @Test
    fun reportsProgressToCompletion() = runBlocking {
        val seen = mutableListOf<InstallProgress>()
        installer().run(req) { seen += it }
        val last = seen.last()
        assertEquals(InstallProgress.Phase.DONE, last.phase)
        assertEquals(last.totalBytes, last.doneBytes)
        assertTrue(last.totalBytes >= exe.size + dll.size)
    }
}
