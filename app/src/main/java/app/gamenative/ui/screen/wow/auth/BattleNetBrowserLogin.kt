package app.gamenative.ui.screen.wow.auth

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.BattleNetSignIn
import app.gamenative.ui.screen.wow.GameRegion
import app.gamenative.ui.screen.wow.WowClientDownloader
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Opt-in browser authentication. Tokens never enter game files, arguments, preferences, or logs. */
internal object BattleNetBrowserLogin {
    private const val PREFS = "battle_net_browser_login"
    private const val ASSET = "wow-session-bridge.exe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUi = MutableStateFlow<Ui>(Ui.Idle)
    val ui = mutableUi.asStateFlow()
    private var active: Attempt? = null

    sealed interface Ui {
        data object Idle : Ui
        class Waiting(val text: String) : Ui
        // Deliberately no generated toString: a challenge URL is sensitive.
        class Browser(val url: String) : Ui
        class Failed(val text: String) : Ui
    }

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /** Runs after Wine prefix initialization. Container executable remains the game for window tracking. */
    @Synchronized
    fun start(context: Context, container: Container): String {
        cancel()
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("G", ignoreCase = true) }?.let { File(it[1]) }
            ?: throw IOException("Cannot locate the game drive for browser login")
        val build = WowClientDownloader.readBuildInfo(File(gameRoot, WowClientDownloader.BUILD_INFO))
            ?.get("Version")?.substringAfterLast('.')?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Cannot read the installed game build for browser login")
        val region = GameRegion.current(context, gameRoot.path)
        BattleNetSignIn.removeLoginFile(gameRoot)
        check(!File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/login.txt").exists()) {
            "Remove the old login.txt before using browser login"
        }

        val directory = File(container.rootDir, ".wine/drive_c/wow-session")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare browser login" }
        // The prefix is in Android's app-private directory, never shared game storage.
        val helper = File(directory, ASSET)
        context.assets.open(ASSET).use { input -> helper.outputStream().use { input.copyTo(it) } }
        val bridge = SessionBridge()
        val request = File(directory, "request-${UUID.randomUUID()}.txt")
        try {
            request.writeText(bridge.config, Charsets.US_ASCII)
            request.setReadable(false, false)
            request.setWritable(false, false)
            check(request.setReadable(true, true) && request.setWritable(true, true)) {
                "Cannot protect the browser login request"
            }
            val attempt = Attempt(bridge, request, "${region.name}.actual.battle.net", build)
            active = attempt
            mutableUi.value = Ui.Waiting("Starting Battle.net browser login…")
            // One deadline covers Wine startup, queues and browser interaction, below the helper's 15 minutes.
            attempt.watchdog = scope.launch {
                delay(10 * 60_000L)
                fail(attempt, "Browser login timed out. Please try again.")
            }
            attempt.job = scope.launch { run(attempt) }
            // These arguments contain paths and public switches only. The token travels over the authenticated socket.
            return "\"C:\\wow-session\\$ASSET\" \"C:\\wow-session\\${request.name}\" " +
                "\"${container.executablePath}\" -launcherlogin -uid ${WowClientDownloader.TARGET_PRODUCT} -d3d11"
        } catch (error: Exception) {
            bridge.close()
            request.delete()
            throw error
        }
    }

    private suspend fun run(attempt: Attempt) {
        try {
            attempt.bridge.accept().use { peer ->
                val device = peer.device().also { attempt.device.set(it) }
                validateDevice(device)
                val url = withClient(attempt) { client ->
                    client.connect()
                    client.webChallenge(device, attempt.build)
                }
                publish(attempt, Ui.Browser(url))
                val ticket = withTimeout(15 * 60_000L) { attempt.ticket.await() }
                try {
                    publish(attempt, Ui.Waiting("Completing Battle.net sign-in…"))
                    // A fresh connection avoids holding a BGS socket open while the user handles CAPTCHA or 2FA.
                    val token = withClient(attempt) { client ->
                        client.connect()
                        client.webChallenge(device, attempt.build)
                        client.verifyAndGenerateGameToken(ticket)
                    }
                    try {
                        publish(attempt, Ui.Waiting("Starting WoW with the Battle.net session…"))
                        peer.launch(token)
                    } finally {
                        token.fill(0)
                    }
                } finally {
                    ticket.fill(0)
                    device.fill(0)
                }
                publish(attempt, Ui.Idle)
            }
        } catch (_: TimeoutCancellationException) {
            fail(attempt, "Browser login timed out. Please try again.")
        } catch (_: CancellationException) {
            // User cancellation does not turn into a password-login attempt.
        } catch (error: BgsException) {
            fail(attempt, error.message ?: "Battle.net sign-in failed.")
        } catch (_: Exception) {
            // Never expose provider payloads, redirect URLs, credentials or arbitrary peer output.
            fail(attempt, "Browser sign-in could not start the game. You can retry or switch back to password login in the launcher.")
        } finally {
            attempt.close()
            synchronized(this) { if (active === attempt) active = null }
        }
    }

    private fun <T> withClient(attempt: Attempt, action: (BgsClient) -> T): T {
        // The bridge reports the real x64 Wine context; do not fabricate ARM64 CPU fields.
        val client = BgsClient(attempt.host, platform = "Wn64")
        attempt.client.set(client)
        try {
            if (attempt.cancelled) throw CancellationException()
            return action(client)
        } finally {
            attempt.client.compareAndSet(client, null)
            client.close()
        }
    }

    private fun validateDevice(device: ByteArray) {
        val expected = setOf("RGKY", "CPGE", "ULNG", "SLNG", "CNME", "UNME", "UTCO", "CARC", "CREV", "CLVL",
            "PMEM", "PSZE", "OVER", "CVRA", "CFTC", "CFTD", "CEFC", "CEFD", "CBRD", "CVEN", "ANME", "ADSC", "MAC")
        val json = JSONObject(device.toString(Charsets.UTF_8))
        check(json.keys().asSequence().toSet() == expected) { "Invalid device description" }
        expected.forEach { check(json.getLong(it) in 0..0xffff_ffffL) { "Invalid device description" } }
    }

    @Synchronized
    private fun publish(attempt: Attempt, state: Ui) {
        if (active === attempt && !attempt.cancelled) mutableUi.value = state
    }

    @Synchronized
    fun submit(browser: Ui.Browser, ticket: ByteArray) {
        val attempt = active
        if (mutableUi.value !== browser || attempt == null || attempt.ticket.isCompleted) {
            ticket.fill(0)
        } else {
            attempt.ticketBytes.set(ticket)
            attempt.ticket.complete(ticket)
        }
    }

    @Synchronized
    fun cancel(preserveFailure: Boolean = false) {
        active?.let { attempt ->
            attempt.cancelled = true
            attempt.close()
            attempt.job?.cancel()
        }
        active = null
        if (!preserveFailure || mutableUi.value !is Ui.Failed) mutableUi.value = Ui.Idle
    }

    @Synchronized
    fun abort(state: Ui) {
        if (mutableUi.value !== state) return
        cancel()
        // Closing the control socket alone cannot stop a helper that has already received the token.
        PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
    }

    @Synchronized
    fun webError(browser: Ui.Browser, message: String) {
        if (mutableUi.value === browser) active?.let { fail(it, message) }
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String) {
        if (active !== attempt || attempt.cancelled || mutableUi.value is Ui.Idle) return
        val failure = Ui.Failed(message)
        mutableUi.value = failure
        cancel(preserveFailure = true)
        scope.launch(Dispatchers.Main.immediate) {
            synchronized(this@BattleNetBrowserLogin) {
                // A queued failure must not tear down a newer attempt or a game the user has since launched.
                if (mutableUi.value === failure) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
            }
        }
    }

    @Synchronized
    fun guestExited() {
        val attempt = active
        if (attempt != null && mutableUi.value !is Ui.Idle && mutableUi.value !is Ui.Failed) {
            mutableUi.value = Ui.Failed("The Wine login helper stopped before sign-in completed.")
        }
        cancel(preserveFailure = true)
    }

    private class Attempt(val bridge: SessionBridge, val request: File, val host: String, val build: Int) {
        val ticket = CompletableDeferred<ByteArray>()
        val client = AtomicReference<BgsClient?>()
        val ticketBytes = AtomicReference<ByteArray?>()
        val device = AtomicReference<ByteArray?>()
        @Volatile var cancelled = false
        var job: Job? = null
        var watchdog: Job? = null
        fun close() {
            runCatching { client.getAndSet(null)?.close() }
            runCatching { bridge.close() }
            watchdog?.cancel()
            ticketBytes.getAndSet(null)?.fill(0)
            device.getAndSet(null)?.fill(0)
            request.delete()
        }
    }
}

@Composable
internal fun BattleNetBrowserLoginHost() {
    val state by BattleNetBrowserLogin.ui.collectAsStateWithLifecycle()
    when (val current = state) {
        BattleNetBrowserLogin.Ui.Idle -> Unit
        is BattleNetBrowserLogin.Ui.Browser -> BattleNetWebDialog(
            url = current.url,
            onTicket = { BattleNetBrowserLogin.submit(current, it) },
            onCancel = { BattleNetBrowserLogin.abort(current) },
            onError = { BattleNetBrowserLogin.webError(current, it) },
        )
        else -> AlertDialog(
            onDismissRequest = { BattleNetBrowserLogin.abort(current) },
            title = { Text("Battle.net browser login") },
            text = { Text(when (current) {
                is BattleNetBrowserLogin.Ui.Waiting -> current.text
                is BattleNetBrowserLogin.Ui.Failed -> current.text
                else -> ""
            }) },
            confirmButton = {
                TextButton(onClick = { BattleNetBrowserLogin.abort(current) }) {
                    Text(if (current is BattleNetBrowserLogin.Ui.Failed) "Close" else "Cancel")
                }
            },
        )
    }
}
