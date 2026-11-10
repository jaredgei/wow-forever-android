package app.gamenative.wow.product

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameRegionTest {

    @After
    fun reset() {
        GameRegions.chosen = null
    }

    @Test
    fun codesMapToRegions() {
        assertEquals(GameRegion.EU, GameRegion.fromCode("eu"))
        assertEquals(GameRegion.TW, GameRegion.fromCode("TW"))
        assertNull(GameRegion.fromCode("cn"))
        assertNull(GameRegion.fromCode(null))
    }

    @Test
    fun effectiveRegionFollowsTheInstallUntilOneIsChosen() {
        assertEquals(GameRegion.US, GameRegions.effective(null))
        assertEquals(GameRegion.EU, GameRegions.effective("eu"))
        GameRegions.chosen = GameRegion.KR
        assertEquals(GameRegion.KR, GameRegions.effective("eu"))
    }

    @Test
    fun taiwanLogsInThroughTheKoreanPortal() {
        assertEquals("KR", GameRegion.TW.portal)
        assertEquals("tw", GameRegion.TW.code)
        assertEquals("US", GameRegion.US.portal)
    }
}
