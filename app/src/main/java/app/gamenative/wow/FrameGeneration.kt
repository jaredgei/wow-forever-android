package app.gamenative.wow

import android.content.Context
import app.gamenative.utils.LsfgVkManager
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Frame generation (LSFG) from Lossless Scaling. Its shaders live in Lossless.dll, which can't ship with
 * the app: the player picks their own copy in Settings and it's kept in the app's private storage.
 * [LsfgVkManager] copies it into the game's container at launch, and the in-game menu's LSFG tab appears.
 */
object FrameGeneration {
    enum class ImportResult { OK, NOT_A_DLL, NO_SHADERS, FAILED }

    private const val PREFS = "wow_settings"
    private const val KEY_ENABLED = "frame_generation"

    /** Windows DLLs start with "MZ"; anything this small isn't Lossless Scaling's. */
    private const val MIN_DLL_BYTES = 100_000L

    fun dllFile(context: Context) = File(context.filesDir, "lsfg/Lossless.dll")

    fun hasDll(context: Context) = dllFile(context).isFile

    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** True when frame generation should run at the next launch: a DLL is set and the switch is on. */
    fun isArmed(context: Context) = hasDll(context) && isEnabled(context)

    /**
     * Whether the added DLL has the shaders frame generation loads. Without them the frame-generation layer
     * closes the game as it starts, so the game is started without frame generation instead.
     */
    fun hasShaders(context: Context) = hasShaders(dllFile(context))

    /** Copies the picked file in, after checking it's a Windows DLL of a plausible size. */
    fun import(context: Context, input: InputStream): ImportResult {
        val target = dllFile(context)
        val temp = File(target.parentFile, "Lossless.dll.part")
        return try {
            target.parentFile?.mkdirs()
            input.use { src -> temp.outputStream().use { src.copyTo(it) } }
            val header = temp.inputStream().use { ByteArray(2).also { b -> it.read(b) } }
            if (header[0] != 'M'.code.toByte() || header[1] != 'Z'.code.toByte() || temp.length() < MIN_DLL_BYTES) {
                temp.delete()
                return ImportResult.NOT_A_DLL
            }
            if (!hasShaders(temp)) {
                temp.delete()
                return ImportResult.NO_SHADERS
            }
            target.delete()
            if (!temp.renameTo(target)) return ImportResult.FAILED
            setEnabled(context, true)
            ImportResult.OK
        } catch (e: Exception) {
            temp.delete()
            ImportResult.FAILED
        }
    }

    fun remove(context: Context) {
        dllFile(context).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENABLED).apply()
    }

    /**
     * The shader resources lsfg-vk extracts from Lossless.dll (RCDATA ids: mipmaps, generate, and the
     * performance-mode chain), the same list as cpp/lsfg/lsfg_dll.c. A DLL missing any of them makes the layer
     * quit with "Shader not found".
     */
    private val SHADER_IDS = listOf(255, 256) + (280..302)
    private const val RT_RCDATA = 10

    /** Reads [dll]'s resource table and checks every shader in [SHADER_IDS] is there. */
    fun hasShaders(dll: File): Boolean = try {
        val ids = rcdataIds(ByteBuffer.wrap(dll.readBytes()).order(ByteOrder.LITTLE_ENDIAN))
        val missing = SHADER_IDS.filterNot(ids::contains)
        if (missing.isNotEmpty()) Timber.w("Lossless.dll is missing shaders $missing")
        missing.isEmpty()
    } catch (e: Exception) {
        Timber.w(e, "Couldn't read Lossless.dll's resources")
        false
    }

    /** The numeric ids of the RCDATA resources in a PE file, or an empty set when it has none. */
    private fun rcdataIds(pe: ByteBuffer): Set<Int> {
        val peOffset = pe.getInt(0x3C)
        check(pe.getInt(peOffset) == 0x00004550) { "not a PE file" }
        val sectionCount = pe.getShort(peOffset + 6).toInt() and 0xFFFF
        val optionalSize = pe.getShort(peOffset + 20).toInt() and 0xFFFF
        val optional = peOffset + 24
        val dataDirectory = optional + when (pe.getShort(optional).toInt() and 0xFFFF) {
            0x10B -> 96
            0x20B -> 112
            else -> error("unknown optional header")
        }
        val resourceRva = pe.getInt(dataDirectory + 2 * 8)
        if (resourceRva == 0) return emptySet()
        val sections = optional + optionalSize
        val base = (0 until sectionCount).firstNotNullOfOrNull { i ->
            val s = sections + i * 40
            val va = pe.getInt(s + 12)
            val size = maxOf(pe.getInt(s + 8), pe.getInt(s + 16))
            if (resourceRva - va in 0 until size) pe.getInt(s + 20) + (resourceRva - va) else null
        } ?: return emptySet()

        fun entries(dir: Int) = (0 until (pe.getShort(dir + 12).toInt() and 0xFFFF) + (pe.getShort(dir + 14).toInt() and 0xFFFF))
            .map { i -> pe.getInt(dir + 16 + i * 8) to pe.getInt(dir + 16 + i * 8 + 4) }
        val subdir = 0x80000000.toInt()
        val rcdata = entries(base).firstOrNull { (name, data) -> name == RT_RCDATA && data and subdir != 0 } ?: return emptySet()
        return entries(base + (rcdata.second and subdir.inv()))
            .filter { (name, data) -> name and subdir == 0 && data and subdir != 0 }
            .map { it.first }
            .toSet()
    }

    /** Lets [LsfgVkManager] find the player's DLL; called once at app start. */
    fun init(context: Context) {
        LsfgVkManager.userDll = dllFile(context)
    }
}
