package app.gamenative.ui.screen.wow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerTest {
    private val screen = IntSize(1000, 500)

    @Test fun zoomIsClampedButReaches150Percent() {
        assertEquals(MIN_ZOOM, clampZoom(0.2f), 0f)
        assertEquals(MAX_ZOOM, clampZoom(50f), 0f)
        assertTrue(clampZoom(1.5f) == 1.5f && DOUBLE_TAP_ZOOM >= 1.5f)
    }

    @Test fun noPanAtFit() = assertEquals(Offset.Zero, clampPan(Offset(300f, 300f), 1f, screen, screen))

    @Test fun panStopsAtImageEdge() {
        val p = clampPan(Offset(9999f, -9999f), 2f, screen, screen)
        assertEquals(500f, p.x, 0f)
        assertEquals(-250f, p.y, 0f)
    }

    @Test fun narrowImageOnlyPansWhereItOverflows() {
        // A tall image fitted to 250x500 in a 1000x500 screen: at 2x it is 500 wide, still inside the screen.
        val p = clampPan(Offset(400f, 400f), 2f, IntSize(250, 500), screen)
        assertEquals(0f, p.x, 0f)
        assertEquals(250f, p.y, 0f)
    }

    @Test fun fittedSizeKeepsAspect() {
        assertEquals(IntSize(1000, 250), fittedSize(2000, 500, screen))
        assertEquals(IntSize(250, 500), fittedSize(500, 1000, screen))
    }
}
