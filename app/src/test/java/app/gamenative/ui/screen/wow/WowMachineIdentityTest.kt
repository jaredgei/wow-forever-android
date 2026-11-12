package app.gamenative.ui.screen.wow

import com.winlator.core.WineRegistryEditor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Random
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class WowMachineIdentityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun generate_makesAPlausibleWindowsIdentity() {
        val identity = WowMachineIdentity.generate(Random(1))

        assertTrue(identity.computerName, Regex("DESKTOP-[A-Z0-9]{7}").matches(identity.computerName))
        assertTrue(identity.machineGuid, Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(identity.machineGuid))
        assertEquals(4, UUID.fromString(identity.machineGuid).version())
        assertEquals(2, UUID.fromString(identity.machineGuid).variant())
        assertTrue(Regex("[0-9a-f]{8}").matches(identity.volumeSerial))
        assertNotEquals(identity, WowMachineIdentity.generate(Random(2)))
    }

    @Test
    fun applyTo_replacesTheSharedPrefixIdentity() {
        val prefix = tempFolder.newFolder(".wine")
        File(prefix, "drive_c").mkdirs()
        File(prefix, "system.reg").writeText(STOCK_SYSTEM_REG)
        val identity = WowMachineIdentity.generate(Random(1))

        identity.applyTo(prefix)

        WineRegistryEditor(File(prefix, "system.reg")).use { registry ->
            assertEquals(identity.machineGuid, registry.getStringValue("Software\\Microsoft\\Cryptography", "MachineGuid"))
            assertEquals(identity.installDate, registry.getDwordValue("Software\\Microsoft\\Windows NT\\CurrentVersion", "InstallDate"))
            assertEquals("00330-50000-00000-AAOEM", registry.getStringValue("Software\\Microsoft\\Windows NT\\CurrentVersion", "ProductId"))
            assertEquals(
                identity.computerName,
                registry.getStringValue("System\\ControlSet001\\Control\\ComputerName\\ComputerName", "ComputerName"),
            )
            assertEquals(identity.computerName, registry.getStringValue("System\\ControlSet001\\Services\\Tcpip\\Parameters", "Hostname"))
        }
        assertEquals(identity.volumeSerial, File(prefix, "drive_c/.windows-serial").readText().trim())
    }

    @Test
    fun applyTo_leavesAnUpToDatePrefixUntouched() {
        val prefix = tempFolder.newFolder(".wine")
        File(prefix, "drive_c").mkdirs()
        val systemReg = File(prefix, "system.reg").apply { writeText(STOCK_SYSTEM_REG) }
        val identity = WowMachineIdentity.generate(Random(1))
        identity.applyTo(prefix)
        val serialFile = File(prefix, "drive_c/.windows-serial")
        val applied = systemReg.readBytes()
        val serial = serialFile.readBytes()
        assertTrue(systemReg.setLastModified(1_600_000_000_000L))
        assertTrue(serialFile.setLastModified(1_600_000_000_000L))
        val registryModified = systemReg.lastModified()
        val serialModified = serialFile.lastModified()

        identity.applyTo(prefix)

        assertArrayEquals(applied, systemReg.readBytes())
        assertArrayEquals(serial, serialFile.readBytes())
        assertEquals(registryModified, systemReg.lastModified())
        assertEquals(serialModified, serialFile.lastModified())
        assertTrue(prefix.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun applyTo_repairsStaleOrMissingMachineGuid() = assertRepairsIdentityField(0)

    @Test
    fun applyTo_repairsStaleOrMissingNativeInstallDate() = assertRepairsIdentityField(1)

    @Test
    fun applyTo_repairsStaleOrMissingWow64InstallDate() = assertRepairsIdentityField(2)

    @Test
    fun applyTo_repairsStaleOrMissingComputerName() = assertRepairsIdentityField(3)

    @Test
    fun applyTo_repairsStaleOrMissingHostname() = assertRepairsIdentityField(4)

    @Test
    fun applyTo_repairsStaleOrMissingNvHostname() = assertRepairsIdentityField(5)

    @Test
    fun applyTo_doesNotAcceptMatchingValuesFromAnUnrelatedSection() {
        for (field in 0..5) assertRepairsIdentityField(field, decoy = true)
    }

    private fun assertRepairsIdentityField(field: Int, decoy: Boolean = false) {
        val identity = WowMachineIdentity.generate(Random(1))
        val fields = listOf(
            Triple("Software\\Microsoft\\Cryptography", "MachineGuid", identity.machineGuid),
            Triple("Software\\Microsoft\\Windows NT\\CurrentVersion", "InstallDate", identity.installDate),
            Triple("Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion", "InstallDate", identity.installDate),
            Triple("System\\ControlSet001\\Control\\ComputerName\\ComputerName", "ComputerName", identity.computerName),
            Triple("System\\ControlSet001\\Services\\Tcpip\\Parameters", "Hostname", identity.computerName),
            Triple("System\\ControlSet001\\Services\\Tcpip\\Parameters", "NV Hostname", identity.computerName),
        )
        val (key, name, expected) = fields[field]
        for (missing in listOf(false, true)) {
            val prefix = tempFolder.newFolder()
            File(prefix, "drive_c").mkdirs()
            val systemReg = File(prefix, "system.reg").apply { writeText(STOCK_SYSTEM_REG) }
            identity.applyTo(prefix)
            WineRegistryEditor(systemReg).use { registry ->
                if (missing) registry.removeValue(key, name)
                else if (expected is Int) registry.setDwordValue(key, name, 1)
                else registry.setStringValue(key, name, "stale")
                if (decoy) {
                    if (expected is Int) registry.setDwordValue("Software\\Unrelated", name, expected)
                    else registry.setStringValue("Software\\Unrelated", name, expected as String)
                }
            }

            identity.applyTo(prefix)

            WineRegistryEditor(systemReg).use { registry ->
                for ((identityKey, identityName, identityValue) in fields) {
                    val actual = if (identityValue is Int) registry.getDwordValue(identityKey, identityName)
                    else registry.getStringValue(identityKey, identityName)
                    assertEquals("$key/$name (missing=$missing, decoy=$decoy): $identityKey/$identityName", identityValue, actual)
                }
                assertEquals("00330-50000-00000-AAOEM", registry.getStringValue("Software\\Microsoft\\Windows NT\\CurrentVersion", "ProductId"))
            }
            assertEquals(identity.volumeSerial, File(prefix, "drive_c/.windows-serial").readText().trim())
        }
    }

    private companion object {
        val STOCK_SYSTEM_REG = """
            WINE REGISTRY Version 2
            ;; All keys relative to REGISTRY\\Machine

            [Software\\Microsoft\\Cryptography] 1753725033
            #time=1dbffe81de76732
            "MachineGuid"="87447f37-0be9-4ca4-8034-20645c186c64"

            [Software\\Microsoft\\Windows NT\\CurrentVersion] 1753725046
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"
            "ProductName"="Windows 10 Pro"

            [Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion] 1753725044
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"

            [System\\ControlSet001\\Control\\ComputerName\\ComputerName] 1753725024
            #time=1dbffe8187431a4
            "ComputerName"="LOCALHOST"

            [System\\ControlSet001\\Services\\Tcpip\\Parameters] 1753725033
            #time=1dbffe81dfb6c82
            "DataBasePath"="C:\\windows\\system32\\drivers\\etc"
            "Domain"=""
            "Hostname"="localhost"
            "NV Hostname"="localhost"

        """.trimIndent()
    }
}
