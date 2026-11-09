package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Detects Blizzard's account-security lock on a sign-in.
 *
 * When an account signs in from a device or network Blizzard doesn't recognise, the password is accepted (the SRP step
 * succeeds) but the logon form submission is answered with status 14400 and the game only shows BLZ51914400. Blizzard
 * emails a "suspicious login" warning, but the game has no way to show the verification. Signing in to battle.net once
 * from the same device and network lifts the lock, so the launcher points the player there instead of at a bare error.
 */
object BattleNetLoginLock {
    const val LOCK_STATUS = "(14400)"

    /** Battle.net account sign-in. It redirects to the account's own region. */
    const val VERIFY_URL = "https://account.battle.net/"

    enum class LoginResult { OK, LOCKED, FAILED }

    private const val FORM_RESPONSE = "logon form submission response received"
    private const val FATAL_ERROR = "Fatal login error received"
    private val RESULT = Regex("""result="([^"]*)"""")

    private const val PREFS = "battle_net_login_lock"
    private const val KEY_HANDLED_MTIME = "handled_log_mtime"
    private const val WATCH_INTERVAL_MS = 2_000L
    private const val WATCH_DURATION_MS = 20 * 60_000L

    @Volatile
    private var watcher: Job? = null

    fun connectionLog(gameRoot: File) = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Logs/Connection.log")

    /** The outcome of the most recent sign-in attempt in a Connection.log excerpt, or null if it has none. */
    fun lastLoginResult(log: String): LoginResult? {
        for (line in log.lineSequence().toList().asReversed()) {
            val isResponse = line.contains(FORM_RESPONSE)
            if (!isResponse && !line.contains(FATAL_ERROR)) continue
            val result = RESULT.find(line)?.groupValues?.get(1).orEmpty()
            return when {
                result.contains(LOCK_STATUS) -> LoginResult.LOCKED
                isResponse && result.startsWith("ERROR_OK") -> LoginResult.OK
                else -> LoginResult.FAILED
            }
        }
        return null
    }

    /** True when the last sign-in in the game's log hit the lock and the player hasn't dealt with it yet. */
    fun isLocked(context: Context, gameRoot: File): Boolean = runCatching {
        val log = connectionLog(gameRoot)
        if (!log.isFile || log.lastModified() <= prefs(context).getLong(KEY_HANDLED_MTIME, 0L)) return false
        lastLoginResult(log.readText()) == LoginResult.LOCKED
    }.getOrElse {
        Timber.w(it, "Couldn't read Connection.log")
        false
    }

    /** Stops [isLocked] reporting the lock already in the log, after the player verified or dismissed it. */
    fun markHandled(context: Context, gameRoot: File) {
        val mtime = connectionLog(gameRoot).takeIf { it.isFile }?.lastModified() ?: System.currentTimeMillis()
        prefs(context).edit().putLong(KEY_HANDLED_MTIME, mtime).apply()
    }

    /**
     * Watches the log while the game runs and tells the player what to do as soon as a sign-in hits the lock. Only
     * lines written after launch count, so a lock left over from an earlier session doesn't trigger it.
     */
    fun startWatcher(gameRoot: File) {
        watcher?.cancel()
        val log = connectionLog(gameRoot)
        var offset = if (log.isFile) log.length() else 0L
        watcher = CoroutineScope(Dispatchers.IO).launch {
            val deadline = System.currentTimeMillis() + WATCH_DURATION_MS
            val fresh = StringBuilder()
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(WATCH_INTERVAL_MS)
                val text = runCatching {
                    if (!log.isFile) return@runCatching ""
                    // The game started a new log, so read it from the start.
                    if (log.length() < offset) {
                        offset = 0L
                        fresh.clear()
                    }
                    val bytes = readFrom(log, offset)
                    offset += bytes.size
                    String(bytes)
                }.getOrDefault("")
                if (text.isEmpty()) continue
                fresh.append(text)
                when (lastLoginResult(fresh.toString())) {
                    LoginResult.LOCKED -> {
                        Timber.w("Battle.net sign-in hit the account-security lock (14400)")
                        SnackbarManager.show(
                            "Blizzard blocked this sign-in as unrecognised (BLZ51914400). Open the menu, tap Exit, " +
                                "then tap Verify Login in the launcher.",
                        )
                        return@launch
                    }
                    LoginResult.OK -> return@launch
                    else -> Unit
                }
            }
        }
    }

    private fun readFrom(file: File, offset: Long): ByteArray = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() <= offset) return ByteArray(0)
        raf.seek(offset)
        ByteArray((raf.length() - offset).coerceAtMost(1L shl 20).toInt()).also(raf::readFully)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
