# Side A

A music player for Android with a retro J-card track list, full-size cover art (tap it for
lyrics), and a bit-perfect audio path. Laid out for phones and foldables. It has three kinds of source.

| Source | How it plays | Audio quality |
|---|---|---|
| **Local** (any folder, SD card or USB drive) | Side A decodes the file itself | Native sample rate and bit depth. **Bit-perfect** to a USB DAC on Android 14+ |
| **NAS** over WebDAV (http/https) or FTPS (explicit or implicit TLS) | WebDAV streams with range requests; FTPS downloads to cache and starts playing while it downloads | Same as Local |
| **Spotify, Apple Music, TIDAL, YT Music** | Side A remote-controls the official app through its media session and shows it on the deck | Whatever that app outputs (its own lossless/exclusive settings apply) |

## Bit-perfect output

Android normally mixes everything at 48 kHz. On **Android 14+ with a USB DAC**, Side A asks the
platform for a bit-perfect mixer (`AudioManager.setPreferredMixerAttributes`,
`MIXER_BEHAVIOR_BIT_PERFECT`) at the file's own rate, then writes samples in the DAC's integer
format with no resampling, dithering or volume scaling. The status lines under the cover say
what actually happened:

* `● BIT-PERFECT · FLAC 96 kHz/24-bit · 24-bit · <DAC name>`: untouched samples at the native rate
* `● MIXED · … `: Android's mixer is in the path, with the reason (no USB DAC, Android < 14, or the
  DAC/phone doesn't offer bit-perfect at that rate)

No permission prompt is involved: Android's bit-perfect mode is a system feature, not a
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

Lossless files are decoded by Side A itself: **ALAC** (Apple Lossless in .m4a, 16–32 bit; many
phones have no decoder for it), **FLAC** (all stereo modes, 8–32 bit, any rate,
seek table or not, and files with an ID3 tag stuck on the front, which Android's extractor rejects as
"audio/unknown"), **WAV** (PCM, extensible, 32/64-bit float), **AIFF/AIFF-C** (incl. `sowt`, `fl32`)
and **DSD**. They come out as the file's exact integer samples, so the bit-perfect path never detours
through float. MP3, AAC (incl. AAC .m4a), Opus and Vorbis go through Android's MediaCodec. If the phone has
no decoder for a file, the error says which format it is.

## Streaming quality

Android doesn't let one app read another app's stream format, so for Spotify, Apple Music, TIDAL
and YT Music Side A shows what can be observed and says so where it can't:

* **The real format, when the app owns the DAC.** On Android 14+, an app that takes a USB DAC
  bit-perfect (e.g. TIDAL's exclusive USB mode) sets the DAC's mixer to the stream's own rate and
  depth. Side A reads that back: `TIDAL · bit-perfect 96 kHz 24-bit → KA17`.
* **The output device** of the playback (USB DAC, Bluetooth, speaker) and the mixer rate.
* **A quality hint** if the app publishes one in its media metadata (few do).
* Otherwise, **the service's ceiling** (e.g. "FLAC up to 24-bit/192 kHz"), marked *not reported*
  in the signal path. Unverified steps never raise the badge.

## Playing nicely with other apps

Side A takes Android's audio focus like any music app. Starting a song pauses Spotify, TIDAL and
the rest, and when another app starts playing Side A pauses. Calls and navigation prompts pause it
(a bit-perfect player can't "duck" the volume without changing the samples) and it resumes after.
Unplugging headphones or the DAC pauses instead of switching to the speaker.

## Phone layout

On a phone, the sources are cards: swipe between them or tap the tab row (all six fit, no
scrolling, and a green dot marks what's playing). What's playing sits in a mini player at the bottom,
showing cover, title, quality, play/pause and next. Tap it or swipe up for the full deck (cover and
lyrics); swipe down or press Back to put it away. The now-playing card follows what's actually
playing, local or streaming, not just the tab you're on. Unfolded (≥600 dp wide), it's the
two-page case layout. Turned sideways, Side A goes full screen: the system bars hide (swipe
from an edge to peek), the title bar goes, and EQ and Settings move to the end of the tab row. On a
phone's short landscape screen it's three columns: cards, a compact deck, and lyrics.

## Signal path and quality badge

Like Roon's signal-path light, the badge under the cover grades the whole path from the file to
your ears, as good as its weakest step. Tap it to see each step.

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
be). The EQ applies to local and NAS playback; streaming apps' audio is theirs.

## Now playing

The deck shows the cover full size. Tap it to swap to the lyrics in the same square (the line being
sung is lit and kept in view), and tap again for the cover. The choice sticks from song to song.

## Artwork

Covers come from the file (FLAC pictures, ID3 APIC, MP4 `covr`, DSF's ID3), else from the folder
(`cover.jpg`, `folder.jpg`, `front.jpg`, `AlbumArt*.jpg`, or a lone image). Streaming apps
supply their own. The cover fills the deck, and shows in the J-card header, the mini player, on the lock screen
and in the notification.

## Gapless playback

About 15 s before a song ends, the next one in the folder is opened and decoded on a helper
thread. If it has the same sample rate and channel count, its samples are appended to the same
audio stream, so live albums and DJ mixes run straight through. MP3 and AAC encoder delay and padding
(the silence encoders add) are trimmed. When the sample rate changes (say, a 44.1 kHz track
followed by a 96 kHz one), the output has to be reopened at the new rate, which costs a short pause;
that is what keeps the bit-perfect path honest. Settings → Gapless playback turns it off.

## Tags

Titles, artists, albums and track numbers come from the files' own tags, read by Side A's own
parser: FLAC/Ogg/Opus Vorbis comments, ID3v2.2–2.4 (MP3, AIFF, WAV, DSF), MP4/M4A atoms, WAV INFO
and DSDIFF. Only the few kilobytes that hold the tags are read, which keeps NAS folders quick
(WebDAV uses small range requests; FTPS songs get their tags once cached). Until a file's tags
arrive, the file name is shown.

## DSD (.dsf, .dff)

* **DoP (native DSD)**: with Settings → *DSD as DoP* on and a bit-perfect USB path at the DoP
  rate (176.4 kHz for DSD64, 352.8 kHz for DSD128), the DSD bits are sent to the DAC inside 24-bit
  PCM frames with the standard 0x05/0xFA markers. Your DAC should show DSD. DoP is never sent
  through Android's mixer, where it would be noise; without the bit-perfect path Side A converts instead.
* **DSD → PCM** (default): a 384-tap (DSD64) linear-phase low-pass filter, decimated to
  176.4 kHz float. It's flat to about 25 kHz, and DSD's ultrasonic noise is filtered out above
  ~64 kHz. Level is unity, so 0 dB SACD lands at −6 dBFS, leaving headroom.
* DSD64/128/256 are supported; DST-compressed DFF is not.

## Lyrics

Tap the cover to see the lyrics in its place (tap again to go back); the line being sung is lit.
Side A looks in this order:

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

Lyrics work for the streaming apps too: they're looked up from what Spotify, Apple Music, TIDAL or YT
Music report as playing, and follow that app's playback position. The apps' own lyrics (Spotify's,
Apple Music's) are licensed to them and can't be read by other apps, so these come from LRCLIB too.
Streaming titles like "Song (feat. X) - 2011 Remaster" and artists like "A, B & C" are cleaned up
before a second and third try, then searched. When nothing turns up, the deck says so and offers
**Retry**. On a phone, the line being sung also shows in the mini player.

## Streaming apps

Android lets an app control another app's playback only through media sessions, the channel the
lock screen and Android Auto use. To see them, Side A needs **notification access** (Settings → Notifications →
Device & app notifications); it doesn't read any notification. Then:

* Play/pause, next, previous and seek drive the streaming app; the deck shows title, artist, cover and progress.
* If the app shares its queue (it varies), the J-card lists it and tapping a song jumps to it.
* To pick a playlist, open the app (the J-card has a button); Side A follows what it plays.

Deeper integrations (browsing playlists inside Side A) need each service's SDK and developer
credentials: Spotify App Remote (client ID + app fingerprint), Apple MusicKit for Android (Apple
Developer Program), TIDAL's SDK (developer.tidal.com credentials). YouTube Music has no public API, so it stays
remote-control only.

## NAS

* **WebDAV**: enter the full URL, e.g. `https://nas.local:5006/music` (Synology), `https://nas/dav`
  (QNAP), or a Nextcloud `remote.php/dav/files/<user>/` URL. Basic auth over HTTPS.
* **FTPS**: host, port (blank for 21/990), implicit or explicit TLS, start folder. Passive mode,
  private data channel. Some servers require TLS session reuse on the data connection (vsftpd's
  `require_ssl_reuse=YES`); if listing fails, turn that off on the server.
* **Self-signed certificates**: turn on the option, connect once, and compare the SHA-256
  fingerprint shown with the one on your NAS. After you accept it, only that certificate is trusted for that server.
* Passwords are stored with EncryptedSharedPreferences (Android Keystore).

## Build

GitHub Actions builds a debug APK on every push (Actions → latest run → `side-a-debug-apk`).
Locally: open in Android Studio, or `./gradlew assembleDebug` with the Android SDK installed.
Min Android 10 (API 29); bit-perfect needs Android 14 (API 34).
