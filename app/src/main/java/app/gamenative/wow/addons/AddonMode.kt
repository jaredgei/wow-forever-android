package app.gamenative.wow.addons

import android.content.Context
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.product.WowProduct
import java.io.File

/**
 * Addon manager mode: the app manages the addons of one WoW install that runs some other way (DroidDeck,
 * Winlator, a copy synced from a PC) and does nothing else. It opens straight to that folder's addons.
 */
object AddonMode {
    private const val PREFS = "wow_settings"
    private const val KEY_ENABLED = "addon_mode"
    private const val KEY_FOLDER = "addon_mode_folder"
    private const val KEY_VERSION = "addon_mode_version"

    /** An `Interface/AddOns` folder and the game version its addons are for, when the folder name tells. */
    data class Folder(val addonsDir: File, val version: GameVersion?)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folder being managed, or null when the mode is off. */
    fun folder(context: Context): Folder? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        val path = prefs.getString(KEY_FOLDER, null) ?: return null
        return Folder(File(path), GameVersion.fromKey(prefs.getString(KEY_VERSION, null)))
    }

    fun enable(context: Context, addonsDir: File, version: GameVersion) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_FOLDER, addonsDir.absolutePath)
            .putString(KEY_VERSION, version.key)
            .apply()
    }

    /** Turns the mode off. The folder is remembered, so turning it back on starts from it. */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    /** The last folder used, kept after the mode is turned off. */
    fun lastFolder(context: Context): Folder? {
        val path = prefs(context).getString(KEY_FOLDER, null) ?: return null
        return Folder(File(path), prefs(context).getString(KEY_VERSION, null)?.let(GameVersion::fromKey))
    }

    /**
     * The addon folders [picked] stands for. Accepts the `AddOns` folder itself, `Interface`, a version
     * folder (`_retail_`, `_classic_era_`), or the game folder holding one or more of those. An `AddOns`
     * folder that doesn't exist yet is listed when its version folder does; it's created on first install.
     */
    fun resolve(picked: File): List<Folder> {
        val name = picked.name
        return when {
            name.equals("AddOns", ignoreCase = true) -> listOf(Folder(picked, versionOf(picked.parentFile?.parentFile)))
            name.equals("Interface", ignoreCase = true) -> listOf(Folder(child(picked, "AddOns"), versionOf(picked.parentFile)))
            versionOf(picked) != null || child(picked, "Interface").isDirectory -> listOf(Folder(addonsIn(picked), versionOf(picked)))
            else -> picked.listFiles { f -> f.isDirectory && versionOf(f) != null }.orEmpty()
                .sortedBy { it.name }
                .map { Folder(addonsIn(it), versionOf(it)) }
        }
    }

    /** The game version a version folder is for: from `.flavor.info` if it has one, else from its name. */
    fun versionOf(dir: File?): GameVersion? {
        dir ?: return null
        val code = runCatching { File(dir, ".flavor.info").readLines().getOrNull(1)?.trim() }.getOrNull()
        val product = WowProduct.fromCode(code) ?: WowProduct.entries.firstOrNull { it.flavorDir.equals(dir.name, ignoreCase = true) }
        return product?.let(GameVersion::of)
    }

    private fun addonsIn(versionDir: File) = child(child(versionDir, "Interface"), "AddOns")

    /** [name] inside [dir], matching an existing folder's case so a Windows copy's `Addons` is reused. */
    private fun child(dir: File, name: String): File =
        dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: File(dir, name)
}
