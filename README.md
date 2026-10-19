# Side A

A retro cassette-deck music player for Android, laid out for foldables: the J-card track list on
one page, the tape deck on the other. It has three kinds of source.

| Source | How it plays | Audio quality |
|---|---|---|
| **Local** (any folder, SD card or USB drive) | Side A decodes the file itself | Native sample rate and bit depth. **Bit-perfect** to a USB DAC on Android 14+ |
| **NAS** over WebDAV (http/https) or FTPS (explicit or implicit TLS) | WebDAV streams with range requests; FTPS downloads to cache and starts playing while it downloads | Same as Local |
| **Spotify, Apple Music, TIDAL, YT Music** | Side A remote-controls the official app through its media session and shows it on the cassette | Whatever that app outputs (its own lossless/exclusive settings apply) |

## Bit-perfect output

Android normally mixes everything at 48 kHz. On **Android 14+ with a USB DAC**, Side A asks the
platform for a bit-perfect mixer (`AudioManager.setPreferredMixerAttributes`,
`MIXER_BEHAVIOR_BIT_PERFECT`) at the file's own rate, then writes samples in the DAC's integer
format with no resampling, dithering or volume scaling. The status line under the cassette says
what actually happened:

* `● BIT-PERFECT · FLAC 96 kHz/24-bit · 24-bit · <DAC name>`: untouched samples at the native rate
* `● MIXED · … `: Android's mixer is in the path, with the reason (no USB DAC, Android < 14, or the
  DAC/phone doesn't offer bit-perfect at that rate)

Notes:
* Set the volume on the DAC or amp. In bit-perfect mode Android's own volume is bypassed, so start low.
* Whether a phone supports bit-perfect USB is up to the manufacturer. Pixels do; for Samsung and
  others, try it and check the status line.
* High-res files are decoded to float (exact for 24-bit) and converted back to the DAC's integer
  format, so nothing is truncated. `PcmConverterTest` checks that round trip.
* What isn't done yet: a UAPP-style user-space USB driver for phones without Android 14 bit-perfect
  support, gapless playback, DSD, multichannel, and tag reading (titles come from file names).

## Streaming apps

Android lets an app control another app's playback only through media sessions, the channel the
lock screen and Android Auto use. To see them, Side A needs **notification access** (Settings → Notifications →
Device & app notifications); it doesn't read any notification. Then:

* Play/pause, next, previous and seek drive the streaming app; the cassette shows title, artist and progress.
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
