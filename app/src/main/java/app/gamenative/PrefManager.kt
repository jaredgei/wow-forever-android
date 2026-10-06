package app.gamenative

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.gamenative.ui.enums.Orientation
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * A universal Preference Manager that can be used anywhere within gamenative.
 * Note: King of ugly though.
 */
object PrefManager {

    // NOT Dispatchers.IO: getPref runBlocking-waits on the datastore from IO threads, so if the read also
    // needed an IO thread, enough concurrent readers would starve the pool and deadlock.
    private val dataStoreDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "PrefDataStore") }.asCoroutineDispatcher()

    private val Context.datastore by preferencesDataStore(
        name = "PluviaPreferences",
        corruptionHandler = ReplaceFileCorruptionHandler {
            Timber.e("Preferences (somehow got) corrupted, resetting.")
            emptyPreferences()
        },
        scope = CoroutineScope(dataStoreDispatcher + SupervisorJob()),
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dataStore: DataStore<Preferences>

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.datastore
        }
    }

    fun clearPreferences() {
        scope.launch {
            dataStore.edit { it.clear() }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getPref(booleanPreferencesKey(key), defaultValue)

    fun getString(key: String, defaultValue: String): String =
        getPref(stringPreferencesKey(key), defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        getPref(floatPreferencesKey(key), defaultValue)

    fun setFloat(key: String, value: Float): Unit =
        setPref(floatPreferencesKey(key), value)

    @Suppress("SameParameterValue")
    private fun <T> getPref(key: Preferences.Key<T>, defaultValue: T): T = runBlocking {
        try {
            dataStore.data.first()[key] ?: defaultValue
        } catch (e: IOException) {
            Timber.w(e, "Failed to read preference ${key.name}, using default")
            defaultValue
        }
    }

    @Suppress("SameParameterValue")
    private fun <T> setPref(key: Preferences.Key<T>, value: T) {
        scope.launch {
            dataStore.edit { pref -> pref[key] = value }
        }
    }

    private fun <T> removePref(key: Preferences.Key<T>) {
        scope.launch {
            dataStore.edit { pref -> pref.remove(key) }
        }
    }

    /* Manifest Cache */
    private val COMPONENT_MANIFEST_JSON = stringPreferencesKey("component_manifest_json")
    var componentManifestJson: String
        get() = getPref(COMPONENT_MANIFEST_JSON, "")
        set(value) {
            setPref(COMPONENT_MANIFEST_JSON, value)
        }

    private val COMPONENT_MANIFEST_FETCHED_AT = longPreferencesKey("component_manifest_fetched_at")
    var componentManifestFetchedAt: Long
        get() = getPref(COMPONENT_MANIFEST_FETCHED_AT, 0L)
        set(value) {
            setPref(COMPONENT_MANIFEST_FETCHED_AT, value)
        }

    /* Container Default Settings */
    private val SCREEN_SIZE = stringPreferencesKey("screen_size")
    var screenSize: String
        get() = getPref(SCREEN_SIZE, PluviaApp.getDefaultScreenSize())
        set(value) {
            setPref(SCREEN_SIZE, value)
        }

    private val ENV_VARS = stringPreferencesKey("env_vars")
    var envVars: String
        get() = getPref(ENV_VARS, Container.DEFAULT_ENV_VARS)
        set(value) {
            setPref(ENV_VARS, value)
        }

    private val GRAPHICS_DRIVER = stringPreferencesKey("graphics_driver")
    var graphicsDriver: String
        get() = getPref(GRAPHICS_DRIVER, Container.DEFAULT_GRAPHICS_DRIVER)
        set(value) {
            setPref(GRAPHICS_DRIVER, value)
        }

    private val GRAPHICS_DRIVER_VERSION = stringPreferencesKey("graphics_driver_version")
    var graphicsDriverVersion: String
        get() = getPref(GRAPHICS_DRIVER_VERSION, "")
        set(value) {
            setPref(GRAPHICS_DRIVER_VERSION, value)
        }

    private val GRAPHICS_DRIVER_CONFIG = stringPreferencesKey("graphics_driver_config")
    var graphicsDriverConfig: String
        get() = getPref(GRAPHICS_DRIVER_CONFIG, Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        set(value) {
            setPref(GRAPHICS_DRIVER_CONFIG, value)
        }

    private val RENDERER_PRESENT_MODE = stringPreferencesKey("renderer_present_mode")
    var rendererPresentMode: String
        get() = getPref(RENDERER_PRESENT_MODE, "fifo")
        set(value) {
            setPref(RENDERER_PRESENT_MODE, value)
        }

    private val DISPLAY_RENDERER_MODE = stringPreferencesKey("display_renderer_mode")
    var displayRendererMode: String
        get() {
            val stored = getPref(DISPLAY_RENDERER_MODE, "")
            if (stored.isNotEmpty()) return stored
            return if (getPref(USE_LEGACY_RENDERER, false)) "gl" else "vulkan"
        }
        set(value) {
            setPref(DISPLAY_RENDERER_MODE, value)
        }

    private val SF_COMPAT_MODE = booleanPreferencesKey("sf_compat_mode")
    var sfCompatMode: Boolean
        get() = getPref(SF_COMPAT_MODE, true)
        set(value) {
            setPref(SF_COMPAT_MODE, value)
        }

    private val USE_LEGACY_RENDERER = booleanPreferencesKey("use_legacy_renderer")
    var useLegacyRenderer: Boolean
        get() = getPref(USE_LEGACY_RENDERER, false)
        set(value) {
            setPref(USE_LEGACY_RENDERER, value)
        }

    private val SHARPNESS_EFFECT = stringPreferencesKey("sharpness_effect")
    var sharpnessEffect: String
        get() = getPref(SHARPNESS_EFFECT, "None")
        set(value) {
            setPref(SHARPNESS_EFFECT, value)
        }

    private val SHARPNESS_LEVEL = intPreferencesKey("sharpness_level")
    var sharpnessLevel: Int
        get() = getPref(SHARPNESS_LEVEL, 100)
        set(value) {
            setPref(SHARPNESS_LEVEL, value.coerceIn(0, 100))
        }

    private val SHARPNESS_DENOISE = intPreferencesKey("sharpness_denoise")
    var sharpnessDenoise: Int
        get() = getPref(SHARPNESS_DENOISE, 100)
        set(value) {
            setPref(SHARPNESS_DENOISE, value.coerceIn(0, 100))
        }

    private val CONTAINER_VARIANT = stringPreferencesKey("container_variant")
    var containerVariant: String
        get() = getPref(CONTAINER_VARIANT, Container.DEFAULT_VARIANT)
        set(value) {
            setPref(CONTAINER_VARIANT, value)
        }

    private val WINE_VERSION = stringPreferencesKey("wine_version")
    var wineVersion: String
        get() = getPref(WINE_VERSION, Container.DEFAULT_WINE_VERSION)
        set(value) {
            setPref(WINE_VERSION, value)
        }

    private val EMULATOR = stringPreferencesKey("emulator")
    var emulator: String
        get() = getPref(EMULATOR, Container.DEFAULT_EMULATOR)
        set(value) {
            setPref(EMULATOR, value)
        }

    private val FEXCORE_VERSION = stringPreferencesKey("fexcore_version")
    var fexcoreVersion: String
        get() = getPref(FEXCORE_VERSION, DefaultVersion.FEXCORE)
        set(value) {
            setPref(FEXCORE_VERSION, value)
        }

    private val FEXCORE_TSO_MODE = stringPreferencesKey("fexcore_tso_mode")
    var fexcoreTSOMode: String
        get() = getPref(FEXCORE_TSO_MODE, "Fast")
        set(value) {
            setPref(FEXCORE_TSO_MODE, value)
        }

    private val FEXCORE_X87_MODE = stringPreferencesKey("fexcore_x87_mode")
    var fexcoreX87Mode: String
        get() = getPref(FEXCORE_X87_MODE, "Fast")
        set(value) {
            setPref(FEXCORE_X87_MODE, value)
        }

    private val FEXCORE_MULTIBLOCK = stringPreferencesKey("fexcore_multiblock")
    var fexcoreMultiBlock: String
        get() = getPref(FEXCORE_MULTIBLOCK, "Disabled")
        set(value) {
            setPref(FEXCORE_MULTIBLOCK, value)
        }

    private val DXWRAPPER = stringPreferencesKey("dxwrapper")
    var dxWrapper: String
        get() = getPref(DXWRAPPER, Container.DEFAULT_DXWRAPPER)
        set(value) {
            setPref(DXWRAPPER, value)
        }

    private val DXWRAPPER_CONFIG = stringPreferencesKey("dxwrapperConfig")
    var dxWrapperConfig: String
        get() = getPref(DXWRAPPER_CONFIG, Container.DEFAULT_DXWRAPPERCONFIG)
        set(value) {
            setPref(DXWRAPPER_CONFIG, value)
        }

    private val AUDIO_DRIVER = stringPreferencesKey("audio_driver")
    var audioDriver: String
        get() = getPref(AUDIO_DRIVER, Container.DEFAULT_AUDIO_DRIVER)
        set(value) {
            setPref(AUDIO_DRIVER, value)
        }

    private val PULSEAUDIO_LOW_LATENCY = booleanPreferencesKey("pulseaudio_low_latency")
    var pulseaudioLowLatency: Boolean
        get() = getPref(PULSEAUDIO_LOW_LATENCY, false)
        set(value) {
            setPref(PULSEAUDIO_LOW_LATENCY, value)
        }

    private val MIC_ENABLED = booleanPreferencesKey("mic_enabled")
    var micEnabled: Boolean
        get() = getPref(MIC_ENABLED, false)
        set(value) {
            setPref(MIC_ENABLED, value)
        }

    private val WIN_COMPONENTS = stringPreferencesKey("wincomponents")
    var winComponents: String
        get() = getPref(WIN_COMPONENTS, Container.DEFAULT_WINCOMPONENTS)
        set(value) {
            setPref(WIN_COMPONENTS, value)
        }

    private val DRIVES = stringPreferencesKey("drives")
    var drives: String
        get() = getPref(DRIVES, Container.DEFAULT_DRIVES)
        set(value) {
            setPref(DRIVES, value)
        }

    private val QUICK_MENU_LAST_TAB = intPreferencesKey("quick_menu_last_tab")
    var quickMenuLastTab: Int
        get() = getPref(QUICK_MENU_LAST_TAB, 0)
        set(value) {
            setPref(QUICK_MENU_LAST_TAB, value.coerceIn(0, 6))
        }

    private val SHOW_FPS = booleanPreferencesKey("show_fps")
    var showFps: Boolean
        get() = getPref(SHOW_FPS, false)
        set(value) {
            setPref(SHOW_FPS, value)
        }

    private val PERFORMANCE_HUD_COMPACT_MODE = booleanPreferencesKey("performance_hud_compact_mode")
    var performanceHudCompactMode: Boolean
        get() = getPref(PERFORMANCE_HUD_COMPACT_MODE, false)
        set(value) {
            setPref(PERFORMANCE_HUD_COMPACT_MODE, value)
        }

    private val PERFORMANCE_HUD_SHOW_FRAME_RATE = booleanPreferencesKey("performance_hud_show_frame_rate")
    var performanceHudShowFrameRate: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FRAME_RATE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_FRAME_RATE, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_USAGE = booleanPreferencesKey("performance_hud_show_cpu_usage")
    var performanceHudShowCpuUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_USAGE = booleanPreferencesKey("performance_hud_show_gpu_usage")
    var performanceHudShowGpuUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_RAM_USAGE = booleanPreferencesKey("performance_hud_show_ram_usage")
    var performanceHudShowRamUsage: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_RAM_USAGE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_RAM_USAGE, value)
        }

    private val PERFORMANCE_HUD_SHOW_BATTERY_LEVEL = booleanPreferencesKey("performance_hud_show_battery_level")
    var performanceHudShowBatteryLevel: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_LEVEL, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_LEVEL, value)
        }

    private val PERFORMANCE_HUD_SHOW_POWER_DRAW = booleanPreferencesKey("performance_hud_show_power_draw")
    var performanceHudShowPowerDraw: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_POWER_DRAW, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_POWER_DRAW, value)
        }

    private val PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME = booleanPreferencesKey("performance_hud_show_battery_runtime")
    var performanceHudShowBatteryRuntime: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_RUNTIME, value)
        }

    private val PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE = booleanPreferencesKey("performance_hud_show_battery_temperature")
    var performanceHudShowBatteryTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_BATTERY_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_CLOCK_TIME = booleanPreferencesKey("performance_hud_show_clock_time")
    var performanceHudShowClockTime: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CLOCK_TIME, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CLOCK_TIME, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE = booleanPreferencesKey("performance_hud_show_cpu_temperature")
    var performanceHudShowCpuTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE = booleanPreferencesKey("performance_hud_show_gpu_temperature")
    var performanceHudShowGpuTemperature: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_TEMPERATURE, value)
        }

    private val PERFORMANCE_HUD_SHOW_FAN = booleanPreferencesKey("performance_hud_show_fan")
    var showPerformanceHudFan: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FAN, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_FAN, value)
        }

    private val PERFORMANCE_HUD_SHOW_TUNER_CAPS = booleanPreferencesKey("performance_hud_show_tuner_caps")
    var showPerformanceHudTunerCaps: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_TUNER_CAPS, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_TUNER_CAPS, value)
        }

    private val PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH = booleanPreferencesKey("performance_hud_show_frame_rate_graph")
    var performanceHudShowFrameRateGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_FRAME_RATE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH = booleanPreferencesKey("performance_hud_show_cpu_usage_graph")
    var performanceHudShowCpuUsageGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_CPU_USAGE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH = booleanPreferencesKey("performance_hud_show_gpu_usage_graph")
    var performanceHudShowGpuUsageGraph: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH, false)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_GPU_USAGE_GRAPH, value)
        }

    private val PERFORMANCE_HUD_BACKGROUND_OPACITY = floatPreferencesKey("performance_hud_background_opacity")
    var performanceHudBackgroundOpacity: Float
        get() = getPref(PERFORMANCE_HUD_BACKGROUND_OPACITY, 0.72f)
        set(value) {
            setPref(PERFORMANCE_HUD_BACKGROUND_OPACITY, value.coerceIn(0f, 1f))
        }

    private val PERFORMANCE_HUD_COLOR_INTENSITY = floatPreferencesKey("performance_hud_color_intensity")
    var performanceHudColorIntensity: Float
        get() = getPref(PERFORMANCE_HUD_COLOR_INTENSITY, 1f)
        set(value) {
            setPref(PERFORMANCE_HUD_COLOR_INTENSITY, value.coerceIn(0f, 1f))
        }

    private val PERFORMANCE_HUD_SHOW_TEXT_OUTLINE = booleanPreferencesKey("performance_hud_show_text_outline")
    var performanceHudShowTextOutline: Boolean
        get() = getPref(PERFORMANCE_HUD_SHOW_TEXT_OUTLINE, true)
        set(value) {
            setPref(PERFORMANCE_HUD_SHOW_TEXT_OUTLINE, value)
        }

    private val PERFORMANCE_HUD_SIZE = stringPreferencesKey("performance_hud_size")
    var performanceHudSize: String
        get() = getPref(PERFORMANCE_HUD_SIZE, "medium")
        set(value) {
            setPref(PERFORMANCE_HUD_SIZE, value)
        }

    private val PERFORMANCE_HUD_X_FRACTION = floatPreferencesKey("performance_hud_x_fraction")
    var performanceHudXFraction: Float
        get() = getPref(PERFORMANCE_HUD_X_FRACTION, -1f)
        set(value) {
            setPref(PERFORMANCE_HUD_X_FRACTION, value.coerceIn(-1f, 1f))
        }

    private val PERFORMANCE_HUD_Y_FRACTION = floatPreferencesKey("performance_hud_y_fraction")
    var performanceHudYFraction: Float
        get() = getPref(PERFORMANCE_HUD_Y_FRACTION, -1f)
        set(value) {
            setPref(PERFORMANCE_HUD_Y_FRACTION, value.coerceIn(-1f, 1f))
        }

    private val SHOW_CONTROLLER_DEBUG_MENU = booleanPreferencesKey("show_controller_debug_menu")
    var showControllerDebugMenu: Boolean
        get() = getPref(SHOW_CONTROLLER_DEBUG_MENU, false)
        set(value) {
            setPref(SHOW_CONTROLLER_DEBUG_MENU, value)
        }

    private val FASTER_EXTERNAL_LOADING = booleanPreferencesKey("faster_external_loading")
    var fasterExternalLoading: Boolean
        get() = getPref(FASTER_EXTERNAL_LOADING, false)
        set(value) {
            setPref(FASTER_EXTERNAL_LOADING, value)
        }

    private val DISABLE_LIBREDIRECT = booleanPreferencesKey("disable_libredirect")
    var disableLibredirect: Boolean
        get() = getPref(DISABLE_LIBREDIRECT, false)
        set(value) {
            setPref(DISABLE_LIBREDIRECT, value)
        }

    private val SUSPEND_POLICY = stringPreferencesKey("suspend_policy")
    var suspendPolicy: String
        get() = Container.normalizeSuspendPolicy(getPref(SUSPEND_POLICY, Container.SUSPEND_POLICY_MANUAL))
        set(value) {
            setPref(SUSPEND_POLICY, Container.normalizeSuspendPolicy(value))
        }

    private val CPU_LIST = stringPreferencesKey("cpu_list")
    var cpuList: String
        get() = getPref(CPU_LIST, Container.getFallbackCPUList())
        set(value) {
            setPref(CPU_LIST, value)
        }

    private val CPU_LIST_WOW64 = stringPreferencesKey("cpu_list_wow64")
    var cpuListWoW64: String
        get() = getPref(CPU_LIST_WOW64, Container.getFallbackCPUListWoW64())
        set(value) {
            setPref(CPU_LIST_WOW64, value)
        }

    private val WOW64_MODE = booleanPreferencesKey("wow64_mode")
    var wow64Mode: Boolean
        get() = getPref(WOW64_MODE, true)
        set(value) {
            setPref(WOW64_MODE, value)
        }

    private val STARTUP_SELECTION = intPreferencesKey("startup_selection")
    var startupSelection: Int
        get() = getPref(STARTUP_SELECTION, Container.STARTUP_SELECTION_ESSENTIAL.toInt())
        set(value) {
            setPref(STARTUP_SELECTION, value)
        }

    private val CONTAINER_LANGUAGE = stringPreferencesKey("container_language")
    var containerLanguage: String
        get() = getPref(CONTAINER_LANGUAGE, "english")
        set(value) {
            setPref(CONTAINER_LANGUAGE, value)
        }

    private val BOX86_PRESET = stringPreferencesKey("box86_preset")
    var box86Preset: String
        get() = getPref(BOX86_PRESET, Box86_64Preset.COMPATIBILITY)
        set(value) {
            setPref(BOX86_PRESET, value)
        }

    private val BOX64_PRESET = stringPreferencesKey("box64_preset")
    var box64Preset: String
        get() = getPref(BOX64_PRESET, Box86_64Preset.COMPATIBILITY)
        set(value) {
            setPref(BOX64_PRESET, value)
        }

    private val FEXCORE_PRESET = stringPreferencesKey("fexcore_preset")
    var fexcorePreset: String
        get() = getPref(FEXCORE_PRESET, com.winlator.fexcore.FEXCorePreset.INTERMEDIATE)
        set(value) {
            setPref(FEXCORE_PRESET, value)
        }

    private val RENDERER = stringPreferencesKey("renderer")
    var renderer: String
        get() = getPref(RENDERER, "gl")
        set(value) {
            setPref(RENDERER, value)
        }

    private val CSMT = booleanPreferencesKey("csmt")
    var csmt: Boolean
        get() = getPref(CSMT, true)
        set(value) {
            setPref(CSMT, value)
        }

    private val VIDEO_PCI_DEVICE_ID = intPreferencesKey("videoPciDeviceID")
    var videoPciDeviceID: Int
        get() = getPref(VIDEO_PCI_DEVICE_ID, 1728)
        set(value) {
            setPref(VIDEO_PCI_DEVICE_ID, value)
        }

    private val OFFSCREEN_RENDERING_MODE = stringPreferencesKey("offScreenRenderingMode")
    var offScreenRenderingMode: String
        get() = getPref(OFFSCREEN_RENDERING_MODE, "fbo")
        set(value) {
            setPref(OFFSCREEN_RENDERING_MODE, value)
        }

    private val STRICT_SHADER_MATH = booleanPreferencesKey("strictShaderMath")
    var strictShaderMath: Boolean
        get() = getPref(STRICT_SHADER_MATH, true)
        set(value) {
            setPref(STRICT_SHADER_MATH, value)
        }

    private val USE_DRI3 = booleanPreferencesKey("useDRI3")
    var useDRI3: Boolean
        get() = getPref(USE_DRI3, true)
        set(value) {
            setPref(USE_DRI3, value)
        }

    private val VIDEO_MEMORY_SIZE = stringPreferencesKey("videoMemorySize")
    var videoMemorySize: String
        get() = getPref(VIDEO_MEMORY_SIZE, "2048")
        set(value) {
            setPref(VIDEO_MEMORY_SIZE, value)
        }

    private val MOUSE_WARP_OVERRIDE = stringPreferencesKey("mouseWarpOverride")
    var mouseWarpOverride: String
        get() = getPref(MOUSE_WARP_OVERRIDE, "disable")
        set(value) {
            setPref(MOUSE_WARP_OVERRIDE, value)
        }

    // Controller Input Defaults
    private val XINPUT_ENABLED = booleanPreferencesKey("xinput_enabled")
    var xinputEnabled: Boolean
        get() = getPref(XINPUT_ENABLED, true)
        set(value) {
            setPref(XINPUT_ENABLED, value)
        }

    private val DINPUT_ENABLED = booleanPreferencesKey("dinput_enabled")
    var dinputEnabled: Boolean
        get() = getPref(DINPUT_ENABLED, true)
        set(value) {
            setPref(DINPUT_ENABLED, value)
        }

    private val DINPUT_MAPPER_TYPE = intPreferencesKey("dinput_mapper_type")
    var dinputMapperType: Int
        get() = getPref(DINPUT_MAPPER_TYPE, 1)
        set(value) {
            setPref(DINPUT_MAPPER_TYPE, value)
        }

    // External display input mode (off|touchpad|keyboard|hybrid)
    private val EXTERNAL_DISPLAY_INPUT_MODE = stringPreferencesKey("external_display_input_mode")
    var externalDisplayInputMode: String
        get() = getPref(EXTERNAL_DISPLAY_INPUT_MODE, Container.DEFAULT_EXTERNAL_DISPLAY_MODE)
        set(value) { setPref(EXTERNAL_DISPLAY_INPUT_MODE, value) }

    private val EXTERNAL_DISPLAY_SWAP = booleanPreferencesKey("external_display_swap")
    var externalDisplaySwap: Boolean
        get() = getPref(EXTERNAL_DISPLAY_SWAP, false)
        set(value) { setPref(EXTERNAL_DISPLAY_SWAP, value) }

    // Disable Mouse Input (prevents external mouse events)
    private val DISABLE_MOUSE_INPUT = booleanPreferencesKey("disable_mouse_input")
    var disableMouseInput: Boolean
        get() = getPref(DISABLE_MOUSE_INPUT, false)
        set(value) {
            setPref(DISABLE_MOUSE_INPUT, value)
        }

    private val PORTRAIT_MODE = booleanPreferencesKey("portrait_mode")
    var portraitMode: Boolean
        get() = getPref(PORTRAIT_MODE, false)
        set(value) {
            setPref(PORTRAIT_MODE, value)
        }

    private val PORTRAIT_BELOW_CUTOUT = booleanPreferencesKey("portrait_below_cutout")
    var portraitBelowCutout: Boolean
        get() = getPref(PORTRAIT_BELOW_CUTOUT, false)
        set(value) {
            setPref(PORTRAIT_BELOW_CUTOUT, value)
        }

    private val BOX_86_VERSION = stringPreferencesKey("box86_version")
    var box86Version: String
        get() = getPref(BOX_86_VERSION, DefaultVersion.BOX86)
        set(value) {
            setPref(BOX_86_VERSION, value)
        }

    private val BOX_64_VERSION = stringPreferencesKey("box64_version")
    var box64Version: String
        get() = getPref(BOX_64_VERSION, DefaultVersion.BOX64)
        set(value) {
            setPref(BOX_64_VERSION, value)
        }

    private val EXEC_ARGS = stringPreferencesKey("exec_args")
    var execArgs: String
        get() = getPref(EXEC_ARGS, "")
        set(value) {
            setPref(EXEC_ARGS, value)
        }



    private val ALLOWED_ORIENTATION = intPreferencesKey("allowed_orientation")
    var allowedOrientation: EnumSet<Orientation>
        get() {
            val defaultValue = Orientation.toInt(
                EnumSet.of(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE),
            )
            val value = getPref(ALLOWED_ORIENTATION, defaultValue)
            return Orientation.fromInt(value)
        }
        set(value) {
            setPref(ALLOWED_ORIENTATION, Orientation.toInt(value))
        }


    private val HAS_ATTEMPTED_GAME_LAUNCH = booleanPreferencesKey("has_attempted_game_launch")
    var hasAttemptedGameLaunch: Boolean
        get() = getPref(HAS_ATTEMPTED_GAME_LAUNCH, false)
        set(value) {
            setPref(HAS_ATTEMPTED_GAME_LAUNCH, value)
        }




    // Whether to open links internally with a webview or open externally with a user's browser.
    private val OPEN_WEB_LINKS_EXTERNALLY = booleanPreferencesKey("open_web_links_externally")
    var openWebLinksExternally: Boolean
        get() = getPref(OPEN_WEB_LINKS_EXTERNALLY, true)
        set(value) {
            setPref(OPEN_WEB_LINKS_EXTERNALLY, value)
        }

    // Whether to hide the Android status bar when not in a game (in game list, settings, etc.)
    private val HIDE_STATUS_BAR_WHEN_NOT_IN_GAME = booleanPreferencesKey("hide_status_bar_when_not_in_game")
    var hideStatusBarWhenNotInGame: Boolean
        get() = getPref(HIDE_STATUS_BAR_WHEN_NOT_IN_GAME, true)
        set(value) {
            setPref(HIDE_STATUS_BAR_WHEN_NOT_IN_GAME, value)
        }

    // Whether to swap A↔B and X↔Y button icons to match Xbox controller layout.
    private val SWAP_FACE_BUTTONS = booleanPreferencesKey("swap_face_buttons")
    var swapFaceButtons: Boolean
        get() = getPref(SWAP_FACE_BUTTONS, false)
        set(value) {
            setPref(SWAP_FACE_BUTTONS, value)
        }

    // Whether to show the on-screen gamepad hints/action bar in the UI
    private val SHOW_GAMEPAD_HINTS = booleanPreferencesKey("show_gamepad_hints")
    var showGamepadHints: Boolean
        get() = getPref(SHOW_GAMEPAD_HINTS, true)
        set(value) {
            setPref(SHOW_GAMEPAD_HINTS, value)
        }
    private val ENABLE_WINE_DEBUG = booleanPreferencesKey("enable_wine_debug")
    var enableWineDebug: Boolean
        get() = getPref(ENABLE_WINE_DEBUG, false)
        set(value) = setPref(ENABLE_WINE_DEBUG, value)

    private val WINE_DEBUG_CHANNELS = stringPreferencesKey("wine_debug_channels")
    var wineDebugChannels: String
        get() = getPref(WINE_DEBUG_CHANNELS, Constants.XServer.DEFAULT_WINE_DEBUG_CHANNELS)
        set(value) = setPref(WINE_DEBUG_CHANNELS, value)


    private val APP_LANGUAGE = stringPreferencesKey("app_language")
    var appLanguage: String
        get() = getPref(APP_LANGUAGE, "")
        set(value) = setPref(APP_LANGUAGE, value)
}
