package app.gamenative.utils

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Locale
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Stable, own Windows machine identity of one WoW Forever installation.
 *
 * The prebuilt Wine prefix ships one fixed MachineGuid / product id / install date for every
 * installation, and Wine derives the computer name from the Android hostname (which the DNS
 * shim turns into the current IP). Blizzard therefore saw either "the same PC for everybody" or
 * "a new PC in every network". This identity is generated once, kept in the app storage and
 * applied to the prefix at every start (see [MachineIdentityApply]).
 *
 * Pure logic: no Android classes, so it runs in plain JUnit. The values are never logged:
 * [toString] hides them.
 */
class MachineIdentity(
    val machineGuid: String,
    val computerName: String,
    val volumeSerial: String,
    val productId: String,
    val installDate: Long,
) {
    override fun toString(): String = "MachineIdentity(***)"

    override fun equals(other: Any?): Boolean = other is MachineIdentity &&
        machineGuid == other.machineGuid &&
        computerName == other.computerName &&
        volumeSerial == other.volumeSerial &&
        productId == other.productId &&
        installDate == other.installDate

    override fun hashCode(): Int = machineGuid.hashCode()

    fun isValid(): Boolean = GUID.matches(machineGuid) &&
        COMPUTER_NAME.matches(computerName) &&
        SERIAL.matches(volumeSerial) &&
        volumeSerial != "00000000" &&
        PRODUCT_ID.matches(productId) &&
        installDate in MIN_INSTALL_DATE..MAX_INSTALL_DATE

    fun serialize(): String = buildString {
        append(KEY_GUID).append('=').append(machineGuid).append('\n')
        append(KEY_NAME).append('=').append(computerName).append('\n')
        append(KEY_SERIAL).append('=').append(volumeSerial).append('\n')
        append(KEY_PRODUCT).append('=').append(productId).append('\n')
        append(KEY_INSTALL).append('=').append(installDate).append('\n')
    }

    companion object {
        /** Id of the one container this identity belongs to. */
        const val WOW_CONTAINER_ID = "wow_forever"

        /** Environment variable read by the DNS shim (gethostname / self resolution). */
        const val ENV_HOSTNAME = "WOWFOREVER_HOSTNAME"

        const val FILE_NAME = "wow_identity.properties"

        private const val KEY_GUID = "machineGuid"
        private const val KEY_NAME = "computerName"
        private const val KEY_SERIAL = "volumeSerial"
        private const val KEY_PRODUCT = "productId"
        private const val KEY_INSTALL = "installDate"

        private const val NAME_PREFIX = "DESKTOP-"
        private const val NAME_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val NAME_SUFFIX_LENGTH = 7

        // 2010-01-01 .. 2038-01-19 (the registry stores InstallDate as a signed 32 bit dword)
        private const val MIN_INSTALL_DATE = 1_262_304_000L
        private const val MAX_INSTALL_DATE = 2_147_483_647L

        private val GUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val COMPUTER_NAME = Regex("^DESKTOP-[A-Z0-9]{7}$")
        private val SERIAL = Regex("^[0-9A-F]{8}$")
        private val PRODUCT_ID = Regex("^00330-8[0-9]{4}-[0-9]{5}-AAOEM$")

        private val lock = Any()

        private fun secureRandom(): Random = SecureRandom().asKotlinRandom()

        /** A random UUID v4 in lower case with dashes. */
        fun randomGuid(random: Random): String {
            val bytes = random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
                hex.substring(16, 20) + "-" + hex.substring(20, 32)
        }

        fun generate(random: Random = secureRandom(), nowSeconds: Long = System.currentTimeMillis() / 1000): MachineIdentity {
            val name = NAME_PREFIX + String(CharArray(NAME_SUFFIX_LENGTH) { NAME_ALPHABET[random.nextInt(NAME_ALPHABET.length)] })
            var serial: Int
            do {
                serial = random.nextInt()
            } while (serial == 0)
            // Locale.ROOT: with the default locale %d writes Arabic-Indic / Persian / Devanagari digits on some phones, the
            // stored product id would then fail isValid() and a new identity would be made at every start.
            val product = "00330-8%04d-%05d-AAOEM".format(Locale.ROOT, random.nextInt(10_000), random.nextInt(100_000))
            return MachineIdentity(
                machineGuid = randomGuid(random),
                computerName = name,
                volumeSerial = "%08X".format(Locale.ROOT, serial),
                productId = product,
                installDate = nowSeconds.coerceIn(MIN_INSTALL_DATE, MAX_INSTALL_DATE),
            )
        }

        /** Parses the stored text; null when a field is missing or the result is not valid. */
        fun parse(text: String?): MachineIdentity? {
            if (text == null) return null
            val values = HashMap<String, String>()
            for (line in text.lines()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            val install = values[KEY_INSTALL]?.toLongOrNull() ?: return null
            val identity = MachineIdentity(
                machineGuid = values[KEY_GUID] ?: return null,
                computerName = values[KEY_NAME] ?: return null,
                volumeSerial = values[KEY_SERIAL] ?: return null,
                productId = values[KEY_PRODUCT] ?: return null,
                installDate = install,
            )
            return if (identity.isValid()) identity else null
        }

        /** Reads the identity file; null when missing, unreadable or invalid. */
        fun load(file: File): MachineIdentity? {
            if (!file.isFile) return null
            return try {
                parse(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            } catch (e: IOException) {
                null
            }
        }

        /** Writes via temp file + rename so a crash never leaves a half written identity. */
        fun save(file: File, identity: MachineIdentity): Boolean {
            val dir = file.absoluteFile.parentFile ?: return false
            return try {
                Files.createDirectories(dir.toPath())
                val temp = File(dir, file.name + ".tmp")
                Files.write(temp.toPath(), identity.serialize().toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        /**
         * Valid file stays untouched. Missing or invalid: generate a new identity and store it.
         * If storing fails the generated identity is still returned for this run.
         */
        fun loadOrCreate(
            file: File,
            random: Random = secureRandom(),
            nowSeconds: Long = System.currentTimeMillis() / 1000,
        ): MachineIdentity = synchronized(lock) {
            load(file) ?: generate(random, nowSeconds).also { save(file, it) }
        }

        /** True for a name the shim may hand out as hostname (<= 63 chars, letters, digits, '-'). */
        fun isValidHostname(name: String?): Boolean =
            name != null && name.isNotEmpty() && name.length <= 63 &&
                name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}
