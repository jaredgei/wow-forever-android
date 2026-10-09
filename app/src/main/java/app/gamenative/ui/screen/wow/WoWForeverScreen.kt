package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.WowClientDownloader.BUILD_INFO
import app.gamenative.ui.screen.wow.WowClientDownloader.EXE_NAME
import app.gamenative.ui.screen.wow.WowClientDownloader.FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.TARGET_PRODUCT
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.renderReleaseNotes
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object WoWLauncherState {
    var shouldAutoLaunch = true
}

private const val CONTAINER_ID = "wow_forever"
private const val READY_STATUS = "Ready to launch"

@Composable
fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpu = remember { GpuProfile.detect(context) }

    var gamePath by remember { mutableStateOf(GamePath.load(context)) }
    var files by remember { mutableStateOf(GamePath.Status()) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var hasSavedLogin by remember { mutableStateOf(BattleNetSignIn.load(context) != null) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(READY_STATUS) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var appUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableFloatStateOf(0f) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var showAppUpdatePermissionDialog by remember { mutableStateOf(false) }
    var pendingInstallPermission by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    suspend fun fetchAppUpdate(): AppUpdater.Release? {
        val release = AppUpdater.check()
        appUpdate = release
        if (release != null) {
            showAppUpdateDialog = true
        }
        return release
    }

    fun checkAppUpdate() {
        scope.launch { fetchAppUpdate() }
    }

    fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            showAppUpdatePermissionDialog = true
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        downloadedApk?.takeIf { it.exists() && it.length() == target.sizeBytes }?.let { apk ->
            installOrRequestPermission(apk)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { progress ->
                    appUpdateProgress = progress
                }
                downloadedApk = apk
                isDownloadingAppUpdate = false
                installOrRequestPermission(apk)
            } catch (e: Exception) {
                Timber.e(e, "Error downloading app update")
                errorMessage = "App update download failed: ${e.message}"
                isDownloadingAppUpdate = false
            }
        }
    }

    suspend fun fetchVersionStatus() {
        isCheckingVersion = true
        versionStatus = withContext(Dispatchers.IO) { WowClientDownloader.checkVersion(File(gamePath)) }
        isCheckingVersion = false
    }

    fun checkVersionStatus() {
        if (!File(gamePath, BUILD_INFO).exists()) return
        scope.launch { fetchVersionStatus() }
    }

    fun checkFiles(): Boolean {
        files = GamePath.status(context, gamePath)
        if (files.isWrongGame) {
            errorMessage = "Incompatible game (${files.product}). WoW Forever requires World of Warcraft Classic Beta ($TARGET_PRODUCT)."
        }
        return files.isReady
    }

    fun denyStorageAccess() {
        files = files.copy(hasStorageAccess = false)
        showStoragePermissionDialog = true
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = READY_STATUS
    }

    fun launchGame() {
        if (isLaunching || files.isWrongGame) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isLaunching = true
        errorMessage = null
        statusText = "Preparing components..."

        launchJob = scope.launch {
            try {
                val containerId = withContext(Dispatchers.IO) {
                    prepareLaunch(context, File(gamePath), gpu) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = msg }
                    }
                }
                statusText = "Booting into World of Warcraft..."
                onLaunch(containerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                isLaunching = false
                statusText = READY_STATUS
                denyStorageAccess()
            } catch (e: Exception) {
                Timber.e(e, "Error launching WoW Forever")
                errorMessage = e.message ?: "Launch failed"
                statusText = "Launch failed"
                isLaunching = false
            }
        }
    }

    LifecycleResumeEffect(gamePath) {
        checkFiles()
        isLaunching = false
        statusText = READY_STATUS
        BattleNetSignIn.removeLoginFile(File(gamePath))
        if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) {
            checkVersionStatus()
        }
        if (appUpdate == null && !WoWLauncherState.shouldAutoLaunch) {
            checkAppUpdate()
        }
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
        onPauseOrDispose {
            launchJob?.cancel()
            launchJob = null
        }
    }

    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = {
            resetLaunch()
            BattleNetSignIn.removeLoginFile(File(gamePath))
        }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path))
        if (root == null) {
            errorMessage = "No $BUILD_INFO found in $path. Pick the folder that contains $BUILD_INFO and Data/."
            return@rememberLauncherForActivityResult
        }
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        versionStatus = null
        val ok = checkFiles()
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
        } else if (ok) {
            checkVersionStatus()
        }
    }

    val filesMissing = !(files.dataExists && files.buildInfoExists)
    val canPlay = !isLaunching && !isUpdating && !filesMissing && !files.isWrongGame

    fun performUpdate() {
        val target = versionStatus ?: return
        if (isUpdating || isLaunching) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = "Connecting to Blizzard CDN..."
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WowClientDownloader.updateGame(File(gamePath), target) { msg ->
                        scope.launch(Dispatchers.Main) { updateStatusText = msg }
                    }
                }
                checkFiles()
                versionStatus = versionStatus?.copy(isOutdated = false, localVersion = target.remoteVersion)
                isUpdating = false
                launchGame()
            } catch (e: Exception) {
                Timber.e(e, "Error updating game")
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = "Update failed: ${e.message}"
                isUpdating = false
            }
        }
    }

    LaunchedEffect(gamePath) {
        val ready = checkFiles()
        if (!files.hasStorageAccess && (files.dataExists || files.buildInfoExists)) {
            showStoragePermissionDialog = true
        }
        Timber.i("WoWForeverScreen LaunchedEffect: gamePath=$gamePath, ready=$ready, hasSavedLogin=$hasSavedLogin, shouldAutoLaunch=${WoWLauncherState.shouldAutoLaunch}")
        when {
            !ready || !hasSavedLogin -> {
                WoWLauncherState.shouldAutoLaunch = false
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                if (ready) {
                    checkVersionStatus()
                    checkAppUpdate()
                }
            }
            !WoWLauncherState.shouldAutoLaunch -> {
                checkVersionStatus()
                checkAppUpdate()
            }
            else -> {
                val updateJob = async { fetchAppUpdate() }
                fetchVersionStatus()
                val update = updateJob.await()
                Timber.i("WoWForeverScreen LaunchedEffect: isOutdated=${versionStatus?.isOutdated}, appUpdate=$update")
                WoWLauncherState.shouldAutoLaunch = false
                if (versionStatus?.isOutdated == true || update != null) {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                } else {
                    launchGame()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "WORLD OF WARCRAFT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 3.sp,
                    color = Color(0xFFF0E6D2)
                )
                Text(
                    text = "FOREVER BETA (ARM64 NATIVE)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = WowGold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "${gpu.edition} Edition", fontSize = 11.sp, color = WowSubtle)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color(0xFF2A3C54), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E).copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SectionTitle("ENVIRONMENT READINESS", WowGold)
                    Spacer(modifier = Modifier.height(14.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckItem(label = if (files.exeExists) "ARM64 Binary ($EXE_NAME)" else "ARM64 Binary (downloads on Play)", ready = files.exeExists)
                        CheckItem(label = "Game Asset Archives (Data/)", ready = files.dataExists)
                        CheckItem(
                            label = if (files.isWrongGame) "Install Info ($BUILD_INFO - requires $TARGET_PRODUCT)" else "Install Info ($BUILD_INFO)",
                            ready = files.buildInfoExists && !files.isWrongGame
                        )
                        CheckItem(label = "All Files Access Permission", ready = files.hasStorageAccess)
                        CheckItem(
                            label = if (hasSavedLogin) "Battle.net Login (Configured)" else "Battle.net Login (Configure below play button)",
                            ready = hasSavedLogin,
                        )
                        appUpdate?.let {
                            CheckItem(
                                label = "App Build: v${BuildConfig.VERSION_NAME} (v${it.version} available)",
                                ready = false,
                            )
                        } ?: CheckItem(
                            label = "App Build: v${BuildConfig.VERSION_NAME}",
                            ready = true,
                        )
                        versionStatus?.let { version ->
                            CheckItem(
                                label = if (version.isOutdated) {
                                    "Client Build: ${version.localVersion} (Update ${version.remoteVersion} available)"
                                } else {
                                    "Client Build: ${version.localVersion} (Up to date)"
                                },
                                ready = !version.isOutdated,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF1E2D44))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = "Target Path: $gamePath", fontSize = 11.sp, color = WowSubtle, maxLines = 2)

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = "Error: $it", fontSize = 12.sp, color = WowError, fontWeight = FontWeight.Medium)
                    }
                }
            }

            versionStatus?.takeIf { it.isOutdated }?.let { version ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, Color(0xFFE53E3E).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1515).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WowError, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle("GAME UPDATE REQUIRED", WowError)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Live servers require build ${version.remoteVersion}, but local files are build ${version.localVersion}. Logging in may fail or disconnect at realm selection.",
                            fontSize = 12.sp,
                            color = Color(0xFFFED7D7),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap Update below to download the latest files directly from Blizzard's CDN over Wi-Fi.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            appUpdate?.let { update ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2838).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle("APP UPDATE AVAILABLE (v${update.version})", WowGold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A new release is available on GitHub. Tap below to view notes and update.",
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PrimaryButton(
                            text = when {
                                isDownloadingAppUpdate -> "DOWNLOADING UPDATE..."
                                downloadedApk?.exists() == true -> "INSTALL UPDATE (v${update.version})"
                                else -> "UPDATE APP (v${update.version})"
                            },
                            icon = Icons.Default.Refresh,
                            enabled = !isDownloadingAppUpdate && !isLaunching && !isUpdating,
                            onClick = {
                                if (downloadedApk?.exists() == true) {
                                    performAppUpdate()
                                } else {
                                    showAppUpdateDialog = true
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLaunching || isUpdating || (isCheckingVersion && WoWLauncherState.shouldAutoLaunch)) {
                    CircularProgressIndicator(color = WowGold, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            isUpdating -> updateStatusText
                            isLaunching -> statusText
                            else -> "Checking for game updates..."
                        },
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        textAlign = TextAlign.Center
                    )
                } else {
                    if (filesMissing) {
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowGold)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("LOCATE GAME FILES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowGold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    when {
                        versionStatus?.isOutdated == true -> {
                            PrimaryButton(
                                text = "UPDATE TO ${versionStatus?.remoteVersion}",
                                icon = Icons.Default.Refresh,
                                enabled = canPlay,
                                onClick = ::performUpdate,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = ::launchGame,
                                enabled = canPlay,
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = "LAUNCH ANYWAY (OUTDATED)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowError)
                            }
                        }
                        !files.hasStorageAccess -> PrimaryButton(
                            text = "GRANT STORAGE PERMISSION",
                            icon = Icons.Default.FolderOpen,
                            enabled = !isLaunching && !isUpdating,
                            onClick = { StorageUtils.requestManageExternalStoragePermission(context) },
                        )
                        else -> PrimaryButton(
                            text = if (files.isWrongGame) "FOREVER BETA REQUIRED" else "PLAY WORLD OF WARCRAFT",
                            icon = Icons.Default.PlayArrow,
                            enabled = canPlay,
                            onClick = ::launchGame,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinkButton("Refresh Status", Icons.Default.Refresh) {
                            checkFiles()
                            checkVersionStatus()
                            checkAppUpdate()
                        }
                        if (!filesMissing) {
                            LinkButton("Change Location", Icons.Default.FolderOpen) { folderPicker.launch(null) }
                        }
                        LinkButton(
                            text = if (hasSavedLogin) "Update Login" else "Configure Login",
                            icon = Icons.Default.Key,
                        ) {
                            showLoginDialog = true
                        }
                        if (hasSavedLogin) {
                            LinkButton("Forget Saved Login", Icons.Default.Delete) {
                                BattleNetSignIn.forget(context)
                                BattleNetSignIn.removeLoginFile(File(gamePath))
                                hasSavedLogin = false
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showLoginDialog) {
        BattleNetCredentialDialog(
            onDismiss = { showLoginDialog = false },
            onConfirm = { login ->
                showLoginDialog = false
                BattleNetSignIn.save(context, login)
                hasSavedLogin = true
            },
        )
    }

    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = { Text(text = "Storage Permission Required", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = "Android requires 'All files access' for WoW Forever to read and update game files in this directory.\n\nPlease enable 'Allow access to manage all files' on the next screen.",
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStoragePermissionDialog = false
                        StorageUtils.requestManageExternalStoragePermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("OPEN SETTINGS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) {
                    Text("CANCEL", color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showAppUpdateDialog) {
        appUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloadingAppUpdate) showAppUpdateDialog = false
                },
                title = {
                    Text(
                        text = "App Update Available (v${update.version})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = WowGold,
                    )
                },
                text = {
                    Column {
                        if (update.notes.isNotBlank()) {
                            Text(
                                text = renderReleaseNotes(update.notes),
                                fontSize = 12.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (isDownloadingAppUpdate) {
                            Text(
                                text = "Downloading: ${(appUpdateProgress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = WowGold,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { appUpdateProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = WowGold,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = ::performAppUpdate,
                        enabled = !isDownloadingAppUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = when {
                                isDownloadingAppUpdate -> "DOWNLOADING..."
                                downloadedApk?.exists() == true -> "INSTALL NOW"
                                else -> "UPDATE NOW"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    if (!isDownloadingAppUpdate) {
                        TextButton(onClick = { showAppUpdateDialog = false }) {
                            Text("LATER", color = WowMuted)
                        }
                    }
                },
                containerColor = Color(0xFF1B2838),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }

    if (showAppUpdatePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAppUpdatePermissionDialog = false },
            title = {
                Text(
                    text = "Install Permission Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = WowGold,
                )
            },
            text = {
                Text(
                    text = "Android requires permission to install apps from WoW Forever.\n\nPlease enable 'Allow from this source' on the next screen.",
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAppUpdatePermissionDialog = false
                        pendingInstallPermission = true
                        AppUpdater.openInstallPermissionSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("OPEN SETTINGS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppUpdatePermissionDialog = false }) {
                    Text("CANCEL", color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }
}

object GamePath {
    private const val PREFS = "wow_forever"
    private const val KEY_GAME_PATH = "game_path"

    private val defaultPath = File(Environment.getExternalStorageDirectory(), "WoW Forever")

    data class Status(
        val exeExists: Boolean = false,
        val dataExists: Boolean = false,
        val buildInfoExists: Boolean = false,
        val product: String = "",
        val hasStorageAccess: Boolean = true,
    ) {
        val isWrongGame get() = buildInfoExists && product.isNotBlank() && product != TARGET_PRODUCT
        val isReady get() = exeExists && dataExists && buildInfoExists && hasStorageAccess && !isWrongGame
    }

    fun status(context: Context, path: String): Status {
        val root = File(path)
        return Status(
            exeExists = File(root, "$FLAVOR_DIR/$EXE_NAME").exists(),
            dataExists = File(root, "Data").listFiles()?.isNotEmpty() == true,
            buildInfoExists = File(root, BUILD_INFO).isFile,
            product = productOf(root).orEmpty(),
            hasStorageAccess = StorageUtils.hasStoragePermission(context, path),
        )
    }

    fun load(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME_PATH, null)?.let(::File)
        val usable = listOfNotNull(saved, defaultPath).firstOrNull { productOf(it) == TARGET_PRODUCT }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    fun save(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GAME_PATH, path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean =
        path.isNotEmpty() && status(context, path).let { it.isReady && it.product == TARGET_PRODUCT }

    fun findGameRoot(picked: File): File? =
        sequenceOf(picked, picked.parentFile, File(picked, "WoW Forever"), File(picked, "World of Warcraft"))
            .filterNotNull()
            .firstOrNull { File(it, BUILD_INFO).isFile }

    private fun productOf(root: File) = WowClientDownloader.readBuildInfo(File(root, BUILD_INFO))?.get("Product")
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = WowMuted)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = WowMuted)
    }
}

@Composable
private fun CheckItem(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready) Color(0xFF48BB78) else Color(0xFFED8936),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (ready) Color(0xFFCBD5E1) else Color(0xFFED8936))
    }
}

private class StorageAccessDeniedException(cause: Throwable) : Exception(cause)

private fun Throwable.isPermissionDenied() =
    message?.let { "EPERM" in it || "Operation not permitted" in it } == true

private const val DEFAULT_DRIVER = "Turnip-WoW-scheduler-test"
private const val ADRENO_8XX_DRIVER = "Turnip-V32-RP6sched"
private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
private const val DXVK_VERSION = "2.4.1-wow-aarch64-test"

private data class GpuProfile(val driver: String, val tuDebug: String, val screenSize: String, val label: String, val edition: String) {
    companion object {
        fun detect(context: Context) = if (isAdreno8xxDevice(context)) {
            GpuProfile(ADRENO_8XX_DRIVER, "noconform,sysmem", "1280x720", "Adreno 8xx", "Snapdragon 8 Elite / Adreno 8xx")
        } else {
            GpuProfile(DEFAULT_DRIVER, "noconform", "1920x1080", "Adreno 740", "Snapdragon 8 Series / Adreno 740")
        }

        private fun isAdreno8xxDevice(context: Context): Boolean {
            if (GPUInformation.isAdreno8Elite(context)) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val soc = Build.SOC_MODEL
                if (soc.contains("SM8750", ignoreCase = true) || soc.contains("SM8850", ignoreCase = true)) return true
            }
            return Build.HARDWARE.contains("sun", ignoreCase = true)
        }
    }
}

private fun prepareLaunch(context: Context, gameRoot: File, gpu: GpuProfile, onStatus: (String) -> Unit): String {
    installBundledComponents(context, onStatus)

    check(File(gameRoot, BUILD_INFO).exists()) { "Missing $BUILD_INFO in $gameRoot. Copy it from your WoW install." }
    try {
        ensureGameConfig(gameRoot)
    } catch (e: Exception) {
        if (e.isPermissionDenied()) throw StorageAccessDeniedException(e)
        throw e
    }

    val arm64Exe = File(gameRoot, "$FLAVOR_DIR/$EXE_NAME")
    BattleNetSignIn.load(context)?.let { login ->
        BattleNetSignIn.writeLoginFile(gameRoot, login)
        BattleNetSignIn.startLoginWatcher(gameRoot)
    }
    if (!arm64Exe.exists()) {
        try {
            WowClientDownloader.download(gameRoot, onStatus)
        } catch (e: Exception) {
            if (!arm64Exe.exists()) throw IllegalStateException("Couldn't download $EXE_NAME: ${e.message}", e)
            Timber.w(e, "Client update check failed, launching the existing $EXE_NAME")
        }
    }

    onStatus("Configuring ${gpu.label} container...")
    val containerManager = ContainerManager(context)
    val config = containerConfig(gameRoot, gpu)
    val container = containerManager.getContainerById(CONTAINER_ID)
    if (container == null) {
        onStatus("Creating prefix environment (first boot)...")
        containerManager.createContainer(CONTAINER_ID, config)
    } else {
        container.loadData(config)
        container.saveData()
    }
    check(containerManager.hasContainer(CONTAINER_ID)) { "Container creation failed. Check system storage and logs." }
    return CONTAINER_ID
}

private fun containerConfig(gameRoot: File, gpu: GpuProfile): JSONObject {
    val samsungEnv = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1" else ""
    val envVars = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact,deck_emu MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=0 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=${gpu.tuDebug} VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144$samsungEnv"
    return JSONObject().apply {
        put("id", CONTAINER_ID)
        put("name", "WoW Forever")
        put("screenSize", gpu.screenSize)
        put("envVars", envVars)
        put("graphicsDriver", "Wrapper")
        put("graphicsDriverVersion", gpu.driver)
        put("graphicsDriverConfig", "version=${gpu.driver},adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high")
        put("displayRenderer", "vulkan")
        put("dxwrapper", "dxvk-$DXVK_VERSION-1")
        put("dxwrapperConfig", "version=$DXVK_VERSION-1")
        put("wineVersion", "$PROTON_VERSION-1")
        put("containerVariant", "bionic")
        put("fexcoreVersion", "2609-0")
        put("drives", "D:/storage/emulated/0/DownloadE:/data/data/app.wowforever/storageG:${gameRoot.path}")
        put("executablePath", "G:\\$FLAVOR_DIR\\$EXE_NAME")
        put("execArgs", "-d3d11")
        put("showFPS", true)
        put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
        put("wow64Mode", true)
        put("wincomponents", "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0,opengl=0")
    }
}

private val CONFIG_DEFAULTS = linkedMapOf(
    "portal" to "\"test\"",
    "agentUID" to "\"$TARGET_PRODUCT\"",
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"enUS\"",
    "audioLocale" to "\"enUS\"",
    "gxMaximize" to "\"1\"",
    "gxWindowedResolution" to "\"1920x1080\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"1\"",
)

private val FORCED_CONFIG_KEYS = listOf("gxApi", "RenderScale", "ResampleQuality", "GamePadEnable", "InputDeviceInterfaceStyle")

private val LEGACY_CONFIG_VALUES = mapOf(
    "farclip" to "\"3000\"",
    "horizonClip" to "\"3000\"",
    "RAIDfarclip" to "\"3000\"",
    "RAIDhorizonClip" to "\"3000\"",
    "worldBaseMip" to "\"2\"",
    "RAIDworldBaseMip" to "\"2\"",
    "graphicsTextureResolution" to "\"0\"",
    "raidGraphicsTextureResolution" to "\"0\"",
    "componentTextureLevel" to "\"1\"",
    "RAIDcomponentTextureLevel" to "\"1\"",
)

private fun ensureGameConfig(root: File) {
    val flavorDir = File(root, FLAVOR_DIR)
    flavorDir.mkdirs()
    val flavorInfo = File(flavorDir, ".flavor.info")
    if (!flavorInfo.exists()) {
        flavorInfo.writeText("Product Flavor!STRING:0\n$TARGET_PRODUCT\n")
    }
    val configWtf = File(flavorDir, "WTF/Config.wtf")
    if (!configWtf.exists()) {
        configWtf.parentFile?.mkdirs()
        configWtf.writeText(CONFIG_DEFAULTS.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        return
    }

    val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
    val existingKeys = mutableSetOf<String>()
    val updated = original.map { line ->
        val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
        if (parts?.size != 2) return@map line
        val (key, value) = parts
        existingKeys.add(key)
        val forcedKey = FORCED_CONFIG_KEYS.firstOrNull { it.equals(key, ignoreCase = true) }
        when {
            forcedKey != null && value != CONFIG_DEFAULTS.getValue(forcedKey) -> "SET $forcedKey ${CONFIG_DEFAULTS.getValue(forcedKey)}"
            LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${CONFIG_DEFAULTS.getValue(key)}"
            else -> line
        }
    } + CONFIG_DEFAULTS.filterKeys { it !in existingKeys }.map { (k, v) -> "SET $k $v" }

    if (updated != original) {
        configWtf.writeText(updated.joinToString("\n") + "\n")
    }
}

private fun installBundledComponents(context: Context, onStatus: (String) -> Unit) {
    installDriver(context, DEFAULT_DRIVER, "turnip-wow-scheduler-test.zip", onStatus)
    installDriver(context, ADRENO_8XX_DRIVER, "turnip-V32-RP6sched.zip", onStatus)

    val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
    if (!File(protonDir, "profile.json").exists() || !File(protonDir, "bin/wine").exists()) {
        onStatus("Extracting Proton 11 ARM64EC (takes ~10s)...")
        check(extractBundledWcp(context, "$PROTON_VERSION.wcp", protonDir)) { "Failed to extract Proton or graphics components." }
        File(protonDir, "bin").listFiles()?.forEach {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }

    val dxvkDir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK), DXVK_VERSION)
    if (!File(dxvkDir, "profile.json").exists()) {
        onStatus("Extracting DXVK ARM64...")
        if (!extractBundledWcp(context, "dxvk-$DXVK_VERSION.wcp", dxvkDir)) Timber.e("Failed to extract DXVK")
    }

    ImageFsInstaller.ensureBionicLib(context, ImageFs.find(context).rootDir)
    ContentsManager(context).syncContents()
}

private fun installDriver(context: Context, driverName: String, assetZip: String, onStatus: (String) -> Unit) {
    val driverDir = File(context.filesDir, "contents/adrenotools/$driverName")
    if (File(driverDir, "meta.json").exists()) return
    onStatus("Installing $driverName driver...")
    driverDir.mkdirs()
    if (!FileUtils.extractZipFromAssets(context, "bundled_components/$assetZip", driverDir)) {
        Timber.e("Error extracting driver $driverName from $assetZip")
    }
}

private fun extractBundledWcp(context: Context, assetName: String, destination: File): Boolean {
    destination.mkdirs()
    return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/$assetName", destination)
}
