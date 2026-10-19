package io.github.momonekomata.sidea.audio

import android.media.AudioTrack

/** Where PCM goes: Android's AudioTrack, or Side A's own USB driver. */
interface PcmSink {
    fun play()
    fun pause()
    fun flush()
    /** Blocking; bytes taken, 0 if it should be retried, negative on a dead output. */
    fun write(data: ByteArray, offset: Int, length: Int): Int
    /** Frames the output has played; only ever grows, flushes included. */
    val framesPlayed: Long
    fun release()
}

class TrackSink(val track: AudioTrack) : PcmSink {
    // AudioTrack resets its head on flush; keep a running total across flushes.
    private var base = 0L
    private val isFloat = track.audioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT
    private var floats = FloatArray(0)
    private fun head() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() {
        base += head()
        track.flush()
    }

    /** Float tracks refuse byte arrays (ERROR_INVALID_OPERATION), so those go in as floats. */
    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (!isFloat) return track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
        val n = length / 4
        if (floats.size < n) floats = FloatArray(n)
        java.nio.ByteBuffer.wrap(data, offset, n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, n)
        val w = track.write(floats, 0, n, AudioTrack.WRITE_BLOCKING)
        return if (w < 0) w else w * 4
    }
    override val framesPlayed: Long get() = base + head()
    override fun release() {
        runCatching { track.stop() }
        track.release()
    }
}
