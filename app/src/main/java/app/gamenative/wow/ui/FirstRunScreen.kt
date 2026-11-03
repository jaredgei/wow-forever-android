package app.gamenative.wow.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.screen.wow.GamePath
import app.gamenative.ui.screen.wow.WowClientDownloader
import app.gamenative.utils.StorageUtils
import app.gamenative.wow.install.InstallController
import app.gamenative.wow.install.InstallRequest
import app.gamenative.wow.install.InstallUiState
import app.gamenative.wow.product.ForeverTracks
import app.gamenative.wow.product.GameInstall
import app.gamenative.wow.product.GameRegion
import app.gamenative.wow.product.GameRegions
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.product.InstallScanner
import app.gamenative.wow.product.WowProduct
import app.gamenative.wow.ui.components.FwButton
import app.gamenative.wow.ui.components.FwButtonKind
import app.gamenative.wow.ui.components.FwChip
import app.gamenative.wow.ui.components.FwLink
import app.gamenative.wow.ui.components.FwProgress
import app.gamenative.wow.ui.components.FwRow
import app.gamenative.wow.ui.theme.Fw
import com.winlator.core.GPUInformation
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step(val label: String) { WELCOME("Welcome"), FIND("Find game"), INSTALL("Install"), SIGN_IN("Sign in") }

/** First launch: Welcome → find an existing copy → install → sign in → home (spec §5.4). */
@Composable
fun FirstRunScreen(
    launcher: LauncherController,
    onEditLogin: () -> Unit,
    onDone: () -> Unit,
    startAtInstall: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onOpenAddonMode: () -> Unit = {},
) {
    var step by remember { mutableStateOf(if (startAtInstall) Step.INSTALL else Step.WELCOME) }
    var installedFresh by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Fw.Ground)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Step.entries.forEach {
                FwStepPill(it.label, it == step)
            }
            FwStepPill("Home", false)
        }
        HorizontalDivider(color = Fw.Rule)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (step) {
                Step.WELCOME -> Welcome(launcher.version, onNext = { step = Step.FIND }, onOpenSettings = onOpenSettings, onOpenAddonMode = onOpenAddonMode)
                Step.FIND -> FindGame(
                    launcher,
                    onUse = { step = Step.SIGN_IN },
                    onInstall = { step = Step.INSTALL },
                )
                Step.INSTALL -> InstallStep(launcher, onDone = {
                    installedFresh = true
                    step = Step.SIGN_IN
                }, onBack = { step = Step.FIND })
                Step.SIGN_IN -> SignIn(launcher, onEditLogin, onDone, installedFresh)
            }
        }
    }
}

@Composable
private fun FwStepPill(label: String, current: Boolean) {
    Text(
        label,
        style = Fw.Label.copy(fontSize = 13.sp, color = if (current) Fw.Ground else Fw.Ash),
        modifier = Modifier.background(if (current) Fw.Bone else Fw.Stone, Fw.Pill).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(version: GameVersion, onNext: () -> Unit, onOpenSettings: () -> Unit, onOpenAddonMode: () -> Unit) {
    val context = LocalContext.current
    val check = remember { DeviceCheck.run(context) }
    Text("${version.displayName}, on this device", style = Fw.Hero.copy(fontSize = 40.sp, lineHeight = 44.sp))
    Text(
        if (version == GameVersion.FOREVER) {
            "This app installs Blizzard's WoW Forever client, keeps it updated, manages your addons and starts the game. You need a Battle.net account with WoW Forever. Nothing here comes from Blizzard: it's an unofficial community app."
        } else {
            "This app installs Blizzard's ${version.displayName} client, keeps it updated, manages your addons and starts the game. Versions other than WoW Forever are experimental. Nothing here comes from Blizzard: it's an unofficial community app."
        },
        style = Fw.Text,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        check.forEach { (label, ok) ->
            Text((if (ok) "✓  " else "!  ") + label, style = Fw.Text.copy(color = if (ok) Fw.Ok else Fw.Warn))
        }
    }
    if (check.any { !it.second }) {
        Text("The game may not run well on this device. You can still continue.", style = Fw.Secondary)
    }
    // Side by side when they fit, stacked on a narrow (portrait) screen.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FwButton("Get started", onNext)
        FwButton("Playing another version of WoW?", onOpenSettings, kind = FwButtonKind.Ghost)
    }
    FwLink("Already play WoW another way? Use the app only as an addon manager", onOpenAddonMode)
}

object DeviceCheck {
    fun run(context: Context): List<Pair<String, Boolean>> {
        val adreno = runCatching { GPUInformation.isAdrenoGPU(context) }.getOrDefault(false)
        val renderer = runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty()
        val is64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
        return listOf(
            (if (adreno) "Adreno GPU ($renderer)" else "Not an Adreno GPU: the game needs a Snapdragon chip") to adreno,
            "64-bit ARM processor" to is64,
            "Android ${Build.VERSION.RELEASE}" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
        )
    }
}

@Composable
private fun FindGame(launcher: LauncherController, onUse: () -> Unit, onInstall: () -> Unit) {
    val context = LocalContext.current
    var found by remember { mutableStateOf<List<GameInstall>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Rescans whenever the screen comes back, e.g. after granting "All files access" in Settings.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val job = scope.launch { found = withContext(Dispatchers.IO) { InstallScanner.scan(InstallScanner.storageRoots(context)) } }
        onPauseOrDispose { job.cancel() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path))
        if (root == null) {
            launcher.errorMessage = "No ${launcher.version.displayName} install in $path."
        } else {
            launcher.useGameRoot(root.absolutePath)
            onUse()
        }
    }

    Text("Where's the game?", style = Fw.Title)
    if (!StorageUtils.hasStoragePermission(context, "/storage/emulated/0")) {
        Text("To look for the game and install it, the app needs \"All files access\".", style = Fw.Text)
        FwButton("Allow file access", launcher::openStorageSettings)
    }
    when (val installs = found) {
        null -> Text("Looking on internal storage and SD cards…", style = Fw.Secondary)
        else -> {
            if (installs.isEmpty()) {
                Text("No copy of ${launcher.version.displayName} found on this device.", style = Fw.Text)
            } else {
                Text("Found ${installs.size} install${if (installs.size > 1) "s" else ""}. Pick one to use it as it is, without downloading again.", style = Fw.Text)
                Column {
                    installs.forEach { install ->
                        FwRow(selected = false, onClick = {
                            launcher.selectInstall(install)
                            onUse()
                        }) {
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
    launcher.errorMessage?.let { Text(it, style = Fw.Secondary.copy(color = Fw.Warn)) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FwButton("Install fresh", onInstall, kind = if (found.isNullOrEmpty()) FwButtonKind.Primary else FwButtonKind.Ghost)
        FwButton("Choose folder", { picker.launch(null) }, kind = FwButtonKind.Ghost)
    }
}

/** Storage choice, product, language, then live progress from [InstallController]. */
@Composable
fun InstallStep(launcher: LauncherController, onDone: () -> Unit, onBack: () -> Unit, repair: GameInstall? = null) {
    val context = LocalContext.current
    val state by InstallController.state.collectAsState()
    val roots = remember { InstallScanner.storageRoots(context) }
    var rootIndex by remember { mutableStateOf(if (roots.size > 1) 1 else 0) }
    var product by remember { mutableStateOf<WowProduct?>(null) }
    var region by remember { mutableStateOf(GameRegions.effective(null)) }
    val locale = remember { deviceLocale() }
    // Android 13+ hides the progress notification unless the app may post notifications. The download
    // starts either way; this only asks once per screen visit.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun start(req: InstallRequest) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        InstallController.start(context, req)
    }

    LaunchedEffect(Unit) {
        // WoW Forever installs the chosen track; the beta only if Blizzard lists no launch build yet.
        product = launcher.version.products.singleOrNull() ?: ForeverTracks.selected.product.takeIf { it == WowProduct.BETA }
            ?: withContext(Dispatchers.IO) {
                if (runCatching { WowClientDownloader.isProductLive(WowProduct.FOREVER) }.getOrDefault(false)) WowProduct.FOREVER else WowProduct.BETA
            }
    }
    LaunchedEffect(state) {
        (state as? InstallUiState.Done)?.let {
            launcher.selectInstall(it.install)
            InstallController.reset()
            onDone()
        }
    }

    Text("Install ${launcher.version.displayName}", style = Fw.Title)
    when (val s = state) {
        is InstallUiState.Running -> {
            Text("Downloading from Blizzard's servers to ${s.request.root}. You can leave the app: the download keeps going and shows progress in your notifications.", style = Fw.Text)
            val p = s.progress
            Text(
                if (p.totalBytes > 0) p.sizeText else "Reading the build manifest…",
                style = Fw.Data.copy(color = Fw.Bone),
            )
            FwProgress(p.fraction)
            p.stepText?.let { Text(it, style = Fw.Data) }
            Text(STREAMING_NOTE, style = Fw.Secondary)
            FwButton("Pause", { InstallController.pause(context) }, kind = FwButtonKind.Ghost)
        }
        is InstallUiState.Paused -> {
            Text(s.reason ?: "Download paused. Everything downloaded so far is kept.", style = Fw.Text)
            s.progress?.let { FwProgress(it.fraction) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton("Resume", { InstallController.resume(context) })
                FwButton("Cancel download", { InstallController.cancel(context) }, kind = FwButtonKind.Ghost)
                FwButton("Back", { InstallController.cancel(context); onBack() }, kind = FwButtonKind.Ghost)
            }
        }
        is InstallUiState.Failed -> {
            Text("The download stopped: ${s.message}", style = Fw.Text.copy(color = Fw.Warn))
            Text("Files already downloaded are kept and checked when you try again.", style = Fw.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton("Try again", { InstallController.resume(context) })
                FwButton("Back", { InstallController.cancel(context); onBack() }, kind = FwButtonKind.Ghost)
            }
        }
        else -> if (repair != null) {
            Text("Repair checks every game file in ${repair.root} and downloads only what's missing or damaged.", style = Fw.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton("Check and repair", { start(InstallRequest(File(repair.root), repair.product, locale)) })
                FwButton("Back", onBack, kind = FwButtonKind.Ghost)
            }
        } else {
            Text("Choose where to put the game. It needs about 80 GB of free space.", style = Fw.Text)
            Column {
                roots.forEachIndexed { i, root ->
                    val free = root.usableSpace
                    FwRow(selected = i == rootIndex, onClick = { rootIndex = i }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (i == 0) "Internal storage" else "SD card / external storage", style = Fw.Text)
                            Text("${File(root, launcher.version.defaultFolder)}", style = Fw.Data)
                        }
                        Text("%.0f GB free".format(free / 1e9), style = Fw.Data.copy(color = if (free > 80e9) Fw.Ok else Fw.Warn))
                        if (i == rootIndex) Text("Selected", style = Fw.Data.copy(color = Fw.Brass))
                    }
                }
            }
            Text("Version: ${product?.displayName ?: "checking…"}  ·  Language: $locale", style = Fw.Data)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Region", style = Fw.Text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameRegion.entries.forEach { r -> FwChip("${r.label} (${r.portal})", r == region, { region = r }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FwButton(
                    "Download and install",
                    {
                        val p = product ?: return@FwButton
                        GameRegions.save(context, region)
                        start(InstallRequest(File(roots[rootIndex], launcher.version.defaultFolder), p, locale, region = region.code))
                    },
                    enabled = product != null && roots.isNotEmpty(),
                )
                FwButton("Back", onBack, kind = FwButtonKind.Ghost)
            }
        }
    }
}

internal const val STREAMING_NOTE =
    "This download is the game program, about half a gigabyte. The first time you start the game, it downloads the rest of its world data (about 67 GB) by itself in the background, so your first sessions may load slowly. Leave it at the login screen on Wi-Fi to speed this up."

internal const val FIRST_START_NOTE =
    "The first time you start the game, it downloads the rest of its world data (about 67 GB) by itself in the background, so your first sessions may load slowly. Leave it at the login screen on Wi-Fi to speed this up."

/** The device language if WoW ships it, else English (spec §6.6). */
private fun deviceLocale(): String {
    val tag = Locale.getDefault().let { it.language + it.country }
    return WOW_LOCALES.firstOrNull { it.equals(tag, ignoreCase = true) } ?: "enUS"
}

private val WOW_LOCALES = listOf("enUS", "enGB", "deDE", "esES", "esMX", "frFR", "itIT", "koKR", "ptBR", "ruRU", "zhCN", "zhTW")

@Composable
private fun SignIn(launcher: LauncherController, onEditLogin: () -> Unit, onDone: () -> Unit, installedFresh: Boolean) {
    Text("Sign in", style = Fw.Title)
    Text(
        "Save your Battle.net email and password so the game logs you in by itself. They're encrypted on this device. Authenticator codes are still typed in the game.",
        style = Fw.Text,
    )
    if (launcher.hasSavedLogin) Text("Login saved.", style = Fw.Text.copy(color = Fw.Ok))
    if (installedFresh) Text(FIRST_START_NOTE, style = Fw.Secondary)
    Text("Foreverywhere is free. If you'd like to support it, there's a Ko-fi link in Settings.", style = Fw.Secondary)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (launcher.hasSavedLogin) {
            FwButton("Continue", onDone)
        } else {
            FwButton("Save login", onEditLogin)
            FwButton("Skip for now", onDone, kind = FwButtonKind.Ghost)
        }
    }
}
