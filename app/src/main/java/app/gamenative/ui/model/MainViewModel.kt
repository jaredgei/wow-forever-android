package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import app.gamenative.ui.data.MainState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.WineProcessSnapshotHelper
import com.winlator.xserver.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_SCREEN_ROUTE = "current_screen_route"

        var gamePlayedThisSession = false
            private set
    }

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object LaunchApp : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> get() = _offline

    fun setOffline(value: Boolean) {
        _offline.value = value
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }


    private val onSetBootingSplashText: (AndroidEvent.SetBootingSplashText) -> Unit = {
        if (_state.value.showBootingSplash) {
            setBootingSplashText(it.text)
        }
    }

    private val onClearBootingSplash: (AndroidEvent.ClearBootingSplash) -> Unit = {
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = null
        setShowBootingSplash(false)
    }

    private var bootingSplashTimeoutJob: Job? = null

    init {
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        val restoredScreen = when (persistedRoute) {
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            else -> null
        }

        _state.update {
            it.copy(
                launchedAppId = "",
                currentScreen = restoredScreen,
            )
        }

        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.on<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.off<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
    }

    fun setLoadingDialogVisible(value: Boolean) {
        _state.update { it.copy(loadingDialogVisible = value) }
    }

    fun setLoadingDialogProgress(value: Float) {
        _state.update { it.copy(loadingDialogProgress = value) }
    }

    fun setLoadingDialogMessage(value: String) {
        _state.update { it.copy(loadingDialogMessage = value) }
    }

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    fun setShowBootingSplash(value: Boolean) {
        PluviaApp.isBootingSplashShowing = value
        _state.update { it.copy(showBootingSplash = value) }
    }

    fun setBootingSplashText(value: String) {
        _state.update { it.copy(bootingSplashText = value) }
    }

    fun setCurrentScreen(currentScreen: String?) {
        // Route matching accounts for query params and path params in templates
        // e.g., "home?offline={offline}" should match Home, "chat/{id}" should match Chat
        val screen = when (currentScreen) {
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            else -> PluviaScreen.WoWLauncher
        }

        setCurrentScreen(screen)
    }

    fun setCurrentScreen(value: PluviaScreen) {
        _state.update { it.copy(currentScreen = value) }
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = value.route
    }

    fun setScreen() {
        _state.update { it.copy(resettedScreen = it.currentScreen) }
    }

    fun setLaunchedAppId(value: String) {
        _state.update { it.copy(launchedAppId = value) }
    }

    private var launchAppJob: Job? = null

    fun launchApp(context: Context, appId: String) {
        gamePlayedThisSession = true
        PrefManager.hasAttemptedGameLaunch = true
        launchAppJob?.cancel()
        launchAppJob = viewModelScope.launch {
            setShowBootingSplash(true)
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation))

            val apiJob = viewModelScope.async(Dispatchers.IO) {
                ContainerUtils.getOrCreateContainer(context, appId)
            }

            delay(100)
            if (!isActive) return@launch
            apiJob.await()
            if (!isActive) return@launch
            _uiEvent.send(MainUiEvent.LaunchApp)
        }
    }

    fun exitApp(context: Context, appId: String, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                bootingSplashTimeoutJob?.cancel()
                bootingSplashTimeoutJob = null
                setShowBootingSplash(false)
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: String) {
        viewModelScope.launch {
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        }
    }

    fun onGameLaunchError(error: String) {
        viewModelScope.launch {
            // Hide the splash screen if it's still showing
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)

            // You could also show an error dialog here if needed
            Timber.tag("MainViewModel").e("Game launch error: $error")
        }
    }

    /** The splash's back button: hide the splash and close the guest the way a blocked session does. */
    fun abortBoot() {
        launchAppJob?.cancel()
        launchAppJob = null
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Boot aborted from the splash")
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
        }
    }

}
