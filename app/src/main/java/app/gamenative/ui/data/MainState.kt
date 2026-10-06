package app.gamenative.ui.data

import app.gamenative.ui.screen.PluviaScreen

data class MainState(
    val resettedScreen: PluviaScreen? = null,
    val currentScreen: PluviaScreen? = PluviaScreen.WoWLauncher,
    val hasLaunched: Boolean = false,
    val loadingDialogVisible: Boolean = false,
    val loadingDialogProgress: Float = 0F,
    val loadingDialogMessage: String = "Loading...",
    val launchedAppId: String = "",
    val showBootingSplash: Boolean = false,
    val bootingSplashText: String = "Booting...",
    val bootingSplashHeroImageUrl: String = "",
)
