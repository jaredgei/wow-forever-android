package app.gamenative.wow.debug

import android.content.Context
import android.os.Process
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * With debug logging on: when Wine has printed nothing [DELAY_MS] after the game program started, logs every
 * other process running as the app and what each is waiting on, so a launch that hangs silently (issue #9)
 * shows which program is stuck and where.
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

    /** `ps` rows for this app's user other than the app itself: state, kernel wait channel and command line. */
    internal fun processes(): String = runCatching {
        val proc = ProcessBuilder("ps", "-A", "-o", "USER,PID,PPID,S,WCHAN,TIME,ARGS").redirectErrorStream(true).start()
        val lines = proc.inputStream.bufferedReader().use { it.readLines() }
        proc.waitFor()
        ownRows(lines, Process.myPid())
    }.getOrElse { "ps unavailable: ${it.message}" }

    /** The header and the rows whose user is the one running [myPid], without [myPid] itself. */
    internal fun ownRows(lines: List<String>, myPid: Int): String {
        val rows = lines.drop(1).map { it.trim().split(Regex("\\s+"), limit = 7) }.filter { it.size >= 2 }
        val user = rows.firstOrNull { it[1] == myPid.toString() }?.get(0) ?: return "app process not found in ps"
        val own = lines.drop(1).filter { it.trim().split(Regex("\\s+"), limit = 3).let { r -> r.size >= 2 && r[0] == user && r[1] != myPid.toString() } }
        return (listOfNotNull(lines.firstOrNull()) + own.ifEmpty { listOf("(none)") }).joinToString("\n")
    }
}
