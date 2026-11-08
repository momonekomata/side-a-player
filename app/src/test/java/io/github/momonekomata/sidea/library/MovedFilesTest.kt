package io.github.momonekomata.sidea.library

import io.github.momonekomata.sidea.sources.MissingFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedFilesTest {
    @Test
    fun knowsEachSourcesWayOfSayingTheFileIsntThere() {
        assertTrue(MissingFiles.isGone(java.io.IOException("HTTP 404 reading https://nas:444/music/x.flac")))
        assertTrue(MissingFiles.isGone(java.io.FileNotFoundException("primary:Music/x.flac")))
        assertTrue(MissingFiles.isGone(RuntimeException("wrapped", java.io.IOException("STATUS_OBJECT_NAME_NOT_FOUND (0xc0000034)"))))
        assertTrue(MissingFiles.isGone(java.io.IOException("No such file")))
        assertTrue(MissingFiles.isGone(java.io.IOException("path/not_found/..")))
        assertTrue(MissingFiles.looksGone("550 /music/x.flac: No such file or directory"))
        // A phone folder (Android's storage provider), as the player saw it.
        assertTrue(MissingFiles.looksGone("Failed to determine if primary:Music/Compilations/Various Hits (1999)/01 Comp Song A.mp3 is child of primary:Music: java.io.FileNotFoundException: Missing file for primary:Music/…"))
        // Not "gone": the server's down, a login failed, a timeout.
        assertFalse(MissingFiles.isGone(java.io.IOException("HTTP 401 reading https://nas/x.flac")))
        assertFalse(MissingFiles.isGone(java.net.SocketTimeoutException("timeout")))
        assertFalse(MissingFiles.isGone(java.io.IOException("HTTP 503 reading x")))
        assertFalse(MissingFiles.isGone(null))
    }

    @Test
    fun songsInARenamedFolderAreFollowedToTheirNewAddress() {
        val gone = listOf(
            Triple("/music/- ) (2024)/01. - ).flac", "01. - ).flac", 22388516L),
            Triple("/music/Old/01 Intro.flac", "01 Intro.flac", 1000L),
            Triple("/music/Old/02 Song.flac", "02 Song.flac", 2000L),
        )
        val found = listOf(
            Triple("/music/) (2024)/01. - ).flac", "01. - ).flac", 22388516L),
            Triple("/music/New/01 intro.flac", "01 intro.flac", 1000L),
            // Same name, new size (retagged with a bigger cover): not taken for the same song.
            Triple("/music/New/02 Song.flac", "02 Song.flac", 2500L),
        )
        assertEquals(
            mapOf("/music/- ) (2024)/01. - ).flac" to "/music/) (2024)/01. - ).flac", "/music/Old/01 Intro.flac" to "/music/New/01 intro.flac"),
            matchMoves(gone, found),
        )
        // Two files that could be either: neither is guessed.
        val twins = listOf(Triple("a/1.flac", "1.flac", 5L), Triple("b/1.flac", "1.flac", 5L))
        assertEquals(emptyMap<String, String>(), matchMoves(twins, listOf(Triple("c/1.flac", "1.flac", 5L))))
    }
}
