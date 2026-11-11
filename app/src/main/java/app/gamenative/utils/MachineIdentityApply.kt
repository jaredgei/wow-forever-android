package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Writes the [MachineIdentity] into the WoW container's own (upper) Wine prefix.
 *
 * Wine facts this relies on (wine-10.0 sources):
 * - wineboot (create_computer_name_keys) calls gethostname() at every wineboot run and stores the
 *   result via SetComputerNameEx as Tcpip\Parameters\Hostname and ControlSet001\...\ComputerName
 *   (upper case, 15 characters), then copies it into the volatile ActiveComputerName key.
 *   GetComputerNameEx(NetBIOS) reads ActiveComputerName, GetComputerNameEx(DnsHostname) reads
 *   Hostname, and winsock gethostname() calls the libc gethostname() (our shim). The registry
 *   values written here and the shim hostname ([MachineIdentity.ENV_HOSTNAME]) are therefore
 *   the same string and all roads lead to the same name.
 * - The registry file stores the machine hive under ControlSet001 (CurrentControlSet is only a
 *   symbolic link key), so the values are written there.
 * - mountmgr.sys (get_filesystem_serial) reads drive_c/.windows-serial as hexadecimal text for the
 *   volume serial of C:.
 */
object MachineIdentityApply {
    private const val CRYPTOGRAPHY = "Software\\Microsoft\\Cryptography"
    private const val WINDOWS_CV = "Software\\Microsoft\\Windows\\CurrentVersion"
    private const val NT_CV = "Software\\Microsoft\\Windows NT\\CurrentVersion"
    private const val WOW_WINDOWS_CV = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion"
    private const val WOW_NT_CV = "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion"
    private const val COMPUTER_NAME_KEY = "System\\ControlSet001\\Control\\ComputerName\\ComputerName"
    private const val TCPIP_KEY = "System\\ControlSet001\\Services\\Tcpip\\Parameters"

    const val SERIAL_FILE = "drive_c/.windows-serial"

    @JvmStatic
    fun identityFile(context: Context): File = File(context.filesDir, MachineIdentity.FILE_NAME)

    @JvmStatic
    fun identity(context: Context): MachineIdentity = MachineIdentity.loadOrCreate(identityFile(context))

    /** Hostname for the shim, only for the WoW container; null otherwise (shim keeps old behaviour). */
    @JvmStatic
    fun shimHostname(context: Context, container: Container?): String? {
        if (container == null || container.id != MachineIdentity.WOW_CONTAINER_ID) return null
        return identity(context).computerName.takeIf { MachineIdentity.isValidHostname(it) }
    }

    /** Applies the identity to the WoW container only; other containers stay as they are. */
    @JvmStatic
    fun applyToContainer(context: Context, container: Container?) {
        if (container == null || container.id != MachineIdentity.WOW_CONTAINER_ID) return
        val wineDir = File(container.rootDir, ".wine")
        try {
            val changed = applyToPrefix(wineDir, identity(context))
            Timber.i("Machine identity applied to prefix, %d value(s) changed", changed)
        } catch (e: Exception) {
            Timber.w("Machine identity could not be applied: %s", e.javaClass.simpleName)
        }
    }

    /** The registry values of the identity, one entry per key the Wine prefix holds. */
    fun registryEdits(identity: MachineIdentity): List<WineRegText.Edit> {
        val edits = ArrayList<WineRegText.Edit>()
        edits += WineRegText.string(CRYPTOGRAPHY, "MachineGuid", identity.machineGuid)
        edits += WineRegText.string(COMPUTER_NAME_KEY, "ComputerName", identity.computerName)
        edits += WineRegText.string(TCPIP_KEY, "Hostname", identity.computerName)
        for (key in listOf(NT_CV, WOW_NT_CV)) {
            edits += WineRegText.string(key, "ProductId", identity.productId)
            edits += WineRegText.dword(key, "InstallDate", identity.installDate.toInt())
        }
        for (key in listOf(WINDOWS_CV, WOW_WINDOWS_CV)) {
            edits += WineRegText.string(key, "ProductId", identity.productId)
        }
        return edits
    }

    /**
     * Writes the identity into [wineDir]/system.reg and the volume serial file. Only values that
     * differ are written; the number of changed values (registry values plus serial file) is
     * returned, 0 on an up to date prefix. Keys the prefix does not have are not created.
     */
    @JvmStatic
    fun applyToPrefix(wineDir: File, identity: MachineIdentity): Int {
        var changed = 0
        val systemReg = File(wineDir, "system.reg")
        if (systemReg.isFile) changed += WineRegText.applyToFile(systemReg, registryEdits(identity))
        if (writeSerial(File(wineDir, SERIAL_FILE), identity.volumeSerial)) changed++
        return changed
    }

    private fun writeSerial(file: File, serial: String): Boolean {
        return try {
            val current = if (file.isFile) String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim() else null
            if (current.equals(serial, ignoreCase = true)) return false
            file.parentFile?.let { Files.createDirectories(it.toPath()) }
            Files.write(file.toPath(), (serial + "\n").toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: IOException) {
            false
        }
    }
}
