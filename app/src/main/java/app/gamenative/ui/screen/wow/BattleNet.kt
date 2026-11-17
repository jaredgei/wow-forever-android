package app.gamenative.ui.screen.wow

import com.winlator.core.WineRegistryEditor
import java.io.File
import org.json.JSONObject

/** What a launch starts in the WoW container. */
enum class LaunchTarget {
    /** The WoW client itself, signed in through login.txt or the in-game login screen. */
    GAME,

    /**
     * The official Battle.net desktop app (x64, run through FEX in the same ARM64EC prefix). It signs in the way it does
     * on a PC and starts the game with a launcher token, so the client never takes the direct login path that Blizzard
     * blocks with BLZ51914400 on some devices. Experimental.
     */
    BATTLE_NET,
}

/**
 * The Battle.net desktop app inside the WoW container. The app ships no Battle.net files: the player downloads Blizzard's
 * official Windows installer to Download/[INSTALLER_NAME], which the container sees as D:\.
 */
object BattleNet {
    const val INSTALLER_NAME = "Battle.net-Setup.exe"

    /**
     * FEX build used for Battle.net: assets/fexcore/fexcore-2508-wowbox.tzst, WoW Box's FEX (MIT) built from
     * https://github.com/FrontMage/FEX at 7519b9605209e9e19eaf7be669dae7c1848fe5f1, archive sha256 2beba31f8d0f32c1…
     */
    const val FEX_VERSION = "2508-wowbox"

    /** Android's shared Download folder, mounted as D: (see the container's drives). */
    private const val DOWNLOAD_DIR = "/storage/emulated/0/Download"

    const val INSTALLER_WINDOWS_PATH = "D:\\$INSTALLER_NAME"
    /** The native ARM64 client Battle.net is expected to start (the regular launch runs the same file). */
    const val GAME_EXECUTABLE = "Wow-arm64.exe"

    const val LAUNCHER_WINDOWS_PATH = "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"

    private const val LAUNCHER_PREFIX_PATH = ".wine/drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe"

    /** Battle.net's own settings file in the Wine user's roaming profile. */
    private const val CONFIG_PREFIX_PATH = ".wine/drive_c/users/xuser/AppData/Roaming/Battle.net/Battle.net.config"

    /**
     * The Battle.net settings this app needs, written before Battle.net starts; every other setting is kept and the
     * file is left alone when both already hold.
     *  - HardwareAcceleration "false": the embedded Chromium window stays plain grey under Wine with GPU compositing
     *    on (first test on a phone), so the login page never shows.
     *  - GameLaunchWindowBehavior "0", keep the Battle.net window open when a game starts. With "2" (exit Battle.net
     *    completely) Battle.net is gone before the game reaches its login screen, about two minutes on a phone, and
     *    the launcher login fails. The app closes Battle.net itself once the game is up.
     */
    fun applyClientSettings(containerRoot: File) {
        val config = File(containerRoot, CONFIG_PREFIX_PATH)
        val json = runCatching { JSONObject(config.readText()) }.getOrNull() ?: JSONObject()
        val client = json.optJSONObject("Client") ?: JSONObject().also { json.put("Client", it) }
        val wanted = mapOf("HardwareAcceleration" to "false", "GameLaunchWindowBehavior" to "0")
        if (wanted.all { (key, value) -> client.optString(key) == value }) return
        wanted.forEach { (key, value) -> client.put(key, value) }
        config.parentFile?.mkdirs()
        config.writeText(json.toString(2))
    }

    private const val USER_REG_PREFIX_PATH = ".wine/user.reg"
    private const val DEBUG_KEY = "[Software\\\\Wine\\\\Debug]"
    private const val REPLACEMENTS_KEY = "[Software\\\\Wine\\\\Fonts\\\\Replacements]"
    private const val FONTS_PREFIX_PATH = ".wine/drive_c/windows/Fonts"
    private const val FONT_ASSETS = "battlenet_fonts"
    private const val SYSTEM_REG_PREFIX_PATH = ".wine/system.reg"
    private const val NT_FONTS_KEY = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
    private const val FONTS_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Fonts"

    /** Registry names for the fonts copied into C:\\windows\\Fonts, which is the only list DirectWrite reads. */
    private val REGISTERED_FONTS = listOf(
        "DejaVu Sans (TrueType)" to "DejaVuSans.ttf",
        "DejaVu Sans Bold (TrueType)" to "DejaVuSans-Bold.ttf",
        "DejaVu Sans Mono (TrueType)" to "DejaVuSansMono.ttf",
        "DejaVu Sans Mono Bold (TrueType)" to "DejaVuSansMono-Bold.ttf",
        "DejaVu Serif (TrueType)" to "DejaVuSerif.ttf",
        "DejaVu Serif Bold (TrueType)" to "DejaVuSerif-Bold.ttf",
        "Source Han Sans CN Regular (OpenType)" to "SourceHanSansCN-Regular.otf",
        "Source Han Sans KR Regular (OpenType)" to "SourceHanSansKR-Regular.otf",
        "Noto Sans Thai (TrueType)" to "NotoSansThai-Regular.ttf",
        "Noto Sans Arabic (TrueType)" to "NotoSansArabic-Regular.ttf",
        "Noto Sans Hebrew (TrueType)" to "NotoSansHebrew-Regular.ttf",
        "Noto Sans Devanagari (TrueType)" to "NotoSansDevanagari-Regular.ttf",
        "Noto Sans Symbols (TrueType)" to "NotoSansSymbols-Regular.ttf",
        "Noto Sans Symbols 2 (TrueType)" to "NotoSansSymbols2-Regular.ttf",
    )

    /**
     * Taken out of DirectWrite's list so the Tahoma replacement below applies there. Wine's Tahoma has only ~850
     * glyphs (no Cyrillic, most of Latin Extended), and Wine's DirectWrite fallback sends all Latin and Cyrillic text
     * to Tahoma only: a character Tahoma lacks made Chromium retry Tahoma, then "sans", one level deeper each time,
     * until the stack ran out (ninth test, +dwrite). GDI keeps Wine's real Tahoma from Wine's own font folder.
     */
    private val UNREGISTERED_FONTS = listOf("Tahoma (TrueType)", "Tahoma Bold (TrueType)")

    /**
     * Windows UI fonts Battle.net's Chromium asks for, and the open font Wine shows in their place. Wine only uses a
     * replacement when the real font isn't installed, so a prefix that has Arial keeps it.
     */
    private val FONT_REPLACEMENTS = listOf(
        // DirectWrite: Wine's fallback table names these families for whole scripts.
        "Tahoma" to "DejaVu Sans", "Tahoma Bold" to "DejaVu Sans", "sans" to "DejaVu Sans", "sans-serif" to "DejaVu Sans",
        // Wine's table names Microsoft Sans Serif for Thai only, but GDI programs ask for it for Latin text, and Noto
        // Sans Thai has no Latin letters, so it stays on DejaVu (no Thai). Every other script in the table that
        // Battle.net's languages, news or symbols can use maps to a font that has it (coverage checked per range).
        "Microsoft Sans Serif" to "DejaVu Sans", "Noto Sans Armenian" to "DejaVu Sans", "Noto Sans Georgian" to "DejaVu Sans",
        "Noto Sans NKo" to "DejaVu Sans", "Noto Sans Lao" to "DejaVu Sans", "Noto Sans Ogham" to "DejaVu Sans",
        "Noto Sans Tifinagh" to "DejaVu Sans", "Noto Sans Lisu" to "DejaVu Sans",
        "Noto Sans Symbols2" to "Noto Sans Symbols 2", "Noto Emoji" to "Noto Sans Symbols 2",
        "Segoe UI Emoji" to "Noto Sans Symbols 2", "Segoe UI Symbol" to "Noto Sans Symbols",
        "Microsoft YaHei" to "Source Han Sans CN", "Microsoft JhengHei" to "Source Han Sans CN",
        "Noto Sans CJK TC" to "Source Han Sans CN", "Meiryo" to "Source Han Sans CN", "Meiryo UI" to "Source Han Sans CN",
        "MS Gothic" to "Source Han Sans CN", "Yu Gothic" to "Source Han Sans CN", "Yu Gothic UI" to "Source Han Sans CN",
        "SimSun" to "Source Han Sans CN",
        "Malgun Gothic" to "Source Han Sans KR", "Gulim" to "Source Han Sans KR",
        "Leelawadee UI" to "Noto Sans Thai", "Nirmala UI" to "Noto Sans Devanagari",
        // Windows UI fonts Chromium asks for by name.
        "Segoe UI" to "DejaVu Sans", "Segoe UI Semibold" to "DejaVu Sans", "Segoe UI Light" to "DejaVu Sans",
        "Arial" to "DejaVu Sans", "Helvetica" to "DejaVu Sans", "Verdana" to "DejaVu Sans", "Calibri" to "DejaVu Sans",
        "MS Shell Dlg" to "DejaVu Sans", "MS Shell Dlg 2" to "DejaVu Sans",
        "Times New Roman" to "DejaVu Serif", "Georgia" to "DejaVu Serif",
        "Courier New" to "DejaVu Sans Mono", "Consolas" to "DejaVu Sans Mono",
    )

    /**
     * Gives the prefix real TrueType fonts for Battle.net. Its main thread recursed inside libcef's text code until
     * the 32-bit stack ran out (sixth and seventh tests): the deepest frame was Chromium's font render-settings lookup,
     * and the prefix only has Wine's Tahoma plus bitmap fonts. WoW Box, which gets Battle.net to its login screen,
     * ships DejaVu in C:\\windows\\Fonts. Copies DejaVu (free licence, see LICENSE-DejaVu.txt) there and maps the
     * Windows UI font names onto it. Returns what the Fonts folder holds afterwards, for the log.
     */
    fun installFonts(context: android.content.Context, containerRoot: File): List<String> {
        val fonts = File(containerRoot, FONTS_PREFIX_PATH).apply { mkdirs() }
        context.assets.list(FONT_ASSETS).orEmpty().forEach { name ->
            val target = File(fonts, name)
            if (!target.isFile) context.assets.open("$FONT_ASSETS/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        setUserRegSection(containerRoot, REPLACEMENTS_KEY, FONT_REPLACEMENTS.map { it.first to it.second })
        // DirectWrite, which Chromium's text code uses, only sees fonts listed in the registry, not files in the
        // Fonts folder. The prefix lists Tahoma under Z:\\opt\\wine, which this app's Wine doesn't use, so DirectWrite
        // had no fonts at all and the stack overflow moved into its FindFamilyName (eighth test).
        File(containerRoot, SYSTEM_REG_PREFIX_PATH).takeIf { it.isFile }?.let { reg ->
            WineRegistryEditor(reg).use { editor ->
                UNREGISTERED_FONTS.forEach { name ->
                    editor.removeValue(NT_FONTS_KEY, name)
                    editor.removeValue(FONTS_KEY, name)
                }
                REGISTERED_FONTS.forEach { (name, file) ->
                    if (File(fonts, file).isFile) {
                        editor.setStringValue(NT_FONTS_KEY, name, file)
                        editor.setStringValue(FONTS_KEY, name, file)
                    }
                }
            }
        }
        return fonts.list().orEmpty().sorted()
    }

    /**
     * Turns Wine's relay trace on or off for calls libcef.dll makes into the windowing DLLs. Only takes effect with
     * +relay in WINEDEBUG.
     */
    fun setRelayTrace(containerRoot: File, enabled: Boolean) = setUserRegSection(
        containerRoot,
        DEBUG_KEY,
        if (enabled) {
            listOf(
                "RelayFromInclude" to "libcef.dll",
                "RelayInclude" to "user32;gdi32;imm32;uxtheme;dwmapi;shcore;ole32;combase;shell32;comctl32",
            )
        } else {
            null
        },
    )

    /** Replaces one key's section in the prefix's user.reg with [values] (string values), or drops it for null. */
    private fun setUserRegSection(containerRoot: File, key: String, values: List<Pair<String, String>>?) {
        val reg = File(containerRoot, USER_REG_PREFIX_PATH)
        if (!reg.isFile) return
        val kept = StringBuilder()
        var skipping = false
        reg.readLines().forEach { line ->
            if (line.startsWith("[")) skipping = line.startsWith(key)
            if (!skipping) kept.append(line).append('\n')
        }
        if (values != null) {
            kept.append('\n').append(key).append(' ').append(System.currentTimeMillis() / 1000).append('\n')
            values.forEach { (name, value) -> kept.append('"').append(name).append("\"=\"").append(value).append("\"\n") }
        }
        reg.writeText(kept.toString())
    }

    /**
     * Points Battle.net's login at [portal]'s region (US, EU, KR; Taiwan logs in through KR). Battle.net otherwise
     * picks it from the container's Windows region, which is the US, and then registers WoW for the US: Blizzard's
     * Agent refused the phone's EU install with error 2800 (BLZBNTAGT00000AF0) while it was US. The login
     * screen no longer has a region picker. Returns true when the region changed (Battle.net may ask to log in again).
     */
    fun setLoginRegion(containerRoot: File, portal: String): Boolean {
        val region = when (portal.uppercase()) {
            "US", "EU", "KR", "CN" -> portal.uppercase()
            "TW" -> "KR"
            else -> return false
        }
        val config = File(containerRoot, CONFIG_PREFIX_PATH)
        val json = runCatching { JSONObject(config.readText()) }.getOrNull() ?: return false
        var changed = false
        // Battle.net doesn't read these at the top level; take out a copy written there by an early test build.
        json.optJSONObject("Services")?.let { top ->
            if (top.optString("LastLoginRegion") == region) { top.remove("LastLoginRegion"); changed = true }
            if (top.optString("LastLoginAddress") == "${region.lowercase()}.actual.battle.net") { top.remove("LastLoginAddress"); changed = true }
            if (top.length() == 0) json.remove("Services")
        }
        // Battle.net keeps its login settings per installation, under a hash key ("5a61123b37cafce1": {"Services":
        // {"LastLoginRegion": "US", "LastLoginAddress": "us.actual.battle.net", "LastLoginTassadar": ...}}). Only
        // the region and its address change; LastLoginTassadar (account.battle.net) is the same for every region.
        json.keys().asSequence().toList().forEach { key ->
            val services = json.optJSONObject(key)?.optJSONObject("Services") ?: return@forEach
            if (!services.has("LastLoginRegion") || services.optString("LastLoginRegion") == region) return@forEach
            services.put("LastLoginRegion", region)
            services.put("LastLoginAddress", "${region.lowercase()}.actual.battle.net")
            changed = true
        }
        if (changed) config.writeText(json.toString(2))
        return changed
    }

    /**
     * Drive H: is the game folder's parent, so Battle.net sees the game as a plain folder, H:/<name>. The Agent checks
     * the install folder's type and refuses anything that isn't a plain directory ("Found symlink while checking
     * permissions" -> Permissions check failure 2123 -> forced update): a C:/WoW Forever symlink and
     * G: (a drive root linked to shared storage) both failed. G: stays as it is for the game itself.
     */
    fun gameDrive(gameRoot: File): String = "H:${gameRoot.absoluteFile.parent}"

    /** An early test build kept the image in the prefix, inside drive Z:, where Wine judged it by Z: (NTFS). */
    private const val OLD_H_DEVICE_IMAGE_PREFIX_PATH = ".wine/h_drive_fat32.img"
    /** Name the preload shim looks for (app/src/main/cpp/gamenative_fuse_chmod.c). */
    private const val H_DEVICE_IMAGE_NAME = "h_drive_fat32.img"
    private const val H_DEVICE_LINK_PREFIX_PATH = ".wine/dosdevices/h::"

    /**
     * Makes Wine report drive H: as FAT32. The Agent reads GetVolumeInformation's flags for the install folder and,
     * when FILE_PERSISTENT_ACLS is missing (a FAT/exFAT drive on Windows), skips its permission check altogether
     * ("Skipping '...' since path doesn't support preserving permissions"; Agent 9873: 0x4b7b9d, 0x4c0bac). Wine's
     * permissions can't satisfy that check on any drive (error 2123 on H:), and Android's shared
     * storage keeps no Windows permissions anyway. Wine's mount manager takes a drive's file system from the boot
     * sector of its "device" (dosdevices/h::, dlls/mountmgr.sys/device.c: VOLUME_ReadFATSuperblock), so h:: points
     * at a 2 KiB file holding just a FAT32 boot sector; the drive's files stay where they are.
     * GetVolumeInformation itself queries that device file, and ntdll judges a file outside every Wine drive by
     * fstatfs(), so the image lives in the app's files folder (outside imagefs, so outside Z:) and the preload shim
     * reports FAT for that one file (inside Z: it still came out as NTFS).
     */
    fun markGameDriveFat32(context: android.content.Context, containerRoot: File): String {
        File(containerRoot, OLD_H_DEVICE_IMAGE_PREFIX_PATH).delete()
        val image = File(context.filesDir, H_DEVICE_IMAGE_NAME)
        val bytes = ByteArray(2048).also { b ->
            fun word(off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte() }
            fun long(off: Int, v: Long) { for (i in 0..3) b[off + i] = (v shr (8 * i)).toByte() }
            b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
            "MSDOS5.0".toByteArray().copyInto(b, 3)
            word(0x0B, 512)          // bytes per sector
            b[0x0D] = 64             // sectors per cluster (32 KiB clusters)
            word(0x0E, 32)           // reserved sectors
            b[0x10] = 2              // FATs
            b[0x15] = 0xF8.toByte()  // media: fixed disk
            long(0x20, 0x40000000L)  // total sectors (512 GiB)
            long(0x24, 0x10000L)     // sectors per FAT
            long(0x2C, 2)            // root directory cluster
            b[0x40] = 0x80.toByte()  // drive number
            b[0x42] = 0x29           // extended boot signature
            long(0x43, 0x57465245L)  // volume serial
            "WOW FOREVER".toByteArray().copyInto(b, 0x47)
            "FAT32   ".toByteArray().copyInto(b, 0x52)
            b[0x1FE] = 0x55; b[0x1FF] = 0xAA.toByte()
        }
        val notes = mutableListOf<String>()
        if (!image.isFile || !image.readBytes().contentEquals(bytes)) { image.writeBytes(bytes); notes += "image written" }
        val link = File(containerRoot, H_DEVICE_LINK_PREFIX_PATH).toPath()
        val target = image.absoluteFile.toPath()
        if (java.nio.file.Files.isSymbolicLink(link) && java.nio.file.Files.readSymbolicLink(link) == target) {
            notes += "h:: already set"
        } else {
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.createSymbolicLink(link, target)
            notes += "h:: linked"
        }
        return notes.joinToString()
    }
    fun gameInstallPath(gameRoot: File): String = "H:/${gameRoot.absoluteFile.name}"

    /** One top-level protobuf field: its number, and where it starts (tag) and ends in the buffer. */
    private class ProtoField(val number: Int, val start: Int, val end: Int, val payload: ByteArray?) {
        companion object {
            /** settings (field 3) -> install_path (field 1) of a ProductInstall, or null. */
            fun installPathOf(productInstall: ByteArray): String? = runCatching {
                val settings = parse(productInstall).firstOrNull { it.number == 3 }?.payload ?: return null
                parse(settings).firstOrNull { it.number == 1 }?.payload?.toString(Charsets.UTF_8)
            }.getOrNull()

            fun parse(b: ByteArray): List<ProtoField> {
                val out = mutableListOf<ProtoField>()
                var i = 0
                fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); s += 7; if (c < 0x80) return r } }
                while (i < b.size) {
                    val start = i
                    val key = varint()
                    val number = (key ushr 3).toInt()
                    var payload: ByteArray? = null
                    when ((key and 7).toInt()) {
                        0 -> varint()
                        1 -> i += 8
                        2 -> { val len = varint().toInt(); payload = b.copyOfRange(i, i + len); i += len }
                        5 -> i += 4
                        else -> error("unsupported wire type in product.db")
                    }
                    require(i <= b.size) { "truncated product.db" }
                    out += ProtoField(number, start, i, payload)
                }
                return out
            }
        }
    }

    /**
     * Writes the game folder's .product.db (plus Launcher.db and .patch.result) when it has none, so Battle.net's
     * "Locate the game" can register it. Agent.exe builds the install list for a modern product only from
     * "<folder>/.product.db" (Agent 9873: 0x4be322 -> 0x482f9d -> 0x475642, "Failed to read db info from ...") and
     * answers 2800 when that list is empty; the app's own download has no such file. The layout copies a real
     * Battle.net-installed WoW Forever folder's .product.db field for field; version, build key, tags and region come
     * from this folder's .build.info. An existing file is kept when it already names this folder (once registered,
     * the Agent owns it) or can't be read; one that names another place, such as the PC a copied game came from, is
     * replaced, because the Agent would otherwise look for the game there.
     */
    fun writeProductDb(gameRoot: File): String {
        val db = File(gameRoot, ".product.db")
        if (db.exists()) {
            val path = ProtoField.installPathOf(db.readBytes()) ?: return "kept (unreadable)"
            if (samePath(path, gameInstallPath(gameRoot))) return "kept"
        }
        val lines = File(gameRoot, ".build.info").readLines().filter { it.isNotBlank() }
        val header = lines.first().split('|').map { it.substringBefore('!') }
        val row = lines.drop(1).map { it.split('|') }.firstOrNull { it.getOrNull(header.indexOf("Active")) == "1" }
            ?: error("no active build in .build.info")
        fun col(name: String) = row.getOrNull(header.indexOf(name)).orEmpty()
        val product = col("Product").ifEmpty { "wowf" }
        val buildKey = col("Build Key")
        val pb = Proto().apply {
            str(1, product)
            str(2, product)
            msg(3, Proto().apply {
                str(1, gameInstallPath(gameRoot))
                str(2, col("Branch").ifEmpty { "eu" })
                int(3, 2); int(4, 2); int(5, 3)
                str(6, "enUS"); str(7, "enUS")
                msg(8, Proto().apply { str(1, "enUS"); int(2, 3) })
                msg(9, Proto()); msg(10, Proto())
                str(13, "_forever_")
            })
            msg(4, Proto().apply {
                msg(1, Proto().apply {
                    int(1, 1); int(2, 1); int(3, 1); int(4, 0); int(5, 0)
                    str(7, col("Version"))
                    msg(10, Proto())
                    str(12, buildKey); str(14, buildKey)
                    str(17, col("Tags"))
                    int(21, System.currentTimeMillis())
                })
            })
            str(6, "wow")
            int(7, 0)
            str(8, "{\"user_install_package_settings\":[]}")
        }
        db.writeBytes(pb.bytes())
        File(gameRoot, "Launcher.db").takeUnless { it.exists() }?.writeText("enUS")
        File(gameRoot, ".patch.result").takeUnless { it.exists() }?.writeText("0")
        return "written (${col("Version")}, $buildKey)"
    }

    /** Windows paths compare without case, slash direction or a trailing slash. */
    private fun samePath(a: String, b: String): Boolean {
        fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    /** Minimal protobuf writer: varints and length-delimited fields are all .product.db uses. */
    private class Proto {
        private val out = java.io.ByteArrayOutputStream()
        private fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun int(field: Int, v: Long) { varint((field shl 3).toLong()); varint(v) }
        fun int(field: Int, v: Int) = int(field, v.toLong())
        fun bytes(field: Int, b: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Proto) = bytes(field, m.bytes())
        fun bytes(): ByteArray = out.toByteArray()
    }

    fun installerFile(downloadDir: File = File(DOWNLOAD_DIR)): File = File(downloadDir, INSTALLER_NAME)

    fun isInstalled(containerRoot: File?): Boolean = containerRoot != null && File(containerRoot, LAUNCHER_PREFIX_PATH).isFile

    /**
     * The Windows path to start: Battle.net once it is installed in the prefix, else the installer from Download.
     * Null when neither exists, so the caller can tell the player where to put the installer.
     */
    fun executable(containerRoot: File?, downloadDir: File = File(DOWNLOAD_DIR)): String? = when {
        isInstalled(containerRoot) -> LAUNCHER_WINDOWS_PATH
        installerFile(downloadDir).isFile -> INSTALLER_WINDOWS_PATH
        else -> null
    }
}
