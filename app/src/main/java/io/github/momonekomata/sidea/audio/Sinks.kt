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
    private fun head() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() {
        base += head()
        track.flush()
    }

    override fun write(data: ByteArray, offset: Int, length: Int) = track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
    override val framesPlayed: Long get() = base + head()
    override fun release() {
        runCatching { track.stop() }
        track.release()
    }
}
