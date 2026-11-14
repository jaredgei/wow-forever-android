package app.gamenative.ui.screen.wow.auth

import android.app.Application
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import com.winlator.container.Container
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
        BattleNetBrowserLogin.start(RuntimeEnvironment.getApplication(), container)
        val request = request(container)
        val port = request.readLines().first().toInt()

        BattleNetBrowserLogin.guestExited()
        val failure = BattleNetBrowserLogin.ui.value
        assertTrue(failure is BattleNetBrowserLogin.Ui.Failed)
        BattleNetBrowserLogin.cancel(preserveFailure = true)
        BattleNetBrowserLogin.guestExited()

        assertSame(failure, BattleNetBrowserLogin.ui.value)
        assertFalse(request.exists())
        assertListenerClosed(port)

        BattleNetBrowserLogin.cancel()
        assertSame(BattleNetBrowserLogin.Ui.Idle, BattleNetBrowserLogin.ui.value)
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
