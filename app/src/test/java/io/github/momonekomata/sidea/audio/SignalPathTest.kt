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
}
