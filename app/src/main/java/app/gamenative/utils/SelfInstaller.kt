package app.gamenative.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.gamenative.GameCategory
import java.io.File
import timber.log.Timber

/**
 * Installs APKs of this app through a PackageInstaller session, which makes the app its own installer of record (unlike
 * handing the file to the system installer with ACTION_VIEW). That's what lets it mark itself a game for Samsung's Game
 * Booster ([GameCategory]). Android still asks the player to confirm, as before.
 */
object SelfInstaller {
    /** Installs [apks] (a base APK, then any splits) over this app. */
    fun install(context: Context, apks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { index, apk ->
                    session.openWrite(if (index == 0) "base.apk" else "split$index.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val result = Intent(context, InstallResultReceiver::class.java)
                // Mutable: the installer adds the status and the confirmation intent to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, result, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    /** Reinstalls the installed copy of this app over itself, so it becomes its own installer of record. */
    fun reinstallSelf(context: Context) {
        val info = context.applicationInfo
        install(context, listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File))
    }
}

/** Shows Android's confirmation for a [SelfInstaller] session and logs how it ended. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Timber.i("Install finished")
            else -> Timber.w("Install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/**
 * Runs after the app is updated or reinstalled. Application.onCreate already marks the app a game; receiving this is
 * what starts the process, so Game Booster knows before the player opens the app again.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) GameCategory.claim(context)
    }
}
