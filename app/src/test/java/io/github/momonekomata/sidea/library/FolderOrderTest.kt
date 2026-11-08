package io.github.momonekomata.sidea.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderOrderTest {
    @Test
    fun foldersGoInTheOrderAFileBrowserListsThem() {
        val folders = listOf("/music/Album%2010/", "/music/A%20B/", "/music/A/x/", "/music/album 2/", "/music/A/", "primary:Music/Zed")
        assertEquals(
            // A folder, then what's inside it, before "A B"; "2" before "10"; case doesn't matter.
            listOf("/music/A/", "/music/A/x/", "/music/A%20B/", "/music/album 2/", "/music/Album%2010/", "primary:Music/Zed"),
            folders.sortedWith(Library.FolderOrder),
        )
    }
}
