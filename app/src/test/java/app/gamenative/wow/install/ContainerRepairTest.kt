package app.gamenative.wow.install

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContainerRepairTest {
    private lateinit var base: File
    private lateinit var home: File
    private lateinit var container: File
    private lateinit var sharedStorage: File
    private lateinit var gameFolder: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("containerrepair").toFile()
        home = File(base, "home").apply { mkdirs() }
        container = File(home, "xuser-wow_forever")
        sharedStorage = File(base, "storage")
        gameFolder = File(sharedStorage, "WoW Forever")
        write(File(sharedStorage, "photo.jpg"))
        write(File(gameFolder, "Data/data/data.000"))
        write(File(container, ".wine/drive_c/windows/system32/kernel32.dll"))
        write(File(container, ".wine/system.reg"))
        // A real prefix's drives: the root of the device, the game folder and a folder link.
        val drives = File(container, ".wine/dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(drives, "z:").toPath(), base.toPath())
        Files.createSymbolicLink(File(drives, "g:").toPath(), gameFolder.toPath())
        Files.createSymbolicLink(File(drives, "c:").toPath(), File("../drive_c").toPath())
    }

    @After
    fun tearDown() {
        // Only ever the temp folder from setUp: links are removed, never walked through.
        GameData.deleteNoFollow(base.toPath())
    }

    @Test
    fun removesTheContainerWithoutTouchingWhatItsDrivesPointTo() {
        ContainerRepair.discard(container)

        assertFalse(container.exists())
        assertFalse(File(home, ContainerRepair.DISCARDING).exists())
        assertEquals("keep", File(sharedStorage, "photo.jpg").readText())
        assertEquals("keep", File(gameFolder, "Data/data/data.000").readText())
    }

    @Test
    fun aContainerFolderThatIsALinkOnlyLosesTheLink() {
        val link = File(home, "xuser-linked").toPath()
        Files.createSymbolicLink(link, gameFolder.toPath())

        ContainerRepair.discard(link.toFile())

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", File(gameFolder, "Data/data/data.000").readText())
    }

    @Test
    fun otherContainersAndTheActiveLinkStay() {
        write(File(home, "xuser-other/.container"))
        Files.createSymbolicLink(File(home, "xuser").toPath(), File("./xuser-wow_forever").toPath())

        ContainerRepair.discard(container)

        assertTrue(File(home, "xuser-other/.container").isFile)
        assertTrue(Files.isSymbolicLink(File(home, "xuser").toPath()))
    }

    @Test
    fun finishesWhatAnInterruptedRepairLeftBehind() {
        write(File(home, "${ContainerRepair.DISCARDING}/.wine/system.reg"))

        ContainerRepair.discard(container)

        assertFalse(File(home, ContainerRepair.DISCARDING).exists())
        assertFalse(container.exists())
    }

    @Test
    fun aMissingContainerIsFine() {
        ContainerRepair.discard(File(home, "xuser-none"))

        assertTrue(container.isDirectory)
    }

    private fun write(file: File) {
        file.parentFile!!.mkdirs()
        file.writeText("keep")
    }
}
