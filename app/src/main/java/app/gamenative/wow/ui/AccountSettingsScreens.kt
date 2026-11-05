package app.gamenative.wow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.wow.addons.AddonMode
import app.gamenative.wow.install.InstallController
import app.gamenative.wow.install.InstallUiState
import app.gamenative.wow.product.GameRegion
import app.gamenative.wow.product.GameRegions
import app.gamenative.wow.product.GameAccounts
import app.gamenative.wow.ui.components.FwChip
import androidx.compose.foundation.horizontalScroll
import app.gamenative.wow.product.ForeverTrack
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.product.RenderSize
import app.gamenative.wow.product.RenderSizes
import app.gamenative.ui.screen.wow.fitScreenSizeToWindow
import app.gamenative.wow.ui.components.FwDialog
import app.gamenative.wow.ui.components.FwRow
import app.gamenative.wow.ui.components.GameIconImage
import app.gamenative.wow.ui.components.icon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import app.gamenative.wow.FrameGeneration
import app.gamenative.wow.debug.DebugLog
import app.gamenative.wow.debug.DebugReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.gamenative.BuildConfig
import app.gamenative.utils.renderReleaseNotes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.ui.platform.LocalContext
import app.gamenative.wow.ui.components.FwButton
import app.gamenative.wow.ui.components.Links
import app.gamenative.wow.ui.components.FwButtonKind
import app.gamenative.wow.ui.components.FwProgress
import app.gamenative.wow.ui.components.FwTopBar
import app.gamenative.wow.ui.theme.Fw

@Composable
internal fun FwPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Fw.Ground)) {
        FwTopBar(title, onBack)
        HorizontalDivider(color = Fw.Rule)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

@Composable
internal fun FwSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = Fw.Label.copy(color = Fw.Brass, fontSize = 12.sp, letterSpacing = 1.6.sp))
        content()
    }
}

@Composable
fun AccountScreen(launcher: LauncherController, onBack: () -> Unit, onEditLogin: () -> Unit) {
    FwPage("Account", onBack) {
        FwSection("Battle.net login") {
            Text(
                if (launcher.hasSavedLogin) {
                    "Your email and password are saved on this device, encrypted with the Android keystore. The game logs you in automatically. Authenticator codes are still typed in the game."
                } else {
                    "No login saved. Save one to skip the login screen, or sign in inside the game each time."
                },
                style = Fw.Text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton(if (launcher.hasSavedLogin) "Change login" else "Save login", onEditLogin)
                if (launcher.hasSavedLogin) FwButton("Forget login", launcher::forgetLogin, kind = FwButtonKind.Danger)
            }
        }
        GameAccountSection()
        BlockedLoginSection()
        FwSection("How it works") {
            Text(
                "When you press Play the app hands your login to the game in a temporary file, then wipes it shortly after the game has read it, or after ten minutes at most.",
                style = Fw.Secondary,
            )
        }
    }
}

/**
 * Account, Can't log in: Blizzard locks direct game logins from a network the account hasn't used (BLZ51914400,
 * after any CAPTCHA), and one sign-in on the web lifts it. BLZ51914500 is a password that didn't match.
 */
@Composable
private fun BlockedLoginSection() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    FwSection("Can't log in?") {
        Text(
            "If the game says \"You have been disconnected\" with BLZ51914400 or BLZ51914500 right after you log in, Blizzard is blocking the sign-in.",
            style = Fw.Secondary,
        )
        FwButton("Fix a blocked login", { open = true }, kind = FwButtonKind.Ghost)
    }
    if (open) {
        FwDialog(
            title = "Blocked login",
            message = "Blizzard blocks game logins from a network it hasn't seen your account use, such as mobile data or a new Wi-Fi. Signing in once on the web unblocks it.",
            confirm = "Open battle.net",
            onConfirm = {
                open = false
                Links.open(context, Links.BATTLE_NET)
            },
            onDismiss = { open = false },
            dismiss = "Close",
        ) {
            listOf(
                "1. Tap Open battle.net and sign in there, on the same connection.",
                "2. Come back to the app and start the game again.",
                "3. Still getting BLZ51914500? Your password didn't match. Re-enter it with Change login.",
            ).forEach { Text(it, style = Fw.Text) }
        }
    }
}

/** Account, Game account: which WoW account to play when the Battle.net login has several. */
@Composable
private fun GameAccountSection() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GameAccounts.chosen) }
    fun pick(number: Int?) {
        GameAccounts.save(context, number)
        current = number
    }
    FwSection("Game account") {
        Text(
            "If your Battle.net login has more than one WoW account, such as WoW1 and WoW2, choose the one to play. " +
                "The Battle.net app lists them in its account menu. A change applies the next time you start the game.",
            style = Fw.Secondary,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FwChip("The game's choice", current == null, { pick(null) })
            GameAccounts.NUMBERS.forEach { n -> FwChip(GameAccounts.name(n), current == n, { pick(n) }) }
        }
    }
}

@Composable
fun SettingsScreen(
    launcher: LauncherController,
    onBack: () -> Unit,
    onVersionChanged: () -> Unit = {},
    onOpenControllerTest: () -> Unit = {},
    /** The folder addon manager mode manages, or null when the app is the full launcher. */
    addonFolder: AddonMode.Folder? = null,
    onOpenAddonMode: () -> Unit = {},
    onDisableAddonMode: () -> Unit = {},
) {
    FwPage("Settings", onBack) {
        FwSection("App") {
            Text("WoW Foreverywhere v${BuildConfig.VERSION_NAME}", style = Fw.Text)
            val update = launcher.appUpdate
            if (update == null) {
                Text("No app update found.", style = Fw.Secondary)
                FwButton("Check for app update", launcher::checkAppUpdate, kind = FwButtonKind.Ghost)
            } else {
                Text("Version ${update.version} is available.", style = Fw.Text.copy(color = Fw.Brass))
                if (update.notes.isNotBlank()) Text(renderReleaseNotes(update.notes), style = Fw.Secondary)
                if (launcher.isDownloadingAppUpdate) {
                    Text("Downloading ${(launcher.appUpdateProgress * 100).toInt()}%", style = Fw.Data)
                    FwProgress(launcher.appUpdateProgress)
                } else {
                    FwButton(if (launcher.downloadedApk?.exists() == true) "Install update" else "Update app", launcher::performAppUpdate)
                }
            }
        }
        if (addonFolder != null) AddonModeSection(addonFolder, onOpenAddonMode, onDisableAddonMode)
        if (addonFolder == null) {
            GameVersionSection(launcher, onVersionChanged)
            RegionSection(launcher)
        }
        FwSection("Support Foreverywhere") {
            val context = LocalContext.current
            Text("Foreverywhere is free and open source. If it saves you some time, you can support its development on Ko-fi.", style = Fw.Text)
            FwButton("Support on Ko-fi", { Links.open(context, Links.KOFI) }, kind = FwButtonKind.Ghost, icon = Icons.Filled.LocalCafe)
        }
        FwSection("Addon sources") {
            ProviderLine("CurseForge", true)
            ProviderLine("Wago Addons", BuildConfig.WAGO_API_KEY.isNotBlank())
            ProviderLine("WoWInterface", true)
            ProviderLine("GitHub (addon catalog and links)", true)
            Text("Wago needs an API key built into the app. Builds without one still use the other sources.", style = Fw.Secondary)
        }
        if (addonFolder == null) {
            FwSection("Device") {
                Text("${launcher.gpu.edition} profile", style = Fw.Data)
            }
            ResolutionSection(launcher)
            FrameGenerationSection()
            TroubleshootingSection(launcher, onOpenControllerTest)
            AddonModeSection(null, onOpenAddonMode, onDisableAddonMode)
        }
        FwSection("About") {
            Text(
                "Unofficial community project, not affiliated with Blizzard Entertainment. The game is downloaded from Blizzard's own servers and needs your own Battle.net account. Based on jaredgei/wow-forever-android (GPL-3.0).",
                style = Fw.Secondary,
            )
            val context = LocalContext.current
            FwButton("Source code on GitHub", { Links.open(context, Links.REPO) }, kind = FwButtonKind.Ghost, icon = Icons.AutoMirrored.Filled.OpenInNew)
        }
    }
}

/** Settings, Addon manager mode: offered when the app is the full launcher, and the way back out of it. */
@Composable
private fun AddonModeSection(folder: AddonMode.Folder?, onOpen: () -> Unit, onDisable: () -> Unit) {
    FwSection("Addon manager mode") {
        if (folder == null) {
            Text(
                "Play WoW some other way, such as through DroidDeck? Addon manager mode turns this app into an addon manager for that install, and turns everything else off.",
                style = Fw.Secondary,
            )
            FwButton("Use addon manager mode", onOpen, kind = FwButtonKind.Ghost)
        } else {
            Text("The app manages the ${folder.version?.displayName ?: ""} addons in this folder and nothing else:", style = Fw.Text)
            Text(folder.addonsDir.path, style = Fw.Data)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton("Change folder", onOpen, kind = FwButtonKind.Ghost)
                FwButton("Turn off addon manager mode", onDisable)
            }
            Text("Turning it off brings back the launcher: installing, updating and playing WoW Forever on this device.", style = Fw.Secondary)
        }
    }
}

/** Settings, Troubleshooting: the controller test, debug logging and a report to attach to an issue. */
@Composable
private fun TroubleshootingSection(launcher: LauncherController, onOpenControllerTest: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logging by remember { mutableStateOf(DebugLog.isEnabled(context)) }
    var building by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    FwSection("Troubleshooting") {
        Text(
            "If a button doesn't work in the game, the controller test shows what each press sends and whether the game receives it.",
            style = Fw.Secondary,
        )
        FwButton("Controller test", onOpenControllerTest, kind = FwButtonKind.Ghost)
        Text(
            "For other problems, turn on debug logging, make the problem happen again, then share a debug report on your GitHub issue. " +
                "The report holds your device details, settings and logs. Email addresses and your saved login are removed from it.",
            style = Fw.Secondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(false to "Debug logging off", true to "Debug logging on").forEach { (value, label) ->
                FwRow(selected = logging == value, onClick = {
                    DebugLog.setEnabled(context, value)
                    logging = value
                }) {
                    Text(label, style = Fw.Text, modifier = Modifier.weight(1f))
                    if (logging == value) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                }
            }
        }
        FwButton(if (building) "Preparing report…" else "Share debug report", {
            building = true
            failed = false
            scope.launch {
                val zip = withContext(Dispatchers.IO) {
                    runCatching { DebugReport.build(context, launcher.debugSummary(), launcher.gameFolder(), launcher.debugSecrets()) }.getOrNull()
                }
                building = false
                if (zip != null) DebugReport.share(context, zip) else failed = true
            }
        }, kind = FwButtonKind.Ghost, enabled = !building)
        if (failed) Text("Couldn't put the report together. Try again.", style = Fw.Secondary.copy(color = Fw.Warn))
    }
}

/** Settings, Frame generation: the player's own Lossless.dll, which turns on the in-game LSFG tab. */
@Composable
private fun FrameGenerationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasDll by remember { mutableStateOf(FrameGeneration.hasDll(context)) }
    var enabled by remember { mutableStateOf(FrameGeneration.isEnabled(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?.let { FrameGeneration.import(context, it) } ?: FrameGeneration.ImportResult.FAILED
            }
            message = when (result) {
                FrameGeneration.ImportResult.OK -> null
                FrameGeneration.ImportResult.NOT_A_DLL -> "That file isn't Lossless.dll. Pick the one from your Lossless Scaling folder."
                FrameGeneration.ImportResult.FAILED -> "Couldn't read that file. Try copying it to the device again."
            }
            hasDll = FrameGeneration.hasDll(context)
            enabled = FrameGeneration.isEnabled(context)
        }
    }
    FwSection("Frame generation") {
        Text(
            "Lossless Scaling's frame generation (LSFG) can raise the frame rate by adding generated frames, at the cost of a little input delay. " +
                "It needs Lossless.dll from your own copy of Lossless Scaling on Steam: on your PC it's in Steam/steamapps/common/Lossless Scaling. " +
                "Copy it to this device and choose it here. The in-game menu (Back) then has an LSFG tab to set it up.",
            style = Fw.Secondary,
        )
        if (hasDll) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "On", false to "Off").forEach { (value, label) ->
                    FwRow(selected = enabled == value, onClick = {
                        FrameGeneration.setEnabled(context, value)
                        enabled = value
                    }) {
                        Text(label, style = Fw.Text, modifier = Modifier.weight(1f))
                        if (enabled == value) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                    }
                }
            }
            Text("Lossless.dll added. A change applies the next time you start the game.", style = Fw.Data)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton("Choose another file", { picker.launch(arrayOf("*/*")) }, kind = FwButtonKind.Ghost)
                FwButton("Remove", {
                    FrameGeneration.remove(context)
                    hasDll = false
                    enabled = FrameGeneration.isEnabled(context)
                }, kind = FwButtonKind.Ghost)
            }
        } else {
            FwButton("Choose Lossless.dll", { picker.launch(arrayOf("*/*")) }, kind = FwButtonKind.Ghost)
        }
        message?.let { Text(it, style = Fw.Secondary.copy(color = Fw.Warn)) }
    }
}

/** Settings, Resolution: the game's screen size, from the device profile or picked here. */
@Composable
private fun ResolutionSection(launcher: LauncherController) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(RenderSizes.selected) }
    FwSection("Resolution") {
        Text("The size the game runs at. Higher looks sharper but is harder on the device. A change applies the next time you start the game.", style = Fw.Secondary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RenderSize.entries.forEach { r ->
                val result = remember(r) { fitScreenSizeToWindow(context, launcher.gpu.screenSize, r) }
                FwRow(selected = r == current, onClick = {
                    RenderSizes.save(context, r)
                    current = r
                }) {
                    Text(if (r == RenderSize.AUTO) "Automatic, for this device" else r.label, style = Fw.Text, modifier = Modifier.weight(1f))
                    Text(result, style = Fw.Data.copy(color = Fw.Ash))
                    if (r == current) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                }
            }
        }
    }
}

/** Settings, Region: the Battle.net region the game plays in, written as its portal at the next launch. */
@Composable
private fun RegionSection(launcher: LauncherController) {
    val context = LocalContext.current
    val branch = remember(launcher.gamePath, launcher.files.product) {
        app.gamenative.wow.product.BuildInfo.read(java.io.File(launcher.gamePath))?.active(launcher.files.product)?.get("Branch")
    }
    var current by remember(branch) { mutableStateOf(GameRegions.effective(branch)) }
    FwSection("Region") {
        Text("The Battle.net region you play in. A change applies the next time you start the game.", style = Fw.Secondary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GameRegion.entries.forEach { r ->
                FwRow(selected = r == current, onClick = {
                    GameRegions.save(context, r)
                    current = r
                }) {
                    Text(r.label, style = Fw.Text, modifier = Modifier.weight(1f))
                    Text(r.portal, style = Fw.Data.copy(color = Fw.Ash))
                    if (r == current) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                }
            }
        }
    }
}

/** Settings, Game version: WoW Forever or one of the experimental official versions. */
@Composable
private fun GameVersionSection(launcher: LauncherController, onVersionChanged: () -> Unit) {
    val install by InstallController.state.collectAsState()
    val downloading = install is InstallUiState.Running || install is InstallUiState.Paused
    var pending by remember { mutableStateOf<GameVersion?>(null) }
    fun switch(to: GameVersion) {
        pending = null
        if (launcher.switchVersion(to)) onVersionChanged()
    }
    FwSection("Game version") {
        Text("Foreverywhere is made for WoW Forever. You can also try other official versions of WoW, which are experimental.", style = Fw.Secondary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GameVersion.entries.forEach { v ->
                val current = v == launcher.version
                FwRow(
                    selected = current,
                    onClick = {
                        when {
                            current || downloading || launcher.busy -> Unit
                            v.isExperimental -> pending = v
                            else -> switch(v)
                        }
                    },
                ) {
                    GameIconImage(v.icon, 24.dp) { Box(Modifier.size(24.dp)) }
                    Text(v.displayName, style = Fw.Text, modifier = Modifier.weight(1f))
                    if (v.isExperimental) Text("Experimental", style = Fw.Data.copy(color = Fw.Ash))
                    if (current) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                }
            }
        }
        if (launcher.version == GameVersion.FOREVER) {
            Text("WoW Forever build", style = Fw.Text)
            Text("Which of Blizzard's WoW Forever releases to use. Keep WoW Forever unless Blizzard opens a new beta or test phase.", style = Fw.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ForeverTrack.entries.forEach { t ->
                    FwRow(selected = t == launcher.foreverTrack, onClick = { if (!downloading) launcher.switchForeverTrack(t) }) {
                        Text(t.label, style = Fw.Text, modifier = Modifier.weight(1f))
                        Text(t.product.code, style = Fw.Data.copy(color = Fw.Ash))
                        if (t == launcher.foreverTrack) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                    }
                }
            }
        }
        when {
            downloading -> Text("Finish or cancel the current download before switching.", style = Fw.Secondary.copy(color = Fw.Warn))
            launcher.busy -> Text("Wait for the game to start or the update to finish before switching.", style = Fw.Secondary.copy(color = Fw.Warn))
        }
    }
    pending?.let { v ->
        FwDialog(
            title = "Other WoW versions are experimental",
            message = "Foreverywhere is made for WoW Forever. Other versions may run poorly or not start at all, and they get little support. Your WoW Forever install and addons stay as they are, and you can switch back any time.",
            confirm = "Switch to ${v.displayName}",
            onConfirm = { switch(v) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ProviderLine(name: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = Fw.Text)
        Text(if (enabled) "On" else "Off: no API key", style = Fw.Data.copy(color = if (enabled) Fw.Ok else Fw.Ash))
    }
}
