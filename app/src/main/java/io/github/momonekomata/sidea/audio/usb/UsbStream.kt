package io.github.momonekomata.sidea.audio.usb

import io.github.momonekomata.sidea.audio.PcmSink
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** JNI bridge to usb_audio.c: one isochronous playback stream on a claimed interface. */
class UsbStream private constructor(private val handle: Long, val frameBytes: Int) : PcmSink {
    // Unplugging the DAC releases the stream from the main thread while the player thread may be
    // in the middle of a write. Every native call holds the read lock and release the write lock,
    // so the native stream is never freed under a call, and calls after release do nothing.
    // (A write waits at most 100 ms for room, so release never waits long.)
    private val lock = ReentrantReadWriteLock()
    @Volatile private var released = false
    @Volatile private var lastFrames = 0L

    private inline fun <T> live(dead: T, block: () -> T): T = lock.read { if (released) dead else block() }

    override fun play() = live(Unit) { nativeSetPaused(handle, false) }
    override fun pause() = live(Unit) { nativeSetPaused(handle, true) }
    override fun flush() = live(Unit) { nativeFlush(handle) }

    /** -19 (ENODEV) once the stream is gone, like a DAC that was pulled out. */
    override fun write(data: ByteArray, offset: Int, length: Int): Int = live(-19) { nativeWrite(handle, data, offset, length) }

    override val framesPlayed: Long get() = live(lastFrames) { nativeFramesPlayed(handle).also { lastFrames = it } }

    /** Silence to send when the buffer runs dry or while paused; DoP needs its idle pattern. */
    fun setSilence(pattern: ByteArray) = live(Unit) { nativeSetSilence(handle, pattern) }

    /** (underruns, feedback ratio vs nominal, error) for the signal path. */
    fun stats(): Triple<Long, Double, Long> = live(Triple(0L, 1.0, 0L)) {
        val s = nativeStats(handle)
        Triple(s[0], if (s[2] > 0) s[1].toDouble() / s[2] else 1.0, s[3])
    }

    override fun release() {
        lock.write {
            if (released) return
            released = true
            nativeClose(handle)
        }
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
