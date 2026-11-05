package app.gamenative.wow.debug

import android.content.Context
import android.os.Process
import java.io.File
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * With debug logging on: when Wine has printed nothing [DELAY_MS] after the game program started, logs every
 * other process running as the app and what state it's in, so a launch that hangs silently (issue #9) shows
 * which program is stuck. Read from /proc, since `ps` doesn't list them on every device.
 */
object LaunchProbe {
    private const val DELAY_MS = 30_000L

    @Volatile
    private var launch = 0

    @Volatile
    private var outputSeen = false

    /** Called for each line of Wine's output. */
    @JvmStatic
    fun onOutput() {
        outputSeen = true
    }

    @JvmStatic
    fun watch(context: Context, pid: Int) {
        if (!DebugLog.isEnabled(context)) return
        val id = ++launch
        outputSeen = false
        Timber.i("Game program started as process $pid")
        thread(isDaemon = true, name = "launch-probe") {
            Thread.sleep(DELAY_MS)
            if (id != launch || outputSeen) return@thread
            Timber.w("No output from Wine ${DELAY_MS / 1000} s after the game program started. The app's other processes:\n${processes()}")
        }
    }

    /** One line per process running as this app, other than the app itself. */
    internal fun processes(proc: File = File("/proc"), myUid: Int = Process.myUid(), myPid: Int = Process.myPid()): String = runCatching {
        val rows = proc.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
            .sortedBy { it.name.toInt() }
            .mapNotNull { dir -> describe(dir, myUid, myPid) }
        rows.ifEmpty { listOf("(none found in ${proc.listFiles()?.size ?: 0} /proc entries)") }.joinToString("\n")
    }.getOrElse { "Couldn't read /proc: $it" }

    private fun describe(dir: File, myUid: Int, myPid: Int): String? {
        val pid = dir.name.toInt()
        if (pid == myPid) return null
        val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return null
        fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim().orEmpty()
        if (field("Uid").split(Regex("\\s+")).firstOrNull() != myUid.toString()) return null
        val cmdline = runCatching { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim() }.getOrDefault("")
        val wchan = runCatching { File(dir, "wchan").readText().trim() }.getOrDefault("").takeUnless { it.isEmpty() || it == "0" }
        val threads = runCatching { threadStates(File(dir, "task")) }.getOrDefault("")
        val syscall = runCatching { File(dir, "syscall").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        return "pid $pid ppid ${field("PPid")} ${field("Name")} state ${field("State")} threads ${field("Threads")}$threads" +
            (wchan?.let { " wchan $it" } ?: "") + " | $cmdline" +
            (syscall?.let { "\n    syscall: $it" } ?: "") +
            loadedFiles(File(dir, "maps")).let { files -> if (files.isEmpty()) "" else "\n    files mapped, by address: " + files.joinToString(", ") }
    }

    /**
     * The files mapped into a process, once each, in address order (not load order): for a program stuck at
     * start-up this shows which libraries it got as far as loading. Paths are shortened to their file names.
     */
    internal fun loadedFiles(maps: File): List<String> = runCatching {
        maps.readLines().mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 6).getOrNull(5)?.takeIf { it.startsWith("/") } }
            .map { it.substringAfterLast('/') }
            .distinct()
    }.getOrDefault(emptyList())

    /** Counts of the threads' states, e.g. " (S 12, R 1)": all asleep is a wait, R or D is busy or stuck in the kernel. */
    private fun threadStates(task: File): String {
        val states = task.listFiles().orEmpty().mapNotNull { t ->
            runCatching { File(t, "stat").readText().substringAfterLast(')').trim().firstOrNull() }.getOrNull()
        }
        if (states.isEmpty()) return ""
        return states.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ", " (", ")") { "${it.key} ${it.value}" }
    }
}
