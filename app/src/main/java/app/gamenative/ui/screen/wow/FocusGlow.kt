package app.gamenative.ui.screen.wow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.WowGold

private const val GLOW_LAYERS = 6

/**
 * A gold glow around whatever has focus, so a D-pad or gamepad player can always see where they are. It is drawn only
 * for keyboard-style input: with touch, tapping something shouldn't leave a ring behind. Put it before `clickable`
 * (or pass it as a Material component's own modifier) so it observes that element's focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusGlow(radius: Dp = 12.dp, color: Color = WowGold): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val show = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val density = LocalDensity.current
    onFocusChanged { focused = it.isFocused }.drawWithContent {
        if (show) {
            val r = with(density) { radius.toPx() }
            val step = with(density) { 1.5.dp.toPx() }
            // Soft halo: wider, fainter rings outward from the element.
            for (i in GLOW_LAYERS downTo 1) {
                val grow = step * i
                drawRoundRect(
                    color = color.copy(alpha = 0.30f * (1f - (i - 1f) / GLOW_LAYERS)),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = CornerRadius(r + grow),
                    style = Stroke(width = step * 1.4f),
                )
            }
        }
        drawContent()
        if (show) {
            val r = with(density) { radius.toPx() }
            val line = with(density) { 2.dp.toPx() }
            drawRoundRect(color = color, cornerRadius = CornerRadius(r), style = Stroke(width = line))
        }
    }
}

/** Material's own focus wash follows the theme's primary color, which is purple here; make it the UI's gold. */
@Composable
fun GoldFocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = WowGold), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/**
 * Makes a text field usable with a D-pad or gamepad. Left and Right still move the caret, but Up and Down move focus
 * on to the next control instead of being swallowed (which would trap the player in the field), the keyboard stays
 * down while focus merely passes over the field, and the center or A button opens it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dpadTextField(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    onFocusChanged { if (it.isFocused && inputMode.inputMode == InputMode.Keyboard) keyboard?.hide() }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up).let { true }
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down).let { true }
                Key.DirectionCenter, Key.ButtonA -> { keyboard?.show(); true }
                else -> false
            }
        }
}

/** True when the player is navigating with a D-pad, gamepad or keyboard rather than touch. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun isKeyboardNavigating(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/** The selected tab's focus target, so the controls along the top of a tab's content can send Up to it. */
val LocalTabFocus = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

/**
 * Up from this control goes to the tab you're on. Plain directional search would pick whichever tab is nearest to this
 * control, which is often a different one. Put it on the controls along the top edge of a tab's content.
 */
fun Modifier.upToTab(): Modifier = composed {
    val target = LocalTabFocus.current
    if (target == null) this else focusProperties { up = target }
}

/**
 * Puts focus on [target] shortly after composing when the player is using keys. A dialog is a new window that still
 * thinks it is in touch mode, so it is told to switch first, or the focus would be invisible.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AutoFocus(target: androidx.compose.ui.focus.FocusRequester, enabled: Boolean) {
    val inputMode = LocalInputModeManager.current
    androidx.compose.runtime.LaunchedEffect(target, enabled) {
        if (enabled) {
            kotlinx.coroutines.delay(150)
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { target.requestFocus() }
        }
    }
}
