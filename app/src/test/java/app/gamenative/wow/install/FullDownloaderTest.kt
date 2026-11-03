package app.gamenative.wow.install

import app.gamenative.wow.casc.LocalStorage
import app.gamenative.wow.casc.StorageBuilder
import app.gamenative.wow.product.BuildInfo
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.tact.TactFixtures
import app.gamenative.wow.tact.TactFixtures.DownloadSpec
import app.gamenative.wow.tact.hex
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
import org.junit.Before
import org.junit.Test

class FullDownloaderTest {
    private val server = MockWebServer()
    private val requested = CopyOnWriteArrayList<String>()
    private val files = mutableMapOf<String, ByteArray>()
    private var failPath: String? = null
    private lateinit var root: File

    private val archive1 = "a1".repeat(16)
    private val archive2 = "a2".repeat(16)
    private val downloadEkey = "dd".repeat(16)

    /** Files by name: the BLTE bytes and their key. */
    private val blte = mutableMapOf<String, Pair<String, ByteArray>>()
    private lateinit var buildKey: String

    private fun file(name: String, seed: Int, chunked: Boolean = true): Pair<String, ByteArray> {
        val bytes = if (chunked) {
            TactFixtures.blte(listOf(ByteArray(2000) { (it * seed).toByte() }, ByteArray(700) { seed.toByte() }), 'Z')
        } else {
            TactFixtures.blte(listOf(ByteArray(900) { (seed + it).toByte() }), 'N', headerless = true)
        }
        val keyed = if (chunked) bytes.copyOf(12 + 24 * 2) else bytes
        return (TactFixtures.md5(keyed) to bytes).also { blte[name] = it }
    }

    private fun cdnPath(kind: String, key: String) = "/tpr/wow/$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("full").toFile()
        val near1 = file("near1", 3)
        val near2 = file("near2", 5, chunked = false)
        val far = file("far", 7)
        val other = file("other", 11)
        val loose = file("loose", 13)
        val german = file("german", 17)
        val missing = "ee".repeat(16)

        // near1 and near2 sit close together and share a request; far is past the merge gap and gets its own.
        val a1 = ByteArray(200_000)
        val spots = mapOf("near1" to 100, "near2" to 100 + near1.second.size + 50, "german" to 20_000, "far" to 150_000)
        spots.forEach { (name, at) -> blte.getValue(name).second.copyInto(a1, at) }
        files[cdnPath("data", archive1)] = a1
        files[cdnPath("data", "$archive1.index")] =
            TactFixtures.archiveIndex(spots.entries.associate { (name, at) -> blte.getValue(name).let { (key, bytes) -> key to (at to bytes.size) } })
        val a2 = ByteArray(10) + other.second
        files[cdnPath("data", archive2)] = a2
        files[cdnPath("data", "$archive2.index")] = TactFixtures.archiveIndex(mapOf(other.first to (10 to other.second.size)))
        files[cdnPath("data", loose.first)] = loose.second

        val us = setOf("Windows", "arm64", "enUS", "US")
        val manifest = TactFixtures.downloadManifest(
            listOf(
                DownloadSpec(near1.first, near1.second.size.toLong(), us),
                DownloadSpec(near2.first, near2.second.size.toLong(), us),
                DownloadSpec(far.first, far.second.size.toLong(), us),
                DownloadSpec(other.first, other.second.size.toLong(), us),
                DownloadSpec(loose.first, loose.second.size.toLong(), us),
                DownloadSpec(german.first, german.second.size.toLong(), setOf("Windows", "arm64", "deDE", "EU")),
                DownloadSpec(missing, 1234, us),
            ),
            version = 2,
        )
        files[cdnPath("data", downloadEkey)] = TactFixtures.blte(listOf(manifest), 'Z')
        val buildConfig = "# Build Configuration\ndownload = ${"00".repeat(16)} $downloadEkey\n"
        val cdnConfig = "# CDN Configuration\narchives = $archive1 $archive2\n"
        buildKey = TactFixtures.md5(buildConfig.toByteArray())
        val cdnKey = TactFixtures.md5(cdnConfig.toByteArray())
        files[cdnPath("config", buildKey)] = buildConfig.toByteArray()
        files[cdnPath("config", cdnKey)] = cdnConfig.toByteArray()

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

        val info = BuildInfo(
            GameInstaller.BUILD_INFO_HEADER,
            listOf(
                mapOf(
                    "Branch" to "us", "Active" to "1", "Build Key" to buildKey, "CDN Key" to cdnKey, "CDN Path" to "tpr/wow",
                    "CDN Hosts" to "${server.hostName}:${server.port}", "Tags" to "Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?",
                    "Version" to "1.60.1.70707", "Product" to WowProduct.FOREVER.code,
                ),
            ),
        )
        File(root, BuildInfo.FILE_NAME).writeText(info.serialize())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun downloader(gameRunning: () -> Boolean = { false }) =
        FullDownloader(http = OkHttpClient(), workers = 2, usableSpace = { Long.MAX_VALUE }, gameRunning = gameRunning, retryDelayMs = { 0 }, gamePollMs = 10)

    private val req get() = InstallRequest(root, WowProduct.FOREVER, full = true)

    /** Every key in the game's storage at [version], with the bytes stored behind its header. */
    private fun stored(version: Int): Map<String, ByteArray> {
        val dir = File(root, "Data/data")
        return (0 until 16).flatMap { b ->
            LocalStorage.readIndex(File(dir, LocalStorage.indexFileName(b, version)).readBytes(), b)!!
        }.associate { entry ->
            val data = File(dir, "data.%03d".format(entry.segment)).readBytes()
            val start = entry.offset.toInt()
            val header = data.copyOfRange(start, start + 30)
            hex(header.copyOf(16).reversedArray()).substring(0, 18) to data.copyOfRange(start + 30, start + entry.size)
        }
    }

    @Test
    fun downloadsTheSelectionIntoTheGamesStorage() = runBlocking {
        val live = File(root, "Data/data").apply { mkdirs() }
        File(live, "0000000004.idx").writeText("old")
        File(live, "shmem").writeText("old")

        val progress = CopyOnWriteArrayList<InstallProgress>()
        downloader().run(req) { progress += it }

        val keys = stored(5)
        for (name in listOf("near1", "near2", "far", "other", "loose")) {
            val (key, bytes) = blte.getValue(name)
            assertArrayEquals(name, bytes, keys[key.substring(0, 18)])
        }
        assertEquals("only the US selection, without the file the CDN doesn't have", 5, keys.size)
        assertFalse("the old storage is replaced whole", File(live, "shmem").exists())
        assertFalse(File(root, "Data/${FullDownloader.STAGING}").exists())
        assertFalse(File(live, StorageBuilder.JOURNAL).exists())
        assertTrue(FullDownloader.isComplete(root, WowProduct.FOREVER))
        assertEquals(InstallProgress.Phase.DONE, progress.last().phase)
        // near1 and near2 came in one ranged request, far in another.
        assertEquals(2, requested.count { it == cdnPath("data", archive1) })
    }

    @Test
    fun resumesWithoutFetchingWhatIsAlreadyStored() = runBlocking {
        failPath = cdnPath("data", archive2)
        try {
            downloader().run(req) {}
            throw AssertionError("expected failure")
        } catch (_: IOException) {
        }
        assertFalse(FullDownloader.isComplete(root, WowProduct.FOREVER))
        assertNull("the game's storage is untouched until the end", File(root, "Data/data").list())
        assertTrue(File(root, "Data/${FullDownloader.STAGING}/${StorageBuilder.JOURNAL}").length() > 0)

        failPath = null
        requested.clear()
        downloader().run(req) {}
        assertFalse(requested.contains(cdnPath("data", archive1)))
        assertTrue(requested.contains(cdnPath("data", archive2)))
        assertEquals(5, stored(1).size)
    }

    @Test
    fun waitsForTheGameToCloseBeforeSwapping() = runBlocking {
        var checks = 0
        val details = CopyOnWriteArrayList<String?>()
        downloader(gameRunning = { checks++ < 3 }).run(req) { details += it.detail }
        assertTrue(details.contains("Waiting for the game to close"))
        assertEquals(5, stored(1).size)
    }

    @Test
    fun refusesWithoutAnInstall() = runBlocking {
        File(root, BuildInfo.FILE_NAME).delete()
        try {
            downloader().run(req) {}
            throw AssertionError("expected failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Install"))
        }
    }
}
