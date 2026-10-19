package io.github.momonekomata.sidea.audio

import kotlin.math.abs

/** Roon-style grading, worst to best. A path is as good as its weakest stage. */
enum class Quality(val label: String) {
    LOSSY("LOSSY"),
    HIGH_QUALITY("HIGH QUALITY"),
    ENHANCED("ENHANCED"),
    LOSSLESS("LOSSLESS"),
}

enum class DeviceKind(val label: String) {
    USB("USB DAC"),
    BLUETOOTH("Bluetooth"),
    WIRED("Headphones"),
    SPEAKER("Phone speaker"),
    OTHER("Phone output"),
}

data class PathStage(val title: String, val detail: String, val quality: Quality)

data class SignalPath(val stages: List<PathStage>) {
    val quality: Quality get() = stages.minOf { it.quality }
}

/** What the engine knows about one playing stream, enough to describe its path. */
data class StreamFacts(
    val codec: String,
    val sourceRate: Int,
    val sourceBits: Int?,
    /** DSD sent natively as DoP, or converted to PCM; null for PCM sources. */
    val dsd: DsdMode?,
    val outputRate: Int,
    val outputFormat: SampleFormat,
    val bitPerfect: Boolean,
    val device: DeviceKind,
    val deviceName: String?,
    /** The system mixer's rate, when the stream goes through it. */
    val mixerRate: Int?,
)

enum class DsdMode { DOP, PCM }

object SignalPaths {
    private val LOSSLESS_CODECS = setOf("FLAC", "RAW", "ALAC", "WAV", "AIFF", "X-AIFF")

    fun isLossless(codec: String) = codec.uppercase() in LOSSLESS_CODECS || codec.startsWith("DSD")

    fun describe(f: StreamFacts, eq: EqConfig?): SignalPath {
        val stages = mutableListOf<PathStage>()
        val bits = f.sourceBits?.takeIf { it > 1 }?.let { "/$it-bit" } ?: ""
        stages += when {
            f.dsd != null -> PathStage("Source", "DSD${f.sourceRate / 44_100} · ${"%.4f".format(f.sourceRate / 1e6)} MHz 1-bit", Quality.LOSSLESS)
            isLossless(f.codec) -> PathStage("Source", "${codecName(f.codec)} · ${khz(f.sourceRate)}$bits", Quality.LOSSLESS)
            else -> PathStage("Source", "${codecName(f.codec)} · ${khz(f.sourceRate)} · compressed with loss", Quality.LOSSY)
        }
        if (f.dsd == DsdMode.PCM) stages += PathStage("DSD to PCM", "Low-pass filtered to ${khz(f.outputRate)} float", Quality.HIGH_QUALITY)
        if (f.dsd == DsdMode.DOP) stages += PathStage("DoP", "DSD bits packed in ${khz(f.outputRate)} PCM frames", Quality.LOSSLESS)
        if (eq != null && eq.isActive && f.dsd != DsdMode.DOP) {
            val n = eq.activeBands.size
            stages += PathStage("Parametric EQ", "$n band${if (n == 1) "" else "s"} · preamp ${signed(eq.effectivePreampDb)} dB", Quality.ENHANCED)
        }
        stages += when {
            f.bitPerfect -> PathStage(
                "Output",
                "${f.deviceName ?: f.device.label} · bit-perfect ${khz(f.outputRate)} ${f.outputFormat.label}",
                Quality.LOSSLESS,
            )
            else -> {
                val mixer = f.mixerRate
                val how = if (mixer != null && mixer != f.outputRate) "resampled to ${khz(mixer)}" else "mixed at ${khz(mixer ?: f.outputRate)}"
                PathStage("Android mixer", "$how, system volume applied", Quality.HIGH_QUALITY)
            }
        }
        if (f.device == DeviceKind.BLUETOOTH) {
            stages += PathStage("Bluetooth", "${f.deviceName ?: "Headphones"} · re-encoded by the Bluetooth codec", Quality.LOSSY)
        } else if (!f.bitPerfect) {
            stages += PathStage("Output", f.deviceName ?: f.device.label, Quality.HIGH_QUALITY)
        }
        return SignalPath(stages)
    }

    private fun codecName(c: String) = when (c.uppercase()) {
        "RAW" -> "PCM"
        "MPEG" -> "MP3"
        "MP4A-LATM" -> "AAC"
        else -> c.uppercase()
    }

    fun khz(hz: Int): String {
        val k = hz / 1000.0
        return (if (abs(k - Math.round(k)) < 1e-9) "%.0f" else "%.1f").format(k) + " kHz"
    }

    private fun signed(db: Double) = (if (db > 0) "+" else "") + "%.1f".format(db)
}
