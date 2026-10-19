package io.github.momonekomata.sidea.audio

/** Decoded PCM with its timestamp. The array is owned by the receiver. */
class PcmChunk(val data: ByteArray, val size: Int, val ptsUs: Long)

/**
 * Drops the encoder's priming samples at the start and its padding at the end. The tail is
 * held back by [paddingFrames] so the padding can be discarded once the end is known.
 */
class GaplessTrimmer(private val frameBytes: Int, delayFrames: Int, private val paddingFrames: Int) {
    private var toSkip = delayFrames.toLong() * frameBytes
    private var tail = ByteArray(0)

    fun process(chunk: PcmChunk): PcmChunk? {
        var data = chunk.data
        var size = chunk.size
        if (toSkip > 0) {
            val skip = minOf(toSkip, size.toLong()).toInt()
            toSkip -= skip
            if (skip == size) return null
            data = data.copyOfRange(skip, size)
            size -= skip
        }
        if (paddingFrames == 0) return PcmChunk(data, size, chunk.ptsUs)
        val hold = paddingFrames * frameBytes
        val joined = ByteArray(tail.size + size)
        System.arraycopy(tail, 0, joined, 0, tail.size)
        System.arraycopy(data, 0, joined, tail.size, size)
        val emit = joined.size - hold
        if (emit <= 0) {
            tail = joined
            return null
        }
        tail = joined.copyOfRange(emit, joined.size)
        return PcmChunk(joined, emit, chunk.ptsUs)
    }

    /** End of stream: what is still held back is exactly the padding. */
    fun finish() { tail = ByteArray(0) }

    /** After a seek there is no priming to skip, and the end padding still applies. */
    fun seeked() {
        toSkip = 0
        tail = ByteArray(0)
    }
}
