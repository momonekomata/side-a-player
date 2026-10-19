package io.github.momonekomata.sidea.audio.usb

import io.github.momonekomata.sidea.audio.PcmSink

/** JNI bridge to usb_audio.c: one isochronous playback stream on a claimed interface. */
class UsbStream private constructor(private val handle: Long, val frameBytes: Int) : PcmSink {
    override fun play() = nativeSetPaused(handle, false)
    override fun pause() = nativeSetPaused(handle, true)
    override fun flush() = nativeFlush(handle)

    override fun write(data: ByteArray, offset: Int, length: Int): Int = nativeWrite(handle, data, offset, length)

    override val framesPlayed: Long get() = nativeFramesPlayed(handle)

    /** Silence to send when the buffer runs dry or while paused; DoP needs its idle pattern. */
    fun setSilence(pattern: ByteArray) = nativeSetSilence(handle, pattern)

    /** (underruns, feedback ratio vs nominal, error) for the signal path. */
    fun stats(): Triple<Long, Double, Long> {
        val s = nativeStats(handle)
        return Triple(s[0], if (s[2] > 0) s[1].toDouble() / s[2] else 1.0, s[3])
    }

    private var released = false
    override fun release() {
        if (released) return
        released = true
        nativeClose(handle)
    }

    companion object {
        init {
            System.loadLibrary("sidea_usb")
        }

        fun open(
            fd: Int, endpointOut: Int, maxPacket: Int, feedbackEndpoint: Int, feedbackPacket: Int,
            packetsPerSecond: Int, feedbackScale: Int, rate: Int, frameBytes: Int,
        ): UsbStream {
            val h = nativeOpen(fd, endpointOut, maxPacket, feedbackEndpoint, feedbackPacket, packetsPerSecond, feedbackScale, rate, frameBytes, 500)
            check(h != 0L) { "Out of memory opening the USB stream" }
            val s = UsbStream(h, frameBytes)
            val r = nativeStart(h)
            if (r < 0) {
                s.release()
                throw java.io.IOException("The DAC refused the audio stream (error ${-r})")
            }
            return s
        }

        @JvmStatic private external fun nativeOpen(fd: Int, epOut: Int, maxPacket: Int, epFb: Int, fbPacket: Int, packetsPerSec: Int, fbIntervalScale: Int, rate: Int, frameBytes: Int, ringMs: Int): Long
        @JvmStatic private external fun nativeStart(h: Long): Int
        @JvmStatic private external fun nativeSetPaused(h: Long, paused: Boolean)
        @JvmStatic private external fun nativeFlush(h: Long)
        @JvmStatic private external fun nativeSetSilence(h: Long, pattern: ByteArray)
        @JvmStatic private external fun nativeWrite(h: Long, data: ByteArray, off: Int, len: Int): Int
        @JvmStatic private external fun nativeFramesPlayed(h: Long): Long
        @JvmStatic private external fun nativeStats(h: Long): LongArray
        @JvmStatic private external fun nativeClose(h: Long)
        @JvmStatic external fun nativeReattachKernel(fd: Int, iface: Int): Int
        @JvmStatic external fun nativeReset(fd: Int): Int
        @JvmStatic external fun nativeSpeed(fd: Int): Int
    }
}
