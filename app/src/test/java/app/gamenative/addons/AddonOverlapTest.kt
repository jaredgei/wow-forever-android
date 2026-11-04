package app.gamenative.addons

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AddonOverlapTest {
    private val github = SourceRecord(Provider.GITHUB, "acme/Spoken", "Spoken", "2.1.0", listOf("Spoken", "Spoken_Options"))
    private val wowi = SourceRecord(Provider.WOWINTERFACE, "123", "Other", "9", listOf("Other"))

    @Test
    fun nothingInstalledMeansNoOverlap() {
        assertTrue(AddonOverlaps.find(emptySet(), listOf(github), Provider.WOWINTERFACE, "5", listOf("Spoken")).isEmpty())
    }

    @Test
    fun anotherSourceOwningTheFolderIsAnOverlapWithItsVersion() {
        val groups = AddonOverlaps.find(setOf("Spoken", "Spoken_Options"), listOf(github), Provider.WOWINTERFACE, "5", listOf("Spoken", "Spoken_Options"))
        assertEquals(1, groups.size)
        assertEquals("GitHub", groups[0].sourceLabel)
        assertEquals("2.1.0", groups[0].version)
        assertEquals(listOf("Spoken", "Spoken_Options"), groups[0].folders)
    }

    @Test
    fun updatingTheSameProductIsNotAnOverlap() {
        assertTrue(AddonOverlaps.find(setOf("Spoken"), listOf(github), Provider.GITHUB, "acme/Spoken", listOf("Spoken")).isEmpty())
    }

    @Test
    fun sameIdFromADifferentProviderIsStillAnOverlap() {
        val record = SourceRecord(Provider.TUKUI, "x", "X", "1", listOf("X"))
        assertEquals(1, AddonOverlaps.find(setOf("X"), listOf(record), Provider.WOWINTERFACE, "x", listOf("X")).size)
    }

    @Test
    fun foldersNobodyTracksAreReportedAsAddedByHand() {
        val groups = AddonOverlaps.find(setOf("Hand"), listOf(github), null, null, listOf("Hand"))
        assertEquals(1, groups.size)
        assertNull(groups[0].record)
        assertEquals(OverlapGroup.UNTRACKED_SOURCE, groups[0].sourceLabel)
        assertNull(groups[0].version)
    }

    @Test
    fun anUntrackedImportOverTrackedFoldersIsAlwaysAnOverlap() {
        assertEquals(1, AddonOverlaps.find(setOf("Spoken"), listOf(github), null, null, listOf("Spoken")).size)
    }

    @Test
    fun foldersOwnedBySeveralSourcesAreGroupedPerSource() {
        val groups = AddonOverlaps.find(setOf("Spoken", "Other", "Hand"), listOf(github, wowi), Provider.TUKUI, "t", listOf("Spoken", "Other", "Hand", "New"))
        assertEquals(listOf("GitHub", "WoWInterface", OverlapGroup.UNTRACKED_SOURCE), groups.map { it.sourceLabel })
        assertEquals(listOf("Hand"), groups.last().folders)
    }

    @Test
    fun onlyAFolderThatActuallyExistsCounts() {
        assertTrue(AddonOverlaps.find(setOf("Spoken"), listOf(github), Provider.WOWINTERFACE, "5", listOf("Other")).isEmpty())
    }

    @Test
    fun anEmptyVersionReadsAsUnknown() {
        val blank = github.copy(version = "")
        assertNull(AddonOverlaps.find(setOf("Spoken"), listOf(blank), null, null, listOf("Spoken"))[0].version)
    }

    // The gate wiring, through the real import path with real zips.

    private fun gameRoot(): File = Files.createTempDirectory("overlap").toFile()

    private fun addonZip(dir: File, name: String, folder: String): File {
        val zip = File(dir, name)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("$folder/$folder.toc"))
            out.write("## Interface: 120000\n".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    private fun installedMarker(root: File, folder: String): File {
        val dir = File(AddonManager.addonsDir(root), folder).also { it.mkdirs() }
        return File(dir, "old.txt").also { it.writeText("old") }
    }

    @Test
    fun importAsksFirstAndKeepsTheInstalledCopyWhenDeclined() {
        val root = gameRoot()
        val marker = installedMarker(root, "Spoken")
        AddonSources.record(root, github)
        val zip = addonZip(root, "Spoken-curse.zip", "Spoken")
        var asked: OverlapPrompt? = null
        val result = AddonManager.importFiles(root, listOf(zip), deleteAfter = true) { asked = it; false }.single()
        assertFalse(result.ok)
        assertTrue(marker.exists())
        assertTrue("a declined import must not delete the zip", zip.exists())
        val prompt = asked!!
        assertEquals("Imported zip", prompt.incoming.sourceLabel)
        assertEquals("GitHub", prompt.groups.single().sourceLabel)
        assertEquals("2.1.0", prompt.groups.single().version)
        assertEquals(github, AddonSources.load(root).single())
    }

    @Test
    fun acceptingReplacesTheFoldersAndStopsTrackingThemUnderTheOldSource() {
        val root = gameRoot()
        val marker = installedMarker(root, "Spoken")
        AddonSources.record(root, github)
        val result = AddonManager.importFiles(root, listOf(addonZip(root, "Spoken-curse.zip", "Spoken")), deleteAfter = false) { true }.single()
        assertTrue(result.ok)
        assertFalse(marker.exists())
        assertEquals(listOf("Spoken_Options"), AddonSources.load(root).single().folders)
    }

    @Test
    fun aFirstTimeImportNeverAsks() {
        val root = gameRoot()
        val result = AddonManager.importFiles(root, listOf(addonZip(root, "New.zip", "New")), deleteAfter = false) { fail("should not ask"); false }.single()
        assertTrue(result.ok)
    }

    @Test
    fun checkOverlapThrowsDeclinedWhenTheGateSaysNo() {
        val root = gameRoot()
        installedMarker(root, "Spoken")
        AddonSources.record(root, github)
        try {
            AddonInstaller.checkOverlap(root, Provider.WOWINTERFACE, "5", IncomingAddon("Spoken", "WoWInterface", "3.0"), listOf("Spoken"), OverlapGate { false })
            fail("expected OverlapDeclined")
        } catch (e: OverlapDeclined) {
            assertEquals("Spoken", e.addon)
        }
    }
}
