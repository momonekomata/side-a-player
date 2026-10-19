package io.github.momonekomata.sidea.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/** How the decoded audio leaves the app. */
data class OutputPlan(
    val sampleRate: Int,
    val format: SampleFormat,
    val channelMask: Int,
    val device: AudioDeviceInfo?,
    val bitPerfect: Boolean,
    val note: String,
    /** Set when Side A's own USB driver carries the stream. */
    val exclusive: ExclusiveTarget? = null,
)

/** Where the USB driver will stream: the device, its alternate setting and container. */
data class ExclusiveTarget(val device: android.hardware.usb.UsbDevice, val alt: io.github.momonekomata.sidea.audio.usb.UsbAltSetting)

/**
 * Picks the output path for a source. With a USB DAC on Android 14+ it asks the platform for a
 * bit-perfect mixer at the file's own sample rate (AudioManager.setPreferredMixerAttributes), which
 * is the supported way to get past the 48 kHz mixer. Everywhere else the audio still leaves at its
 * native rate and depth, and Android resamples it; the plan says so.
 */
class OutputRouter(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var activeMixer: Pair<AudioDeviceInfo, Any>? = null

    /** True while Side A holds a bit-perfect mixer on the DAC. */
    val holdsMixer: Boolean get() = activeMixer != null

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** What this phone offers for the connected USB DAC, or null when there's none. */
    fun usbSupport(): UsbSupport? {
        val d = usbDevice() ?: return null
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
        if (Build.VERSION.SDK_INT < 34) return UsbSupport(name, apiAvailable = false, bitPerfectRates = emptyList())
        val rates = am.getSupportedMixerAttributes(d)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .map { it.format.sampleRate }
            .distinct()
            .sorted()
        return UsbSupport(name, apiAvailable = true, bitPerfectRates = rates)
    }

    /** The rate Android's mixer runs at for the default output. */
    fun mixerRate(): Int? = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    fun usbDevice(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    fun plan(sampleRate: Int, channels: Int, sourceBits: Int?, decoded: SampleFormat): OutputPlan {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val usb = usbDevice()
        if (usb != null && Build.VERSION.SDK_INT >= 34) {
            planBitPerfect(usb, sampleRate, channels, sourceBits ?: decoded.bitsGuess())?.let { return it }
        }
        clear()
        val mixerRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val why = when {
            usb == null -> "Android mixer${mixerRate?.let { " · ${it / 1000.0} kHz".replace(".0 ", " ") } ?: ""}"
            Build.VERSION.SDK_INT < 34 -> "Android mixer (bit-perfect needs Android 14)"
            // The phone's limit, not the DAC's: say so.
            usbSupport()?.bitPerfectRates.isNullOrEmpty() -> "Android mixer (this phone has no bit-perfect USB mode)"
            else -> "Android mixer (no bit-perfect ${sampleRate / 1000.0} kHz on this phone)".replace(".0 kHz", " kHz")
        }
        // Float keeps hi-res material intact up to the system mixer.
        val fmt = if (decoded == SampleFormat.S16) SampleFormat.S16 else SampleFormat.FLOAT
        return OutputPlan(sampleRate, fmt, mask, usb, bitPerfect = false, note = why)
    }

    /** Whether DoP can go out bit-perfect right now. Queries only; changes nothing. */
    fun canDop(rate: Int, channels: Int): Boolean {
        val usb = usbDevice() ?: return false
        return Build.VERSION.SDK_INT >= 34 && bitPerfectChoice(usb, rate, channels, bits = 24, integerOnly = true) != null
    }

    /** Plain float output through Android's mixer, for playback that changes the samples anyway. */
    fun mixed(sampleRate: Int, channels: Int) = OutputPlan(
        sampleRate = sampleRate,
        format = SampleFormat.FLOAT,
        channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
        device = usbDevice(),
        bitPerfect = false,
        note = "Android mixer",
    )

    /** A bit-perfect plan for DoP, or null; DoP must never be sent through the mixer. */
    fun planDop(rate: Int, channels: Int): OutputPlan? {
        val usb = usbDevice() ?: return null
        if (Build.VERSION.SDK_INT < 34) return null
        return planBitPerfect(usb, rate, channels, bits = 24, integerOnly = true)
    }

    @RequiresApi(34)
    private fun bitPerfectChoice(device: AudioDeviceInfo, rate: Int, channels: Int, bits: Int, integerOnly: Boolean): AudioMixerAttributes? {
        val candidates = am.getSupportedMixerAttributes(device).filter {
            val f = SampleFormat.fromAndroidEncoding(it.format.encoding)
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == rate &&
                it.format.channelCount == channels &&
                f != null &&
                (!integerOnly || (f != SampleFormat.FLOAT && f.bytesPerSample * 8 >= bits))
        }
        // Prefer the narrowest integer container that holds the source, so the DAC sees the
        // original samples; float is the last resort because not every DAC accepts it.
        return candidates.minByOrNull { rank(SampleFormat.fromAndroidEncoding(it.format.encoding)!!, bits) }
    }

    @RequiresApi(34)
    private fun planBitPerfect(device: AudioDeviceInfo, rate: Int, channels: Int, bits: Int, integerOnly: Boolean = false): OutputPlan? {
        val chosen = bitPerfectChoice(device, rate, channels, bits, integerOnly) ?: return null
        val current = activeMixer
        if (current == null || current.first.id != device.id || current.second != chosen) {
            clear()
            if (!am.setPreferredMixerAttributes(attributes, device, chosen)) return null
            activeMixer = device to chosen
        }
        val fmt = SampleFormat.fromAndroidEncoding(chosen.format.encoding)!!
        return OutputPlan(
            sampleRate = rate,
            format = fmt,
            channelMask = chosen.format.channelMask,
            device = device,
            bitPerfect = true,
            note = "Bit-perfect to ${device.productName}",
        )
    }

    private fun rank(f: SampleFormat, bits: Int): Int = when {
        f == SampleFormat.FLOAT -> 100
        f.bytesPerSample * 8 < bits -> 1000 // would truncate
        else -> f.bytesPerSample
    }

    fun clear() {
        val current = activeMixer ?: return
        activeMixer = null
        if (Build.VERSION.SDK_INT >= 34) am.clearPreferredMixerAttributes(attributes, current.first)
    }

    private fun SampleFormat.bitsGuess() = when (this) {
        SampleFormat.S16 -> 16
        SampleFormat.S24_PACKED, SampleFormat.FLOAT -> 24
        SampleFormat.S32 -> 32
    }
}
