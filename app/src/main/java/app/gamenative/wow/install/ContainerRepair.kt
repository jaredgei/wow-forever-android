package app.gamenative.wow.install

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Removes a container folder that can't be loaded, for example when first-time setup was cut short before the
 * config was written. Without this, creating the container fails every time because the folder already exists
 * (issue #30). Only the Wine prefix goes: the game, its settings and its addons live in the game folder.
 */
object ContainerRepair {
    /** Next to the containers, under a name the container manager doesn't load. */
    const val DISCARDING = "discarded-container"

    /** A prefix is full of links to shared storage, so this never follows them. */
    @Throws(IOException::class)
    fun discard(containerDir: File) {
        val folder = containerDir.toPath()
        val discarding = folder.resolveSibling(DISCARDING)
        if (Files.exists(discarding, LinkOption.NOFOLLOW_LINKS)) GameData.deleteNoFollow(discarding)
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return
        Files.move(folder, discarding)
        GameData.deleteNoFollow(discarding)
    }
}
