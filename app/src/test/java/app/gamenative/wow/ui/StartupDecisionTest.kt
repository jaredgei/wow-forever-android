package app.gamenative.wow.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDecisionTest {

    @Test
    fun launchesOnlyWhenNothingNeedsTheUserFirst() {
        assertTrue(shouldAutoLaunch(gameOutdated = false, appUpdate = false, switchToLaunch = false, supportedGpu = true))
        assertFalse(shouldAutoLaunch(gameOutdated = true, appUpdate = false, switchToLaunch = false, supportedGpu = true))
        assertFalse(shouldAutoLaunch(gameOutdated = false, appUpdate = true, switchToLaunch = false, supportedGpu = true))
        assertFalse(shouldAutoLaunch(gameOutdated = false, appUpdate = false, switchToLaunch = false, supportedGpu = false))
    }

    @Test
    fun aBetaInstallWaitingForTheSwitchNeverAutoLaunches() {
        assertFalse(shouldAutoLaunch(gameOutdated = false, appUpdate = false, switchToLaunch = true, supportedGpu = true))
    }

    @Test
    fun debugLoggingKeepsTheGameFromStartingByItself() {
        assertFalse(shouldAutoLaunch(gameOutdated = false, appUpdate = false, switchToLaunch = false, supportedGpu = true, debugLogging = true))
        assertTrue(shouldAutoLaunch(gameOutdated = false, appUpdate = false, switchToLaunch = false, supportedGpu = true, debugLogging = false))
    }
}
