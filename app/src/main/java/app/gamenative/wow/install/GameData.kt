package app.gamenative.wow.install

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Clears the game's world data so the game downloads it again when it next runs, the same state as a fresh install.
 * Only `Data/data` and our full download's working folders go. Settings, addons, the game program and
 * `Data/config` and `Data/indices`, which the game needs to download, all stay.
 */
object GameData {
    /** Where the folders wait to be deleted, so the game never sees half-deleted data. */
    const val CLEARING = "foreverywhere-cleared"

    private val CLEARED = listOf("data", FullDownloader.STAGING, FullDownloader.OLD)

    fun clear(root: File) {
        if (!File(root, ".build.info").isFile) throw IOException("${root.path} isn't a game folder")
        val data = realDataFolder(root) ?: throw IOException("The game's Data folder isn't a normal folder")
        finishClearing(root)
        File(root, FullDownloader.MARKER).delete()
        val clearing = Files.createDirectory(data.resolve(CLEARING))
        for (name in CLEARED) {
            val folder = data.resolve(name)
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) Files.move(folder, clearing.resolve(name))
        }
        deleteNoFollow(clearing)
    }

    /** Deletes what a clear left behind if the app was closed partway through. */
    fun finishClearing(root: File) {
        val clearing = realDataFolder(root)?.resolve(CLEARING) ?: return
        if (Files.exists(clearing, LinkOption.NOFOLLOW_LINKS)) deleteNoFollow(clearing)
    }

    private fun realDataFolder(root: File): Path? =
        root.toPath().resolve("Data").takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    /**
     * Deletes [path] and what's under it. A link is removed without touching what it points to. Never use
     * [File.deleteRecursively] on game or Wine folders: it walks through links and can empty shared storage.
     */
    fun deleteNoFollow(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                    if (e != null) throw e
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
