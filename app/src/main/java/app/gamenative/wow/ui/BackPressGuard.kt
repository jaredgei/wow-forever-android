package app.gamenative.wow.ui

/**
 * Makes one back press count once. Apps targeting Android 16 get back through the predictive-back callback,
 * but some ROMs (RedMagic, issues #33 and #34) also send it as a KEYCODE_BACK key press. The key press opened
 * the side menu and the callback for the same press closed it again, so the menu flashed or never appeared.
 * Whichever arrives first is handled, and the other half of the same press is ignored.
 */
object BackPressGuard {
    /** Longer than the gap between a press's key and its callback, shorter than two deliberate presses. */
    const val SAME_PRESS_MS = 400L

    private const val LONG_AGO = -10 * SAME_PRESS_MS

    private var keyHeld = false
    private var keyUpAt = LONG_AGO
    private var keyPressPending = false
    private var callbackHandledAt = LONG_AGO

    /** A KEYCODE_BACK key went down. Returns whether to act on it. */
    @Synchronized
    fun onKeyDown(now: Long, repeatCount: Int): Boolean {
        if (repeatCount > 0) return false
        keyHeld = true
        if (now - callbackHandledAt <= SAME_PRESS_MS) return false
        keyPressPending = true
        return true
    }

    @Synchronized
    fun onKeyUp(now: Long) {
        keyHeld = false
        keyUpAt = now
    }

    /** The predictive-back callback fired. Returns whether to act on it. */
    @Synchronized
    fun onCallback(now: Long): Boolean {
        val sameAsKeyPress = keyPressPending && (keyHeld || now - keyUpAt <= SAME_PRESS_MS)
        keyPressPending = false
        if (sameAsKeyPress) return false
        callbackHandledAt = now
        return true
    }
}
