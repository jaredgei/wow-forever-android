package app.gamenative.wow.addons

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.wow.product.GameVersion
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddonModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var game: File

    @Before
    fun setUp() {
        game = Files.createTempDirectory("World of Warcraft").toFile()
        File(game, "_retail_/Interface/AddOns/WeakAuras").mkdirs()
        // A copy made on Windows can name the folder with different case.
        File(game, "_classic_era_/Interface/Addons").mkdirs()
        File(game, "Data").mkdirs()
    }

    @Test
    fun findsTheAddonsFolderFromAnyLevel() {
        val retail = File(game, "_retail_/Interface/AddOns")
        val expected = listOf(AddonMode.Folder(retail, GameVersion.RETAIL))
        assertEquals(expected, AddonMode.resolve(retail))
        assertEquals(expected, AddonMode.resolve(File(game, "_retail_/Interface")))
        assertEquals(expected, AddonMode.resolve(File(game, "_retail_")))
    }

    @Test
    fun listsEveryVersionInAGameFolderAndKeepsTheFolderCase() {
        val found = AddonMode.resolve(game)
        assertEquals(listOf(GameVersion.CLASSIC_ERA, GameVersion.RETAIL), found.map { it.version })
        assertEquals("Addons", found.first().addonsDir.name)
    }

    @Test
    fun readsTheVersionFromFlavorInfoAndCreatesNothing() {
        val renamed = File(game, "my-wow").apply { mkdirs() }
        File(renamed, ".flavor.info").writeText("Product Flavor!STRING:0\nwow_anniversary\n")
        val found = AddonMode.resolve(renamed).single()
        assertEquals(GameVersion.ANNIVERSARY, found.version)
        assertEquals(File(renamed, "Interface/AddOns"), found.addonsDir)
        assertTrue("resolving never creates folders", !found.addonsDir.exists())
    }

    @Test
    fun anUnknownFolderGivesNoVersionOrNothing() {
        val loose = File(game, "Interface/AddOns").apply { mkdirs() }
        assertNull(AddonMode.resolve(loose).single().version)
        assertEquals(emptyList<AddonMode.Folder>(), AddonMode.resolve(File(game, "Data")))
    }

    @Test
    fun remembersTheFolderAfterTurningOff() {
        val dir = File(game, "_retail_/Interface/AddOns")
        assertNull(AddonMode.folder(context))
        AddonMode.enable(context, dir, GameVersion.RETAIL)
        assertEquals(AddonMode.Folder(dir, GameVersion.RETAIL), AddonMode.folder(context))
        AddonMode.disable(context)
        assertNull(AddonMode.folder(context))
        assertEquals(AddonMode.Folder(dir, GameVersion.RETAIL), AddonMode.lastFolder(context))
    }
}
