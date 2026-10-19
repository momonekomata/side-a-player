package io.github.momonekomata.sidea.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Something the extractor can open. */
sealed interface PlayableSource {
    data class Content(val uri: Uri) : PlayableSource
    data class File(val path: String) : PlayableSource
    class Stream(val dataSource: MediaDataSource) : PlayableSource
}

data class OutputInfo(
    val sourceRate: Int,
    val sourceBits: Int?,
    val codec: String,
    val outputRate: Int,
    val outputFormat: SampleFormat,
    val bitPerfect: Boolean,
    val note: String,
)

data class EngineState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val output: OutputInfo? = null,
    val error: String? = null,
)

/**
 * Decodes with MediaExtractor/MediaCodec and writes PCM straight to an AudioTrack opened at the
 * file's own sample rate. No ExoPlayer: its sink converts to 16-bit or float on its own terms, and
 * bit-perfect output needs the exact container the DAC's mixer was opened with.
 */
class AudioEngine(context: Context, private val onCompleted: () -> Unit) {
    private val router = OutputRouter(context.applicationContext)
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var session: Session? = null

    fun play(source: PlayableSource, startMs: Long = 0) {
        stop()
        val s = Session(source, startMs * 1000)
        session = s
        _state.value = EngineState(isBuffering = true, positionMs = startMs)
        s.thread.start()
    }

    fun pause() {
        val s = session ?: return
        s.paused = true
        s.track?.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        val s = session ?: return
        synchronized(s.lock) {
            s.paused = false
            s.track?.play()
            s.lock.notifyAll()
        }
        _state.update { it.copy(isPlaying = true) }
    }

    fun seekTo(ms: Long) {
        session?.seekUs?.set(ms * 1000)
        _state.update { it.copy(positionMs = ms) }
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.cancel()
        s.thread.join(1500)
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    /** Polled by the UI clock; cheap. */
    fun refreshPosition() {
        val s = session ?: return
        val pos = s.positionUs() ?: return
        _state.update { it.copy(positionMs = pos / 1000) }
    }

    /** Shows an error that happened before the engine got a source (e.g. the NAS refused). */
    fun fail(message: String) {
        _state.value = EngineState(error = message)
    }

    fun release() {
        stop()
        router.clear()
    }

    private inner class Session(val source: PlayableSource, val startUs: Long) {
        val lock = Object()
        @Volatile var paused = false
        @Volatile var cancelled = false
        @Volatile var track: AudioTrack? = null
        val seekUs = AtomicLong(-1)
        val thread = Thread({ run() }, "SideA-decoder")

        // Position = time of the first sample written since the last flush + frames played since.
        @Volatile private var baseUs = startUs
        @Volatile private var headAtBase = 0L
        @Volatile private var rate = 44100
        private var rebase = true

        fun cancel() {
            cancelled = true
            synchronized(lock) { lock.notifyAll() }
            track?.let { runCatching { it.pause(); it.flush() } }
        }

        fun positionUs(): Long? {
            val t = track ?: return null
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return baseUs + (head - headAtBase).coerceAtLeast(0) * 1_000_000 / rate
        }

        private fun run() {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val ex = openExtractor(source)
                extractor = ex
                val trackIndex = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("No audio stream in this file")
                val inFormat = ex.getTrackFormat(trackIndex)
                ex.selectTrack(trackIndex)
                val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
                val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
                val sourceBits = sourceBitDepth(inFormat)
                val dec = createDecoder(inFormat, wantFloat = sourceBits == null || sourceBits > 16)
                codec = dec
                dec.start()
                if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                _state.update { it.copy(durationMs = durationUs / 1000) }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var decodedFormat: SampleFormat? = null
                var plan: OutputPlan? = null
                var scratch = ByteArray(0)
                var framesWritten = 0L

                while (!cancelled) {
                    synchronized(lock) { while (paused && !cancelled) lock.wait() }
                    if (cancelled) break

                    val seek = seekUs.getAndSet(-1)
                    if (seek >= 0) {
                        ex.seekTo(seek, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dec.flush()
                        track?.let { it.pause(); it.flush(); if (!paused) it.play() }
                        inputDone = false
                        framesWritten = 0
                        rebase = true
                    }

                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, 5_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        decodedFormat = null // re-read below with the next buffer
                        continue
                    }
                    if (outIdx < 0) continue

                    if (decodedFormat == null) {
                        val of = dec.outputFormat
                        decodedFormat = pcmEncoding(of)
                        val sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(ch in 1..2) { "Only mono and stereo are supported (this file has $ch channels)" }
                        val newPlan = router.plan(sr, ch, sourceBits, decodedFormat)
                        if (newPlan != plan || track == null) {
                            track?.release()
                            track = buildTrack(newPlan)
                            plan = newPlan
                            rate = sr
                            rebase = true
                            if (!paused) track!!.play()
                        }
                        _state.update {
                            it.copy(
                                isBuffering = false,
                                isPlaying = !paused,
                                output = OutputInfo(
                                    sourceRate = sr,
                                    sourceBits = sourceBits,
                                    codec = mime.removePrefix("audio/").uppercase(),
                                    outputRate = newPlan.sampleRate,
                                    outputFormat = newPlan.format,
                                    bitPerfect = newPlan.bitPerfect,
                                    note = newPlan.note,
                                ),
                            )
                        }
                    }

                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val out = dec.getOutputBuffer(outIdx)!!
                        out.position(info.offset).limit(info.offset + info.size)
                        val p = plan!!
                        val need = PcmConverter.outputSize(info.size, decodedFormat!!, p.format)
                        if (scratch.size < need) scratch = ByteArray(need)
                        PcmConverter.convert(out, decodedFormat, p.format, scratch)
                        val t = track!!
                        if (rebase) {
                            baseUs = info.presentationTimeUs
                            headAtBase = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            rebase = false
                        }
                        var off = 0
                        while (off < need && !cancelled && seekUs.get() < 0) {
                            val w = t.write(scratch, off, need - off, AudioTrack.WRITE_BLOCKING)
                            if (w < 0) error("AudioTrack write failed ($w)")
                            if (w == 0) synchronized(lock) { if (paused) lock.wait(50) }
                            off += w
                        }
                        framesWritten += need / (p.format.bytesPerSample * channelCount(p.channelMask))
                    }
                    dec.releaseOutputBuffer(outIdx, false)

                    if (eos) {
                        drain(framesWritten)
                        if (!cancelled) {
                            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                            onCompleted()
                        }
                        break
                    }
                }
            } catch (t: Throwable) {
                if (!cancelled) {
                    Log.e(TAG, "playback failed", t)
                    _state.update { it.copy(isPlaying = false, isBuffering = false, error = t.message ?: t.javaClass.simpleName) }
                }
            } finally {
                track?.let { runCatching { it.stop() }; it.release() }
                track = null
                runCatching { codec?.stop() }
                codec?.release()
                extractor?.release()
                (source as? PlayableSource.Stream)?.dataSource?.close()
            }
        }

        private fun drain(framesWritten: Long) {
            val t = track ?: return
            val target = headAtBase + framesWritten
            val deadline = System.currentTimeMillis() + 10_000
            while (!cancelled && System.currentTimeMillis() < deadline) {
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= target) break
                synchronized(lock) { lock.wait(20) }
            }
        }
    }

    private fun openExtractor(source: PlayableSource) = MediaExtractor().apply {
        when (source) {
            is PlayableSource.Content -> setDataSource(appContext, source.uri, null)
            is PlayableSource.File -> setDataSource(source.path)
            is PlayableSource.Stream -> setDataSource(source.dataSource)
        }
    }

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

    private fun buildTrack(plan: OutputPlan): AudioTrack {
        val fmt = AudioFormat.Builder()
            .setSampleRate(plan.sampleRate)
            .setEncoding(plan.format.androidEncoding)
            .setChannelMask(plan.channelMask)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(plan.sampleRate, plan.channelMask, plan.format.androidEncoding)
        val track = AudioTrack.Builder()
            .setAudioAttributes(router.attributes)
            .setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minBuf.coerceAtLeast(4096)) * 4)
            .build()
        plan.device?.let { track.preferredDevice = it }
        return track
    }

    companion object {
        private const val TAG = "SideA.Engine"

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

        private fun channelCount(mask: Int) = Integer.bitCount(mask)
    }
}
