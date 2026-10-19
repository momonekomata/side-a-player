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

/** [verified] is false for what another app doesn't report; such stages don't move the badge. */
data class PathStage(val title: String, val detail: String, val quality: Quality, val verified: Boolean = true)

data class SignalPath(val stages: List<PathStage>) {
    val quality: Quality get() = stages.filter { it.verified }.minOfOrNull { it.quality } ?: Quality.HIGH_QUALITY
}

/** What can be seen of a streaming app's playback from outside it. */
data class RemoteFacts(
    val appLabel: String,
    /** The service's best format, e.g. "FLAC up to 24-bit/192 kHz". */
    val ceiling: String,
    /** True for services that only stream lossy audio. */
    val lossyOnly: Boolean,
    /** A quality string the app published in its media metadata, if any. */
    val hint: String?,
    val device: DeviceKind?,
    val deviceName: String?,
    /** Set when the app holds the USB DAC in bit-perfect mode: the real stream format. */
    val bitPerfectRate: Int?,
    val bitPerfectFormat: SampleFormat?,
    val mixerRate: Int?,
)

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
    /** A USB DAC that's connected but not used bit-perfect, and what the phone offers for it. */
    val usb: UsbSupport? = null,
)

/** What Android on this phone offers for a connected USB DAC. */
data class UsbSupport(val name: String, val apiAvailable: Boolean, val bitPerfectRates: List<Int>)

enum class DsdMode { DOP, PCM }

object SignalPaths {
    private val LOSSLESS_CODECS = setOf("FLAC", "RAW", "PCM", "PCM FLOAT", "ALAC", "WAV", "AIFF", "X-AIFF")

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
        f.usb?.let { u ->
            val why = when {
                !u.apiAvailable -> "Connected, but bit-perfect output needs Android 14 or newer"
                u.bitPerfectRates.isEmpty() -> "Connected, but this phone's audio system offers no bit-perfect mode for it, so Android's mixer stays in the path. Exclusive access on this phone needs an app with its own USB driver"
                else -> "Bit-perfect is offered at ${u.bitPerfectRates.joinToString(", ") { khz(it) }}, not at this file's ${khz(f.outputRate)}"
            }
            stages += PathStage(u.name, why, Quality.HIGH_QUALITY)
        }
        if (f.device == DeviceKind.BLUETOOTH) {
            stages += PathStage("Bluetooth", "${f.deviceName ?: "Headphones"} · re-encoded by the Bluetooth codec", Quality.LOSSY)
        } else if (!f.bitPerfect) {
            stages += PathStage("Output", f.deviceName ?: f.device.label, Quality.HIGH_QUALITY)
        }
        return SignalPath(stages)
    }

    fun describeRemote(r: RemoteFacts): SignalPath {
        val stages = mutableListOf<PathStage>()
        stages += when {
            r.lossyOnly -> PathStage("Source", "${r.appLabel} stream · ${r.ceiling}", Quality.LOSSY)
            r.hint != null -> PathStage("Source", "${r.appLabel} reports: ${r.hint}", if (looksLossy(r.hint)) Quality.LOSSY else Quality.LOSSLESS)
            else -> PathStage(
                "Source",
                "${r.appLabel} · ${r.ceiling} at best; the streaming quality you set in ${r.appLabel} isn't reported to other apps",
                Quality.LOSSLESS,
                verified = false,
            )
        }
        val rate = r.bitPerfectRate
        stages += when {
            rate != null -> PathStage(
                "Output",
                "${r.deviceName ?: "USB DAC"} · bit-perfect ${khz(rate)}${r.bitPerfectFormat?.let { " ${it.label}" } ?: ""}, held by ${r.appLabel}",
                Quality.LOSSLESS,
            )
            r.device == DeviceKind.BLUETOOTH -> PathStage("Bluetooth", "${r.deviceName ?: "Headphones"} · re-encoded by the Bluetooth codec", Quality.LOSSY)
            r.device != null -> PathStage(
                "Android mixer",
                "${r.mixerRate?.let { "mixed at ${khz(it)}" } ?: "mixed"} → ${r.deviceName ?: r.device.label}",
                Quality.HIGH_QUALITY,
            )
            else -> PathStage("Output", "Not reported", Quality.HIGH_QUALITY, verified = false)
        }
        return SignalPath(stages)
    }

    private fun looksLossy(hint: String) = Regex("""\b(aac|mp3|ogg|opus|vorbis|kbps|low|normal|high quality)\b""", RegexOption.IGNORE_CASE).containsMatchIn(hint) &&
        !Regex("""lossless|flac|alac|hi-?res|24.?bit""", RegexOption.IGNORE_CASE).containsMatchIn(hint)

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
