package app.gamenative.wow.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.BattleNetSignIn
import app.gamenative.ui.screen.wow.GamePath
import app.gamenative.ui.screen.wow.GpuProfile
import app.gamenative.ui.screen.wow.StorageAccessDeniedException
import app.gamenative.ui.screen.wow.WoWLauncherState
import app.gamenative.ui.screen.wow.WowClientDownloader
import app.gamenative.ui.screen.wow.fitScreenSizeToWindow
import app.gamenative.ui.screen.wow.installLocale
import app.gamenative.wow.install.GameData
import app.gamenative.wow.install.GameInstaller
import app.gamenative.wow.install.DownloadSpeed
import app.gamenative.wow.install.InstallController
import app.gamenative.wow.install.InstallProgress
import app.gamenative.wow.install.InstallRequest
import app.gamenative.wow.install.InstallUiState
import app.gamenative.ui.screen.wow.isPermissionDenied
import app.gamenative.ui.screen.wow.prepareLaunch
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.StorageUtils
import app.gamenative.wow.product.GameInstall
import app.gamenative.wow.product.ForeverTrack
import app.gamenative.wow.product.ForeverTracks
import app.gamenative.wow.product.GameVersion
import app.gamenative.wow.product.GameRegions
import app.gamenative.wow.product.RenderSizes
import app.gamenative.wow.FrameGeneration
import app.gamenative.wow.product.GameVersions
import app.gamenative.wow.product.InstallRegistry
import app.gamenative.wow.product.ProductConverter
import app.gamenative.wow.product.WowProduct
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Launch, game-update and app-update state for the active install. The logic is moved from upstream's
 * `WoWForeverScreen` with its behavior kept: auto-launch on start when everything is ready, an outdated
 * game blocks Play with "Update" / "Launch anyway", and login.txt is wiped on resume and force-close.
 */
class LauncherController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLaunch: (appId: String) -> Unit,
) {
    val registry = InstallRegistry(context)
    internal val gpu by lazy { GpuProfile.detect(context) }

    /** The game version the whole app works with (Settings, Game version). */
    var version by mutableStateOf(GameVersions.selected)
        private set

    /** WoW Forever's launch game or beta (Settings, under Game version). */
    var foreverTrack by mutableStateOf(ForeverTracks.selected)
        private set

    var gamePath by mutableStateOf(registry.active()?.root ?: GamePath.load(context))
        private set
    var files by mutableStateOf(GamePath.Status())
        private set
    var hasSavedLogin by mutableStateOf(BattleNetSignIn.load(context) != null)
        private set

    var isLaunching by mutableStateOf(false)
        private set
    var statusText by mutableStateOf(READY)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    var needsStoragePermission by mutableStateOf(false)

    var versionStatus by mutableStateOf<WowClientDownloader.VersionCheckResult?>(null)
        private set
    var isCheckingVersion by mutableStateOf(false)
        private set

    /** False until the first version check has finished (successfully or not). */
    var versionChecked by mutableStateOf(false)
        private set
    var isUpdating by mutableStateOf(false)
        private set
    var updateStatusText by mutableStateOf("")
        private set

    var appUpdate by mutableStateOf<AppUpdater.Release?>(null)
        private set
    var isDownloadingAppUpdate by mutableStateOf(false)
        private set
    var appUpdateProgress by mutableFloatStateOf(0f)
        private set
    var downloadedApk by mutableStateOf<File?>(null)
        private set
    var needsInstallPermission by mutableStateOf(false)

    /**
     * Set when Play is pressed on a device without an Adreno GPU (an emulator, a non-Snapdragon phone).
     * The game's graphics stack crashes the whole app there, so ask before launching.
     */
    var unsupportedDeviceWarning by mutableStateOf(false)
    private val supportedGpu = runCatching { com.winlator.core.GPUInformation.isAdrenoGPU(context) }.getOrDefault(true)

    /** The switch to the launch version is running; [convertProgress] and [convertSpeed] follow it. */
    var isConverting by mutableStateOf(false)
        private set
    var convertProgress by mutableStateOf<InstallProgress?>(null)
        private set
    var convertSpeed by mutableStateOf<Long?>(null)
        private set

    /** The "switch to WoW Forever" prompt was answered with Later; it comes back the next time the app opens. */
    var switchPromptDismissed by mutableStateOf(false)

    /** Launch-version product is live and this install only has the beta. */
    var canConvertToLaunch by mutableStateOf(false)
        private set

    var isClearingData by mutableStateOf(false)
        private set

    private var launchJob: Job? = null
    private var pendingInstallPermission = false

    val filesMissing get() = !(files.dataExists && files.buildInfoExists)
    val canPlay get() = !busy && !filesMissing && !files.isWrongGame
    val busy get() = isLaunching || isUpdating || isClearingData

    /** Deletes the world data so the game downloads it again. The caller has checked the game and downloads are stopped. */
    fun clearGameData() {
        if (busy || PluviaApp.xEnvironment != null) return
        isClearingData = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { GameData.clear(File(gamePath)) }
                checkFiles()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing game data")
                if (e.isPermissionDenied()) needsStoragePermission = true
                errorMessage = "Couldn't clear the game data: ${e.message}"
            } finally {
                isClearingData = false
            }
        }
    }

    fun checkFiles(): Boolean {
        files = GamePath.status(context, gamePath)
        if (files.isWrongGame) errorMessage = "This folder holds ${files.productCode}, not ${version.displayName}."
        return files.isReady
    }

    /** True while a game download is running or paused; switching versions waits for it. */
    val installInProgress get() = InstallController.state.value.let { it is InstallUiState.Running || it is InstallUiState.Paused }

    /**
     * Switches the whole app to [to] and points it at that version's install. Refused (false) while a
     * download, update or launch is under way.
     */
    /** Picks WoW Forever's launch game or beta, then re-reads the install for it. Refused while busy. */
    fun switchForeverTrack(to: ForeverTrack): Boolean {
        if (to == foreverTrack) return true
        if (busy || installInProgress) return false
        ForeverTracks.save(context, to)
        foreverTrack = to
        versionStatus = null
        versionChecked = false
        canConvertToLaunch = false
        if (checkFiles()) checkVersionStatus()
        return true
    }

    fun switchVersion(to: GameVersion): Boolean {
        if (to == version) return true
        if (busy || installInProgress) return false
        GameVersions.save(context, to)
        version = to
        errorMessage = null
        versionStatus = null
        versionChecked = false
        canConvertToLaunch = false
        gamePath = registry.active()?.root ?: GamePath.load(context)
        if (checkFiles()) checkVersionStatus()
        return true
    }

    fun refresh() {
        checkFiles()
        checkVersionStatus()
        checkAppUpdate()
    }

    fun selectInstall(install: GameInstall) {
        registry.add(install)
        registry.setActive(install.root)
        useGameRoot(install.root)
    }

    /** Points the launcher at a game root picked by the user (folder picker or scan). */
    fun useGameRoot(root: String) {
        errorMessage = null
        gamePath = root
        GamePath.save(context, root)
        versionStatus = null
        val ok = checkFiles()
        files.product?.let { registry.add(GameInstall(root, it, versionStatus?.localVersion.orEmpty())) }
        registry.setActive(root)
        if (!files.hasStorageAccess) needsStoragePermission = true else if (ok) checkVersionStatus()
    }

    fun onLoginSaved(login: BattleNetSignIn.Login) {
        if (BattleNetSignIn.save(context, login)) {
            hasSavedLogin = true
        } else {
            errorMessage = "Couldn't save your login on this device. You can still sign in inside the game."
        }
    }

    fun forgetLogin() {
        BattleNetSignIn.forget(context)
        BattleNetSignIn.removeLoginFile(File(gamePath))
        hasSavedLogin = false
    }

    private suspend fun fetchVersionStatus() {
        isCheckingVersion = true
        val product = files.product
        versionStatus = withContext(Dispatchers.IO) { WowClientDownloader.checkVersion(File(gamePath), product) }
        canConvertToLaunch = product == WowProduct.BETA && ForeverTracks.selected == ForeverTrack.LAUNCH &&
            withContext(Dispatchers.IO) { runCatching { WowClientDownloader.isProductLive(WowProduct.FOREVER) }.getOrDefault(false) }
        isCheckingVersion = false
        versionChecked = true
    }

    fun checkVersionStatus() {
        if (!File(gamePath, WowClientDownloader.BUILD_INFO).exists()) return
        scope.launch { fetchVersionStatus() }
    }

    private suspend fun fetchAppUpdate(): AppUpdater.Release? = AppUpdater.check().also { appUpdate = it }

    fun checkAppUpdate() {
        scope.launch { fetchAppUpdate() }
    }

    fun launchGame(confirmedUnsupported: Boolean = false) {
        val product = files.product
        if (isLaunching || product == null) return
        if (!supportedGpu && !confirmedUnsupported) {
            unsupportedDeviceWarning = true
            return
        }
        if (!files.hasStorageAccess) {
            needsStoragePermission = true
            return
        }
        isLaunching = true
        errorMessage = null
        statusText = "Preparing components..."
        launchJob = scope.launch {
            try {
                val screenSize = fitScreenSizeToWindow(context, gpu.screenSize)
                val containerId = withContext(Dispatchers.IO) {
                    prepareLaunch(context, File(gamePath), product, gpu, screenSize) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = msg }
                    }
                }
                statusText = "Booting into World of Warcraft..."
                onLaunch(containerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                resetLaunch()
                needsStoragePermission = true
            } catch (e: Exception) {
                Timber.e(e, "Error launching WoW Forever")
                errorMessage = e.message ?: "Launch failed"
                statusText = "Launch failed"
                isLaunching = false
            }
        }
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = READY
    }

    fun performUpdate() {
        val target = versionStatus ?: return
        val product = files.product ?: return
        if (busy) return
        if (!files.hasStorageAccess) {
            needsStoragePermission = true
            return
        }
        isUpdating = true
        updateStatusText = "Connecting to Blizzard CDN..."
        errorMessage = null
        scope.launch {
            try {
                // The full installer, not upstream's binaries-only updater: patches also change the
                // client's .dat/.pak/.bin files (see the installer spike notes).
                val root = File(gamePath)
                GameInstaller().run(InstallRequest(root, product, installLocale(root, product))) { p ->
                    scope.launch(Dispatchers.Main) {
                        updateStatusText = when (p.phase) {
                            InstallProgress.Phase.RESOLVING -> "Reading build ${target.remoteVersion}..."
                            InstallProgress.Phase.DOWNLOADING -> p.fraction?.let { "Updating game files ${(it * 100).toInt()}%" } ?: "Updating game files..."
                            else -> "Finishing update..."
                        }
                    }
                }
                checkFiles()
                versionStatus = versionStatus?.copy(isOutdated = false, localVersion = target.remoteVersion)
                isUpdating = false
                launchGame()
            } catch (e: Exception) {
                Timber.e(e, "Error updating game")
                if (e.isPermissionDenied()) needsStoragePermission = true
                errorMessage = "Update failed: ${e.message}"
                isUpdating = false
            }
        }
    }

    /** Adds the launch product to a beta install, reusing its Data/, then downloads the launch client. */
    fun convertToLaunch() {
        if (busy || files.product != WowProduct.BETA) return
        isUpdating = true
        updateStatusText = "Switching to the launch version..."
        errorMessage = null
        isConverting = true
        convertProgress = null
        convertSpeed = null
        val speed = DownloadSpeed()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val root = File(gamePath)
                    ProductConverter.applyLocal(root, WowProduct.BETA, WowProduct.FOREVER)
                    // The installer always asks the patch service for the launch build (and its KeyRing), so
                    // offline it fails cleanly instead of reusing the beta build keys cloned into the new row.
                    GameInstaller().run(InstallRequest(root, WowProduct.FOREVER, installLocale(root, WowProduct.BETA))) { p ->
                        val bytesPerSecond = speed.add(p.fetchedBytes, System.nanoTime() / 1_000_000)
                        scope.launch(Dispatchers.Main) {
                            convertProgress = p
                            if (bytesPerSecond != null) convertSpeed = bytesPerSecond
                        }
                    }
                }
                canConvertToLaunch = false
                isUpdating = false
                isConverting = false
                convertProgress = null
                useGameRoot(gamePath)
            } catch (e: Exception) {
                Timber.e(e, "Error converting to launch product")
                if (e.isPermissionDenied()) needsStoragePermission = true
                errorMessage = "Couldn't switch to the launch version: ${e.message}"
                isUpdating = false
                isConverting = false
                convertProgress = null
            }
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        downloadedApk?.takeIf { it.exists() && it.length() == target.sizeBytes }?.let {
            installOrRequestPermission(it)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { appUpdateProgress = it }
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

    private fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            needsInstallPermission = true
        }
    }

    fun openInstallPermissionSettings() {
        needsInstallPermission = false
        pendingInstallPermission = true
        AppUpdater.openInstallPermissionSettings(context)
    }

    fun openStorageSettings() {
        needsStoragePermission = false
        StorageUtils.requestManageExternalStoragePermission(context)
    }

    fun onResume() {
        // An install may have finished in the background service while another screen was showing.
        registry.active()?.root?.takeIf { it != gamePath && !files.isReady }?.let { useGameRoot(it) }
        checkFiles()
        isLaunching = false
        statusText = READY
        BattleNetSignIn.removeLoginFile(File(gamePath))
        if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) checkVersionStatus()
        if (appUpdate == null && !WoWLauncherState.shouldAutoLaunch) checkAppUpdate()
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
    }

    fun onPause() {
        launchJob?.cancel()
        launchJob = null
    }

    fun onGameEnded() {
        resetLaunch()
        BattleNetSignIn.removeLoginFile(File(gamePath))
    }

    /** Start-up in addon manager mode: no game checks and never an auto-launch, only the app update check. */
    fun startAddonMode() {
        WoWLauncherState.shouldAutoLaunch = false
        PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        checkAppUpdate()
    }

    /** Upstream's start-up sequence: auto-launch only when files, login, game and app are all current. */
    suspend fun start() = coroutineScope {
        val ready = checkFiles()
        if (!files.hasStorageAccess && (files.dataExists || files.buildInfoExists)) needsStoragePermission = true
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
                WoWLauncherState.shouldAutoLaunch = false
                if (shouldAutoLaunch(versionStatus?.isOutdated == true, update != null, canConvertToLaunch, supportedGpu)) {
                    launchGame()
                } else {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                }
            }
        }
    }

    // Read the install before the first frame, so screens built from this state never start out empty.
    init {
        checkFiles()
    }

    /** Settings, Share debug report: this launcher's state as "label: value" lines. */
    fun debugSummary(): List<String> = listOf(
        "GPU profile: ${gpu.edition}, driver ${gpu.driver}, ${gpu.screenSize}",
        "GPU renderer: ${runCatching { com.winlator.core.GPUInformation.getRenderer(context) }.getOrNull() ?: "unknown"}",
        "Game version: ${version.displayName}" + if (version == GameVersion.FOREVER) ", ${foreverTrack.label}" else "",
        "Region: ${GameRegions.chosen?.label ?: "not chosen (default)"}",
        "Resolution: ${RenderSizes.selected.label}",
        "Frame generation: dll ${if (FrameGeneration.hasDll(context)) "added" else "not added"}, ${if (FrameGeneration.isEnabled(context)) "on" else "off"}",
        "Game folder: $gamePath",
        "Installed: ${files.product?.displayName ?: "none"} ${versionStatus?.localVersion.orEmpty()}",
        "Live version: ${versionStatus?.remoteVersion ?: "unknown"}",
        "Saved login: ${if (hasSavedLogin) "yes" else "no"}",
    )

    /** The product folder holding the game's own Logs and Errors. */
    fun gameFolder(): File? = files.product?.let { File(gamePath, it.flavorDir) }

    /** Values the debug report must never contain. */
    fun debugSecrets(): List<String> = BattleNetSignIn.load(context)?.let { listOf(it.email, it.password) }.orEmpty()

    private companion object {
        const val READY = "Ready to launch"
    }

}

/**
 * Whether the app starts the game by itself on opening: not when the game or the app needs an update,
 * when a beta install should switch to WoW Forever first, or on a device the game can't run on.
 */
internal fun shouldAutoLaunch(gameOutdated: Boolean, appUpdate: Boolean, switchToLaunch: Boolean, supportedGpu: Boolean): Boolean =
    !gameOutdated && !appUpdate && !switchToLaunch && supportedGpu
