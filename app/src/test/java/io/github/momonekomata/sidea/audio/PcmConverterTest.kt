package io.github.momonekomata.sidea.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmConverterTest {
    private fun s24(vararg v: Int): ByteArray {
        val out = ByteArray(v.size * 3)
        v.forEachIndexed { i, s ->
            out[i * 3] = s.toByte(); out[i * 3 + 1] = (s shr 8).toByte(); out[i * 3 + 2] = (s shr 16).toByte()
        }
        return out
    }

    private fun s16(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putShort(it.toShort()) }
        return b.array()
    }

    private fun roundTrip(data: ByteArray, from: SampleFormat, via: SampleFormat): ByteArray {
        val mid = PcmConverter.convert(ByteBuffer.wrap(data), from, via)
        return PcmConverter.convert(ByteBuffer.wrap(mid), via, from)
    }

    private val edge24 = intArrayOf(0, 1, -1, 8_388_607, -8_388_608, 1_234_567, -7_654_321)
    private val edge16 = intArrayOf(0, 1, -1, 32_767, -32_768, 12_345, -23_456)

    @Test
    fun `24-bit survives float and 32-bit round trips exactly`() {
        val data = s24(*edge24)
        assertArrayEquals(data, roundTrip(data, SampleFormat.S24_PACKED, SampleFormat.FLOAT))
        assertArrayEquals(data, roundTrip(data, SampleFormat.S24_PACKED, SampleFormat.S32))
    }

    @Test
    fun `16-bit survives every wider format exactly`() {
        val data = s16(*edge16)
        for (via in listOf(SampleFormat.S24_PACKED, SampleFormat.S32, SampleFormat.FLOAT)) {
            assertArrayEquals(via.name, data, roundTrip(data, SampleFormat.S16, via))
        }
    }

    @Test
    fun `16-bit widens by zero padding`() {
        val out = PcmConverter.convert(ByteBuffer.wrap(s16(0x1234, -2)), SampleFormat.S16, SampleFormat.S24_PACKED)
        assertArrayEquals(s24(0x123400, -2 shl 8), out)
    }

    @Test
    fun `full scale float clamps instead of wrapping`() {
        val f = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.0f).putFloat(-1.0f).array()
        val out = PcmConverter.convert(ByteBuffer.wrap(f), SampleFormat.FLOAT, SampleFormat.S24_PACKED)
        assertArrayEquals(s24(8_388_607, -8_388_608), out)
    }
}
