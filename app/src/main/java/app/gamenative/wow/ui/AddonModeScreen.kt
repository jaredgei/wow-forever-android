package app.gamenative.wow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.utils.StorageUtils
import app.gamenative.wow.addons.AddonMode
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.ui.components.FwButton
import app.gamenative.wow.ui.components.FwButtonKind
import app.gamenative.wow.ui.components.FwChip
import app.gamenative.wow.ui.components.FwRow
import app.gamenative.wow.ui.theme.Fw
import java.io.File

/**
 * Sets up addon manager mode: the addons folder of a game that runs some other way, and its game version.
 * [onStarted] runs once the mode is on, with the folder to manage.
 */
@Composable
fun AddonModeScreen(onBack: () -> Unit, onStarted: (AddonMode.Folder) -> Unit) {
    val context = LocalContext.current
    val last = remember { AddonMode.lastFolder(context) }
    var choices by remember { mutableStateOf(listOfNotNull(last)) }
    var chosen by remember { mutableStateOf(last) }
    var version by remember { mutableStateOf(last?.version) }
    var error by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(StorageUtils.hasStoragePermission(context, "")) }
    LifecycleResumeEffect(Unit) {
        // Back from Android's file access screen.
        access = StorageUtils.hasStoragePermission(context, "")
        onPauseOrDispose { }
    }

    fun choose(folder: AddonMode.Folder) {
        chosen = folder
        version = folder.version ?: version
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val found = AddonMode.resolve(File(path))
        error = if (found.isEmpty()) "No addons folder in $path. Pick the game's Interface/AddOns folder, or the folder for your game version, such as _retail_." else null
        choices = found
        found.singleOrNull()?.let(::choose) ?: run { chosen = null }
    }

    FwPage("Addon manager mode", onBack) {
        FwSection("Only manage addons") {
            Text(
                "Use the app as an addon manager for a WoW install you run some other way, such as DroidDeck, Winlator or a copy synced from a PC. " +
                    "It opens straight to your addons, and the launcher, game files and account screens are turned off. You can turn this off again in Settings.",
                style = Fw.Text,
            )
        }

        FwSection("Addons folder") {
            Text("Choose the game's Interface/AddOns folder, or the folder for your game version, such as _retail_ or _classic_era_.", style = Fw.Secondary)
            if (choices.size > 1) {
                Column {
                    choices.forEach { folder ->
                        FwRow(selected = folder == chosen, onClick = { choose(folder) }) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(folder.version?.displayName ?: "Unknown version", style = Fw.Text)
                                Text(folder.addonsDir.path, style = Fw.Data)
                            }
                            if (folder == chosen) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                        }
                    }
                }
            } else {
                chosen?.let { Text(it.addonsDir.path, style = Fw.Data.copy(color = Fw.Bone)) }
            }
            error?.let { Text(it, style = Fw.Secondary.copy(color = Fw.Warn)) }
            FwButton(if (chosen == null) "Choose folder" else "Choose another folder", { picker.launch(null) }, kind = FwButtonKind.Ghost)
            Text("Folders inside another app's private storage (Android/data) can't be opened: Android doesn't allow it.", style = Fw.Secondary)
        }

        if (chosen != null) {
            FwSection("Game version") {
                Text(
                    if (chosen?.version != null) "Found from the folder. Addons are installed in their version for it." else "Pick the version this folder is for, so addons are installed in the right version.",
                    style = Fw.Secondary,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameVersion.entries.forEach { v -> FwChip(v.displayName, v == version, { version = v }) }
                }
            }
        }

        if (!access) {
            FwSection("File access") {
                Text("Android needs \"All files access\" so the app can change addons in another app's game folder.", style = Fw.Secondary)
                FwButton("Allow file access", { StorageUtils.requestManageExternalStoragePermission(context) }, kind = FwButtonKind.Ghost)
            }
        }

        val folder = chosen
        val v = version
        FwButton(
            "Start addon manager mode",
            {
                if (folder != null && v != null) {
                    AddonMode.enable(context, folder.addonsDir, v)
                    onStarted(folder.copy(version = v))
                }
            },
            enabled = folder != null && v != null && access,
        )
    }
}
