package app.gamenative

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import timber.log.Timber

/**
 * Samsung's game manager decides whether Game Booster's in-game panel appears, and it ignores the manifest's
 * android:appCategory. It reads the package manager's category override instead, which the Play Store sets for its
 * games. Adding the app in Gaming Hub's library doesn't set it either. Android lets only an app's installer of record set
 * the override, so the app can mark itself a game only once it installed itself: through its own updater, or through
 * [needsSelfInstall]'s one-time reinstall.
 */
object GameCategory {
    /** Sets the override to game. Android refuses (and this does nothing) unless the app is its own installer. */
    fun claim(context: Context) {
        if (!isOwnInstaller(context)) return
        runCatching { context.packageManager.setApplicationCategoryHint(context.packageName, ApplicationInfo.CATEGORY_GAME) }
            .onFailure { Timber.w(it, "Couldn't mark the app as a game") }
    }

    /** On a Samsung device, whether the app has to reinstall itself before Game Booster treats it as a game. */
    fun needsSelfInstall(context: Context): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) && !isOwnInstaller(context)

    private fun isOwnInstaller(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == context.packageName
    }.getOrDefault(false)
}
