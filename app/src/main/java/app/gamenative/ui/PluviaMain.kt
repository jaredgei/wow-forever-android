package app.gamenative.ui

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.gamenative.BuildConfig
import app.gamenative.Constants
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.wow.GamePath
import app.gamenative.ui.screen.wow.WoWForeverScreen
import app.gamenative.ui.screen.wow.WoWLauncherState
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.Net
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.StringUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFSLegacyMigrator
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.Date
import java.util.EnumSet
import kotlin.reflect.KFunction2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val SNACKBAR_SHOW_TIMEOUT_MS = 15_000L


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }



    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }


    var gameBackAction by remember { mutableStateOf<() -> Unit?>({}) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.LaunchApp -> {
                    if (state.showBootingSplash) {
                        navController.navigate(PluviaScreen.XServer.route)
                    }
                }

                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (PluviaApp.keepAlive) {
                        gameBackAction?.invoke() ?: run { navController.popBackStack() }
                    } else if (hasBack) {
                        navController.popBackStack()
                    }
                }
            }
        }
    }

    LaunchedEffect(navController) {
        Timber.i("navController changed")

        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)

            Timber.i("Creating on destination changed listener")

            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                Timber.i("onDestinationChanged to ${destination.route}")
                viewModel.setCurrentScreen(destination.route)
            }
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }

        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            // Log.d("PluviaMain", "Screen changed to $currentScreen, resetting some values")
            // TODO: remove this if statement once XServerScreen orientation change bug is fixed
            if (state.currentScreen != PluviaScreen.XServer) {
                // Hide or show status bar based on if in game or not
                val shouldShowStatusBar = !PrefManager.hideStatusBarWhenNotInGame
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(shouldShowStatusBar))

                // reset system ui visibility based on user preference
                // TODO: add option for user to set
                // reset available orientations
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    var onActionClick: (() -> Unit)? = null
    when (msgDialogState.type) {

        DialogType.SYNC_FAIL -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.EXECUTABLE_NOT_FOUND -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    val snackbarController = LocalSnackbarHostController.current
    var exitSnackbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarController) {
        SnackbarManager.messages.collect { message ->
            if (
                withTimeoutOrNull(SNACKBAR_SHOW_TIMEOUT_MS) {
                    snackbarController.hostState.showSnackbar(message)
                } == null
            ) {
                Timber.w("[Snackbar]: Display timed out before dismissal")
            }
            // snackbar dismissed (timeout or new message) — reset exit flag
            exitSnackbarVisible = false
        }
    }

    BackHandler(enabled = state.loadingDialogVisible && !PluviaApp.keepAlive) {
        // TODO: Make prelaunch/loading operations cancellable so Back can exit safely.
    }

    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingDialog(
                visible = state.loadingDialogVisible,
                progress = state.loadingDialogProgress,
                message = state.loadingDialogMessage,
            )

            MessageDialog(
                visible = msgDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                confirmBtnText = msgDialogState.confirmBtnText,
                onDismissClick = onDismissClick,
                dismissBtnText = msgDialogState.dismissBtnText,
                onActionClick = onActionClick,
                actionBtnText = msgDialogState.actionBtnText,
                icon = msgDialogState.type.icon,
                title = msgDialogState.title,
                message = msgDialogState.message,
            )

            var initialSplash by remember {
                mutableStateOf(WoWLauncherState.shouldAutoLaunch && GamePath.isReady(context))
            }
            var preLaunchJob by remember { mutableStateOf<Job?>(null) }

            DisposableEffect(Unit) {
                val clearSplash: (AndroidEvent.ClearBootingSplash) -> Unit = { initialSplash = false }
                val forceClose: (AndroidEvent.ForceCloseApp) -> Unit = {
                    initialSplash = false
                    preLaunchJob?.cancel()
                    preLaunchJob = null
                    WoWLauncherState.shouldAutoLaunch = false
                }
                PluviaApp.events.on<AndroidEvent.ClearBootingSplash, Unit>(clearSplash)
                PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(forceClose)
                onDispose {
                    PluviaApp.events.off<AndroidEvent.ClearBootingSplash, Unit>(clearSplash)
                    PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(forceClose)
                }
            }

            Box(modifier = Modifier.zIndex(10f)) {
                BootingSplash(
                    visible = state.showBootingSplash || initialSplash,
                    text = if (state.showBootingSplash) state.bootingSplashText else "Booting into World of Warcraft...",
                    onAbort = {
                        initialSplash = false
                        preLaunchJob?.cancel()
                        preLaunchJob = null
                        viewModel.setLoadingDialogVisible(false)
                        WoWLauncherState.shouldAutoLaunch = false
                        viewModel.abortBoot()
                    },
                )
            }

            val startDestination = PluviaScreen.WoWLauncher.route

            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(route = PluviaScreen.WoWLauncher.route) {
                    WoWForeverScreen(
                        onLaunch = { appId ->
                            viewModel.setLaunchedAppId(appId)
                            viewModel.setOffline(true)
                            preLaunchJob?.cancel()
                            preLaunchJob = preLaunchApp(
                                context = context,
                                appId = appId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = { msgDialogState = it },
                                onSuccess = viewModel::launchApp,
                            )
                        },
                    )
                }
                /** Game Screen **/
                composable(route = PluviaScreen.XServer.route) {
                    val xServerIsOffline by viewModel.isOffline.collectAsStateWithLifecycle()
                    val launchedAppId = state.launchedAppId
                    val hasContainer = remember(launchedAppId) {
                        launchedAppId.isNotEmpty() && ContainerUtils.hasContainer(context, launchedAppId)
                    }
                    if (!hasContainer) {
                        LaunchedEffect(launchedAppId) {
                            Timber.w("XServer route entered without a container for '$launchedAppId', returning to WoW launcher")
                            navController.navigate(PluviaScreen.WoWLauncher.route) {
                                popUpTo(PluviaScreen.XServer.route) { inclusive = true }
                            }
                        }
                        return@composable
                    }
                    XServerScreen(
                        appId = state.launchedAppId,
                        isOffline = xServerIsOffline,
                        registerBackAction = { cb ->
                            Timber.d("registerBackAction called: $cb")
                            gameBackAction = cb
                        },
                        navigateBack = {
                            CoroutineScope(Dispatchers.Main).launch {
                                val currentRoute = navController.currentBackStackEntry
                                    ?.destination
                                    ?.route

                                if (currentRoute == PluviaScreen.XServer.route) {
                                    navController.popBackStack()
                                }
                            }
                        },
                        onWindowMapped = { context, window ->
                            viewModel.onWindowMapped(context, window, state.launchedAppId)
                        },
                        onExit = { onComplete ->
                            viewModel.exitApp(context, state.launchedAppId, onComplete)
                        },
                        onGameLaunchError = { error ->
                            viewModel.onGameLaunchError(error)
                        },
                    )
                }
            }

            if (snackbarController.rootOwnsHost) {
                SnackbarHost(
                    hostState = snackbarController.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(bottom = 16.dp),
                ) { data ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 4.dp,
                        ) {
                            Text(
                                text = data.visuals.message,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

        }
    }
}

fun preLaunchApp(
    context: Context,
    appId: String,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: KFunction2<Context, String, Unit>,
): Job {
    setLoadingDialogVisible(true)

    return CoroutineScope(Dispatchers.IO).launch {
        val containerManager = ContainerManager(context)
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        container.clearSessionMetadata()

        val legacyImageFsRoot = File(context.filesDir, "imagefs")
        val migrationOk = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(
            context,
            legacyImageFsRoot,
            container.wineVersion,
        )
        if (!migrationOk) {
            Timber.tag("preLaunchApp").e(
                "Legacy ImageFS migration failed: ${legacyImageFsRoot.absolutePath}",
            )
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.install_failed_title),
                    message = context.getString(R.string.install_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        val effectiveExe = container.executablePath
        if (effectiveExe.isBlank()) {
            Timber.tag("preLaunchApp").w("Cannot launch $appId: no executable found")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.EXECUTABLE_NOT_FOUND,
                    title = context.getString(R.string.game_executable_not_found_title),
                    message = context.getString(R.string.game_executable_not_found),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }


        try {
            val imageFsArchive = "imagefs_bionic.txz"
            if (!File(context.filesDir, imageFsArchive).exists() && context.assets.list("")?.contains(imageFsArchive) != true) {
                setLoadingMessage("Downloading first-time files")
                Net.fetchFileWithFallback(imageFsArchive, File(context.filesDir, imageFsArchive), setLoadingProgress)
            }
        } catch (e: Exception) {
            Timber.tag("preLaunchApp").e(e, "File download failed")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.download_failed_title),
                    message = e.message ?: context.getString(R.string.download_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        val loadingMessage = if (container.containerVariant.equals(Container.GLIBC)) {
            context.getString(R.string.main_installing_glibc)
        } else {
            context.getString(R.string.main_installing_bionic)
        }
        setLoadingMessage(loadingMessage)
        val imageFsInstallSuccess =
            ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                setLoadingProgress(progress / 100f)
            }.get()

        if (!imageFsInstallSuccess) {
            Timber.tag("preLaunchApp").e("ImageFS installation failed")
            setLoadingDialogVisible(false)
            setMessageDialogState(
                MessageDialogState(
                    visible = true,
                    type = DialogType.SYNC_FAIL,
                    title = context.getString(R.string.install_failed_title),
                    message = context.getString(R.string.install_failed_message),
                    dismissBtnText = context.getString(R.string.ok),
                ),
            )
            return@launch
        }

        setLoadingMessage(context.getString(R.string.main_loading))
        setLoadingProgress(-1f)

        // must activate container before downloading save files
        containerManager.activateContainer(container)

        setLoadingDialogVisible(false)
        if (!isActive) return@launch
        onSuccess(context, appId)
    }
}
