package app.gamenative.ui.screen.wow

import app.gamenative.wow.product.WowProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameConfigTest {

    @Test
    fun retailGetsOnlyWhatItNeedsToStart() {
        val keys = buildConfigWtf(null, WowProduct.RETAIL, "1920x1080")!!.map { it.split(" ")[1] }
        assertEquals(
            listOf(
                "portal", "agentUID", "gxApi", "textLocale", "audioLocale", "GamePadEnable", "InputDeviceInterfaceStyle",
                "showScreenNarrationDialog", "accessibilityScreenNarrationEnabled", "gxFullscreenResolution", "gxWindowedResolution",
            ),
            keys,
        )
    }

    @Test
    fun classicVersionsKeepTheHandheldPreset() {
        val keys = buildConfigWtf(null, WowProduct.CLASSIC_ERA, "1920x1080")!!.map { it.split(" ")[1] }
        assertTrue("farclip" in keys && "graphicsQuality" in keys)
    }

    @Test
    fun newForeverConfigUsesLivePortalAndProduct() {
        val lines = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET portal \"US\"" in lines)
        assertTrue("SET agentUID \"wowf\"" in lines)
        assertTrue("SET gxApi \"D3D11\"" in lines)
        assertTrue("SET gxFullscreenResolution \"1920x1080\"" in lines)
    }

    @Test
    fun newConfigUsesInstallLocale() {
        val lines = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080", "deDE")!!
        assertTrue("SET textLocale \"deDE\"" in lines)
        assertTrue("SET audioLocale \"deDE\"" in lines)
    }

    @Test
    fun newItalianConfigGetsEnglishTextAndKeepsItalianAudio() {
        val lines = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080", locale = "itIT")!!
        assertTrue("SET textLocale \"enUS\"" in lines)
        assertTrue("SET audioLocale \"itIT\"" in lines)
    }

    @Test
    fun existingItalianTextIsSwitchedToEnglishOnce() {
        val first = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080")!!
        val italian = first.map { if (it.startsWith("SET textLocale")) "SET textLocale \"itIT\"" else if (it.startsWith("SET audioLocale")) "SET audioLocale \"itIT\"" else it }
        val fixed = buildConfigWtf(italian, WowProduct.FOREVER, "1920x1080")!!
        assertEquals(1, fixed.count { it.startsWith("SET textLocale") })
        assertEquals("enUS", configTextLocale(fixed))
        assertTrue("SET audioLocale \"itIT\"" in fixed)
        assertEquals(null, buildConfigWtf(fixed, WowProduct.FOREVER, "1920x1080"))
    }

    @Test
    fun otherTextLanguagesAreLeftAlone() {
        val first = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080", locale = "deDE")!!
        assertEquals("deDE", configTextLocale(first))
        assertEquals(null, buildConfigWtf(first, WowProduct.FOREVER, "1920x1080"))
    }

    @Test
    fun installLocaleReadsBuildInfoTags() {
        val root = java.nio.file.Files.createTempDirectory("wow").toFile()
        assertEquals("enUS", installLocale(root, WowProduct.FOREVER))
        java.io.File(root, ".build.info").writeText(
            "Active!DEC:1|Tags!STRING:0|Product!STRING:0\n1|Windows arm64 EU? acct-EUA? geoip-EU? deDE speech?:Windows arm64 EU? deDE text?|wowf\n",
        )
        assertEquals("deDE", installLocale(root, WowProduct.FOREVER))
    }

    @Test
    fun portalFollowsInstallRegion() {
        val root = java.nio.file.Files.createTempDirectory("wow").toFile()
        assertEquals("US", installPortal(root, WowProduct.FOREVER))
        java.io.File(root, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Product!STRING:0\neu|1|wowf\neu|1|wow_classic_beta\n")
        assertEquals("EU", installPortal(root, WowProduct.FOREVER))
        assertEquals("test", installPortal(root, WowProduct.BETA))
    }

    @Test
    fun chosenRegionSetsThePortal() {
        val root = java.nio.file.Files.createTempDirectory("wow").toFile()
        java.io.File(root, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Product!STRING:0\nus|1|wowf\nus|1|wow_classic_beta\n")
        try {
            app.gamenative.wow.product.GameRegions.chosen = app.gamenative.wow.product.GameRegion.EU
            assertEquals("EU", installPortal(root, WowProduct.FOREVER))
            assertEquals("test", installPortal(root, WowProduct.BETA))
        } finally {
            app.gamenative.wow.product.GameRegions.chosen = null
        }
    }

    @Test
    fun forcedPortalReplacesAnExistingOne() {
        val existing = listOf(
            "SET gxApi \"D3D11\"", "SET portal \"US\"", "SET agentUID \"wowf\"", "SET gxFullscreenResolution \"1920x1080\"",
            "SET gxWindowedResolution \"1920x1080\"", "SET showScreenNarrationDialog \"0\"", "SET accessibilityScreenNarrationEnabled \"0\"",
        )
        assertNull(buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080", portal = "EU"))
        val forced = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080", portal = "EU", forcePortal = true)!!
        assertTrue("SET portal \"EU\"" in forced)
        assertTrue(forced.none { it == "SET portal \"US\"" })
        assertNull(buildConfigWtf(forced, WowProduct.FOREVER, "1920x1080", portal = "EU", forcePortal = true))
    }

    @Test
    fun screenNarratorPromptIsAnsweredSoAutoLoginWorks() {
        val fresh = buildConfigWtf(null, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET showScreenNarrationDialog \"0\"" in fresh)
        assertTrue("SET accessibilityScreenNarrationEnabled \"0\"" in fresh)
        // A config from before (or from the beta) gets them added once; a player's own choice stays.
        val old = listOf("SET gxApi \"D3D11\"", "SET portal \"US\"", "SET agentUID \"wowf\"", "SET gxFullscreenResolution \"1920x1080\"", "SET gxWindowedResolution \"1920x1080\"")
        val updated = buildConfigWtf(old, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET showScreenNarrationDialog \"0\"" in updated)
        val chosen = updated.map { if (it.startsWith("SET accessibilityScreenNarrationEnabled")) "SET accessibilityScreenNarrationEnabled \"1\"" else it }
        assertNull(buildConfigWtf(chosen, WowProduct.FOREVER, "1920x1080"))
    }

    @Test
    fun newBetaConfigUsesTestPortal() {
        val lines = buildConfigWtf(null, WowProduct.BETA, "1280x720")!!
        assertTrue("SET portal \"test\"" in lines)
        assertTrue("SET agentUID \"wow_classic_beta\"" in lines)
    }

    @Test
    fun existingConfigKeepsUserLinesAndForcesD3D11() {
        val existing = listOf(
            "SET portal \"US\"",
            "SET gxApi \"Metal\"",
            "SET graphicsQuality \"7\"",
            "SET gxFullscreenResolution \"1920x1080\"",
            "SET gxWindowedResolution \"1920x1080\"",
        )
        val lines = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET graphicsQuality \"7\"" in lines)
        assertTrue("SET gxApi \"D3D11\"" in lines)
        assertTrue(lines.none { it == "SET gxApi \"Metal\"" })
    }

    @Test
    fun resolutionAlwaysMatchesTheContainer() {
        val existing = listOf(
            "SET portal \"US\"",
            "SET gxApi \"D3D11\"",
            "SET gxFullscreenResolution \"2560x1440\"",
            "SET gxWindowedResolution \"1280x720\"",
        )
        val lines = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080")!!
        // WoW saves its own 720p pick on exit; the chosen size wins on the next launch.
        assertTrue("SET gxFullscreenResolution \"1920x1080\"" in lines)
        assertTrue("SET gxWindowedResolution \"1920x1080\"" in lines)
        assertTrue(lines.none { it.contains("1280x720") || it.contains("2560x1440") })
    }

    @Test
    fun carriedOverConfigGetsProductPortalAndAgent() {
        val existing = listOf(
            "SET agentUID \"wow_classic_beta\"",
            "SET gxApi \"D3D11\"",
            "SET gxFullscreenResolution \"1920x1080\"",
            "SET gxWindowedResolution \"1920x1080\"",
        )
        val lines = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET portal \"US\"" in lines)
        assertTrue("SET agentUID \"wowf\"" in lines)
        assertTrue("SET agentUID \"wow_classic_beta\"" !in lines)
    }

    @Test
    fun userPortalIsKept() {
        val existing = listOf("SET portal \"eu\"", "SET gxApi \"Metal\"")
        val lines = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080")!!
        assertTrue("SET portal \"eu\"" in lines)
        assertTrue(lines.none { it == "SET portal \"US\"" })
    }

    @Test
    fun unchangedConfigReturnsNull() {
        val existing = listOf(
            "SET portal \"US\"",
            "SET gxApi \"D3D11\"",
            "SET gxFullscreenResolution \"1920x1080\"",
            "SET gxWindowedResolution \"1920x1080\"",
            "SET showScreenNarrationDialog \"0\"",
            "SET accessibilityScreenNarrationEnabled \"0\"",
        )
        assertNull(buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080"))
    }

    @Test
    fun aPickedGameAccountIsMarkedAndTheOthersKept() {
        assertEquals("SET accountList \"!WoW2|\"", accountListLine(null, "WoW2"))
        assertEquals("SET accountList \"WoW1|!WoW2|\"", accountListLine("SET accountList \"!WoW1|WoW2|\"", "WoW2"))
        assertEquals("SET accountList \"WoW1|!WoW3|\"", accountListLine("SET accountList \"!WoW1|\"", "WoW3"))
        assertEquals("SET accountList \"!wow2|WoW1|\"", accountListLine("SET accountList \"wow2|!WoW1|\"", "WoW2"))
    }

    @Test
    fun gameAccountIsWrittenOnlyWhenPickedAndOnlyOnce() {
        assertTrue(buildConfigWtf(null, WowProduct.FOREVER, "1920x1080")!!.none { it.startsWith("SET accountList") })
        assertTrue("SET accountList \"!WoW2|\"" in buildConfigWtf(null, WowProduct.FOREVER, "1920x1080", gameAccount = "WoW2")!!)

        val existing = listOf(
            "SET gxApi \"D3D11\"", "SET portal \"US\"", "SET agentUID \"wowf\"", "SET gxFullscreenResolution \"1920x1080\"",
            "SET gxWindowedResolution \"1920x1080\"", "SET showScreenNarrationDialog \"0\"", "SET accessibilityScreenNarrationEnabled \"0\"",
            "SET accountList \"!WoW1|WoW2|\"",
        )
        // Without a pick, the game's own list stays as it is.
        assertNull(buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080"))
        val picked = buildConfigWtf(existing, WowProduct.FOREVER, "1920x1080", gameAccount = "WoW2")!!
        assertEquals(listOf("SET accountList \"WoW1|!WoW2|\""), picked.filter { it.startsWith("SET accountList") })
        assertNull(buildConfigWtf(picked, WowProduct.FOREVER, "1920x1080", gameAccount = "WoW2"))
    }

    @Test
    fun flavorInfoNamesProduct() {
        assertEquals("Product Flavor!STRING:0\nwowf\n", flavorInfoText(WowProduct.FOREVER))
    }
}
