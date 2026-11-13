package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import com.winlator.core.WineRegistryEditor
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /**
     * Which Windows identity WoW shows Battle.net. [OWN] is this install's own identity (v2.3.5 and later). [ORIGINAL]
     * is the identity every install shared up to v2.3.4: the bundled prefix's MachineGuid and install date, no C:
     * serial file, and a computer name Wine derives from the phone's IP address. Accounts that logged in from Android
     * before v2.3.5 have only ever seen that one, so this tells whether changing it is what Battle.net rejects (#57).
     */
    enum class Mode(val label: String) {
        OWN("This install"), ORIGINAL("Original (v2.3.3)");

        companion object {
            private const val KEY_MODE = "windows_identity"

            fun current(context: Context): Mode =
                prefs(context).getString(KEY_MODE, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: OWN

            fun save(context: Context, mode: Mode) {
                prefs(context).edit().putString(KEY_MODE, mode.name).apply()
            }

            private fun prefs(context: Context) = context.getSharedPreferences("wow_forever", Context.MODE_PRIVATE)
        }
    }

    companion object {
        /** The env var the DNS shim reads the computer name from. */
        const val HOSTNAME_ENV = "GAMENATIVE_HOSTNAME"

        private const val FILE_NAME = "wow_identity.properties"

        // The bundled prefix's own values, which every install used up to v2.3.4.
        internal const val ORIGINAL_MACHINE_GUID = "87447f37-0be9-4ca4-8034-20645c186c64"
        internal const val ORIGINAL_INSTALL_DATE = 0x4be5019a
        private const val ORIGINAL_COMPUTER_NAME = "LOCALHOST"
        private const val ORIGINAL_HOSTNAME = "localhost"

        /** The env var that sets the computer name for [mode], or an empty string to keep the v2.3.3 behaviour. */
        fun hostnameEnv(context: Context, mode: Mode = Mode.current(context)): String =
            if (mode == Mode.OWN) " $HOSTNAME_ENV=${load(context).computerName}" else ""

        /** Writes the identity for the current [Mode] into the prefix at [prefixDir]. */
        fun applyCurrent(context: Context, prefixDir: File) {
            when (Mode.current(context)) {
                Mode.OWN -> load(context).applyTo(prefixDir)
                Mode.ORIGINAL -> restoreOriginal(prefixDir)
            }
        }

        /**
         * Puts the bundled prefix's identity back, as v2.3.3 ran it: its MachineGuid and install date, no C: serial
         * file, and the stock computer name, which wineboot then replaces with the one derived from gethostname().
         */
        internal fun restoreOriginal(prefixDir: File) {
            val systemReg = File(prefixDir, "system.reg")
            if (!systemReg.isFile) {
                Timber.w("No system.reg in $prefixDir, skipping the original machine identity")
                return
            }
            WineRegistryEditor(systemReg).use { registry ->
                registry.setStringValue("Software\\Microsoft\\Cryptography", "MachineGuid", ORIGINAL_MACHINE_GUID)
                CURRENT_VERSION_KEYS.forEach { registry.setDwordValue(it, "InstallDate", ORIGINAL_INSTALL_DATE) }
                registry.setStringValue(COMPUTER_NAME_KEY, "ComputerName", ORIGINAL_COMPUTER_NAME)
                registry.setStringValue(TCPIP_KEY, "Hostname", ORIGINAL_HOSTNAME)
                registry.removeValue(TCPIP_KEY, "NV Hostname")
            }
            File(prefixDir, "drive_c/.windows-serial").delete()
        }

        /**
         * Appends one line to `_forever_/Logs/WindowsIdentity.log` in the game folder describing the identity the prefix
         * holds before this launch applies one, i.e. what Wine left after the previous session. Values are shortened to
         * hashes so the line can be posted publicly. Comparing lines between a working and a failing login shows whether
         * the identity drifted.
         */
        fun logPrefixIdentity(context: Context, gameRoot: File, prefixDir: File) {
            runCatching {
                val mode = Mode.current(context)
                val systemReg = File(prefixDir, "system.reg")
                val found = if (systemReg.isFile) {
                    WineRegistryEditor(systemReg).use { registry ->
                        listOf(
                            "guid" to registry.getStringValue("Software\\Microsoft\\Cryptography", "MachineGuid"),
                            "installDate" to registry.getDwordValue(CURRENT_VERSION_KEYS[0], "InstallDate")?.let { "%08x".format(it) },
                            "computerName" to registry.getStringValue(COMPUTER_NAME_KEY, "ComputerName"),
                            "hostname" to registry.getStringValue(TCPIP_KEY, "Hostname"),
                            "nvHostname" to registry.getStringValue(TCPIP_KEY, "NV Hostname"),
                        )
                    }.map { (name, value) -> "$name=${describe(value)}" }.joinToString(" ")
                } else {
                    "noPrefixYet"
                }
                val serial = File(prefixDir, "drive_c/.windows-serial").takeIf { it.isFile }?.readText()?.trim()
                val own = load(context)
                val line = listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()),
                    "app=${app.gamenative.BuildConfig.VERSION_NAME}",
                    "mode=${mode.name}",
                    "soc=${socName()}",
                    "ownGuid=${describe(own.machineGuid)}",
                    "ownName=${describe(own.computerName)}",
                    found,
                    "serial=${describe(serial)}",
                ).joinToString(" ")
                val log = File(gameRoot, "_forever_/Logs/WindowsIdentity.log")
                log.parentFile?.mkdirs()
                log.appendText(line + "\n")
                Timber.i("Windows identity: $line")
            }.onFailure { Timber.w(it, "Couldn't log the Windows identity") }
        }

        private fun socName(): String {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}" else Build.HARDWARE
            return "$soc(${Build.MANUFACTURER} ${Build.MODEL})".replace(' ', '_')
        }

        /** A value's kind plus a short hash: enough to compare between lines without posting the value itself. */
        private fun describe(value: String?): String {
            if (value == null) return "none"
            val kind = when {
                value == ORIGINAL_MACHINE_GUID || value == "%08x".format(ORIGINAL_INSTALL_DATE) -> "original"
                value.startsWith("DESKTOP-") -> "desktop"
                value.equals("localhost", ignoreCase = true) -> "localhost"
                value.isNotEmpty() && value.all { it.isDigit() } -> "number"
                else -> "other"
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .take(3).joinToString("") { "%02x".format(it) }
            return "$kind:$hash"
        }
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
