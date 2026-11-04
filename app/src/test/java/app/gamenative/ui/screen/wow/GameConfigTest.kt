package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun configWtf(root: File) = File(root, "${WowClientDownloader.FLAVOR_DIR}/WTF/Config.wtf")

    private fun portals(root: File) = configWtf(root).readLines().filter { it.startsWith("SET portal ") }

    private fun install(branch: String, config: String? = null): File {
        val root = tempFolder.newFolder()
        File(root, WowClientDownloader.BUILD_INFO).writeText(
            "Branch!STRING:0|Product!STRING:0\n$branch|${WowClientDownloader.TARGET_PRODUCT}\n",
        )
        config?.let { configWtf(root).apply { parentFile?.mkdirs() }.writeText(it) }
        return root
    }

    @Test
    fun noRegionPicked_newConfig_usesInstallRegion() {
        val root = install("eu")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun noRegionPicked_keepsExistingPortal() {
        val root = install("us", "SET portal \"EU\"\n")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun pickedRegion_replacesExistingPortal() {
        val root = install("us", "SET portal \"US\"\nSET gxApi \"D3D11\"\n")
        ensureGameConfig(root, "1920x1080", GameRegion.EU)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun resolution_alwaysMatchesContainer() {
        val root = install("us", "SET gxFullscreenResolution \"1280x720\"\nSET gxWindowedResolution \"953x720\"\n")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(
            listOf("SET gxFullscreenResolution \"1920x1080\"", "SET gxWindowedResolution \"1920x1080\""),
            configWtf(root).readLines().filter { it.contains("Resolution ") },
        )
    }

    private fun lines(root: File, key: String) = configWtf(root).readLines().filter { it.startsWith("SET $key ") }

    @Test
    fun uiScale_isWrittenWithUseUiScaleOn_replacingTheGamesOwnValue() {
        val root = install("us", "SET useUiScale \"0\"\nSET uiScale \"1.1000000238419\"\nSET vsync \"1\"\n")
        ensureGameConfig(root, "1920x1080", null, DisplaySettings(uiScale = 1.2f))
        assertEquals(listOf("SET useUiScale \"1\""), lines(root, "useUiScale"))
        assertEquals(listOf("SET uiScale \"1.2\""), lines(root, "uiScale"))
        assertEquals(listOf("SET vsync \"1\""), lines(root, "vsync"))
    }

    @Test
    fun inGameSetting_leavesThePlayersOwnValuesAlone() {
        val root = install("us", "SET useUiScale \"1\"\nSET uiScale \"1.1000000238419\"\nSET userFontScale \"2\"\n")
        ensureGameConfig(root, "1920x1080", null, DisplaySettings())
        assertEquals(listOf("SET uiScale \"1.1000000238419\""), lines(root, "uiScale"))
        assertEquals(listOf("SET userFontScale \"2\""), lines(root, "userFontScale"))
    }

    @Test
    fun uiScale_isAppliedToABrandNewConfigToo_andNotDuplicatedOnRelaunch() {
        val root = install("us")
        repeat(2) { ensureGameConfig(root, "1920x1080", null, DisplaySettings(uiScale = 0.8f)) }
        assertEquals(listOf("SET uiScale \"0.8\""), lines(root, "uiScale"))
        assertEquals(1, lines(root, "useUiScale").size)
    }

    @Test
    fun textSize_setsBothTheGameAndTheLoginScreenKeys() {
        val root = install("us", "SET userFontScale \"0\"\n")
        ensureGameConfig(root, "1920x1080", null, DisplaySettings(textScale = 2f))
        assertEquals(listOf("SET userFontScale \"2\""), lines(root, "userFontScale"))
        assertEquals(listOf("SET userFontScaleGlue \"2\""), lines(root, "userFontScaleGlue"))
    }

    @Test
    fun displayKeys_formatsScalesWithoutFloatNoise() {
        assertEquals("\"1\"", displayKeys(DisplaySettings(uiScale = 1.0f)).getValue("uiScale"))
        assertEquals("\"1.1\"", displayKeys(DisplaySettings(uiScale = 1.1f)).getValue("uiScale"))
        assertEquals("\"0.9\"", displayKeys(DisplaySettings(uiScale = 0.9f)).getValue("uiScale"))
        assertEquals(emptyMap<String, String>(), displayKeys(DisplaySettings()))
    }

    @Test
    fun textScale_writesExactlyWhatWowItselfWritesForEachOption() {
        // Measured on a Thor: choose the option in WoW Forever 1.60.1, quit, read Config.wtf.
        val measured = mapOf(
            TextScale.SMALL to "0.8571", TextScale.MEDIUM to "1", TextScale.LARGE to "1.1429",
            TextScale.EXTRA_LARGE to "1.4286", TextScale.HUGE to "1.7143", TextScale.GIGANTIC to "2",
        )
        for ((option, written) in measured) {
            val keys = displayKeys(DisplaySettings(textScale = option.value))
            assertEquals("${option.label} userFontScale", "\"$written\"", keys.getValue("userFontScale"))
            assertEquals("${option.label} userFontScaleGlue", "\"$written\"", keys.getValue("userFontScaleGlue"))
        }
        assertEquals(measured.keys + TextScale.GAME, TextScale.entries.toSet())
        assertEquals(null, TextScale.GAME.value)
    }
}
