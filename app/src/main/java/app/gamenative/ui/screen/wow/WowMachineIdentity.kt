package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.core.WineRegistryEditor
import java.io.File
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import timber.log.Timber

/**
 * The Windows identity this install shows Battle.net. The bundled prefix ships one fixed MachineGuid and install date,
 * so every player looked like the same PC, and the computer name was the phone's IP address, which changes with every
 * network. Battle.net's fraud checks reject logins like that with BLZ51914400 (#57).
 *
 * Generated once per install and kept in app storage, so it stays the same across launches and app updates. The
 * ProductId is left as is: it's also baked into DigitalProductId, and OEM installs share it anyway.
 */
internal data class WowMachineIdentity(
    val machineGuid: String,
    val computerName: String,
    val installDate: Int,
    val volumeSerial: String,
) {
    /** Writes the identity into the Wine prefix at [prefixDir] (the container's `.wine`), unless it's already there. */
    fun applyTo(prefixDir: File) {
        val systemReg = File(prefixDir, "system.reg")
        if (!systemReg.isFile) {
            Timber.w("No system.reg in $prefixDir, skipping the machine identity")
            return
        }
        // The editor copies and rescans the whole multi-MB file for every value, so it only runs when something differs.
        val applied = listOf(
            "\"MachineGuid\"=\"$machineGuid\"",
            "\"InstallDate\"=dword:%08x".format(installDate),
            "\"ComputerName\"=\"$computerName\"",
        )
        if (!systemReg.readText().let { reg -> applied.all { it in reg } }) {
            WineRegistryEditor(systemReg).use { registry ->
                registry.setStringValue("Software\\Microsoft\\Cryptography", "MachineGuid", machineGuid)
                CURRENT_VERSION_KEYS.forEach { registry.setDwordValue(it, "InstallDate", installDate) }
                // wineboot also sets these on boot, from gethostname(), which the DNS shim answers with the same name.
                registry.setStringValue(COMPUTER_NAME_KEY, "ComputerName", computerName)
                registry.setStringValue(TCPIP_KEY, "Hostname", computerName)
                registry.setStringValue(TCPIP_KEY, "NV Hostname", computerName)
            }
        }
        // Wine's mount manager reads C:'s volume serial from this file.
        val serialFile = File(prefixDir, "drive_c/.windows-serial")
        if (serialFile.parentFile?.isDirectory == true && serialFile.takeIf { it.isFile }?.readText()?.trim() != volumeSerial) {
            serialFile.writeText("$volumeSerial\n")
        }
    }

    companion object {
        /** The env var the DNS shim reads the computer name from. */
        const val HOSTNAME_ENV = "GAMENATIVE_HOSTNAME"

        private const val FILE_NAME = "wow_identity.properties"
        private val CURRENT_VERSION_KEYS = listOf(
            "Software\\Microsoft\\Windows NT\\CurrentVersion",
            "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion",
        )
        private const val COMPUTER_NAME_KEY = "System\\ControlSet001\\Control\\ComputerName\\ComputerName"
        private const val TCPIP_KEY = "System\\ControlSet001\\Services\\Tcpip\\Parameters"
        private const val NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        // 2021-01-01 to 2025-06-01: a plausible Windows 10/11 install date.
        private const val INSTALL_DATE_FROM = 1_609_459_200
        private const val INSTALL_DATE_UNTIL = 1_748_736_000

        // Also keeps the env var and the registry in agreement within a run if the identity can't be saved.
        private var cached: WowMachineIdentity? = null

        @Synchronized
        fun load(context: Context): WowMachineIdentity = cached ?: read(context).also { cached = it }

        private fun read(context: Context): WowMachineIdentity {
            val file = File(context.filesDir, FILE_NAME)
            val props = Properties()
            runCatching { file.inputStream().use(props::load) }
            fromProperties(props)?.let { return it }

            val identity = generate(SecureRandom())
            props.clear()
            props.putAll(identity.toProperties())
            runCatching { file.outputStream().use { props.store(it, null) } }
                .onFailure { Timber.e(it, "Couldn't save the machine identity") }
            return identity
        }

        internal fun generate(random: java.util.Random): WowMachineIdentity {
            // A random (version 4) UUID, like Windows generates.
            val machineGuid = UUID(random.nextLong() and 0xF000L.inv() or 0x4000L, random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL or Long.MIN_VALUE)
            return WowMachineIdentity(
                machineGuid = machineGuid.toString(),
                computerName = "DESKTOP-" + (1..7).map { NAME_CHARS[random.nextInt(NAME_CHARS.length)] }.joinToString(""),
                installDate = INSTALL_DATE_FROM + random.nextInt(INSTALL_DATE_UNTIL - INSTALL_DATE_FROM),
                volumeSerial = "%08x".format(random.nextInt()),
            )
        }

        private fun fromProperties(props: Properties): WowMachineIdentity? = runCatching {
            WowMachineIdentity(
                machineGuid = props.getProperty("machineGuid")!!,
                computerName = props.getProperty("computerName")!!,
                installDate = props.getProperty("installDate")!!.toInt(),
                volumeSerial = props.getProperty("volumeSerial")!!,
            )
        }.getOrNull()

        private fun WowMachineIdentity.toProperties() = mapOf(
            "machineGuid" to machineGuid,
            "computerName" to computerName,
            "installDate" to installDate.toString(),
            "volumeSerial" to volumeSerial,
        )
    }
}
