package app.gamenative.addons

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, body) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun gameRoot() = File(tmp.root, "game").also { File(it, "_forever_/Interface/AddOns").mkdirs() }

    @Test
    fun parseToc_readsFieldsAndStripsColorCodes() {
        val info = AddonManager.parseToc("﻿## Interface: 120000\n## Title: |cff00ff00Deadly|r Boss Mods\n## Version: 11.0.3\n## Author: Someone\n## Notes: Warns you\n## Title-deDE: Ignored\nFile.lua")
        assertEquals("Deadly Boss Mods", info.title)
        assertEquals("11.0.3", info.version)
        assertEquals("120000", info.interfaceVersion)
        assertEquals("Someone", info.author)
    }

    @Test
    fun parseToc_picksTheForeverTitleFromElvUisRealToc() {
        val toc = """
            ## Interface: 11509, 16001, 20506, 38002, 50504, 120100, 120105
            ## Version: v16.00
            ## Notes: User Interface Replacement
            ## Title: |cff1784d1ElvUI|r |cfd9b9b9bMists|r [AllowLoadGameType mists]
            ## Title: |cff1784d1ElvUI|r |cfd9b9b9bMidnight|r [AllowLoadGameType standard]
            ## Title: |cff1784d1ElvUI|r |cfd9b9b9bForever|r [AllowLoadGameType camelot][ExcludeLoadGameType standard, classic]
            ## Title: |cff1784d1ElvUI|r |cfd9b9b9bWrath|r [AllowLoadGameType wrath]
        """.trimIndent()
        assertEquals("ElvUI Forever", AddonManager.parseToc(toc).title)
    }

    @Test
    fun parseToc_prefersAnUntaggedTitleOverOtherGameTypes() {
        assertEquals("Plain", AddonManager.parseToc("## Title: Old [AllowLoadGameType vanilla]\n## Title: Plain").title)
        assertEquals("Only", AddonManager.parseToc("## Title: Only [AllowLoadGameType mists]").title)
    }

    @Test
    fun parseToc_turnsLineBreakEscapesIntoSpaces() {
        assertEquals("Powers the window. Does not store data.", AddonManager.parseToc("## Notes: Powers the window.|nDoes not store data.").notes)
    }

    @Test
    fun listInstalled_prefersTheMainlineToc() {
        val root = gameRoot()
        val dir = File(AddonManager.addonsDir(root), "Multi").also { it.mkdirs() }
        File(dir, "Multi_Mists.toc").writeText("## Title: Multi Mists [AllowLoadGameType mists]")
        File(dir, "Multi_Mainline.toc").writeText("## Title: Multi")
        File(dir, "Multi_Vanilla.toc").writeText("## Title: Multi Vanilla")
        assertEquals("Multi", AddonManager.listInstalled(root).single().title)
        File(dir, "Multi.toc").writeText("## Title: Plain")
        assertEquals("Plain", AddonManager.listInstalled(root).single().title)
    }

    @Test
    fun inspectZip_findsTopLevelAddons() {
        val z = zip("a.zip", "Foo/Foo.toc" to "## Title: Foo", "Foo/Foo.lua" to "", "Foo_Options/Foo_Options.toc" to "", "README.txt" to "x")
        assertEquals(listOf("Foo", "Foo_Options"), AddonManager.inspectZip(z))
    }

    @Test
    fun inspectZip_unwrapsSingleWrapperFolder() {
        val z = zip("w.zip", "Foo-1.2/Foo/Foo.toc" to "", "Foo-1.2/Foo/a.lua" to "", "Foo-1.2/notes.txt" to "")
        assertEquals(listOf("Foo"), AddonManager.inspectZip(z))
    }

    @Test
    fun inspectZip_acceptsFlavorTocVariants() {
        assertEquals(listOf("Foo"), AddonManager.inspectZip(zip("v.zip", "Foo/Foo_Mainline.toc" to "")))
    }

    @Test
    fun inspectZip_rejectsNonAddons() {
        assertTrue(AddonManager.inspectZip(zip("n.zip", "readme.txt" to "hi", "Foo/Foo.lua" to "")).isEmpty())
        val notAZip = File(tmp.root, "bad.zip").also { it.writeText("nope") }
        assertTrue(AddonManager.inspectZip(notAZip).isEmpty())
    }

    @Test
    fun installZip_replacesExistingAndSkipsStrayFiles() {
        val root = gameRoot()
        val addons = AddonManager.addonsDir(root)
        File(addons, "Foo").mkdirs()
        File(addons, "Foo/old.lua").writeText("old")
        val z = zip("a.zip", "Foo/Foo.toc" to "## Title: Foo", "Foo/new.lua" to "new", "README.txt" to "x")
        assertEquals(listOf("Foo"), AddonManager.installZip(z, addons))
        assertTrue(File(addons, "Foo/new.lua").isFile)
        assertFalse(File(addons, "Foo/old.lua").exists())
        assertFalse(File(addons, "README.txt").exists())
        assertFalse(File(addons.parentFile, ".addon-staging").exists())
    }

    @Test
    fun installZip_rejectsPathTraversal() {
        val root = gameRoot()
        val addons = AddonManager.addonsDir(root)
        val z = zip("evil.zip", "Foo/Foo.toc" to "", "Foo/../../../escaped.txt" to "x")
        try {
            AddonManager.installZip(z, addons)
            fail("expected failure")
        } catch (_: Exception) {
        }
        assertFalse(File(tmp.root, "escaped.txt").exists())
        assertFalse(File(addons, "Foo").exists())
    }

    @Test
    fun listInstalled_readsTocsAndIgnoresOtherFolders() {
        val root = gameRoot()
        val addons = AddonManager.addonsDir(root)
        File(addons, "Zed").mkdirs(); File(addons, "Zed/Zed.toc").writeText("## Title: Zed Addon\n## Version: 2")
        File(addons, "Alpha").mkdirs(); File(addons, "Alpha/Alpha.toc").writeText("## Interface: 1")
        File(addons, "NotAnAddon").mkdirs()
        val list = AddonManager.listInstalled(root)
        assertEquals(listOf("Alpha", "Zed Addon"), list.map { it.title })
    }

    @Test
    fun importFiles_deletesOnlyWhatInstalled_andOnlyWhenAsked() {
        val root = gameRoot()
        val good = zip("good.zip", "Foo/Foo.toc" to "")
        val bad = zip("bad.zip", "readme.txt" to "x")
        val keep = AddonManager.importFiles(root, listOf(good), deleteAfter = false)
        assertTrue(keep.single().ok); assertTrue(good.exists()); assertFalse(keep.single().deleted)

        val results = AddonManager.importFiles(root, listOf(good, bad), deleteAfter = true)
        assertTrue(results[0].ok && results[0].deleted); assertFalse(good.exists())
        assertFalse(results[1].ok); assertTrue(bad.exists())
    }

    @Test
    fun scanFolder_splitsAddonsFromOtherZips() {
        val dir = File(tmp.root, "dl").also { it.mkdirs() }
        File(dir, "a.zip").writeBytes(zip("t1.zip", "Foo/Foo.toc" to "").readBytes())
        File(dir, "b.zip").writeBytes(zip("t2.zip", "photo.jpg" to "x").readBytes())
        File(dir, "c.txt").writeText("x")
        val scan = AddonManager.scanFolder(dir)
        assertEquals(listOf("a.zip"), scan.candidates.map { it.file.name })
        assertEquals(listOf("b.zip"), scan.skipped.map { it.name })
    }

    @Test
    fun remove_refusesUnsafeNames() {
        val root = gameRoot()
        try {
            AddonManager.remove(root, "../Interface")
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun installZip_overlappingInstallsDontClobberEachOther() {
        val root = gameRoot()
        val addons = AddonManager.addonsDir(root)
        val files = 40
        fun addonZip(n: Int) = zip("a$n.zip", "Addon$n/Addon$n.toc" to "", *(1..files).map { "Addon$n/f$it.lua" to "x$it" }.toTypedArray())
        val zipA = addonZip(1)
        val zipB = addonZip(2)
        val paused = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        // Hold install A partway through extraction, run install B, then let A finish: B must not disturb A.
        AddonManager.afterFileExtracted = {
            if (Thread.currentThread().name == "installer-A" && first.compareAndSet(true, false)) {
                paused.countDown()
                release.await()
            }
        }
        try {
            val a = Thread({ try { AddonManager.installZip(zipA, addons) } catch (t: Throwable) { errors.add(t) } }, "installer-A")
            a.start()
            paused.await()
            val b = Thread({ try { AddonManager.installZip(zipB, addons) } catch (t: Throwable) { errors.add(t) } }, "installer-B")
            b.start()
            Thread.sleep(500) // without serialization B runs to completion in this window
            release.countDown()
            a.join(); b.join()
        } finally {
            AddonManager.afterFileExtracted = null
        }
        assertTrue(errors.toString(), errors.isEmpty())
        for (n in 1..2) assertEquals("Addon$n is incomplete", files + 1, File(addons, "Addon$n").listFiles()!!.size)
    }
}
