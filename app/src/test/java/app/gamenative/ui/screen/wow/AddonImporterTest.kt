package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddonImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val addonsDir by lazy { File(tempFolder.root, "AddOns") }

    private fun zip(vararg entries: String): File {
        val file = tempFolder.newFile("addon${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(name.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun install_extractsEachTopLevelAddonWithItsBundledLibraries() {
        val installed = AddonImporter.install(
            zip(
                "DBM-Core/DBM-Core.toc",
                "DBM-Core/Libs/LibStub/LibStub.toc",
                "DBM-Core/Libs/LibStub/LibStub.lua",
                "DBM-StatusBarTimers/DBM-StatusBarTimers.toc",
            ),
            addonsDir,
        )

        assertEquals(listOf("DBM-Core", "DBM-StatusBarTimers"), installed)
        assertTrue(File(addonsDir, "DBM-Core/Libs/LibStub/LibStub.lua").isFile)
        assertFalse(File(addonsDir, "LibStub").exists())
    }

    @Test
    fun install_namesAWrappedAddonAfterItsToc() {
        val installed = AddonImporter.install(zip("MyAddon-main/MyAddon_Mainline.toc", "MyAddon-main/core.lua"), addonsDir)

        assertEquals(listOf("MyAddon"), installed)
        assertTrue(File(addonsDir, "MyAddon/core.lua").isFile)
    }

    @Test
    fun install_handlesATocAtTheRootAndIgnoresMacMetadata() {
        val installed = AddonImporter.install(
            zip("Bagnon.toc", "main.lua", "libs/AceAddon/AceAddon.toc", "__MACOSX/._Bagnon.toc", "._main.lua"),
            addonsDir,
        )

        assertEquals(listOf("Bagnon"), installed)
        assertTrue(File(addonsDir, "Bagnon/libs/AceAddon/AceAddon.toc").isFile)
        assertFalse(File(addonsDir, "Bagnon/._main.lua").exists())
    }

    @Test
    fun install_replacesAnOlderCopy() {
        File(addonsDir, "Details/old.lua").apply { parentFile?.mkdirs(); writeText("old") }

        AddonImporter.install(zip("Details/Details.toc", "Details/new.lua"), addonsDir)

        assertFalse(File(addonsDir, "Details/old.lua").exists())
        assertTrue(File(addonsDir, "Details/new.lua").isFile)
    }

    @Test
    fun install_rejectsZipsWithoutAddonsOrWithEscapingPaths() {
        assertThrows(IOException::class.java) { AddonImporter.install(zip("readme.txt"), addonsDir) }
        assertThrows(IOException::class.java) { AddonImporter.install(zip("Evil/Evil.toc", "Evil/../../escaped.lua"), addonsDir) }
        assertFalse(File(tempFolder.root, "escaped.lua").exists())
    }
}
