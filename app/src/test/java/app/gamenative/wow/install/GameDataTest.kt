package app.gamenative.wow.install

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameDataTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var precious: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("gamedata").toFile()
        root = File(base, "game")
        outside = File(base, "outside")
        precious = File(outside, "photo.jpg")
        write(precious)
        write(File(root, ".build.info"))
        write(File(root, FullDownloader.MARKER))
        write(File(root, "_forever_/Wow-arm64.exe"))
        write(File(root, "_forever_/WTF/Config.wtf"))
        write(File(root, "_forever_/Interface/AddOns/Details/Details.toc"))
        write(File(root, "Data/config/ab/cd/abcd"))
        write(File(root, "Data/indices/abcd.index"))
        write(File(root, "Data/data/data.000"))
        write(File(root, "Data/data/0000000001.idx"))
        write(File(root, "Data/${FullDownloader.STAGING}/data.000"))
        write(File(root, "Data/${FullDownloader.OLD}/data.000"))
    }

    @After
    fun tearDown() {
        // Only ever a temp folder made in setUp. Links are cut first so this can't reach through them.
        base.walkBottomUp().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { it.delete() }
        listOf("game/Data/data/link", "game/Data/data", "game/Data").forEach { File(base, it).delete() }
    }

    @Test
    fun clearsWorldDataAndTheFullDownloadsFolders() {
        GameData.clear(root)

        assertFalse(File(root, "Data/data").exists())
        assertFalse(File(root, "Data/${FullDownloader.STAGING}").exists())
        assertFalse(File(root, "Data/${FullDownloader.OLD}").exists())
        assertFalse(File(root, "Data/${GameData.CLEARING}").exists())
        assertFalse(File(root, FullDownloader.MARKER).exists())
    }

    @Test
    fun keepsTheGameProgramSettingsAddonsAndWhatTheGameNeedsToDownload() {
        GameData.clear(root)

        listOf(
            ".build.info",
            "_forever_/Wow-arm64.exe",
            "_forever_/WTF/Config.wtf",
            "_forever_/Interface/AddOns/Details/Details.toc",
            "Data/config/ab/cd/abcd",
            "Data/indices/abcd.index",
        ).forEach { assertTrue(it, File(root, it).isFile) }
    }

    @Test
    fun aLinkInsideTheDataIsRemovedWithoutTouchingWhatItPointsTo() {
        Files.createSymbolicLink(File(root, "Data/data/link").toPath(), outside.toPath())

        GameData.clear(root)

        assertFalse(File(root, "Data/data").exists())
        assertEquals("keep", precious.readText())
    }

    @Test
    fun aDataFolderThatIsItselfALinkIsRemovedWithoutTouchingWhatItPointsTo() {
        val data = File(root, "Data/data")
        data.walkBottomUp().forEach { it.delete() }
        Files.createSymbolicLink(data.toPath(), outside.toPath())

        GameData.clear(root)

        assertFalse(Files.exists(data.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", precious.readText())
    }

    @Test
    fun refusesALinkedDataFolder() {
        val data = File(root, "Data")
        data.walkBottomUp().forEach { it.delete() }
        write(File(outside, "data/data.000"))
        Files.createSymbolicLink(data.toPath(), outside.toPath())

        assertThrows(IOException::class.java) { GameData.clear(root) }

        assertTrue(File(outside, "data/data.000").isFile)
        assertEquals("keep", precious.readText())
    }

    @Test
    fun refusesAFolderThatIsNotAGameFolder() {
        File(root, ".build.info").delete()

        assertThrows(IOException::class.java) { GameData.clear(root) }

        assertTrue(File(root, "Data/data/data.000").isFile)
    }

    @Test
    fun finishesAClearTheAppWasClosedDuring() {
        write(File(root, "Data/${GameData.CLEARING}/data/data.001"))

        GameData.finishClearing(root)

        assertFalse(File(root, "Data/${GameData.CLEARING}").exists())
        assertTrue(File(root, "Data/data/data.000").isFile)
    }

    @Test
    fun clearingTwiceIsFine() {
        GameData.clear(root)
        GameData.clear(root)

        assertTrue(File(root, "Data/indices/abcd.index").isFile)
    }

    private fun write(file: File) {
        file.parentFile!!.mkdirs()
        file.writeText("keep")
    }
}
