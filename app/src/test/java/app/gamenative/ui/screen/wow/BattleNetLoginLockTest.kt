package app.gamenative.ui.screen.wow

import app.gamenative.ui.screen.wow.BattleNetLoginLock.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BattleNetLoginLockTest {

    // From a v2.3.3 Connection.log that hit the lock.
    private val lockedAttempt = """
        11/8 14:31:20.469  [N][Connection] Battle.net logon form received result="ERROR_OK (0)" hasCaptcha="false"
        11/8 14:31:20.469  [N][Connection] Battle.net logon form submission requested
        11/8 14:31:20.769  [N][Connection] Battle.net SRP endpoint response received result="ERROR_OK (0)"
        11/8 14:31:21.152  [N][Connection] Battle.net logon form submission response received result="Status has no name. (14400)" authenticatorRequired="false" legalRequired="false"
        11/8 14:31:21.152  [N][Connection] Fatal login error received result="Status has no name. (14400)"
        11/8 14:31:21.402  [N][Connection] Battle.net front disconnected connectionId="1" reason="ERROR_NETWORK_MODULE_SOCKET_CLOSED (1016)"
        11/8 14:31:28.402  [N][Connection] Login error state cleared from glue script
    """.trimIndent()

    private val okAttempt = """
        11/9 10:00:01.000  [N][Connection] Battle.net SRP endpoint response received result="ERROR_OK (0)"
        11/9 10:00:01.300  [N][Connection] Battle.net logon form submission response received result="ERROR_OK (0)" authenticatorRequired="false" legalRequired="false"
    """.trimIndent()

    @Test
    fun lockedAttempt_isLocked() {
        assertEquals(LoginResult.LOCKED, BattleNetLoginLock.lastLoginResult(lockedAttempt))
    }

    @Test
    fun successfulAttempt_isOk() {
        assertEquals(LoginResult.OK, BattleNetLoginLock.lastLoginResult(okAttempt))
    }

    @Test
    fun successAfterLock_isOk() {
        assertEquals(LoginResult.OK, BattleNetLoginLock.lastLoginResult(lockedAttempt + "\n" + okAttempt))
    }

    @Test
    fun lockAfterSuccess_isLocked() {
        assertEquals(LoginResult.LOCKED, BattleNetLoginLock.lastLoginResult(okAttempt + "\n" + lockedAttempt))
    }

    @Test
    fun otherError_isFailed() {
        val log = "11/9 10:00:01  [N][Connection] Fatal login error received result=\"ERROR_DENIED (3)\""
        assertEquals(LoginResult.FAILED, BattleNetLoginLock.lastLoginResult(log))
    }

    @Test
    fun noSignIn_isNull() {
        val log = "11/9 10:00:00  [N][Connection] Battle.net logon requested host=\"EU.actual.battle.net\" port=\"1119\""
        assertNull(BattleNetLoginLock.lastLoginResult(log))
        assertNull(BattleNetLoginLock.lastLoginResult(""))
    }
}
