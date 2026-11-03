package app.gamenative.wow.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.wow.product.GameInstall
import app.gamenative.wow.product.InstallRegistry
import app.gamenative.wow.product.WowProduct
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface InstallUiState {
    data object Idle : InstallUiState
    data class Running(val request: InstallRequest, val progress: InstallProgress) : InstallUiState
    data class Paused(val request: InstallRequest, val progress: InstallProgress?, val reason: String? = null) : InstallUiState
    data class Failed(val request: InstallRequest, val message: String) : InstallUiState
    data class Done(val install: GameInstall, val full: Boolean = false) : InstallUiState
}

/** Start, pause and resume game installs. The work runs in [InstallService] so it survives leaving the app. */
object InstallController {
    private val _state = MutableStateFlow<InstallUiState>(InstallUiState.Idle)
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    internal fun update(state: InstallUiState) {
        _state.value = state
    }

    fun start(context: Context, req: InstallRequest) {
        val intent = Intent(context, InstallService::class.java)
            .setAction(InstallService.ACTION_START)
            .putExtra(InstallService.EXTRA_ROOT, req.root.absolutePath)
            .putExtra(InstallService.EXTRA_PRODUCT, req.product.code)
            .putExtra(InstallService.EXTRA_LOCALE, req.locale)
            .putExtra(InstallService.EXTRA_FULL, req.full)
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause(context: Context) {
        context.startService(Intent(context, InstallService::class.java).setAction(InstallService.ACTION_PAUSE))
    }

    /** Stops for good and forgets the attempt. Files already downloaded stay, so a later install resumes. */
    fun cancel(context: Context) {
        if (state.value is InstallUiState.Running) {
            context.startService(Intent(context, InstallService::class.java).setAction(InstallService.ACTION_CANCEL))
        }
        _state.value = InstallUiState.Idle
    }

    fun resume(context: Context) {
        when (val s = state.value) {
            is InstallUiState.Paused -> start(context, s.request)
            is InstallUiState.Failed -> start(context, s.request)
            else -> Unit
        }
    }

    fun reset() {
        if (_state.value !is InstallUiState.Running) _state.value = InstallUiState.Idle
    }
}

class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var request: InstallRequest? = null
    private var lastProgress: InstallProgress? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotified = 0L

    /**
     * One install attempt. Pausing marks it stopped before cancelling: cancellation can't interrupt a
     * blocking download, so the old attempt may still finish its current file, and must not report
     * progress, failure or stop the service under a newer attempt.
     */
    private class Attempt {
        @Volatile var stopped = false
    }

    private var attempt: Attempt? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val root = intent.getStringExtra(EXTRA_ROOT)
                val product = WowProduct.fromCode(intent.getStringExtra(EXTRA_PRODUCT))
                if (root == null || product == null) {
                    // startForegroundService() requires startForeground() even when there's nothing to do.
                    startForegroundCompat(placeholderNotification())
                    finish()
                    return START_NOT_STICKY
                }
                val locale = intent.getStringExtra(EXTRA_LOCALE) ?: "enUS"
                startInstall(InstallRequest(File(root), product, locale, full = intent.getBooleanExtra(EXTRA_FULL, false)))
            }
            ACTION_PAUSE -> pause(null)
            ACTION_CANCEL -> {
                pause(null)
                InstallController.reset()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInstall(req: InstallRequest) {
        if (attempt?.stopped == false && job?.isActive == true) return
        val previous = job
        val current = Attempt()
        attempt = current
        request = req
        startForegroundCompat(notification(req, null))
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "foreverywhere:install")
                .apply { acquire(WAKE_LOCK_MS) }
        }
        // Some devices slow Wi-Fi down once the screen is off; downloads can run for hours that way.
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "foreverywhere:install")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        job = scope.launch {
            // Let a paused attempt finish its last file before this one touches the same temp files.
            previous?.join()
            try {
                val report: (InstallProgress) -> Unit = report@{ progress ->
                    if (current.stopped) return@report
                    lastProgress = progress
                    InstallController.update(InstallUiState.Running(req, progress))
                    val now = System.currentTimeMillis()
                    if (now - lastNotified > 1000) {
                        lastNotified = now
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(req, progress))
                    }
                }
                if (req.full) {
                    FullDownloader(gameRunning = { PluviaApp.xEnvironment != null }).run(req, report)
                } else {
                    GameInstaller().run(req, onProgress = report)
                }
                if (current.stopped) return@launch
                val product = req.product
                val version = app.gamenative.wow.product.BuildInfo.read(req.root)?.active(product)?.get("Version").orEmpty()
                val install = GameInstall(req.root.absolutePath, product, version)
                InstallRegistry(applicationContext).apply {
                    add(install)
                    setActive(install.root, app.gamenative.wow.product.GameVersion.of(product))
                }
                InstallController.update(InstallUiState.Done(install, req.full))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current.stopped) return@launch
                Timber.e(e, "Install failed")
                InstallController.update(InstallUiState.Failed(req, e.message ?: "Install failed"))
                finish()
            }
        }
    }

    private fun pause(reason: String?) {
        val req = request ?: return
        attempt?.stopped = true
        job?.cancel()
        InstallController.update(InstallUiState.Paused(req, lastProgress, reason))
        finish()
    }

    /**
     * Android 15+ stops data-sync services after about six hours a day in the background; keep the progress
     * and pause. The limit doesn't apply while the app is on screen, which is what Overnight mode is for.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        pause("Android paused the download after several hours in the background. Resume it, and use Overnight mode to keep the app open for long downloads.")
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun placeholderNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.fw_ic_download).setContentTitle("Game download").build()
    }

    private fun notification(req: InstallRequest, progress: InstallProgress?): Notification {
        ensureChannel()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        fun action(name: String, label: String) = NotificationCompat.Action(
            0,
            label,
            PendingIntent.getService(this, name.hashCode(), Intent(this, InstallService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE),
        )
        val total = progress?.totalBytes ?: 0
        val done = progress?.doneBytes ?: 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.fw_ic_download)
            .setContentTitle(if (req.full) "Downloading all ${req.product.displayName} game data" else "Installing ${req.product.displayName}")
            .setContentText(progress?.takeIf { total > 0 }?.sizeText ?: "Preparing download…")
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(action(ACTION_PAUSE, "Pause"))
            .addAction(action(ACTION_CANCEL, "Cancel"))
            .build()
    }

    companion object {
        const val ACTION_START = "app.wowforeverywhere.install.START"
        const val ACTION_PAUSE = "app.wowforeverywhere.install.PAUSE"
        const val ACTION_CANCEL = "app.wowforeverywhere.install.CANCEL"
        const val EXTRA_ROOT = "root"
        const val EXTRA_PRODUCT = "product"
        const val EXTRA_LOCALE = "locale"
        const val EXTRA_FULL = "full"
        private const val CHANNEL = "installs"
        private const val NOTIFICATION_ID = 4101
        /** A safety net only: the lock is released when the download ends. A full download can take most of a day. */
        private const val WAKE_LOCK_MS = 24 * 60 * 60 * 1000L
    }
}
