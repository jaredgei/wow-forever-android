package app.gamenative.ui.screen.wow.auth

import android.app.Application
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import com.winlator.container.Container
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Starts the real coordinator and its loopback listener, but never a Wine helper or BGS connection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BattleNetBrowserLoginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun reset() {
        BattleNetBrowserLogin.cancel()
    }

    @After
    fun closeOutstandingAttempt() {
        BattleNetBrowserLogin.cancel()
    }

    @Test
    fun callbackFromPreviousAttemptCannotAbortItsReplacement() {
        val first = container("first")
        BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), first)
        val staleUi = BattleNetBrowserLogin.ui.value
        val firstRequest = request(first)

        val second = container("second")
        BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), second)
        val currentUi = BattleNetBrowserLogin.ui.value
        val secondRequest = request(second)
        var forcedClose = false
        val listener: (AndroidEvent.ForceCloseApp) -> Unit = { forcedClose = true }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(listener)
        try {
            BattleNetBrowserLogin.abort(staleUi)

            assertSame(currentUi, BattleNetBrowserLogin.ui.value)
            assertTrue(secondRequest.isFile)
            assertFalse(firstRequest.exists())
            assertFalse(forcedClose)
        } finally {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(listener)
        }
    }

    @Test
    fun staleBrowserTicketIsWipedWithoutChangingCurrentAttempt() {
        val container = container("current")
        BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val currentUi = BattleNetBrowserLogin.ui.value
        val staleBrowser = BattleNetBrowserLogin.Ui.Browser("https://eu.battle.net/login/")
        val ticket = "not-a-real-session".toByteArray()

        BattleNetBrowserLogin.submit(staleBrowser, ticket)
        BattleNetBrowserLogin.webError(staleBrowser, "An old page failed")

        assertArrayEquals(ByteArray(ticket.size), ticket)
        assertSame(currentUi, BattleNetBrowserLogin.ui.value)
        assertTrue(request(container).isFile)
    }

    @Test
    fun unexpectedGuestExitPreservesErrorAcrossAutomaticCleanup() {
        val container = container("exited")
        val launch = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val request = request(container)
        val port = request.readLines().first().toInt()

        launch.onExit(53)
        val failure = BattleNetBrowserLogin.ui.value
        assertTrue(failure is BattleNetBrowserLogin.Ui.Failed)
        assertTrue((failure as BattleNetBrowserLogin.Ui.Failed).text.contains("status 53"))
        BattleNetBrowserLogin.cancel(preserveFailure = true)
        launch.onExit(53)

        assertSame(failure, BattleNetBrowserLogin.ui.value)
        assertFalse(request.exists())
        assertListenerClosed(port)
        assertEquals(1, exitLines(container).size)
        assertTrue(exitLines(container).single().endsWith("Wine launch exit status: 53"))

        BattleNetBrowserLogin.cancel()
        assertSame(BattleNetBrowserLogin.Ui.Idle, BattleNetBrowserLogin.ui.value)
    }

    @Test
    fun zeroExitBeforeSignInIsStillAPrematureExit() {
        val container = container("zero-exit")
        val launch = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)

        launch.onExit(0)

        assertTrue(BattleNetBrowserLogin.ui.value is BattleNetBrowserLogin.Ui.Failed)
        assertTrue(exitLines(container).single().endsWith("Wine launch exit status: 0"))
    }

    @Test
    fun launchFailureStatusIsPreservedVerbatim() {
        val container = container("launch-failure")
        val launch = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)

        launch.onExit(-1)

        assertTrue(BattleNetBrowserLogin.ui.value is BattleNetBrowserLogin.Ui.Failed)
        assertTrue(exitLines(container).single().endsWith("Wine launch exit status: -1"))
    }

    @Test
    fun lateExitAfterCancellationIsLoggedWithoutChangingIdleState() {
        val container = container("cancelled-exit")
        val launch = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        BattleNetBrowserLogin.cancel(preserveFailure = true)
        assertTrue(exitLines(container).isEmpty()) // Screen cleanup is not a process-exit event.

        launch.onExit(143)

        assertSame(BattleNetBrowserLogin.Ui.Idle, BattleNetBrowserLogin.ui.value)
        assertTrue(exitLines(container).single().endsWith("Wine launch exit status: 143"))
    }

    @Test
    fun lateExitIsAttributedToItsOriginalAttemptWithoutCancellingRetry() {
        val container = container("retry")
        val first = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val firstRequest = request(container)
        val firstId = firstRequest.name.removePrefix("request-").removeSuffix(".txt")
        val second = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val currentUi = BattleNetBrowserLogin.ui.value
        val secondRequest = request(container)
        val secondId = secondRequest.name.removePrefix("request-").removeSuffix(".txt")

        first.onExit(53)
        first.onExit(53)

        assertSame(currentUi, BattleNetBrowserLogin.ui.value)
        assertTrue(secondRequest.isFile)
        assertFalse(firstRequest.exists())
        assertEquals(1, exitLines(container).size)
        assertTrue(exitLines(container).single().contains("[$firstId]"))
        assertFalse(exitLines(container).single().contains("[$secondId]"))

        second.onExit(0)
        assertEquals(2, exitLines(container).size)
        assertTrue(exitLines(container).last().contains("[$secondId]"))
    }

    @Test
    fun launchArgumentsReachWineWithoutQuoteCharacters() {
        val container = container("arguments")
        val command = BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container).command
        val request = request(container)

        // The launcher splits the command with ProcessHelper.splitCommand, which keeps quote characters.
        val arguments = com.winlator.core.ProcessHelper.splitCommand(command).toList()

        org.junit.Assert.assertEquals(
            listOf(
                "C:\\wow-session\\wow-session-bridge.exe",
                "C:\\wow-session\\${request.name}",
                "G:\\_forever_\\Wow-ARM64.exe",
                "-launcherlogin",
                "-uid",
                "wowf",
                "-d3d11",
            ),
            arguments,
        )
        BattleNetBrowserLogin.cancel()
    }

    @Test
    fun cancelRemovesThePrivateCapabilityAndClosesTheListener() {
        val container = container("cancelled")
        BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val request = request(container)
        val port = request.readLines().first().toInt()

        BattleNetBrowserLogin.cancel()

        assertSame(BattleNetBrowserLogin.Ui.Idle, BattleNetBrowserLogin.ui.value)
        assertFalse(request.exists())
        assertListenerClosed(port)
    }

    private fun container(name: String): Container {
        val root = temporaryFolder.newFolder(name)
        val game = File(root, "game").apply { mkdirs() }
        File(game, ".build.info").writeText(
            "Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\n" +
                "eu|1|1.60.1.70938|wowf\n",
        )
        File(game, "_forever_").mkdirs()
        return Container(name).apply {
            rootDir = File(root, "prefix").apply { mkdirs() }
            drives = "G:${game.absolutePath}"
            executablePath = "G:\\_forever_\\Wow-ARM64.exe"
        }
    }

    private fun request(container: Container): File = File(container.rootDir, ".wine/drive_c/wow-session")
        .listFiles()!!.single { it.name.startsWith("request-") }

    private fun exitLines(container: Container): List<String> =
        File(container.rootDir.parentFile, "game/_forever_/Logs/BrowserLogin.log")
            .readLines().filter { "Wine launch exit status:" in it }

    private fun assertListenerClosed(port: Int) {
        var connected = false
        try {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", port), 500)
                connected = true
            }
        } catch (_: IOException) {
            // Expected: closing the attempt releases the listening socket synchronously.
        }
        assertFalse("Cancelled browser login still accepts connections", connected)
    }
}
