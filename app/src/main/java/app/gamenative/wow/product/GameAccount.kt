package app.gamenative.wow.product

import android.content.Context

/**
 * Which WoW game account (WoW1, WoW2, ...) to play when one Battle.net login holds several. The game keeps
 * them in Config.wtf as `SET accountList "!WoW1|WoW2|"`, where `!` marks the one it logs into, and with
 * auto-login it never shows its account picker. Until one is picked ([chosen] null) the game's own choice stays.
 */
object GameAccounts {
    private const val PREFS = "wow_settings"
    private const val KEY = "game_account"

    /** The numbers offered in Account. Blizzard numbers game accounts from WoW1. */
    val NUMBERS = 1..8

    @Volatile
    var chosen: Int? = null

    fun name(number: Int) = "WoW$number"

    fun load(context: Context) {
        chosen = prefs(context).getInt(KEY, 0).takeIf { it > 0 }
    }

    /** Saves [number], or null to leave the choice to the game. */
    fun save(context: Context, number: Int?) {
        prefs(context).edit().apply { if (number == null) remove(KEY) else putInt(KEY, number) }.apply()
        chosen = number
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
