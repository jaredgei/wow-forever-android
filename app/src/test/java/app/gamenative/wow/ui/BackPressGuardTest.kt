package app.gamenative.wow.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackPressGuardTest {
    private var now = 100_000L

    @Before
    fun reset() {
        // The guard is a singleton: move far past anything an earlier test left behind.
        now += 60_000
        BackPressGuard.onKeyUp(now)
        BackPressGuard.onCallback(now + 10_000)
        now += 20_000
    }

    @Test
    fun aGestureSentAsKeyAndCallbackActsOnce() {
        assertTrue(BackPressGuard.onKeyDown(now, 0))
        BackPressGuard.onKeyUp(now + 2)
        assertFalse(BackPressGuard.onCallback(now + 3))
    }

    @Test
    fun holdingTheBackButtonDoesNotCloseTheMenuOnRelease() {
        assertTrue(BackPressGuard.onKeyDown(now, 0))
        assertFalse(BackPressGuard.onKeyDown(now + 500, 1))
        assertFalse(BackPressGuard.onCallback(now + 3_000))
        BackPressGuard.onKeyUp(now + 3_001)
    }

    @Test
    fun aCallbackBeforeTheKeyActsOnce() {
        assertTrue(BackPressGuard.onCallback(now))
        assertFalse(BackPressGuard.onKeyDown(now + 5, 0))
        BackPressGuard.onKeyUp(now + 7)
    }

    @Test
    fun phonesThatOnlySendTheCallbackActEveryTime() {
        assertTrue(BackPressGuard.onCallback(now))
        assertTrue(BackPressGuard.onCallback(now + 600))
        assertTrue(BackPressGuard.onCallback(now + 650))
    }

    @Test
    fun phonesThatOnlySendKeysActEveryPress() {
        assertTrue(BackPressGuard.onKeyDown(now, 0))
        BackPressGuard.onKeyUp(now + 50)
        assertTrue(BackPressGuard.onKeyDown(now + 900, 0))
        BackPressGuard.onKeyUp(now + 950)
    }

    @Test
    fun aSecondPressAfterAKeyPressStillWorksThroughTheCallback() {
        assertTrue(BackPressGuard.onKeyDown(now, 0))
        BackPressGuard.onKeyUp(now + 2)
        assertFalse(BackPressGuard.onCallback(now + 3))
        assertTrue(BackPressGuard.onCallback(now + 1_500))
    }
}
