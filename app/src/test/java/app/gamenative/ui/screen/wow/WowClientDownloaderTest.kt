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
    fun readBuildInfo_selectsForeverFromMultiGameInstall() {
        val root = tempFolder.newFolder("multi_wow")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Arm64!DEC:1|Version!STRING:0|Product!STRING:0\n" +
            "us|1|11111111111111111111111111111111|22222222222222222222222222222222|33333333333333333333333333333333|10000|tpr/wow|level3.blizzard.com|?|0|11.0.7.58238|wow\n" +
            "us|1|44444444444444444444444444444444|55555555555555555555555555555555|66666666666666666666666666666666|10000|tpr/wow|level3.blizzard.com|?|0|1.15.5.58238|wow_classic_era\n" +
            "us|1|77777777777777777777777777777777|88888888888888888888888888888888|99999999999999999999999999999999|10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.70205|wow_classic_beta\n" +
            "us|1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb||10000|tpr/wow|level3.blizzard.com|?|1|1.60.1.70658|wowf\n"
        )

        val result = WowClientDownloader.readBuildInfo(buildInfo)
        assertNotNull(result)
        assertEquals("wowf", result?.get("Product"))
        assertEquals("1.60.1.70658", result?.get("Version"))
    }

    @Test
    fun installedProducts_listsBetaWhenForeverMissing() {
        val buildInfo = File(tempFolder.newFolder("beta_only"), ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        assertEquals(setOf("wow_classic_era", "wow_classic_beta"), WowClientDownloader.installedProducts(buildInfo))
        assertEquals(emptySet<String>(), WowClientDownloader.installedProducts(File(tempFolder.root, "missing")))
    }

    @Test
    fun upsertBuildInfo_addsForeverRowAndKeepsBetaRegion() {
        val root = tempFolder.newFolder("beta_upgrade")
        val buildInfo = File(root, ".build.info")
        buildInfo.writeText(BETA_BUILD_INFO)

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(4, lines.size)
        assertEquals(BETA_BUILD_INFO.lines().take(3), lines.take(3))
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("eu", row["Branch"])
        assertEquals("1", row["Active"])
        assertEquals("1.60.1.70658", row["Version"])
        assertEquals("165410aca770316ea0f88100bb4830c2", row["Build Key"])
        assertEquals("f0fcf03baf658e38f2f6c96a77798a2c", row["CDN Key"])
        assertEquals("level3.blizzard.com us.cdn.blizzard.com", row["CDN Hosts"])
        assertEquals("http://level3.blizzard.com/?maxhosts=8 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1", row["CDN Servers"])
        assertEquals("Windows arm64 EU? enUS speech?:Windows arm64 EU? enUS text?", row["Tags"])
    }

    @Test
    fun upsertBuildInfo_createsFileForFreshInstall() {
        val buildInfo = File(tempFolder.newFolder("fresh"), ".build.info")

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        val lines = buildInfo.readLines()
        assertEquals(2, lines.size)
        assertEquals(15, lines[0].split("|").size)
        assertEquals(15, lines[1].split("|").size)
        val row = WowClientDownloader.readBuildInfo(buildInfo)!!
        assertEquals("wowf", row["Product"])
        assertEquals("tpr/wow", row["CDN Path"])
        assertEquals("1.60.1.70658", row["Version"])
    }

    @Test
    fun upsertBuildInfo_updatesExistingForeverRowInPlace() {
        val buildInfo = File(tempFolder.newFolder("update"), ".build.info")
        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET.copy(remoteVersion = "1.60.1.70000", remoteBuildConfig = "0".repeat(32)))

        WowClientDownloader.upsertBuildInfo(buildInfo, TARGET)

        assertEquals(2, buildInfo.readLines().size)
        assertEquals("1.60.1.70658", WowClientDownloader.readBuildInfo(buildInfo)?.get("Version"))
        assertEquals("165410aca770316ea0f88100bb4830c2", WowClientDownloader.readBuildInfo(buildInfo)?.get("Build Key"))
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
        assertNotNull(result?.get("Version"))
    }

    private companion object {
        val BETA_BUILD_INFO =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n" +
                "eu|1|77777777777777777777777777777777|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.15.9.70003|wow_classic_era\n" +
                "eu|1|99999999999999999999999999999999|88888888888888888888888888888888|||tpr/wow|level3.blizzard.com|http://level3.blizzard.com/?maxhosts=8|OSX x86_64 EU? enUS speech?|1.60.1.70205|wow_classic_beta\n"

        val TARGET = WowClientDownloader.VersionCheckResult(
            localVersion = "",
            remoteVersion = "1.60.1.70658",
            isOutdated = true,
            remoteBuildConfig = "165410aca770316ea0f88100bb4830c2",
            remoteCdnConfig = "f0fcf03baf658e38f2f6c96a77798a2c",
            remoteCdnHosts = listOf("level3.blizzard.com", "us.cdn.blizzard.com"),
            remoteCdnPath = "tpr/wow",
            remoteCdnServers = listOf("http://level3.blizzard.com/?maxhosts=8", "https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"),
        )
    }

    @Test
    fun versionServiceBase_usesRegionalHttpsEndpoint() {
        assertEquals(
            "https://eu.version.battle.net/v2/products/wowf",
            WowClientDownloader.versionServiceBase("eu"),
        )
        assertEquals(
            "https://us.version.battle.net/v2/products/wowf",
            WowClientDownloader.versionServiceBase("US"),
        )
        assertEquals(
            "https://cn.version.battlenet.com.cn/v2/products/wowf",
            WowClientDownloader.versionServiceBase("cn"),
        )
    }

    @Test
    fun secureCdnServers_keepsOnlyHttpsAndStripsQueryParameters() {
        val servers = listOf(
            "http://eu.cdn.blizzard.com/?maxhosts=4",
            "https://eu.cdn.blizzard.com/?maxhosts=4&fallback=1",
            "https://level3.ssl.blizzard.com/?fallback=1&maxhosts=4",
        )

        assertEquals(
            listOf(
                "https://eu.cdn.blizzard.com",
                "https://level3.ssl.blizzard.com",
            ),
            WowClientDownloader.secureCdnServers(servers = servers),
        )
    }

    @Test
    fun secureCdnServers_fallsBackToHostsAndMapsLevel3ToSsl() {
        assertEquals(
            listOf(
                "https://level3.ssl.blizzard.com",
                "https://us.cdn.blizzard.com",
            ),
            WowClientDownloader.secureCdnServers(
                servers = listOf("http://level3.blizzard.com/?maxhosts=8"),
                hosts = listOf("level3.blizzard.com", "us.cdn.blizzard.com"),
            ),
        )
    }

    @Test
    fun defaultCdnServers_prefersMatchingRegionOverUs() {
        assertEquals(
            listOf(
                "https://eu.cdn.blizzard.com",
                "https://level3.ssl.blizzard.com",
            ),
            WowClientDownloader.defaultCdnServers("eu"),
        )
    }

    @Test(expected = java.io.IOException::class)
    fun verifyConfigDigest_rejectsMismatchedDigest() {
        val data = "build-config-content".toByteArray()
        WowClientDownloader.verifyConfigDigest("00000000000000000000000000000000", data)
    }

    @Test
    fun readBuildInfo_returnsNullOnMissingFile() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        assertNull(WowClientDownloader.readBuildInfo(nonExistent))
    }
}
