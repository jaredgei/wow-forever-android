package app.gamenative.wow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.BattleNetCredentialDialog
import app.gamenative.wow.ui.addons.AddonsRoute
import app.gamenative.wow.ui.addons.addonsControllerFor
import app.gamenative.wow.ui.components.FwDialog
import app.gamenative.wow.ui.theme.Fw
import app.gamenative.wow.ui.theme.ForeverywhereTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Routes {
    const val HOME = "home"
    const val ADDONS = "addons"
    const val GAME = "game"
    const val ACCOUNT = "account"
    const val SETTINGS = "settings"
    const val FIRST_RUN = "firstrun"
    const val INSTALL = "install"
    const val CONTROLLER_TEST = "controllertest"
    const val OVERNIGHT = "overnight"
}

/** The app's own screens. Hosted by upstream's NavHost in place of `WoWForeverScreen`. */
@Composable
fun ForeverywhereApp(onLaunch: (appId: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = remember { LauncherController(context, scope, onLaunch) }
    val nav = rememberNavController()
    var showLoginDialog by remember { mutableStateOf(false) }
    val addonsNow = remember(launcher.gamePath, launcher.files.product) { addonsControllerFor(launcher.gamePath, launcher.files.product, scope) }
    LaunchedEffect(addonsNow) { addonsNow?.refreshInstalled() }
    // The nav graph is built once, so destinations read this state instead of capturing a stale value.
    val addonsState = rememberUpdatedState(addonsNow)

    // Decided once: recomputing it would rebuild the graph and drop first-run mid-way when an install appears.
    val startRoute = remember {
        if (launcher.registry.active() == null && launcher.filesMissing) Routes.FIRST_RUN else Routes.HOME
    }
    var appUpdateDismissed by remember { mutableStateOf(false) }
    // After a game version switch: that version's Home, or its first run when it has no install yet.
    val onVersionChanged = {
        val route = if (launcher.registry.active() == null && launcher.filesMissing) Routes.FIRST_RUN else Routes.HOME
        nav.navigate(route) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    LaunchedEffect(Unit) { launcher.start() }
    LifecycleResumeEffect(launcher.gamePath) {
        launcher.onResume()
        onPauseOrDispose { launcher.onPause() }
    }
    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = { launcher.onGameEnded() }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    ForeverywhereTheme {
        Box(Modifier.fillMaxSize().background(Fw.Ground)) {
            NavHost(nav, startDestination = startRoute) {
                composable(Routes.HOME) {
                    HomeScreen(
                        launcher = launcher,
                        addonUpdates = addonsState.value?.updates,
                        addonCount = addonsState.value?.installed?.size,
                        onOpenAddons = { nav.navigate(Routes.ADDONS) },
                        onOpenGame = { nav.navigate(Routes.GAME) },
                        onOpenAccount = { nav.navigate(Routes.ACCOUNT) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.GAME) {
                    GameFilesScreen(
                        launcher,
                        onBack = { nav.popBackStack() },
                        onInstall = { nav.navigate(Routes.INSTALL) },
                        onOpenOvernight = { nav.navigate(Routes.OVERNIGHT) },
                    )
                }
                composable(Routes.OVERNIGHT) { OvernightScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.ACCOUNT) {
                    AccountScreen(launcher, onBack = { nav.popBackStack() }, onEditLogin = { showLoginDialog = true })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        launcher,
                        onBack = { nav.popBackStack() },
                        onVersionChanged = onVersionChanged,
                        onOpenControllerTest = { nav.navigate(Routes.CONTROLLER_TEST) },
                    )
                }
                composable(Routes.CONTROLLER_TEST) { ControllerTestScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.ADDONS) { AddonsRoute(launcher, addonsState.value, onBack = { nav.popBackStack() }) }
                composable(Routes.INSTALL) {
                    val back = { nav.popBackStack(); Unit }
                    FwPage("Install or repair", back) {
                        val repair = launcher.files.product?.takeIf { !launcher.filesMissing }?.let {
                            app.gamenative.wow.product.GameInstall(launcher.gamePath, it, launcher.versionStatus?.localVersion.orEmpty())
                        }
                        InstallStep(launcher, onDone = back, onBack = back, repair = repair)
                    }
                }
                composable(Routes.FIRST_RUN) {
                    FirstRunScreen(
                        launcher = launcher,
                        onEditLogin = { showLoginDialog = true },
                        onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.FIRST_RUN) { inclusive = true } } },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
            }
        }

        if (showLoginDialog) {
            BattleNetCredentialDialog(
                onDismiss = { showLoginDialog = false },
                onConfirm = {
                    showLoginDialog = false
                    launcher.onLoginSaved(it)
                },
            )
        }
        if (launcher.needsStoragePermission) {
            FwDialog(
                title = "Allow file access",
                message = "Android needs \"All files access\" so the app can install and update the game in this folder. Turn on \"Allow access to manage all files\" on the next screen.",
                confirm = "Open settings",
                onConfirm = launcher::openStorageSettings,
                onDismiss = { launcher.needsStoragePermission = false },
            )
        }
        launcher.appUpdate?.takeIf { !appUpdateDismissed && !launcher.isDownloadingAppUpdate }?.let { update ->
            FwDialog(
                title = "App update ${update.version}",
                message = "A new version of WoW Foreverywhere is available.",
                confirm = "Update now",
                onConfirm = {
                    appUpdateDismissed = true
                    launcher.performAppUpdate()
                },
                onDismiss = { appUpdateDismissed = true },
                dismiss = "Later",
            )
        }
        // A beta install once WoW Forever is out: asked on every app start until it's switched.
        val updatePromptShowing = launcher.appUpdate != null && !appUpdateDismissed && !launcher.isDownloadingAppUpdate
        if (launcher.canConvertToLaunch && !launcher.switchPromptDismissed && !launcher.isConverting && !updatePromptShowing) {
            FwDialog(
                title = "WoW Forever is out",
                message = "This install is still on the beta. Switch it to WoW Forever to keep playing. Your game data, settings and addons are kept, so only the new game program downloads.",
                confirm = "Switch now",
                onConfirm = {
                    launcher.switchPromptDismissed = true
                    nav.navigate(Routes.GAME) { launchSingleTop = true }
                    launcher.convertToLaunch()
                },
                onDismiss = { launcher.switchPromptDismissed = true },
                dismiss = "Later",
            )
        }
        if (launcher.unsupportedDeviceWarning) {
            FwDialog(
                title = "This device can't run the game",
                message = "WoW Forever needs a Snapdragon chip with an Adreno GPU. On this device, including Android emulators, starting the game will most likely crash the app.",
                confirm = "Try anyway",
                onConfirm = {
                    launcher.unsupportedDeviceWarning = false
                    launcher.launchGame(confirmedUnsupported = true)
                },
                onDismiss = { launcher.unsupportedDeviceWarning = false },
            )
        }
        if (launcher.needsInstallPermission) {
            FwDialog(
                title = "Allow app installs",
                message = "Android needs permission to install the update. Turn on \"Allow from this source\" on the next screen.",
                confirm = "Open settings",
                onConfirm = launcher::openInstallPermissionSettings,
                onDismiss = { launcher.needsInstallPermission = false },
            )
        }
    }
}
