package io.github.momonekomata.sidea.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistFilesTest {
    private fun song(id: String, source: String, folder: String, folderName: String, file: String, title: String, artist: String) = LibTrack(
        id = id, source = source, folder = folder, folderName = folderName, file = file, size = 1, title = title, artist = artist,
        albumArtist = "", album = folderName, genre = "", year = null, track = null, disc = null, durationMs = 200_000,
        codec = "FLAC", sampleRate = 44_100, bits = 16, channels = 2,
    )

    // A WebDAV source (folders are encoded URL paths), a phone folder (document ids) and Drive (ids).
    private val lib = listOf(
        song("a1", "nas", "/music/Radiohead/OK%20Computer/", "OK Computer", "01 Airbag.flac", "Airbag", "Radiohead"),
        song("a2", "nas", "/music/Radiohead/OK%20Computer/", "OK Computer", "02 Paranoid Android.flac", "Paranoid Android", "Radiohead"),
        song("b1", "nas", "/music/Various/Hits/", "Hits", "01 Airbag.flac", "Airbag (Live)", "Someone"),
        song("c1", "phone", "primary:Music/Björk/Post", "Post", "01 Army of Me.flac", "Army of Me", "Björk"),
        song("d1", "drive", "1AbCdEf", "Homogenic", "01 Hunter.flac", "Hunter", "Björk"),
    )
    private val index = PlaylistFiles.Index(lib)

    @Test
    fun readsExtendedM3u() {
        val f = PlaylistFiles.parseM3u(
            "#EXTM3U\r\n#PLAYLIST:Road trip\r\n#EXTINF:287,Radiohead - Airbag\r\nRadiohead\\OK Computer\\01 Airbag.flac\r\n\r\n# a note\r\nhttp://x/y.mp3\r\n",
        )
        assertEquals("Road trip", f.name)
        assertEquals(listOf(ListEntry("Radiohead\\OK Computer\\01 Airbag.flac", "Radiohead", "Airbag", 287), ListEntry("http://x/y.mp3")), f.entries)
    }

    @Test
    fun readsPls() {
        val f = PlaylistFiles.parsePls("[playlist]\nFile2=b.flac\nTitle1=Björk - Hunter\nFile1=a.flac\nLength1=255\nNumberOfEntries=2\n")
        assertEquals(listOf(ListEntry("a.flac", "Björk", "Hunter", 255), ListEntry("b.flac")), f.entries)
    }

    @Test
    fun decodesUtf8AndOldCodePage() {
        assertEquals("Björk", PlaylistFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Björk".toByteArray()))
        assertEquals("Björk", PlaylistFiles.decode("Björk".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun findsSongsWhereverThePlaylistWasMade() {
        fun m(path: String, base: List<String>? = null) = index.match(ListEntry(path), base, "nas")?.id
        // A computer's path, another phone's, a URL: the folders that match pick the right Airbag.
        assertEquals("a1", m("D:\\Music\\Radiohead\\OK Computer\\01 Airbag.flac"))
        assertEquals("a1", m("/storage/emulated/0/Music/OK Computer/01 Airbag.flac"))
        assertEquals("b1", m("http://other/share/Hits/01%20Airbag.flac"))
        assertEquals("c1", m("/sdcard/Music/Björk/Post/01 Army of Me.flac"))
        // Decomposed accents (a Mac's NAS share) and case don't matter.
        assertEquals("c1", m("music/BJO\u0308RK/post/01 army of me.flac"))
        // Relative to the playlist's own folder, ".." and all.
        val here = PlaylistFiles.rawSegments("/music/Various/Hits/").map(PlaylistFiles::norm)
        assertEquals("b1", m("01 Airbag.flac", here))
        assertEquals("a2", m("../../Radiohead/OK Computer/02 Paranoid Android.flac", here))
        // Drive keeps ids for folders: its folder name and the file name still find it.
        assertEquals("d1", m("Homogenic/01 Hunter.flac"))
        // Another format of the same song.
        assertEquals("a2", m("OK Computer/02 Paranoid Android.mp3"))
        assertNull(m("Nowhere/99 Nothing.flac"))
    }

    @Test
    fun fallsBackToTheNamesTheFileGives() {
        assertEquals("a2", index.match(ListEntry("C:\\old\\track02.wma", "radiohead", "PARANOID ANDROID"))?.id)
        assertEquals("c1", index.match(ListEntry("x.mp3", "", "Army of Me"))?.id)
    }

    @Test
    fun exportsPathsInsideTheLibraryFolder() {
        val nas = LibrarySource(id = "nas", name = "NAS", serverId = "s", root = "/music/")
        assertEquals("Radiohead/OK Computer/01 Airbag.flac", PlaylistFiles.pathIn(lib[0], nas))
        val phone = LibrarySource(id = "phone", name = "Phone", tree = "content://x", root = "primary:Music")
        assertEquals("Björk/Post/01 Army of Me.flac", PlaylistFiles.pathIn(lib[3], phone))
        assertEquals("Homogenic/01 Hunter.flac", PlaylistFiles.pathIn(lib[4], null))
        // And what's written is found again.
        assertEquals("a1", index.match(ListEntry(PlaylistFiles.pathIn(lib[0], nas)))?.id)
    }
}
