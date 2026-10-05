package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowClientDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readBuildInfo_parsesValidPipeDelimitedFile() {
        val root = tempFolder.newFolder("wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow_classic_era", result?.get("Product"))
        assertEquals("1.15.5.58238", result?.get("Version"))
    }

    @Test
    fun checkVersion_rejectsWrongProduct() {
        val root = tempFolder.newFolder("classic_era")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|9d5b7a136208535a8dfbc971489e8ae8|b1f5f5904d9c7cb3241bc6eeea50cce8|f68b323351f044ef3c9902641775796b|10332|tpr/wow|level3.blizzard.com|?|1|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.checkVersion(root)
        assertNull(result)
    }

    @Test
    fun readBuildInfo_selectsForeverBetaFromMultiGameInstall() {
        val root = tempFolder.newFolder("multi_wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n" +
            "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n" +
            "us|1|77777777777777777777777777777777|88888888888888888888888888888888|99999999999999999999999999999999|10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.61118|wow_classic_beta\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow_classic_beta", result?.get("Product"))
        assertEquals("1.60.1.61118", result?.get("Version"))
    }

    @Test
    fun readBuildInfo_detectsIncompatibleGameWhenForeverBetaMissingFromMultiGame() {
        val root = tempFolder.newFolder("multi_incompatible")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n" +
            "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wow", result?.get("Product"))
        val check = WowClientDownloader.checkVersion(root)
        assertNull(check)
    }

    @Test
    fun readBuildInfo_parsesHostInstallationIfExists() {
        val hostFile = File("/Applications/World of Warcraft/.build.info")
        if (!hostFile.isFile) return
        val result = WowClientDownloader.readBuildInfo(hostFile)
        assertNotNull(result)
        assertEquals("wow_classic_beta", result?.get("Product"))
        assertEquals("1.60.1.70205", result?.get("Version"))
    }

    @Test
    fun readBuildInfo_returnsNullOnMissingFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        assertNull(WowClientDownloader.readBuildInfo(nonExistent))
    }
}

