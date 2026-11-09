package app.gamenative.wow.product

/**
 * The game's clock stops once the device has been on for 2^32 milliseconds, about 49.7 days: `GetTime()` stays at
 * 4294967.295, so nothing animates, the login buttons never fade in and the world looks frozen. Restarting the
 * device resets it (issue #24). Which clock the game reads isn't known; [elapsedMs] is the time since boot including
 * sleep, because a phone that froze showed only 18 days of awake time.
 */
object DeviceClock {
    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    /** A little before the limit, so the clock doesn't stop in the middle of a session. */
    private const val WARN_AFTER_DAYS = 49L

    fun needsRestart(elapsedMs: Long) = elapsedMs >= WARN_AFTER_DAYS * MS_PER_DAY

    fun days(elapsedMs: Long) = elapsedMs / MS_PER_DAY
}
