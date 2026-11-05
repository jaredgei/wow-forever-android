package app.gamenative.wow.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber

object Links {
    const val KOFI = "https://ko-fi.com/rkremeier"
    const val REPO = "https://github.com/YoruDev-Ryland/WoWForeverywhere"
    const val BATTLE_NET = "https://account.battle.net/"

    /** Opens [url] in the device's browser. Returns false when nothing can handle it. */
    fun open(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Timber.w(it, "No app to open $url") }.getOrDefault(false)
}
