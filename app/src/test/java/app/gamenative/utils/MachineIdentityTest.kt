package app.gamenative.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.random.Random

class MachineIdentityTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("machine-identity-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun identityFile() = File(dir, MachineIdentity.FILE_NAME)

    private fun excerpt(): String =
        checkNotNull(javaClass.getResourceAsStream("/machineidentity/system_excerpt.reg")) { "Testressource fehlt" }
            .use { String(it.readBytes(), Charsets.UTF_8) }

    /** Wine-Praefix mit der Registry-Beispieldatei (Ausschnitt der echten system.reg). */
    private fun prefix(): File {
        val wine = File(dir, "wine")
        wine.mkdirs()
        File(wine, "system.reg").writeText(excerpt())
        return wine
    }

    private fun generate(seed: Int, now: Long = 1_800_000_000L) = MachineIdentity.generate(Random(seed), now)

    // --- Erzeugung ---

    @Test
    fun generatedValuesHaveTheExpectedFormats() {
        val id = generate(42)
        assertTrue(id.isValid())
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id.machineGuid))
        assertTrue(Regex("DESKTOP-[A-Z0-9]{7}").matches(id.computerName))
        assertTrue(id.computerName.length <= 15)
        assertTrue(Regex("[0-9A-F]{8}").matches(id.volumeSerial))
        assertNotEquals("00000000", id.volumeSerial)
        assertTrue(Regex("00330-8[0-9]{4}-[0-9]{5}-AAOEM").matches(id.productId))
        assertEquals(1_800_000_000L, id.installDate)
    }

    @Test
    fun sameSeedGivesSameIdentityDifferentSeedDiffers() {
        assertEquals(generate(7), generate(7))
        assertNotEquals(generate(7), generate(8))
        assertNotEquals(generate(7).machineGuid, generate(8).machineGuid)
    }

    @Test
    fun manySeedsAlwaysGiveValidIdentitiesAndHostnames() {
        for (seed in 0 until 500) {
            val id = generate(seed)
            assertTrue("seed $seed", id.isValid())
            assertTrue("seed $seed", MachineIdentity.isValidHostname(id.computerName))
            assertNotEquals("00000000", id.volumeSerial)
        }
    }

    @Test
    fun installDateIsClampedIntoThePlausibleRange() {
        assertTrue(MachineIdentity.generate(Random(1), 0L).isValid())
        assertTrue(MachineIdentity.generate(Random(1), Long.MAX_VALUE).isValid())
    }

    @Test
    fun secureDefaultRandomProducesValidDistinctIdentities() {
        val a = MachineIdentity.generate()
        val b = MachineIdentity.generate()
        assertTrue(a.isValid())
        assertNotEquals(a.machineGuid, b.machineGuid)
    }

    @Test
    fun digitsAreAsciiWhateverTheSystemLanguageIs() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-SA-u-nu-arab", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                for (seed in 0 until 50) {
                    assertTrue("$tag seed $seed", generate(seed).isValid())
                }
                // The file must survive a restart: valid after saving and reading back, same values the second time.
                val file = File(dir, "locale-$tag.properties")
                val first = MachineIdentity.loadOrCreate(file)
                assertEquals(tag, first, MachineIdentity.loadOrCreate(file))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun twoInstallationsShareNoValue() {
        val a = MachineIdentity.loadOrCreate(File(dir, "a.properties"))
        val b = MachineIdentity.loadOrCreate(File(dir, "b.properties"))
        assertNotEquals(a.machineGuid, b.machineGuid)
        assertNotEquals(a.computerName, b.computerName)
        assertNotEquals(a.volumeSerial, b.volumeSerial)
        assertNotEquals(a.productId, b.productId)
        // nothing of the prebuilt prefix (the same for everybody) may come back
        assertNotEquals("87447f37-0be9-4ca4-8034-20645c186c64", a.machineGuid)
        assertNotEquals("00330-50000-00000-AAOEM", a.productId)
    }

    @Test
    fun toStringHidesTheValues() {
        val id = generate(3)
        assertFalse(id.toString().contains(id.machineGuid))
        assertFalse(id.toString().contains(id.computerName))
    }

    // --- Laden / Speichern ---

    @Test
    fun saveAndLoadRoundTrip() {
        val id = generate(11)
        assertTrue(MachineIdentity.save(identityFile(), id))
        assertEquals(id, MachineIdentity.load(identityFile()))
        assertFalse(File(dir, MachineIdentity.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun loadOrCreateCreatesOnceAndKeepsAValidFile() {
        val first = MachineIdentity.loadOrCreate(identityFile(), Random(1), 1_800_000_000L)
        assertTrue(identityFile().isFile)
        val bytes = identityFile().readBytes()

        val second = MachineIdentity.loadOrCreate(identityFile(), Random(2), 1_900_000_000L)
        assertEquals(first, second)
        assertTrue(bytes.contentEquals(identityFile().readBytes()))
    }

    @Test
    fun brokenFileIsReplacedByANewIdentity() {
        val old = generate(5)
        MachineIdentity.save(identityFile(), old)
        identityFile().writeText("das ist kein Inhalt")

        val fresh = MachineIdentity.loadOrCreate(identityFile(), Random(6), 1_800_000_000L)
        assertTrue(fresh.isValid())
        assertNotEquals(old, fresh)
        assertEquals(fresh, MachineIdentity.load(identityFile()))
    }

    @Test
    fun invalidOrIncompleteContentIsRejected() {
        val good = generate(9).serialize()
        assertNotNull(MachineIdentity.parse(good))
        assertNull(MachineIdentity.parse(null))
        assertNull(MachineIdentity.parse(""))
        assertNull(MachineIdentity.parse(good.lines().filterNot { it.startsWith("volumeSerial") }.joinToString("\n")))
        assertNull(MachineIdentity.parse(good.replace(Regex("computerName=.*"), "computerName=localhost")))
        assertNull(MachineIdentity.parse(good.replace(Regex("machineGuid=.*"), "machineGuid=87447F37-0BE9-4CA4-8034-20645C186C64")))
        assertNull(MachineIdentity.parse(good.replace(Regex("volumeSerial=.*"), "volumeSerial=00000000")))
        assertNull(MachineIdentity.parse(good.replace(Regex("installDate=.*"), "installDate=abc")))
        assertNull(MachineIdentity.parse(good.replace(Regex("installDate=.*"), "installDate=5")))
        assertNull(MachineIdentity.parse(good.replace(Regex("productId=.*"), "productId=00330-50000-00000-AAOEM")))
    }

    @Test
    fun parseToleratesWindowsLineEndingsAndUnknownKeys() {
        val id = generate(10)
        val text = id.serialize().replace("\n", "\r\n") + "zukunft=1\r\n"
        assertEquals(id, MachineIdentity.parse(text))
    }

    // --- Rechnername fuer die Shim ---

    @Test
    fun hostnameValidationFollowsTheShimRules() {
        assertTrue(MachineIdentity.isValidHostname("DESKTOP-AB12CD3"))
        assertTrue(MachineIdentity.isValidHostname("a".repeat(63)))
        assertFalse(MachineIdentity.isValidHostname("a".repeat(64)))
        assertFalse(MachineIdentity.isValidHostname(""))
        assertFalse(MachineIdentity.isValidHostname(null))
        assertFalse(MachineIdentity.isValidHostname("with space"))
        assertFalse(MachineIdentity.isValidHostname("under_score"))
        assertFalse(MachineIdentity.isValidHostname("dot.name"))
    }

    // --- Registry-Anwendung ---

    private fun value(wine: File, key: String, name: String): String? =
        WineRegText.getRaw(String(File(wine, "system.reg").readBytes(), Charsets.ISO_8859_1), key, name)
            ?.let { if (it.startsWith("\"")) it.substring(1, it.length - 1) else it }

    @Test
    fun appliesAllValuesToTheExcerptAndChangesNothingElse() {
        val wine = prefix()
        val before = File(wine, "system.reg").readLines()
        val id = generate(21)

        val changed = MachineIdentityApply.applyToPrefix(wine, id)
        // 1 MachineGuid + 1 ComputerName + 1 Hostname + 2x(ProductId+InstallDate) + 2x ProductId + Seriendatei
        assertEquals(10, changed)

        val after = File(wine, "system.reg").readLines()
        assertEquals(before.size, after.size)
        assertEquals("nur die 9 Werte duerfen sich aendern", 9, before.indices.count { before[it] != after[it] })

        assertEquals(id.machineGuid, value(wine, "Software\\Microsoft\\Cryptography", "MachineGuid"))
        assertEquals(id.computerName, value(wine, "System\\ControlSet001\\Control\\ComputerName\\ComputerName", "ComputerName"))
        assertEquals(id.computerName, value(wine, "System\\ControlSet001\\Services\\Tcpip\\Parameters", "Hostname"))
        val ntKeys = listOf("Software\\Microsoft\\Windows NT\\CurrentVersion", "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion")
        for (key in ntKeys) {
            assertEquals(id.productId, value(wine, key, "ProductId"))
            assertEquals("dword:" + "%08x".format(id.installDate.toInt()), value(wine, key, "InstallDate"))
        }
        for (key in listOf("Software\\Microsoft\\Windows\\CurrentVersion", "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion")) {
            assertEquals(id.productId, value(wine, key, "ProductId"))
        }
        // unberuehrt
        assertEquals("Windows 10 Pro", value(wine, "Software\\Microsoft\\Windows NT\\CurrentVersion", "ProductName"))
        assertEquals("22H2", value(wine, "Software\\Microsoft\\Windows NT\\CurrentVersion", "DisplayVersion"))
        // Rohwerte der Datei: Backslashes sind dort verdoppelt
        assertEquals("C:\\\\Program Files", value(wine, "Software\\Microsoft\\Windows\\CurrentVersion", "ProgramFilesDir"))
        val tcpip = "System\\ControlSet001\\Services\\Tcpip\\Parameters"
        assertEquals("C:\\\\windows\\\\system32\\\\drivers\\\\etc", value(wine, tcpip, "DataBasePath"))
        assertEquals(id.volumeSerial + "\n", File(wine, "drive_c/.windows-serial").readText())
    }

    @Test
    fun secondRunChangesNothing() {
        val wine = prefix()
        val id = generate(22)
        MachineIdentityApply.applyToPrefix(wine, id)
        val bytes = File(wine, "system.reg").readBytes()
        val stamp = File(wine, "drive_c/.windows-serial").lastModified()

        assertEquals(0, MachineIdentityApply.applyToPrefix(wine, id))
        assertTrue(bytes.contentEquals(File(wine, "system.reg").readBytes()))
        assertEquals(stamp, File(wine, "drive_c/.windows-serial").lastModified())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aNewIdentityOverwritesTheOldOne() {
        val wine = prefix()
        MachineIdentityApply.applyToPrefix(wine, generate(23))
        val other = generate(24, 1_810_000_000L)
        assertEquals(10, MachineIdentityApply.applyToPrefix(wine, other))
        assertEquals(other.machineGuid, value(wine, "Software\\Microsoft\\Cryptography", "MachineGuid"))
        assertEquals(other.volumeSerial + "\n", File(wine, "drive_c/.windows-serial").readText())
    }

    @Test
    fun restoredSharedDefaultsAreFixedAgain() {
        // refreshSkeleton kopiert die system.reg des Basispraefixes zurueck: danach wird neu geschrieben
        val wine = prefix()
        val id = generate(25)
        MachineIdentityApply.applyToPrefix(wine, id)
        File(wine, "system.reg").writeText(excerpt())

        assertEquals(9, MachineIdentityApply.applyToPrefix(wine, id))
        assertEquals(id.machineGuid, value(wine, "Software\\Microsoft\\Cryptography", "MachineGuid"))
    }

    @Test
    fun missingRegistryFileOnlyWritesTheSerialAndInventsNoKeys() {
        val wine = File(dir, "empty")
        wine.mkdirs()
        val id = generate(26)
        assertEquals(1, MachineIdentityApply.applyToPrefix(wine, id))
        assertFalse(File(wine, "system.reg").exists())
        assertEquals(id.volumeSerial + "\n", File(wine, "drive_c/.windows-serial").readText())
    }

    @Test
    fun keysMissingInThePrefixAreNotCreated() {
        val wine = File(dir, "partial")
        wine.mkdirs()
        val text = "WINE REGISTRY Version 2\n;; All keys relative to REGISTRY\\\\Machine\n\n#arch=win64\n\n" +
            "[Software\\\\Microsoft\\\\Cryptography] 1753725033\n#time=1dbffe81de76732\n" +
            "\"MachineGuid\"=\"87447f37-0be9-4ca4-8034-20645c186c64\"\n"
        File(wine, "system.reg").writeText(text)
        val id = generate(27)

        MachineIdentityApply.applyToPrefix(wine, id)
        assertEquals(id.machineGuid, value(wine, "Software\\Microsoft\\Cryptography", "MachineGuid"))
        assertEquals(text.replace("87447f37-0be9-4ca4-8034-20645c186c64", id.machineGuid), File(wine, "system.reg").readText())
    }
}
