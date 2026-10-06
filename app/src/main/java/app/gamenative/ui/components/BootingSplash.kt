package app.gamenative.ui.components

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.theme.BrandGradient
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowGold

@Composable
fun BootingSplash(
    visible: Boolean = true,
    text: String = "Initializing...",
    progress: Float = -1f,
    onAbort: (() -> Unit)? = null,
) {
    if (visible && onAbort != null) {
        DisposableEffect(visible) {
            var start = false
            var select = false
            var l2 = false
            var r2 = false
            var aborted = false

            fun check(): Boolean = (!aborted && start && select && l2 && r2).also {
                if (it) {
                    aborted = true
                    onAbort()
                }
            }

            val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = keyHandler@ { event ->
                val down = event.event.action == KeyEvent.ACTION_DOWN
                when (event.event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_START -> start = down
                    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> select = down
                    KeyEvent.KEYCODE_BUTTON_L2 -> l2 = down
                    KeyEvent.KEYCODE_BUTTON_R2 -> r2 = down
                    else -> return@keyHandler false
                }
                check() || (down && (start || select || l2 || r2))
            }

            val motionHandler: (AndroidEvent.MotionEvent) -> Boolean = { event ->
                event.event?.let { me ->
                    l2 = maxOf(me.getAxisValue(MotionEvent.AXIS_LTRIGGER), me.getAxisValue(MotionEvent.AXIS_BRAKE)) >= 0.3f
                    r2 = maxOf(me.getAxisValue(MotionEvent.AXIS_RTRIGGER), me.getAxisValue(MotionEvent.AXIS_GAS)) >= 0.3f
                    check()
                } ?: false
            }

            PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
            PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motionHandler)
            onDispose {
                PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
                PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motionHandler)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "bootSplash")
        val shimmerPosition by infiniteTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(WowBackgroundGradient)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Spacer(modifier = Modifier.weight(0.4f))

                Text(
                    text = "World of Warcraft",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = 2.sp,
                        shadow = Shadow(
                            color = Color(0xFFC7AF8E).copy(alpha = 0.6f),
                            offset = Offset(0f, 0f),
                            blurRadius = 20f,
                        ),
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFFF5E296), Color(0xFFE5C158), Color(0xFFC7AF8E)),
                        ),
                    ),
                )

                Spacer(modifier = Modifier.height(28.dp))

                ProgressBar(
                    progress = progress,
                    shimmerPosition = shimmerPosition,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp),
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Press Start + Select + L2 + R2 to return to setup screen",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    ),
                    color = WowGold.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.weight(0.4f))
            }

            if (onAbort != null) {
                IconButton(
                    onClick = onAbort,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(
    progress: Float,
    shimmerPosition: Float,
    modifier: Modifier = Modifier,
) {
    val isIndeterminate = progress < 0f
    val actualProgress = if (isIndeterminate) 1f else progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(PluviaTheme.colors.borderDefault.copy(alpha = 0.3f)),
    ) {
        // Progress fill with gradient
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(actualProgress)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = BrandGradient,
                    ),
                ),
        )

        // Shimmer overlay
        if (isIndeterminate || progress > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp)),
            ) {
                val shimmerWidth = size.width * 0.3f
                val shimmerStart = (shimmerPosition * size.width) - shimmerWidth
                val shimmerEnd = shimmerStart + shimmerWidth

                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.4f),
                            Color.Transparent,
                        ),
                        startX = shimmerStart,
                        endX = shimmerEnd,
                    ),
                )
            }
        }
    }
}


@Preview(name = "BootingSplash - Indeterminate")
@Composable
fun BootingSplashPreview() {
    PluviaTheme {
        BootingSplash(visible = true)
    }
}

@Preview(name = "BootingSplash - 50% Progress")
@Composable
fun BootingSplashProgressPreview() {
    PluviaTheme {
        BootingSplash(
            visible = true,
            text = "Loading game files...",
            progress = 0.5f,
        )
    }
}

@Preview(name = "BootingSplash - Dark", device = "spec:width=1920px,height=1080px,dpi=440")
@Composable
fun BootingSplashLandscapePreview() {
    PluviaTheme {
        BootingSplash(
            visible = true,
            text = "Preparing container...",
            progress = -1f,
        )
    }
}
