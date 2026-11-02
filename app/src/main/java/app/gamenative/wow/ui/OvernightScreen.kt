package app.gamenative.wow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.wow.install.DownloadSpeed
import app.gamenative.wow.install.InstallController
import app.gamenative.wow.install.InstallUiState
import app.gamenative.wow.ui.components.rememberDownloadSpeed
import app.gamenative.wow.ui.theme.Fw
import kotlin.random.Random
import kotlinx.coroutines.delay

private const val MOVE_EVERY_MS = 60_000L
private const val DIM_BRIGHTNESS = 0.01f
private val Dim = Fw.Ash.copy(alpha = 0.55f)

/**
 * Game files, Overnight mode: keeps the app on screen during a long download, so Android doesn't pause it
 * after hours in the background, with the screen at its dimmest and nearly all black. The few lines of
 * progress move every minute so nothing stays lit in one place. A tap or any button leaves.
 */
@Composable
fun OvernightScreen(onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val state by InstallController.state.collectAsState()
    val leave by rememberUpdatedState(onBack)
    val running = state as? InstallUiState.Running
    val speed = rememberDownloadSpeed(running?.progress, windowMs = 15_000)
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }

    DisposableEffect(activity) {
        val window = activity?.window
        val brightness = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = DIM_BRIGHTNESS } }
        onDispose {
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (brightness != null) w.attributes = w.attributes.apply { screenBrightness = brightness }
            }
        }
    }
    // The screen stays on only while there's something to wait for; once it's done or stopped it may sleep.
    LaunchedEffect(running != null) {
        val window = activity?.window ?: return@LaunchedEffect
        if (running != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MOVE_EVERY_MS)
            x = Random.nextFloat() * 1.6f - 0.8f
            y = Random.nextFloat() * 1.6f - 0.8f
        }
    }
    DisposableEffect(Unit) {
        val onKey: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            if (e.event.action == KeyEvent.ACTION_UP) leave()
            true
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKey)
        onDispose { PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKey) }
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { leave() } }) {
        Column(
            Modifier.align(BiasAlignment(x, y)).padding(32.dp).widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (val s = state) {
                is InstallUiState.Running -> {
                    val p = s.progress
                    Text(p.fraction?.let { "${(it * 100).toInt()}%" } ?: "Preparing", style = Fw.Title.copy(color = Dim, fontSize = 30.sp))
                    Text(if (s.request.full) "Downloading all game data" else "Downloading the game", style = Fw.Data.copy(color = Dim))
                    if (p.totalBytes > 0) Text(DownloadSpeed.summary(p, speed), style = Fw.Data.copy(color = Dim))
                    p.stepText?.let { Text(it, style = Fw.Data.copy(color = Dim)) }
                }
                is InstallUiState.Done -> Text("Download complete.", style = Fw.Title.copy(color = Dim, fontSize = 22.sp))
                is InstallUiState.Paused -> Text(s.reason ?: "Download paused.", style = Fw.Data.copy(color = Dim))
                is InstallUiState.Failed -> Text("The download stopped: ${s.message}", style = Fw.Data.copy(color = Dim))
                InstallUiState.Idle -> Text("Nothing is downloading.", style = Fw.Data.copy(color = Dim))
            }
            Text("Tap or press any button to wake.", style = Fw.Data.copy(color = Dim.copy(alpha = 0.35f)))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
