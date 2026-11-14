package app.gamenative.ui.screen.wow.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAuthUrlTest {
    @Test
    fun acceptsOnlyBattleNetHttpsChallenges() {
        assertTrue(BrowserAuthUrl.isInitialUrl("https://eu.battle.net/login/en/?app=wow"))
        assertTrue(BrowserAuthUrl.isInitialUrl("https://BATTLE.NET:443/login"))
        listOf(
            "http://eu.battle.net/login",
            "https://battle.net.evil.example/login",
            "https://evilbattle.net/login",
            "https://battle.net@evil.example/login",
            "https://user@battle.net/login",
            "https://battle.net:444/login",
            "https://battle.net./login",
            "https://battle.net\\@evil.example/login",
            "javascript:alert(1)",
            "https://blizzard.com/login",
            "https://battle.net/login\n",
            "https://battle.net/" + "x".repeat(16_384),
        ).forEach { assertFalse(it, BrowserAuthUrl.isInitialUrl(it)) }
    }

    @Test
    fun acceptsBlizzardAuthNavigationWithoutDomainConfusion() {
        assertTrue(BrowserAuthUrl.isNavigationUrl("https://account.blizzard.com/login"))
        assertTrue(BrowserAuthUrl.isNavigationUrl("https://account.battlenet.com.cn/login"))
        assertFalse(BrowserAuthUrl.isNavigationUrl("https://account.blizzard.com.evil.example/login"))
        assertFalse(BrowserAuthUrl.isNavigationUrl("file:///storage/emulated/0/login.txt"))
        assertFalse(BrowserAuthUrl.isNavigationUrl("intent://login"))
    }

    @Test
    fun capturesExactRedirectAndDecodesTicketOnce() {
        assertEquals("EU-ABC-123", BrowserAuthUrl.ticket("http://localhost:0/?ST=EU-ABC-123")?.decodeToString())
        assertEquals("token+value", BrowserAuthUrl.ticket("http://localhost:0/?ST=token%2Bvalue")?.decodeToString())
        assertEquals("%20", BrowserAuthUrl.ticket("http://localhost:0/?ST=%2520")?.decodeToString())
    }

    @Test
    fun rejectsRedirectLookalikesAndAmbiguousParameters() {
        listOf(
            "https://localhost:0/?ST=x",
            "http://localhost:1/?ST=x",
            "http://127.0.0.1:0/?ST=x",
            "http://localhost.evil.example:0/?ST=x",
            "http://localhost:0.evil.example/?ST=x",
            "http://user@localhost:0/?ST=x",
            "http://localhost:0/other?ST=x",
            "http://localhost:0/?ST=x#fragment",
            "http://localhost:0/?ST=x&ST=y",
            "http://localhost:0/?ST=x&other=y",
            "http://localhost:0/?ST=x;ST=y",
            "http://localhost:0/?%53T=x",
            "http://localhost:0/?st=x",
            "http://localhost:0/?ST=",
        ).forEach { assertNull(it, BrowserAuthUrl.ticket(it)) }
    }

    @Test
    fun rejectsNonAsciiWhitespaceAndOversizedTickets() {
        listOf("a+b", "a%20b", "%00", "%0A", "%7F", "%C3%A9", "%GG", "x".repeat(2049)).forEach {
            assertNull(it.take(32), BrowserAuthUrl.ticket("http://localhost:0/?ST=$it"))
        }
        assertEquals(2048, BrowserAuthUrl.ticket("http://localhost:0/?ST=${"x".repeat(2048)}")?.size)
    }

    @Test
    fun displayOriginDoesNotExposePathOrQuerySecrets() {
        assertEquals("https://eu.battle.net", BrowserAuthUrl.displayOrigin("https://eu.battle.net/path-secret?ST=query-secret#fragment-secret"))
        assertNull(BrowserAuthUrl.displayOrigin("http://localhost:0/?ST=secret"))
        assertNull(BrowserAuthUrl.displayOrigin("https://user:secret@battle.net/"))
    }
}
