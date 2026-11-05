package app.gamenative.wow.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import timber.log.Timber

/**
 * Settings, Share debug report: one zip with a summary of the device and settings, the app's logs,
 * the game's own logs and the controller test. Emails, the saved login and other [secrets] are
 * replaced before anything is written, so the zip is safe to attach to a GitHub issue.
 */
object DebugReport {
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_LOGCAT_CHARS = 3_000_000
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val TEXT_FILES = setOf("txt", "log")

    /** Replaces each secret and every email address. Secrets shorter than 4 characters are left alone. */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "[redacted]") }
        return EMAIL.replace(out, "[email]")
    }

    /** Builds the zip in the cache folder. [summary] is "label: value" lines from the caller's state. */
    fun build(context: Context, summary: List<String>, gameFolder: File?, secrets: Collection<String>): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "debug_report").apply { deleteRecursively(); mkdirs() }
        val zip = File(dir, "foreverywhere-report-$stamp.zip")
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(redact(text, secrets).toByteArray())
                out.closeEntry()
            }
            fun putFile(name: String, file: File) {
                if (file.isFile) runCatching { put(name, tail(file)) }.onFailure { Timber.w(it, "Report skipped $name") }
            }

            put("summary.txt", summaryText(summary))
            put("logcat.txt", logcat())
            putFile("app.log", File(DebugLog.dir(context), "app.log"))
            putFile("app.log.1", File(DebugLog.dir(context), "app.log.1"))
            putFile("wine_debug.log", File(external, "wine_logs/wine_debug.log"))
            newest(File(external, "crash_logs"), 5).forEach { putFile("crash_logs/${it.name}", it) }
            gameFolder?.let { game ->
                newest(File(game, "Logs"), 8).forEach { putFile("game/Logs/${it.name}", it) }
                newest(File(game, "Errors"), 5).forEach { putFile("game/Errors/${it.name}", it) }
            }
        }
        return zip
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "WoW Foreverywhere debug report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share debug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun summaryText(summary: List<String>) = buildString {
        appendLine("WoW Foreverywhere ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"
        appendLine("Chip: $soc, hardware ${Build.HARDWARE}")
        summary.forEach { appendLine(it) }
        appendLine()
        appendLine("Input devices")
        runCatching { InputRouting.devices().forEach { appendLine(InputRouting.describe(it)) } }
        if (ControllerTestLog.presses.isNotEmpty()) {
            appendLine()
            append(ControllerTestLog.report(emptyList()).substringBefore("\nInput devices"))
        }
    }

    /**
     * The app's part of the system log: every process running as this app, so Wine and the processes it starts
     * are included, as are Android's denials of them and an earlier run of the app that's still in the buffer.
     * Apps can read their own lines without any permission. Older logcats without `--uid` get this process only.
     */
    private fun logcat(): String = runCatching {
        val (status, text) = runLogcat("--uid", Process.myUid().toString())
        val output = if (status == 0) text else runLogcat("--pid", Process.myPid().toString()).second
        output.takeLast(MAX_LOGCAT_CHARS)
    }.getOrElse { "logcat unavailable: ${it.message}" }

    private fun runLogcat(vararg filter: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + filter).redirectErrorStream(true).start()
        val text = proc.inputStream.use { String(it.readBytes()) }
        return proc.waitFor() to text
    }

    private fun newest(dir: File, count: Int): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in TEXT_FILES }.orEmpty().sortedByDescending { it.lastModified() }.take(count)

    /** The last [MAX_FILE_BYTES] of a file: the end of a log is where a problem shows. */
    private fun tail(file: File): String = RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - MAX_FILE_BYTES).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        (if (start > 0) "[earlier lines left out]\n" else "") + String(bytes)
    }
}
