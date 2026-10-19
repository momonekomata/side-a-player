package io.github.momonekomata.sidea.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalPathTest {
    private val flacUsb = StreamFacts(
        codec = "FLAC", sourceRate = 96_000, sourceBits = 24, dsd = null,
        outputRate = 96_000, outputFormat = SampleFormat.S24_PACKED, bitPerfect = true,
        device = DeviceKind.USB, deviceName = "KA17", mixerRate = null,
    )
    private val eqOn = EqConfig(enabled = true, bands = listOf(EqBand(gainDb = 3.0)))

    @Test
    fun `bit-perfect flac is lossless`() = assertEquals(Quality.LOSSLESS, SignalPaths.describe(flacUsb, null).quality)

    @Test
    fun `eq makes it enhanced`() = assertEquals(Quality.ENHANCED, SignalPaths.describe(flacUsb, eqOn).quality)

    @Test
    fun `the mixer makes it high quality, even with eq`() {
        val mixed = flacUsb.copy(bitPerfect = false, device = DeviceKind.SPEAKER, deviceName = null, mixerRate = 48_000)
        assertEquals(Quality.HIGH_QUALITY, SignalPaths.describe(mixed, null).quality)
        assertEquals(Quality.HIGH_QUALITY, SignalPaths.describe(mixed, eqOn).quality)
    }

    @Test
    fun `mp3 and bluetooth are lossy`() {
        assertEquals(Quality.LOSSY, SignalPaths.describe(flacUsb.copy(codec = "MPEG", sourceBits = 16), null).quality)
        val bt = flacUsb.copy(bitPerfect = false, device = DeviceKind.BLUETOOTH, deviceName = "WH-1000XM6", mixerRate = 48_000)
        assertEquals(Quality.LOSSY, SignalPaths.describe(bt, null).quality)
    }

    @Test
    fun `dop is lossless, dsd to pcm is high quality, and eq never claims dop`() {
        val dop = flacUsb.copy(codec = "DSD64 DoP", sourceRate = 2_822_400, sourceBits = 1, dsd = DsdMode.DOP, outputRate = 176_400)
        assertEquals(Quality.LOSSLESS, SignalPaths.describe(dop, eqOn).quality)
        assertEquals(Quality.HIGH_QUALITY, SignalPaths.describe(dop.copy(dsd = DsdMode.PCM, outputFormat = SampleFormat.FLOAT), null).quality)
    }

    private val tidal = RemoteFacts(
        appLabel = "TIDAL", ceiling = "FLAC up to 24-bit/192 kHz", lossyOnly = false, hint = null,
        device = DeviceKind.USB, deviceName = "KA17", bitPerfectRate = null, bitPerfectFormat = null, mixerRate = 48_000,
    )

    @Test
    fun `an unreported stream quality doesn't decide the badge`() {
        val p = SignalPaths.describeRemote(tidal)
        assertEquals(false, p.stages.first().verified)
        assertEquals(Quality.HIGH_QUALITY, p.quality) // the mixer is what we can see
    }

    @Test
    fun `an app holding the dac bit-perfect shows its real format`() {
        val p = SignalPaths.describeRemote(tidal.copy(bitPerfectRate = 96_000, bitPerfectFormat = SampleFormat.S24_PACKED))
        assertEquals(Quality.LOSSLESS, p.quality)
        assertEquals(true, p.stages.last().detail.contains("96 kHz 24-bit"))
    }

    @Test
    fun `yt music and bluetooth are lossy`() {
        assertEquals(Quality.LOSSY, SignalPaths.describeRemote(tidal.copy(appLabel = "YT Music", lossyOnly = true)).quality)
        assertEquals(Quality.LOSSY, SignalPaths.describeRemote(tidal.copy(device = DeviceKind.BLUETOOTH)).quality)
        assertEquals(Quality.LOSSY, SignalPaths.describeRemote(tidal.copy(hint = "quality: AAC 256 kbps")).quality)
    }

    @Test
    fun `a dac the phone won't drive bit-perfect is explained`() {
        val mixed = flacUsb.copy(bitPerfect = false, mixerRate = 48_000, usb = UsbSupport("KA17", apiAvailable = true, bitPerfectRates = emptyList()))
        val p = SignalPaths.describe(mixed, null)
        assertEquals(Quality.HIGH_QUALITY, p.quality)
        assertEquals(true, p.stages.any { it.title == "KA17" && it.detail.contains("no bit-perfect USB mode") })
        val someRates = mixed.copy(usb = UsbSupport("KA17", true, listOf(44_100, 48_000)))
        assertEquals(true, SignalPaths.describe(someRates, null).stages.any { it.detail.contains("44.1 kHz, 48 kHz, not at this file's 96 kHz") })
    }
}
