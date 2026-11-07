# WALKDECK

A bit-perfect music player for your own files, designed for the Samsung Galaxy S26 Ultra and
dressed like the first Walkman: a metallic blue body, a brushed-aluminium faceplate, silver piano
keys with REW / PLAY / FF printed on them (PLAY turns orange while the tape runs, and the keys sink
when pressed), the orange hotline colour, the quality light in a small dark window, and the
Library printed in navy on an aluminium panel, its name in heavy slanted capitals like the lid's
lettering. Full-width cover art (tap it for lyrics), a cassette whose reels turn while it plays,
and WALKDECK's own USB driver for DACs, since Samsung's Android has no bit-perfect USB mode.

This is the `s26` branch of Side A, renamed WALKDECK. It plays only music you own, on the phone, a NAS or a cloud
drive; the streaming-app remote control on `main` is gone. It installs as its own app
(`io.github.momonekomata.sidea.s26`, "WALKDECK", with its own cassette-player icon), so it sits beside the `main` build rather than
replacing it.

| Source | How it's reached | How it plays |
|---|---|---|
| **Local** (any folder on the phone, SD card or USB drive) | Android's folder picker | Decoded by WALKDECK at the file's own rate and depth |
| **SMB** (Windows shares, Samba on most NAS boxes) | SMB 2/3, user name and password or guest | Downloaded into the cache on four connections at once, playing as it arrives |
| **SFTP** | SSH, user name and password; host key pinned on first use | Same |
| **FTP** and **FTPS** | FTP plain (home network only) or FTPS with TLS | Same (FTPS on one connection) |
| **WebDAV** | http/https, Basic auth (Synology, QNAP, Nextcloud) | Same |
| **Google Drive**, **OneDrive**, **Dropbox** | Their official APIs, signed in through the browser | Same |

Sources are set up in **Settings → Music sources** and their folders added to the **Library**,
which is where all music is browsed and played. Every source gets the same treatment: tags, covers,
`.lrc` lyrics (read and saved), search, favourites and playlists, and bit-perfect output.

## Bit-perfect output

Android normally mixes everything at 48 kHz. On **Android 14+ with a USB DAC**, WALKDECK asks the
platform for a bit-perfect mixer (`AudioManager.setPreferredMixerAttributes`,
`MIXER_BEHAVIOR_BIT_PERFECT`) at the file's own rate, then writes samples in the DAC's integer
format with no resampling, dithering or volume scaling. The status lines under the cover say
what actually happened:

* `● BIT-PERFECT · FLAC 96 kHz/24-bit · 24-bit · <DAC name>`: untouched samples at the native rate
* `● MIXED · … `: Android's mixer is in the path, with the reason (no USB DAC, Android < 14, or the
  DAC/phone doesn't offer bit-perfect at that rate)

### Exclusive USB driver (phones without bit-perfect, e.g. Samsung)

When Android can't send to the DAC bit-perfect at a file's rate, WALKDECK drives the DAC itself,
like USB Audio Player Pro (Settings → *Exclusive USB driver*, on by default):

1. The first time, Android asks *"Allow WALKDECK to access <DAC>?"*. Plugging the DAC in can also
   offer to open WALKDECK, which grants the same access.
2. WALKDECK takes the DAC's audio interfaces from Android's USB audio driver, picks the DAC's
   format for the file (16/24/32-bit, the narrowest that holds every bit), sets the DAC's clock
   to the file's own rate (UAC2 clock source or UAC1 endpoint), and streams the samples over
   isochronous USB itself (native code over usbfs, `app/src/main/cpp/usb_audio.c`). For async DACs
   it follows the DAC's feedback endpoint, so the DAC's clock sets the pace.
3. The badge reads LOSSLESS and the signal path says *WALKDECK USB driver, exclusive*.
4. When playback stops, the DAC goes back to Android. Between songs it stays with WALKDECK, and only
   the rate changes. Unplugging stops cleanly.

**Volume.** With Android bypassed, the phone's volume can't reach the DAC, so WALKDECK applies its
own volume (the phone's volume keys and a DAC's own buttons drive it). It starts at
100 %, which is bit-perfect (the badge says LOSSLESS straight away), so keep the DAC or amp's own
volume sensible before the first song. Anything lower is digital attenuation in 64-bit float into a 24-bit or
wider slot, and the badge says ENHANCED. DoP always plays at 100 %, so set the level on the DAC or amp.

Other apps can't play through the DAC while WALKDECK holds it (that's what exclusive means). WALKDECK
gives it back as soon as another app starts playing, and ten seconds into a pause. Play
picks up from the same spot. To give it back, WALKDECK takes every audio interface
of the DAC while it holds it, and on release resets the DAC over USB, which looks like a replug to
Linux and to Android, so both set the DAC up from scratch. (Only handing Linux's driver back
rebuilds Linux's sound card, but Android keeps using the old one: the DAC stays listed in other
apps and they play into silence.) If a reset isn't allowed, WALKDECK falls back to reattaching the
driver, and if Android doesn't list the DAC a few seconds later, WALKDECK says so.

A reset looks like a replug to Android too, so Android asks once which app should open for the
DAC: pick WALKDECK and tick **Always**. That's what keeps WALKDECK's USB access for good; WALKDECK then
receives the replug silently and doesn't come to the front.

### Android's bit-perfect mode

No permission prompt is involved here: Android's bit-perfect mode is a system feature, not a
USB takeover. Whether it's available is the phone maker's call. If the DAC is connected but the
phone offers no bit-perfect mode for it, the signal path says exactly that, listing the rates it
does offer, if any. Plugging the DAC in (or out) mid-song reopens the song on the new path where it
was. Apps that prompt for USB access (like UAPP) bypass Android with their own USB driver;
that's what exclusive mode takes on phones without the bit-perfect feature.

Notes:
* Set the volume on the DAC or amp. In bit-perfect mode Android's own volume is bypassed, so start low.
* Whether a phone supports bit-perfect USB is up to the manufacturer. Pixels do; for Samsung and
  others, try it and check the status line.
* High-res files are decoded to float (exact for 24-bit) and converted back to the DAC's integer
  format, so nothing is truncated. `PcmConverterTest` checks that round trip.
* What isn't done yet: a UAPP-style user-space USB driver for phones without Android 14 bit-perfect
  support, multichannel, DST-compressed DFF, and DSD at 48 kHz-family rates.

## Decoding

Lossless files are decoded by WALKDECK itself: **ALAC** (Apple Lossless in .m4a, 16–32 bit; many
phones have no decoder for it; also when an ID3 tag sits in front), **FLAC** (all stereo modes, 8–32 bit, any rate,
seek table or not, and files with an ID3 tag stuck on the front, which Android's extractor rejects as
"audio/unknown"), **WAV** (PCM, extensible, 32/64-bit float), **AIFF/AIFF-C** (incl. `sowt`, `fl32`)
and **DSD**. They come out as the file's exact integer samples, so the bit-perfect path never detours
through float. MP3, AAC (incl. AAC .m4a), Opus and Vorbis go through Android's MediaCodec. If the phone has
no decoder for a file, the error says which format it is.

## Library

The Library is the app's one screen for music. Point it at your folders in **Settings → Music
sources**: **Add a folder on the phone** (Android's folder picker; SD cards and USB drives too), or
add a NAS or cloud server there, tap it, go into the folder you want and tap **Add this folder to the
Library**. Tap a Library folder in that list to remove it. (A folder that was open in the old Local
tab joins the Library by itself on the first start of this version.) Add as many as you like;
everything inside them is scanned in (hidden folders such as `.thumbnails` are skipped), with each
song's tags (title, artist, album artist, album, genre, year, track and disc) and what's inside the
file (format, sample rate, bit depth, channels, length) read from the file itself, a few kilobytes
each, so it's cheap over a NAS too. It all lives in a database on the phone.

* **Artists** (by album artist), **Albums** (with covers, year and the format of the album's best
  file), **Tracks** and **Genres**; tap through artist → albums → songs, genre → albums. Play or
  Shuffle a whole album, artist or genre.
* **Your lists** at the top of Focus: **♥ Favourites** and each **playlist** (and + New playlist).
  Focusing on one shows its songs in its own order (newest favourite first); the line above them
  leaves the list, or deletes a playlist. Long-press a song there to take it out of the playlist.
* **Search** across titles, artists, albums, genres, file and folder names and formats at once;
  every word must match.
* **Albums** as a grid of covers or a list (the ▦ / ☰ button).
* **Focus**, after Roon's, from what the scan reads: genres, release date (decades or a year
  range), performers, composers, labels, most played (top 25…1000), played in the last day…year,
  added in the last day…year, compilations, storage locations, the inspector (no genre, no year,
  no artist, untagged, duplicates, albums in several formats), format, sample rate, bit depth and
  channel layout, plus lossless only. Every section shows its top six, with View more. Focus and
  search apply to every view.
* Rescans only read songs that are new or changed size, and drop songs that are gone. Each source
  is rescanned on start when it's half a day old, or with Rescan in Music sources. FTPS songs get
  their tags once they've been played (FTPS can't read just part of a file).
* Long-press a song for Play Next / Play Last, favourites and playlists, as everywhere.

## Playback controls

Shuffle and repeat (all / one) beside the transport keys; hold ⏮/⏭ to rewind or fast-forward.
Swipe the transport keys left for −10 / −5 / +5 / +10 s (on a phone held sideways, shuffle and
repeat are there too). ♡ beside the title adds a song to **Favourites** (Focus → ♥ Favourites).
Long lists (20 entries or more) get an A–Z index down the right
edge: touch or slide along it to jump to a letter; digits file under #, and Thai, kana, kanji and
hangul get a mark each after Z.

**Most played**: Focus → Most played narrows the Library to the songs played most, from anywhere.
A play counts when a song starts.

Under the deck, **DECK · TAPE · QUEUE** (swipe, or tap the names): **TAPE** is a cassette with the
song's name and artist on its label; its reels turn while it plays and the tape winds from the left
reel onto the right as the song goes on, with REW / PLAY / FF under it.

**Long-press a song** (in the Library, a playlist, the queue or the history) for
add to / remove from favourites, play next, add to queue and add to playlist. The **queue**, laid out like Apple Music's: Shuffle, Repeat and Clear stay
at the top; what played is above (scroll up for more), then the song playing with its cover, then
**Playing Next**. Drag a song by its ≡ handle to move it, swipe it left to remove it, tap it to
jump there, or long-press it for the song menu (Play Next, Play Last, Remove from Queue,
favourites, playlists). Long-press any song elsewhere for **Play Next** (right after this song) or
**Play Last** (end of the queue).

The queue, the song and where it had got to are kept on the phone: after the app is closed (or a
DAC is pulled out), the deck shows the last song paused at that spot, with shuffle as it was, and
Play (in the app, on the lock screen or a headset) carries on; ±5/±10 s skip from that spot too.
Play and pause from a headset, car or the lock screen do exactly that: Play never pauses.

## NAS playback

Songs from WebDAV and FTPS are downloaded in one continuous transfer into a cache (up to 2 GB,
least recently played dropped first), and play starts as soon as the beginning is in. Replays come
from the phone. This keeps large hi-res files from stuttering the way reading them in pieces on demand did.

A song starts once three seconds of it are in (DSD needs several times the bytes of PCM for the
same seconds, so a fixed byte count made DSD slow to start). If playback catches up with the
download (DSD256 is ~2.8 MB/s), it waits for three seconds ahead when the NAS is clearly faster
than the song, ten when it only just keeps up, or, on a link slower than the song, until enough
is in that the rest arrives before playback gets there, and the deck says "Buffering". One clean
pause instead of a stutter.
Downloads run one at a time, so the next song's early download for gapless never takes bandwidth
from the song that's playing. While it waits, the line beside the quality badge reads like
"Buffering 40% · NAS 1.9 MB/s, song 2.8": how far the refill has got, how fast the NAS sends,
and what the song needs.

WebDAV downloads use four connections at once, each fetching the next 4 MB piece in order (servers
that ignore range requests get one ordinary download). One HTTP connection often tops out well
below the link: TLS on a NAS's small CPU, Wi-Fi latency, or the server's per-connection limits.
A protocol change wouldn't help by itself: Subsonic/Navidrome streams over the same HTTP (and
transcodes DSD unless told not to), and SFTP is usually slower than HTTP on a NAS because of its
encryption and window sizes. If the NAS still can't keep up, plain HTTP on the home network (no
TLS) is the next step, then a wired or 5/6 GHz link.

## Playing nicely with other apps

WALKDECK takes Android's audio focus like any music app. Starting a song pauses other players, and
when another app starts playing WALKDECK pauses and hands the DAC back. Calls and voice assistants pause it
and it resumes after. Notifications and navigation prompts only ask other apps to duck, so WALKDECK
keeps playing through them: Android lowers its own mixer path for the moment, and WALKDECK's USB
path stays bit-perfect.
Unplugging headphones or the DAC pauses instead of switching to the speaker.

## Phone layout

On the phone, the Library fills the screen under a slim title line (EQ and Settings at its end).
What's playing sits in a mini player at the bottom, showing cover, title, quality, play/pause and
next. Tap it or swipe up for the full deck (cover and lyrics, the tape, the queue); swipe down or
press Back to put it away. Unfolded (≥600 dp wide), it's two pages: the Library and the deck.
Turned sideways, WALKDECK goes full screen: the system bars hide (swipe from an edge to peek), the
title line goes, and it's two panels, the Library and the deck (cover beside the song, badge and
keys), with EQ and Settings at the far edge.

## Signal path and quality badge

Like Roon's signal-path light, the badge under the cover grades the whole path from the file to
your ears, as good as its weakest step. Beside it is just the format (say, FLAC · 44.1 kHz · 24-bit);
tap either to see each step.

| Badge | Meaning |
|---|---|
| **LOSSLESS** (purple) | Lossless file, no DSP, bit-perfect to the USB DAC (DoP included) |
| **ENHANCED** (blue) | Lossless file, deliberately changed by your EQ, then bit-perfect out |
| **HIGH QUALITY** (green) | Lossless file, but Android's mixer resamples/mixes it, or DSD is converted to PCM |
| **LOSSY** (amber) | A compressed file (MP3, AAC, Opus…) or a Bluetooth link anywhere in the path |

The output is watched while playing, so plugging in Bluetooth headphones or pulling the DAC
updates the badge.

## Parametric EQ

Up to 10 bands (peak, low/high shelf, low/high pass) with frequency, gain and Q, a live
response curve, presets, and **Paste AutoEQ**: copy the "Parametric EQ" text for your headphones from
[autoeq.app](https://autoeq.app) (Equalizer APO format) and paste it in. The EQ runs in 64-bit
floating point at the file's own sample rate, *before* the output. Bit-perfect USB stays
bit-perfect, but carries your EQ'd signal in at least a 24-bit container (the badge says
ENHANCED). Auto preamp lowers the level by the biggest boost so nothing clips. A flat or switched-off EQ
is fully bypassed. While the EQ is on, DSD is converted to PCM so it can be equalised (DoP can't
be).

## Now playing

The deck shows the cover full size. Tap it to swap to the lyrics in the same square (the line being
sung is lit and kept in view), and tap again for the cover. The choice sticks from song to song.

## Artwork

Covers come from the file (FLAC pictures, ID3 APIC, MP4 `covr`, DSF's ID3), else from the folder
(`cover.jpg`, `folder.jpg`, `front.jpg`, `AlbumArt*.jpg`, or a lone image). The cover fills the deck, and shows in the mini player, the queue, on the lock screen
and in the notification.

## Gapless playback

About 15 s before a song ends, the next one in the folder is opened and decoded on a helper
thread. If it has the same sample rate and channel count, its samples are appended to the same
audio stream, so live albums and DJ mixes run straight through. MP3 and AAC encoder delay and padding
(the silence encoders add) are trimmed. When the sample rate changes (say, a 44.1 kHz track
followed by a 96 kHz one), the output has to be reopened at the new rate, which costs a short pause;
that is what keeps the bit-perfect path honest. Settings → Gapless playback turns it off.

## Tags

Titles, artists, albums and track numbers come from the files' own tags, read by WALKDECK's own
parser: FLAC/Ogg/Opus Vorbis comments, ID3v2.2–2.4 (MP3, AIFF, WAV, DSF), MP4/M4A atoms, WAV INFO
and DSDIFF. Only the few kilobytes that hold the tags are read, which keeps NAS folders quick
(WebDAV uses small range requests; FTPS songs get their tags once cached). Until a file's tags
arrive, the file name is shown.

## DSD (.dsf, .dff)

* **DoP (native DSD)**: with Settings → *DSD as DoP* on and a bit-perfect USB path at the DoP
  rate (176.4 kHz for DSD64, 352.8 kHz for DSD128), the DSD bits are sent to the DAC inside 24-bit
  PCM frames with the standard 0x05/0xFA markers. Your DAC should show DSD. DoP is never sent
  through Android's mixer, where it would be noise; without the bit-perfect path WALKDECK converts instead.
* **DSD → PCM** (default): a 384-tap (DSD64) linear-phase low-pass filter, decimated to
  176.4 kHz float. It's flat to about 25 kHz, and DSD's ultrasonic noise is filtered out above
  ~64 kHz. Level is unity, so 0 dB SACD lands at −6 dBFS, leaving headroom.
* DSD64/128/256 are supported; DST-compressed DFF is not.

## Lyrics

Tap the cover to see the lyrics in its place (tap the COVER chip to go back). The line being sung
fills in as it's sung, karaoke-style: word by word when the lyrics are enhanced LRC (with
`<mm:ss.xx>` word stamps, which are now kept and saved in `.lrc` files), and otherwise swept evenly
across the line's time, which suits Japanese and Chinese, written without spaces.

**Japanese lyrics** get **furigana** (the reading in small hiragana over each kanji, as it's read
in that word) and **romaji** (Hepburn, a line under each line; は, へ and を said as wa, e and o).
Switch either with the ふりがな and Romaji buttons in the lyrics' corner. Readings come from
Kuromoji with the IPADIC dictionary, on the phone; it's about 13 MB of the app and loads the first
time Japanese lyrics are shown. Lyrics count as Japanese when they have kana, so Chinese lyrics
(kanji only) are left alone. The karaoke fill runs through the furigana too. Scroll them freely: they follow the song again four seconds after you let go. With timed
lyrics, tap a line to play from there.
WALKDECK looks in this order:

1. The file's tags (`LYRICS`/`UNSYNCEDLYRICS`, ID3 `USLT`, MP4 `©lyr`).
2. A `.lrc` file with the same name in the same folder (local, WebDAV and FTPS).
3. Online, in this order, each switchable in Settings:
   * [LRCLIB](https://lrclib.net): free, open, timed lyrics.
   * [Musixmatch](https://developer.musixmatch.com): the official API with **your own key** (free to
     register). The free plan returns part of each song's lyrics; timed lyrics need a paid plan.
   * [lyrics.ovh](https://lyrics.ovh): free, open, plain lyrics.

   Only the title, artist, album and length are sent. Results, including "not found", are cached
   on the phone. Sites without an API (AZLyrics, Genius pages) aren't scraped: their terms forbid
   it and they block it.

Lyrics found online are saved as a `.lrc` with the song's name in the song's folder (on the phone
or the NAS, over WebDAV or FTPS), timed when the lyrics are, so step 2 finds them next time and
other players can use them too. WALKDECK never writes into the audio files themselves: rewriting a
multi-hundred-megabyte file over the network to add a few kilobytes risks the file for little gain,
and DSD and WAV have no common lyrics tag anyway. A `.lrc` that's already there is never
overwritten (it may be one you wrote): when it only has plain lyrics, WALKDECK may show timed ones
found online, but leaves the file alone. Settings → "Save found lyrics beside the songs" turns it
off. For a folder on the phone this needs write access, which the folder picker grants; a folder
added with an older version may need adding again in Music sources.

## NAS and cloud storage

Add servers and cloud accounts in Settings → Music sources (+ Add a NAS or cloud storage), then tap
one to choose which of its folders go in the Library. Passwords and sign-in tokens are kept with
EncryptedSharedPreferences (Android Keystore).

* **SMB**: host (or IP), and the share as the folder, e.g. `Music` or `Media/Music` (SMB can't list
  shares without extra RPC, so name the one you want). User name and password; leave the user
  name blank for guest access; `DOMAIN\user` works for Windows domains. SMB 2 and 3; SMB 1 isn't
  supported (it's switched off on current Windows and NAS firmware for security).
* **SFTP**: host, port (blank for 22), start folder, user name and password. The server's SSH host
  key is trusted the first time and pinned; a different key later is refused. Key-based login isn't
  supported yet.
* **FTP**: like FTPS but unencrypted, password included. For a NAS on the home network that has
  nothing better switched on; prefer SMB, SFTP or FTPS.
* **WebDAV**: enter the full URL, e.g. `https://nas.local:5006/music` (Synology), `https://nas/dav`
  (QNAP), or a Nextcloud `remote.php/dav/files/<user>/` URL. Basic auth over HTTPS.
* **FTPS**: host, port (blank for 21/990), implicit or explicit TLS, start folder. Passive mode,
  private data channel. Some servers require TLS session reuse on the data connection (vsftpd's
  `require_ssl_reuse=YES`); if listing fails, turn that off on the server.
* **Self-signed certificates**: turn on the option, connect once, and compare the SHA-256
  fingerprint shown with the one on your NAS. After you accept it, only that certificate is trusted for that server.

### Cloud storage

Google, Microsoft and Dropbox only let registered apps in, and an app's registration (its client ID)
belongs to whoever made it. So WALKDECK signs in as an app *you* register, once, for free, and nothing
passes through anyone else's servers. Sign-in happens in the phone's browser on the cloud's own
page; the cloud then sends the browser back to a small web server WALKDECK runs on the phone only
(127.0.0.1, port 53682) with a one-time code (OAuth with PKCE). WALKDECK never sees your password.

**Google Drive**
1. [console.cloud.google.com](https://console.cloud.google.com): create a project, then APIs &
   Services → Library → **Google Drive API** → Enable.
2. OAuth consent screen: External; add your Google account as a test user. Then **Publish app**,
   or sign-ins expire after 7 days while it's in testing (Google will warn that the app isn't
   verified; that's expected for an app only you use).
3. Credentials → Create credentials → OAuth client ID → **Desktop app**. Copy the client ID and
   client secret into WALKDECK (Settings → Music sources → + → Google Drive) and tap **Save & sign in**.

**OneDrive** (personal, work or school)
1. [portal.azure.com](https://portal.azure.com) → App registrations → New registration. Account
   types: *any organizational directory and personal Microsoft accounts*.
2. Authentication → Add a platform → **Mobile and desktop applications** → custom redirect URI
   `http://localhost`. Under Advanced settings, allow public client flows.
3. API permissions: Microsoft Graph → delegated `Files.ReadWrite` and `offline_access`.
4. Copy the Application (client) ID into WALKDECK and tap **Save & sign in**.

**Dropbox**
1. [dropbox.com/developers/apps](https://www.dropbox.com/developers/apps) → Create app → Scoped
   access → Full Dropbox (or App folder, if your music is in it).
2. Permissions: `files.metadata.read`, `files.content.read`, `files.content.write` → Submit.
3. Settings → OAuth 2 → Redirect URIs: add `http://127.0.0.1:53682/`.
4. Copy the App key into WALKDECK and tap **Save & sign in**.

The sign-in page says "Signed in. Go back to WALKDECK" when it's done. Songs download through the
same cache as a NAS, four ranges at a time, and found lyrics are saved as `.lrc` next to the song in
the cloud too.

## Build

GitHub Actions builds a debug APK on every push (Actions → latest run → `side-a-s26-apk`).
Locally: open in Android Studio, or `./gradlew assembleDebug` with the Android SDK installed.

Every build is signed with the debug key in `app/side-a-debug.keystore` and numbered from the CI
run, so a newer APK installs over an older one without uninstalling. (Builds before this one were
each signed with a throwaway key, which is why Android said "App not installed": uninstall once,
then updates go over the top.)
Min Android 10 (API 29); bit-perfect needs Android 14 (API 34).
