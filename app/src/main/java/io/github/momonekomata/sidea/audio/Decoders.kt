package io.github.momonekomata.sidea.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.momonekomata.sidea.sources.openRandomAccess
import io.github.momonekomata.sidea.sources.readUpTo

/** One file's worth of PCM, whatever it was encoded as. */
interface PcmDecoder {
    val sampleRate: Int
    val channels: Int
    val format: SampleFormat
    val sourceBits: Int?
    val codecName: String
    val durationUs: Long
    /** True when the PCM is DoP-packed DSD and must only go out over a bit-perfect path. */
    val isDop: Boolean get() = false

    /** Next chunk, or null at the end. */
    fun read(): PcmChunk?
    fun seekTo(us: Long)
    fun close()
}

/** How a source should be decoded; DoP is only asked for when a bit-perfect path exists. */
fun interface DopPlanner {
    /** Returns true if DoP can go out at [pcmRate] with [channels]; the plan is kept for the track. */
    fun canDop(pcmRate: Int, channels: Int): Boolean
}

object Decoders {
    fun open(context: Context, source: PlayableSource, dop: DopPlanner?): PcmDecoder {
        val ra = source.openRandomAccess(context)
        val head = ra.readUpTo(0, 4)
        if (DsdStream.isDsd(head)) return DsdDecoder(DsdStream.open(ra), dop)
        // Content and file sources reopen cleanly; a stream must stay open for the extractor.
        if (source !is PlayableSource.Stream) ra.close()
        return MediaCodecDecoder(context, source)
    }
}

/**
 * MediaExtractor + MediaCodec, asking for float output so 24-bit sources keep their low byte,
 * and trimming the encoder delay/padding that MP3 and AAC carry so albums play gaplessly.
 */
class MediaCodecDecoder(context: Context, source: PlayableSource) : PcmDecoder {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending: PcmChunk? = null
    private val trimmer: GaplessTrimmer

    override val sampleRate: Int
    override val channels: Int
    override val format: SampleFormat
    override val sourceBits: Int?
    override val codecName: String
    override val durationUs: Long

    init {
        when (source) {
            is PlayableSource.Content -> extractor.setDataSource(context, source.uri, null)
            is PlayableSource.File -> extractor.setDataSource(source.path)
            is PlayableSource.Stream -> extractor.setDataSource(source.dataSource)
        }
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("No audio stream in this file") }
        val inFormat = extractor.getTrackFormat(index)
        extractor.selectTrack(index)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        codecName = mime.removePrefix("audio/").uppercase()
        durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        sourceBits = sourceBitDepth(inFormat)
        codec = createDecoder(inFormat, wantFloat = sourceBits == null || sourceBits > 16)
        codec.start()
        // Decode the first buffer now so the real output format is known before planning.
        val first = decodeNext()
        val of = codec.outputFormat
        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        format = pcmEncoding(of)
        require(channels in 1..2) { "Only mono and stereo are supported (this file has $channels channels)" }
        trimmer = GaplessTrimmer(
            frameBytes = channels * format.bytesPerSample,
            delayFrames = inFormat.intOr("encoder-delay"),
            paddingFrames = inFormat.intOr("encoder-padding"),
        )
        pending = first
    }

    override fun read(): PcmChunk? {
        while (true) {
            val raw = pending?.also { pending = null } ?: decodeNext()
            if (raw == null) {
                trimmer.finish()
                return null
            }
            val trimmed = trimmer.process(raw)
            if (trimmed != null) return trimmed
        }
    }

    private fun decodeNext(): PcmChunk? {
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx < 0) continue
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            val chunk = if (info.size > 0) {
                val out = codec.getOutputBuffer(outIdx)!!
                out.position(info.offset).limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                out.get(bytes)
                PcmChunk(bytes, bytes.size, info.presentationTimeUs)
            } else null
            codec.releaseOutputBuffer(outIdx, false)
            if (chunk != null) return chunk
        }
        return null
    }

    override fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        pending = null
        trimmer.seeked()
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private fun MediaFormat.intOr(key: String) = if (containsKey(key)) getInteger(key) else 0

    private fun createDecoder(format: MediaFormat, wantFloat: Boolean): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        if (wantFloat) {
            // Without this, most decoders hand back 16-bit and a 24-bit FLAC loses its low byte.
            val codec = MediaCodec.createDecoderByType(mime)
            val ok = runCatching {
                val f = MediaFormat(format).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
                codec.configure(f, null, null, 0)
            }.isSuccess
            if (ok) return codec
            codec.release()
        }
        return MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) }
    }

    companion object {
        fun pcmEncoding(f: MediaFormat): SampleFormat {
            val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            return SampleFormat.fromAndroidEncoding(enc) ?: SampleFormat.S16
        }

        /** Bit depth the container declares, when it does (FLAC and WAV do). */
        fun sourceBitDepth(f: MediaFormat): Int? {
            if (f.containsKey("bits-per-sample")) return f.getInteger("bits-per-sample")
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                return when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> null
                }
            }
            return when (f.getString(MediaFormat.KEY_MIME)) {
                MediaFormat.MIMETYPE_AUDIO_MPEG, MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_OPUS,
                MediaFormat.MIMETYPE_AUDIO_VORBIS -> 16
                else -> null
            }
        }
    }
}

/**
 * Plays DSF/DFF either as DoP (native DSD at the DAC) or converted to 176.4 kHz PCM. DoP is
 * chosen only when [dop] confirms a bit-perfect path at the DoP rate.
 */
class DsdDecoder(private val dsd: DsdStream, dop: DopPlanner?) : PcmDecoder {
    private val packer: DopPacker?
    private val converter: DsdToPcm?
    private val inBuf = Array(dsd.channels) { ByteArray(READ_BYTES) }

    override val isDop: Boolean
    override val sampleRate: Int
    override val channels = dsd.channels
    override val format: SampleFormat
    override val sourceBits: Int? = 1
    override val codecName: String
    override val durationUs = dsd.durationUs
    private var positionBytes = 0L

    init {
        require(channels in 1..2) { "Only mono and stereo DSD are supported" }
        val dopRate = dsd.dsdRate / 16
        isDop = dop?.canDop(dopRate, channels) == true
        if (isDop) {
            packer = DopPacker(channels); converter = null
            sampleRate = dopRate; format = SampleFormat.S24_PACKED
            codecName = "${dsd.formatName} DoP"
        } else {
            converter = DsdToPcm(dsd.dsdRate, channels); packer = null
            sampleRate = converter.outputRate; format = SampleFormat.FLOAT
            codecName = "${dsd.formatName}→PCM"
        }
    }

    override fun read(): PcmChunk? {
        val n = dsd.read(inBuf, READ_BYTES)
        if (n == 0) return null
        val pts = positionBytes * 8 * 1_000_000 / dsd.dsdRate
        positionBytes += n
        return if (packer != null) {
            val out = ByteArray(packer.outputBytesFor(n))
            PcmChunk(out, packer.process(inBuf, n, out), pts)
        } else {
            val c = converter!!
            val out = ByteArray(c.outputBytesFor(n))
            PcmChunk(out, c.process(inBuf, n, out), pts)
        }
    }

    override fun seekTo(us: Long) {
        dsd.seekToUs(us)
        positionBytes = us * dsd.dsdRate / 8 / 1_000_000
        positionBytes -= positionBytes % 2
        packer?.reset()
        converter?.reset()
    }

    override fun close() = dsd.close()

    companion object {
        private const val READ_BYTES = 4096 // one DSF block per channel
    }
}
