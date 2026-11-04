package app.gamenative.ui.screen.wow

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MIN_ZOOM = 1f
internal const val DOUBLE_TAP_ZOOM = 2.5f
internal const val MAX_ZOOM = 4f
private const val KEY_ZOOM_STEP = 1.25f
private const val KEY_PAN_FRACTION = 0.2f

/** Zoom stays within [MIN_ZOOM], [MAX_ZOOM]: at least 150% is reachable, but never absurdly far in. */
internal fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

/**
 * How far the image may be panned so its edge never leaves the viewport. [content] is the size of the
 * fitted image at 1x, [viewport] the size of the screen; at 1x (or if the image is smaller than the
 * screen on an axis) there is nothing to pan.
 */
internal fun clampPan(offset: Offset, zoom: Float, content: IntSize, viewport: IntSize): Offset {
    val maxX = ((content.width * zoom - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((content.height * zoom - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** Size of an image fitted inside a viewport, preserving aspect ratio. */
internal fun fittedSize(imageW: Int, imageH: Int, viewport: IntSize): IntSize {
    if (imageW <= 0 || imageH <= 0) return viewport
    val scale = minOf(viewport.width / imageW.toFloat(), viewport.height / imageH.toFloat())
    return IntSize((imageW * scale).toInt(), (imageH * scale).toInt())
}

/**
 * Full-screen preview viewer. Swipe (or left/right at 1x) to change image; pinch, double-tap, the
 * +/- buttons or the A button to zoom; drag or the D-pad to pan when zoomed. Back or Close returns
 * to the previews tab underneath.
 */
@Composable
internal fun ImageViewer(images: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    if (images.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var content by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }

    // A new page always starts fitted.
    LaunchedEffect(pager.currentPage) { zoom = MIN_ZOOM; pan = Offset.Zero }
    val keyboardNav = isKeyboardNavigating()

    fun setZoom(target: Float) {
        zoom = clampZoom(target)
        pan = clampPan(pan, zoom, content, viewport)
    }
    fun panBy(dx: Float, dy: Float) { pan = clampPan(pan + Offset(dx, dy), zoom, content, viewport) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Inside the dialog, so the FocusRequester is attached by the time it runs (outside it could fire first and throw).
        if (keyboardNav) AutoFocus(focus, true) else LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = KEY_PAN_FRACTION * viewport.width.coerceAtLeast(1)
                    when (e.key) {
                        Key.ButtonA, Key.DirectionCenter, Key.Enter -> { setZoom(if (zoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM); true }
                        Key.ButtonR1, Key.Equals, Key.Plus -> { setZoom(zoom * KEY_ZOOM_STEP); true }
                        Key.ButtonL1, Key.Minus -> { setZoom(zoom / KEY_ZOOM_STEP); true }
                        Key.DirectionLeft -> if (zoom > MIN_ZOOM) { panBy(step, 0f); true } else if (pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; true } else false
                        Key.DirectionRight -> if (zoom > MIN_ZOOM) { panBy(-step, 0f); true } else if (pager.currentPage < images.lastIndex) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; true } else false
                        Key.DirectionUp -> if (zoom > MIN_ZOOM) { panBy(0f, step); true } else false
                        Key.DirectionDown -> if (zoom > MIN_ZOOM) { panBy(0f, -step); true } else false
                        else -> false
                    }
                },
        ) {
            HorizontalPager(state = pager, userScrollEnabled = zoom <= MIN_ZOOM, modifier = Modifier.fillMaxSize()) { page ->
                val active = page == pager.currentPage
                ViewerPage(
                    url = images[page],
                    zoom = if (active) zoom else MIN_ZOOM,
                    pan = if (active) pan else Offset.Zero,
                    onFitted = { if (active) content = it },
                    onTransform = { zoomChange, panChange ->
                        val newZoom = clampZoom(zoom * zoomChange)
                        zoom = newZoom
                        pan = clampPan(pan + panChange, newZoom, content, viewport)
                    },
                    currentZoom = { zoom },
                    onDoubleTap = { setZoom(if (zoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM) },
                )
            }
            Row(
                // The image runs edge to edge, but the controls stay clear of the status and navigation bars.
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.systemBars).padding(12.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${pager.currentPage + 1}/${images.size}  ${(zoom * 100).toInt()}%", fontSize = 13.sp, color = WowMuted, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(modifier = Modifier.focusGlow(20.dp), onClick = { setZoom(zoom / KEY_ZOOM_STEP) }, enabled = zoom > MIN_ZOOM) { Text("−", fontSize = 20.sp, color = WowGold) }
                TextButton(modifier = Modifier.focusGlow(20.dp), onClick = { setZoom(zoom * KEY_ZOOM_STEP) }, enabled = zoom < MAX_ZOOM) { Text("+", fontSize = 20.sp, color = WowGold) }
                TextButton(modifier = Modifier.focusGlow(20.dp), onClick = onDismiss) { Text("Close", color = WowGold) }
            }
        }
    }
}

@Composable
private fun ViewerPage(
    url: String,
    zoom: Float,
    pan: Offset,
    onFitted: (IntSize) -> Unit,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
    currentZoom: () -> Float,
    onDoubleTap: () -> Unit,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember(url) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, url) {
        value = fetchBitmap(url)?.asImageBitmap()
        failed = value == null
    }
    val loaded = image
    LaunchedEffect(loaded, viewport) {
        if (loaded != null) onFitted(fittedSize(loaded.width, loaded.height, viewport))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .pointerInput(Unit) {
                // Pinch always zooms; a one-finger drag pans only while zoomed, so at 1x it still swipes pages.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || currentZoom() > MIN_ZOOM) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            if (z != 1f || p != Offset.Zero) {
                                onTransform(z, p)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loaded != null -> Image(
                loaded, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
            )
            failed -> Text("Couldn't load image", fontSize = 14.sp, color = WowMuted)
            else -> CircularProgressIndicator(color = WowGold, modifier = Modifier.size(32.dp))
        }
    }
}
