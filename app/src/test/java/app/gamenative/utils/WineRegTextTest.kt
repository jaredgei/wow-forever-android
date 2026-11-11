package app.gamenative.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WineRegTextTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("wine-reg-text-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val sample = "WINE REGISTRY Version 2\n" +
        ";; All keys relative to REGISTRY\\\\Machine\n\n#arch=win64\n\n" +
        "[Software\\\\A\\\\B] 1753725033\n#time=1dbffe81de76732\n\"One\"=\"alt\"\n\"Zwei\"=dword:00000001\n\n" +
        "[Software\\\\A\\\\B\\\\C] 1753725033\n\"One\"=\"andere Taste\"\n\n" +
        "[Software\\\\X] 1\n\"Hex\"=hex:01,02,\\\n  03,04\n\"Name\"=\"x\"\n"

    @Test
    fun changesOnlyTheMatchingValueLine() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\A\\B", "One", "neu")))
        assertEquals(1, changed)
        assertEquals(sample.replace("\"One\"=\"alt\"", "\"One\"=\"neu\""), text)
    }

    @Test
    fun matchingValueChangesNothing() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\A\\B", "One", "alt")))
        assertEquals(0, changed)
        assertEquals(sample, text)
    }

    @Test
    fun keysAndNamesAreCaseInsensitive() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("software\\a\\b", "ONE", "neu")))
        assertEquals(1, changed)
        assertTrue(text.contains("\"One\"=\"neu\""))
    }

    @Test
    fun dwordIsWrittenAsEightHexDigits() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.dword("Software\\A\\B", "Zwei", 0x4be5019a)))
        assertEquals(1, changed)
        assertTrue(text.contains("\"Zwei\"=dword:4be5019a\n"))
    }

    @Test
    fun missingValueIsAddedAtTheEndOfItsKeyOnly() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\A\\B", "Neu", "wert")))
        assertEquals(1, changed)
        assertEquals(sample.replace("\"Zwei\"=dword:00000001\n", "\"Zwei\"=dword:00000001\n\"Neu\"=\"wert\"\n"), text)
    }

    @Test
    fun missingKeyIsNotCreated() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\Gibt\\EsNicht", "One", "neu")))
        assertEquals(0, changed)
        assertEquals(sample, text)
    }

    @Test
    fun subkeyWithTheSameValueNameIsNotTouched() {
        val (text, _) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\A\\B", "One", "neu")))
        assertTrue(text.contains("[Software\\\\A\\\\B\\\\C] 1753725033\n\"One\"=\"andere Taste\""))
    }

    @Test
    fun valuesContinuedOnTheNextLineAreLeftAlone() {
        val (text, changed) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\X", "Hex", "text")))
        assertEquals(0, changed)
        assertEquals(sample, text)
    }

    @Test
    fun quotesAndBackslashesAreEscaped() {
        val (text, _) = WineRegText.apply(sample, listOf(WineRegText.string("Software\\A\\B", "One", "a\\b\"c")))
        assertTrue(text.contains("\"One\"=\"a\\\\b\\\"c\""))
    }

    @Test
    fun getRawReadsTheValueOfTheRightKey() {
        assertEquals("\"alt\"", WineRegText.getRaw(sample, "Software\\A\\B", "One"))
        assertEquals("\"andere Taste\"", WineRegText.getRaw(sample, "Software\\A\\B\\C", "One"))
        assertNull(WineRegText.getRaw(sample, "Software\\A\\B", "Gibt es nicht"))
        assertNull(WineRegText.getRaw(sample, "Software\\Nope", "One"))
    }

    @Test
    fun fileRoundTripIsByteExactForNonAsciiBytes() {
        val file = File(dir, "system.reg")
        val bytes = sample.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xC3.toByte(), 0xA4.toByte(), 0x0A)
        file.writeBytes(bytes)

        assertEquals(1, WineRegText.applyToFile(file, listOf(WineRegText.string("Software\\A\\B", "One", "neu"))))
        val expected = String(bytes, Charsets.ISO_8859_1).replace("\"One\"=\"alt\"", "\"One\"=\"neu\"").toByteArray(Charsets.ISO_8859_1)
        assertTrue(expected.contentEquals(file.readBytes()))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })

        val stamp = file.lastModified()
        assertEquals(0, WineRegText.applyToFile(file, listOf(WineRegText.string("Software\\A\\B", "One", "neu"))))
        assertEquals(stamp, file.lastModified())
    }
}
