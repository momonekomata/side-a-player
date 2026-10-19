package io.github.momonekomata.sidea.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaDataSource
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Something the decoders can open. */
sealed interface PlayableSource {
    data class Content(val uri: Uri) : PlayableSource
    data class File(val path: String) : PlayableSource
    class Stream(val dataSource: MediaDataSource) : PlayableSource
}

data class EngineState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val output: StreamFacts? = null,
    /** Why the stream isn't bit-perfect, when it isn't. */
    val outputNote: String? = null,
    val error: String? = null,
)

/** The song after the current one, for gapless hand-over. [tag] comes back in onAdvanced. */
class UpNext(val tag: Any, val open: () -> PlayableSource?)

interface EngineCallbacks {
    /** Called on a background thread near the end of a song; null if nothing follows. */
    fun upNext(): UpNext?
    /** The gapless hand-over reached the speaker: the song with [tag] is now playing. */
    fun onAdvanced(tag: Any)
    /** Playback ran out without a hand-over (end of queue, gapless off, or a format change). */
    fun onCompleted()
    fun dopAllowed(): Boolean
}

/**
 * Decodes and writes PCM straight to an AudioTrack opened at the file's own rate. No
 * ExoPlayer: its sink converts to 16-bit or float on its own terms, and bit-perfect output
 * needs the exact container the DAC's mixer was opened with.
 *
 * Gapless: about 15 s before a song ends the next one is opened and primed on a helper thread.
 * At the end, if it has the same rate, channels and output format, its samples are appended to
 * the same AudioTrack, so there is no gap and no click. A different format needs a new track
 * (and a new bit-perfect mixer), which every player does with a short pause.
 */
class AudioEngine(context: Context, private val callbacks: EngineCallbacks) {
    private val router = OutputRouter(context.applicationContext)
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile var gapless = true
    /** Read for every chunk, so changes apply within a few milliseconds of audio. */
    @Volatile var eq = EqConfig()

    /** [paused] opens the song ready to play at [startMs] without starting it. */
    fun play(source: PlayableSource, startMs: Long = 0, paused: Boolean = false) {
        stop()
        val s = Session(source, startMs * 1000)
        s.paused = paused
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

    val holdsBitPerfectMixer: Boolean get() = router.holdsMixer

    fun stop() {
        val s = session ?: return
        session = null
        s.cancel()
        s.thread.join(1500)
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    /** Polled by the UI clock; also where a gapless hand-over becomes visible. */
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

    /** A song queued on the AudioTrack behind the current one: when the head reaches [startHead] it's audible. */
    private class Segment(val startHead: Long, val tag: Any, val durationUs: Long, val output: StreamFacts)

    private inner class Session(val source: PlayableSource, val startUs: Long) {
        val lock = Object()
        @Volatile var paused = false
        @Volatile var cancelled = false
        @Volatile var track: AudioTrack? = null
        val seekUs = AtomicLong(-1)
        val thread = Thread({ run() }, "SideA-decoder")

        // Position = time of the first sample written since the last rebase + frames played since.
        @Volatile private var baseUs = startUs
        @Volatile private var headAtBase = 0L
        @Volatile private var rate = 44100
        @Volatile private var next: Segment? = null

        private var prefetch: Thread? = null
        @Volatile private var prefetched: Pair<UpNext, PcmDecoder>? = null

        fun cancel() {
            cancelled = true
            synchronized(lock) { lock.notifyAll() }
            track?.let { runCatching { it.pause(); it.flush() } }
        }

        private fun head(t: AudioTrack) = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

        @Synchronized
        fun positionUs(): Long? {
            val t = track ?: return null
            val h = head(t)
            next?.let { seg ->
                if (h >= seg.startHead) {
                    next = null
                    baseUs = 0
                    headAtBase = seg.startHead
                    _state.update { it.copy(durationMs = seg.durationUs / 1000, output = seg.output) }
                    main.post { callbacks.onAdvanced(seg.tag) }
                }
            }
            return baseUs + (h - headAtBase).coerceAtLeast(0) * 1_000_000 / rate
        }

        // DoP can't be equalised, so an active EQ means DSD is converted to PCM instead.
        private val dopPlanner = DopPlanner { r, ch -> callbacks.dopAllowed() && !eq.isActive && router.canDop(r, ch) }
        private var eqProc: EqProcessor? = null
        private var floatBuf = FloatArray(0)
        private var floatBytes = ByteArray(0)

        private fun run() {
            var dec: PcmDecoder? = null
            try {
                dec = Decoders.open(appContext, source, dopPlanner)
                if (startUs > 0) dec.seekTo(startUs)
                val plan = planFor(dec)
                val t = buildTrack(plan)
                track = t
                rate = dec.sampleRate
                val info = facts(dec, plan, t)
                _state.update { it.copy(isBuffering = false, isPlaying = !paused, durationMs = dec!!.durationUs / 1000, output = info, outputNote = plan.note.takeIf { !plan.bitPerfect }) }
                // Headphones unplugged, Bluetooth connected: keep the signal path honest.
                t.addOnRoutingChangedListener(
                    AudioRouting.OnRoutingChangedListener { _state.update { s -> s.copy(output = s.output?.withDevice(t.routedDevice)) } },
                    main,
                )
                if (!paused) t.play()

                var scratch = ByteArray(0)
                // Head position the end of the written audio corresponds to; it only resets on a flush.
                var flushHead = 0L
                var written = 0L
                var rebase = true
                var decodedUs = startUs

                while (!cancelled) {
                    synchronized(lock) { while (paused && !cancelled) lock.wait() }
                    if (cancelled) break

                    val seek = seekUs.getAndSet(-1)
                    if (seek >= 0) {
                        // A seek always lands in the song on screen, which is the one being decoded
                        // unless a hand-over is still queued in the track buffer; drop that.
                        next = null
                        dec!!.seekTo(seek)
                        eqProc?.reset()
                        t.pause(); t.flush(); if (!paused) t.play()
                        flushHead = head(t)
                        written = 0
                        rebase = true
                    }

                    val d = dec!!
                    maybePrefetch(d, decodedUs)
                    val chunk = d.read()
                    if (chunk == null) {
                        val handOver = takePrefetched()
                        if (handOver != null && compatible(handOver.second, d, plan)) {
                            d.close()
                            dec = handOver.second
                            decodedUs = 0
                            next = Segment(flushHead + written, handOver.first.tag, handOver.second.durationUs, facts(handOver.second, plan, t))
                            continue
                        }
                        handOver?.second?.close()
                        drain(t, flushHead + written)
                        if (!cancelled) {
                            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                            main.post { callbacks.onCompleted() }
                        }
                        break
                    }

                    val (pcm, pcmSize, pcmFormat) = equalise(chunk, d)
                    val need = PcmConverter.outputSize(pcmSize, pcmFormat, plan.format)
                    if (scratch.size < need) scratch = ByteArray(need)
                    PcmConverter.convert(java.nio.ByteBuffer.wrap(pcm, 0, pcmSize), pcmFormat, plan.format, scratch)
                    decodedUs = chunk.ptsUs
                    if (rebase) {
                        baseUs = chunk.ptsUs
                        headAtBase = flushHead + written
                        rebase = false
                    }
                    var off = 0
                    while (off < need && !cancelled && seekUs.get() < 0) {
                        val w = t.write(scratch, off, need - off, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) error("AudioTrack write failed ($w)")
                        if (w == 0) synchronized(lock) { if (paused) lock.wait(50) }
                        off += w
                    }
                    written += off / (plan.format.bytesPerSample * Integer.bitCount(plan.channelMask))
                    // Notices a gapless hand-over even when no UI is polling (screen off, app closed).
                    positionUs()
                }
            } catch (e: Throwable) {
                if (!cancelled) {
                    Log.e(TAG, "playback failed", e)
                    _state.update { it.copy(isPlaying = false, isBuffering = false, error = e.message ?: e.javaClass.simpleName) }
                }
            } finally {
                track?.let { runCatching { it.stop() }; it.release() }
                track = null
                dec?.close()
                prefetch?.join(2000)
                prefetched?.second?.close()
                prefetched = null
                // Give the DAC back once nothing of ours plays, so other apps can use it as they like.
                if (session == null || session === this) router.clear()
            }
        }

        /** [decodedUs] is how far the decoder has got in [d], which runs a little ahead of the speaker. */
        private fun maybePrefetch(d: PcmDecoder, decodedUs: Long) {
            if (!gapless || prefetch != null || d.durationUs <= 0) return
            if (d.durationUs - decodedUs > PREFETCH_US) return
            prefetch = Thread({
                try {
                    val up = callbacks.upNext() ?: return@Thread
                    val src = up.open() ?: return@Thread
                    val nd = Decoders.open(appContext, src, dopPlanner)
                    if (cancelled) nd.close() else prefetched = up to nd
                } catch (e: Throwable) {
                    // Not fatal: the queue opens it again the ordinary way and shows any error.
                    Log.w(TAG, "couldn't open the next song early", e)
                }
            }, "SideA-prefetch").also { it.start() }
        }

        private fun takePrefetched(): Pair<UpNext, PcmDecoder>? {
            val p = prefetch ?: return null
            // The next song may still be opening (a slow NAS); waiting here is still gapless as
            // long as the track buffer holds out.
            while (p.isAlive && !cancelled) p.join(50)
            prefetch = null
            return prefetched.also { prefetched = null }
        }

        /** The next song can share the track if nothing about the output would change or lose bits. */
        private fun compatible(n: PcmDecoder, cur: PcmDecoder, plan: OutputPlan): Boolean =
            n.sampleRate == cur.sampleRate && n.channels == cur.channels && n.isDop == cur.isDop &&
                plan.format.exactBits() >= (n.sourceBits?.takeIf { it > 1 } ?: n.format.exactBits())

        /** Runs the EQ when it's on (never on DoP); otherwise hands the chunk through untouched. */
        private fun equalise(chunk: PcmChunk, d: PcmDecoder): Triple<ByteArray, Int, SampleFormat> {
            val config = eq
            if (!config.isActive || d.isDop) return Triple(chunk.data, chunk.size, d.format)
            val proc = eqProc ?: EqProcessor(d.sampleRate, d.channels).also { eqProc = it }
            proc.configure(config)
            val floatSize = PcmConverter.outputSize(chunk.size, d.format, SampleFormat.FLOAT)
            if (floatBytes.size < floatSize) floatBytes = ByteArray(floatSize)
            PcmConverter.convert(java.nio.ByteBuffer.wrap(chunk.data, 0, chunk.size), d.format, SampleFormat.FLOAT, floatBytes)
            val samples = floatSize / 4
            if (floatBuf.size < samples) floatBuf = FloatArray(samples)
            val fb = java.nio.ByteBuffer.wrap(floatBytes, 0, floatSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            fb.get(floatBuf, 0, samples)
            proc.process(floatBuf, samples / d.channels)
            fb.rewind()
            fb.put(floatBuf, 0, samples)
            return Triple(floatBytes, floatSize, SampleFormat.FLOAT)
        }

        private fun drain(t: AudioTrack, target: Long) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!cancelled && System.currentTimeMillis() < deadline) {
                if (head(t) >= target) break
                positionUs()
                synchronized(lock) { lock.wait(20) }
            }
        }
    }

    private fun planFor(dec: PcmDecoder): OutputPlan = when {
        dec.isDop -> router.planDop(dec.sampleRate, dec.channels) ?: error("The USB DAC stopped offering a bit-perfect path for DoP")
        // EQ output has more than the source's bits: give it at least a 24-bit container.
        eq.isActive -> router.plan(dec.sampleRate, dec.channels, maxOf(dec.sourceBits ?: 24, 24), SampleFormat.FLOAT)
        else -> router.plan(dec.sampleRate, dec.channels, dec.sourceBits, dec.format)
    }

    private fun facts(dec: PcmDecoder, plan: OutputPlan, track: AudioTrack): StreamFacts {
        val device = track.routedDevice ?: plan.device
        return StreamFacts(
            codec = dec.codecName,
            sourceRate = dec.sourceRate,
            sourceBits = dec.sourceBits,
            dsd = if (dec is DsdDecoder) (if (dec.isDop) DsdMode.DOP else DsdMode.PCM) else null,
            outputRate = plan.sampleRate,
            outputFormat = plan.format,
            bitPerfect = plan.bitPerfect,
            device = device.kind(),
            deviceName = device.displayName(),
            mixerRate = if (plan.bitPerfect) null else router.mixerRate(),
            usb = if (plan.bitPerfect) null else router.usbSupport(),
        )
    }

    private fun StreamFacts.withDevice(d: AudioDeviceInfo?) =
        if (d == null) this else copy(device = d.kind(), deviceName = d.displayName())

    /** The product name for external gear; the phone's own outputs report the phone's model. */
    private fun AudioDeviceInfo?.displayName(): String? {
        val d = this ?: return null
        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return null
        return d.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun AudioDeviceInfo?.kind(): DeviceKind = when (this?.type) {
        null -> DeviceKind.OTHER
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> DeviceKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> DeviceKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> DeviceKind.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DeviceKind.SPEAKER
        else -> DeviceKind.OTHER
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

    /** Bits a container carries without loss; float is exact up to 24. */
    private fun SampleFormat.exactBits() = when (this) {
        SampleFormat.S16 -> 16
        SampleFormat.S24_PACKED, SampleFormat.FLOAT -> 24
        SampleFormat.S32 -> 32
    }

    companion object {
        private const val TAG = "SideA.Engine"
        private const val PREFETCH_US = 15_000_000L
    }
}
