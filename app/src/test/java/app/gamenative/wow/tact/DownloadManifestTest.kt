package app.gamenative.wow.tact

import app.gamenative.wow.tact.TactFixtures.DownloadSpec
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadManifestTest {
    private val entries = listOf(
        DownloadSpec("a1".repeat(16), 5_000_000_000L, setOf("Windows", "arm64", "enUS", "US")),
        DownloadSpec("b2".repeat(16), 300, setOf("Windows", "arm64", "deDE", "EU")),
        DownloadSpec("c3".repeat(16), 400, setOf("Windows", "x86_64", "enUS", "US")),
        DownloadSpec("d4".repeat(16), 500, setOf("Windows", "arm64", "enUS", "US", "HighRes")),
        // No locale tag at all: shared by every language.
        DownloadSpec("e5".repeat(16), 600, setOf("Windows", "arm64", "US")),
    )
    private val wanted = setOf("Windows", "arm64", "enUS", "US")

    @Test
    fun readsKeysAndFortyBitSizes() {
        for (version in 1..3) {
            val manifest = DownloadManifest.parse(TactFixtures.downloadManifest(entries, version))
            assertEquals(5, manifest.count)
            assertEquals("a1".repeat(16), hex(manifest.key(0)))
            assertEquals(5_000_000_000L, manifest.size(0))
            assertEquals(600L, manifest.size(4))
        }
    }

    @Test
    fun selectsLikeTheInstallManifestAndLeavesOutOptionalContent() {
        val manifest = DownloadManifest.parse(TactFixtures.downloadManifest(entries, 3))
        // Real manifests tag shared files with every locale; one with no locale tag at all is left out.
        assertEquals(listOf(0, 3), manifest.select(wanted).toList())
        assertEquals(listOf(0), manifest.select(wanted, DownloadManifest.OPTIONAL_TAGS).toList())
    }

    @Test(expected = IOException::class)
    fun rejectsOtherFiles() {
        DownloadManifest.parse("IN".toByteArray() + ByteArray(20))
    }
}
