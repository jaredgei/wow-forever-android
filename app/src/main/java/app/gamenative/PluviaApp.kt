package app.gamenative

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StrictMode
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.events.EventDispatcher
import app.gamenative.ui.screen.xserver.RadialMenuCoordinator
import android.app.Application
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        app.gamenative.wow.StallWatchdog.start(this)
        app.gamenative.wow.product.GameVersions.load(this)
        app.gamenative.wow.product.ForeverTracks.load(this)
        app.gamenative.wow.product.GameRegions.load(this)
        app.gamenative.wow.product.GameAccounts.load(this)
        app.gamenative.wow.product.RenderSizes.load(this)
        app.gamenative.wow.FrameGeneration.init(this)
        android.system.Os.setenv("EVSHIM_BASE_PATH", filesDir.absolutePath, true)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        app.gamenative.wow.debug.DebugLog.apply(this)

        CrashHandler.initialize(this)
        PrefManager.init(this)
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        private lateinit var instance: PluviaApp
        private var cachedDefaultScreenSize: String? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var imeInputReceiver: app.gamenative.externaldisplay.IMEInputReceiver? = null
        var radialMenuCoordinator: RadialMenuCoordinator? = null

        @JvmStatic
        @Volatile
        var keepAlive: Boolean = false

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true
        // True while the booting splash covers the game screen (and its Resume overlay).
        @Volatile
        var isBootingSplashShowing: Boolean = false

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        fun shutdownEnvironment() {
            val env = xEnvironment
            Timber.i("shutdownEnvironment: env=%s", env != null)

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { touchpadView?.releasePointerCapture() }
                .onFailure { Timber.e(it, "shutdownEnvironment: releasePointerCapture") }
            runCatching { radialMenuCoordinator?.detach() }
                .onFailure { Timber.e(it, "shutdownEnvironment: radialMenuCoordinator.detach") }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Timber.e(it, "shutdownEnvironment: stopEnvironmentComponents") }


            xEnvironment = null
            inputControlsView = null
            inputControlsManager = null
            touchpadView = null
            radialMenuCoordinator = null
            keepAlive = false
            clearActiveSuspendState()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

        fun getDefaultScreenSize(): String {
            cachedDefaultScreenSize?.let { return it }

            return try {
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val width : Int
                    val height : Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val mode = display.mode
                        width = mode.physicalWidth
                        height = mode.physicalHeight
                    } else {
                        // API < 30 - Use deprecated Display API
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        display.getRealMetrics(displayMetrics)
                        width = displayMetrics.widthPixels
                        height = displayMetrics.heightPixels
                    }

                    // Calculate aspect ratio (always use landscape orientation for calculation)
                    val aspectRatio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()

                    // Aspect ratio thresholds:
                    // 4:3 = 1.33
                    // 16:10 = 1.6
                    // 16:9 = 1.77

                    val result = when {
                        aspectRatio < 1.5f -> Container.DEFAULT_SCREEN_SIZE_4_3  // 4:3 aspect ratio devices
                        aspectRatio < 1.7f -> Container.DEFAULT_SCREEN_SIZE_16_10  // 16:10 aspect ratio devices
                        else -> Container.DEFAULT_SCREEN_SIZE_16_9  // 16:9 and wider aspect ratio devices
                    }
                    cachedDefaultScreenSize = result
                    result
                } else {
                    val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                    cachedDefaultScreenSize = fallback
                    fallback
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get device screen size")
                val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                cachedDefaultScreenSize = fallback
                fallback
            }
        }
    }

}
