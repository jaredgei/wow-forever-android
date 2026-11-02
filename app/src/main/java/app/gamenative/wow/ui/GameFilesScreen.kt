package app.gamenative.wow.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.wow.GamePath
import app.gamenative.utils.StorageUtils
import app.gamenative.wow.install.FullDownloader
import app.gamenative.wow.install.InstallController
import app.gamenative.wow.install.InstallRequest
import app.gamenative.wow.install.InstallUiState
import app.gamenative.wow.product.GameInstall
import app.gamenative.wow.product.InstallScanner
import app.gamenative.wow.ui.components.FwButton
import app.gamenative.wow.ui.components.FwDownloadPanel
import app.gamenative.wow.ui.components.FwButtonKind
import app.gamenative.wow.ui.components.FwProgress
import app.gamenative.wow.ui.components.FwRow
import app.gamenative.wow.ui.components.rememberDownloadSpeed
import app.gamenative.wow.ui.theme.Fw
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GameFilesScreen(launcher: LauncherController, onBack: () -> Unit, onInstall: () -> Unit, onOpenOvernight: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by InstallController.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<GameInstall>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path))
        if (root == null) {
            launcher.errorMessage = "No ${launcher.version.displayName} install in $path. Pick the folder that holds .build.info and Data."
        } else {
            launcher.useGameRoot(root.absolutePath)
        }
    }

    FwPage("Game files", onBack) {
        FwSection("Current install") {
            val files = launcher.files
            if (launcher.filesMissing) {
                Text("No game files at ${launcher.gamePath}.", style = Fw.Text)
            } else {
                Text(files.product?.displayName ?: "Unknown product", style = Fw.Text)
                Text(launcher.gamePath, style = Fw.Data)
                launcher.versionStatus?.let { Text("Version ${it.localVersion}", style = Fw.Data) }
                if (!files.exeExists) Text("The game program downloads when you press Play.", style = Fw.Secondary)
            }
            launcher.errorMessage?.let { Text(it, style = Fw.Secondary.copy(color = Fw.Warn)) }
            if (launcher.isUpdating && !launcher.isConverting) Text(launcher.updateStatusText, style = Fw.Text)
        }

        if (launcher.canConvertToLaunch) {
            FwSection("Launch version") {
                Text(
                    "WoW Forever has launched. Switch this beta install to the launch version. Your game data, settings and addons are reused, so only the new game program downloads.",
                    style = Fw.Text,
                )
                if (launcher.isConverting) {
                    FwDownloadPanel("Downloading WoW Forever", launcher.convertProgress, launcher.convertSpeed)
                } else {
                    FwButton("Switch to launch version", launcher::convertToLaunch, enabled = !launcher.busy)
                }
            }
        }

        FwSection("Install or repair") {
            Text(
                "Downloads ${launcher.version.displayName} from Blizzard's servers. Repair checks every file and only downloads what's missing or damaged.",
                style = Fw.Secondary,
            )
            FwButton(
                if (launcher.filesMissing) "Install ${launcher.version.displayName}" else "Install or repair",
                onInstall,
                enabled = !launcher.busy && install.fullRequest == null,
            )
        }

        if (!launcher.filesMissing) FullDownloadSection(launcher, install, onOpenOvernight)

        FwSection("Find an existing copy") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton(
                    if (scanning) "Searching…" else "Search this device",
                    {
                        scanning = true
                        scope.launch {
                            found = withContext(Dispatchers.IO) { InstallScanner.scan(InstallScanner.storageRoots(context)) }
                            scanning = false
                        }
                    },
                    kind = FwButtonKind.Ghost,
                    enabled = !scanning,
                )
                FwButton("Choose folder", { picker.launch(null) }, kind = FwButtonKind.Ghost)
            }
            found?.let { installs ->
                if (installs.isEmpty()) Text("No installs found on internal storage or SD cards.", style = Fw.Secondary)
                Column {
                    installs.forEach { install ->
                        FwRow(selected = install.root == launcher.gamePath, onClick = { launcher.selectInstall(install) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${install.product.displayName} · ${install.version}", style = Fw.Text)
                                Text(install.root, style = Fw.Data)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The full download behind [state], if that's what is running, paused or failed. */
internal val InstallUiState.fullRequest: InstallRequest?
    get() = when (this) {
        is InstallUiState.Running -> request
        is InstallUiState.Paused -> request
        is InstallUiState.Failed -> request
        else -> null
    }?.takeIf { it.full }

/** Game files, Download all game data: everything the game would otherwise stream, with Overnight mode for long runs. */
@Composable
private fun FullDownloadSection(launcher: LauncherController, state: InstallUiState, onOpenOvernight: () -> Unit) {
    val context = LocalContext.current
    val product = launcher.files.product ?: return
    val root = File(launcher.gamePath)
    val complete = remember(launcher.gamePath, state) { FullDownloader.isComplete(root, product) }
    val otherDownload = state.fullRequest == null && (state is InstallUiState.Running || state is InstallUiState.Paused)
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state) {
        if ((state as? InstallUiState.Done)?.full == true) InstallController.reset()
    }

    FwSection("Download all game data") {
        val running = state as? InstallUiState.Running
        val speed = rememberDownloadSpeed(running?.progress, windowMs = 15_000)
        when {
            running != null && running.request.full -> {
                FwDownloadPanel("Downloading all game data", running.progress, speed)
                Text(
                    "You can leave the app or keep playing while it downloads. The new data takes over the next time the game is closed.",
                    style = Fw.Secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FwButton("Overnight mode", onOpenOvernight)
                    FwButton("Pause", { InstallController.pause(context) }, kind = FwButtonKind.Ghost)
                }
            }
            state is InstallUiState.Paused && state.request.full -> {
                Text(state.reason ?: "Download paused. Everything downloaded so far is kept.", style = Fw.Text)
                state.progress?.let { FwProgress(it.fraction) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FwButton("Resume", { InstallController.resume(context) })
                    FwButton("Cancel", { InstallController.cancel(context) }, kind = FwButtonKind.Ghost)
                }
            }
            state is InstallUiState.Failed && state.request.full -> {
                Text("The download stopped: ${state.message}", style = Fw.Text.copy(color = Fw.Warn))
                Text("Everything downloaded so far is kept, and trying again picks up where it stopped.", style = Fw.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FwButton("Try again", { InstallController.resume(context) })
                    FwButton("Cancel", { InstallController.cancel(context) }, kind = FwButtonKind.Ghost)
                }
            }
            complete -> Text(
                "All game data is on this device, so the game doesn't download world data while you play. After a game update it downloads only the new files.",
                style = Fw.Text,
            )
            else -> {
                Text(
                    "The game normally downloads its world data while you play. On a slow connection that means long loading screens and missing text or characters. " +
                        "Download all of it now instead, about 50 GB from Blizzard's servers.",
                    style = Fw.Text,
                )
                Text(
                    "On a slow connection this can take all night: start it, open Overnight mode, and leave the device on its charger.",
                    style = Fw.Secondary,
                )
                FwButton(
                    "Download everything",
                    {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        InstallController.start(context, InstallRequest(root, product, full = true))
                    },
                    enabled = !launcher.busy && !otherDownload,
                )
            }
        }
    }
}
