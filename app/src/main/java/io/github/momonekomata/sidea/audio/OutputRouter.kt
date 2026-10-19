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
)

/**
 * Picks the output path for a source. With a USB DAC on Android 14+ it asks the platform for a
 * bit-perfect mixer at the file's own sample rate (AudioManager.setPreferredMixerAttributes), which
 * is the supported way to get past the 48 kHz mixer. Everywhere else the audio still leaves at its
 * native rate and depth, and Android resamples it; the plan says so.
 */
class OutputRouter(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var activeMixer: Pair<AudioDeviceInfo, Any>? = null

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

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
            usb == null -> "No USB DAC: Android mixes to ${mixerRate ?: "?"} Hz"
            Build.VERSION.SDK_INT < 34 -> "Bit-perfect needs Android 14+: Android mixes the stream"
            else -> "${usb.productName} offers no bit-perfect mode at ${sampleRate / 1000.0} kHz"
        }
        // Float keeps hi-res material intact up to the system mixer.
        val fmt = if (decoded == SampleFormat.S16) SampleFormat.S16 else SampleFormat.FLOAT
        return OutputPlan(sampleRate, fmt, mask, usb, bitPerfect = false, note = why)
    }

    @RequiresApi(34)
    private fun planBitPerfect(device: AudioDeviceInfo, rate: Int, channels: Int, bits: Int): OutputPlan? {
        val candidates = am.getSupportedMixerAttributes(device).filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == rate &&
                it.format.channelCount == channels &&
                SampleFormat.fromAndroidEncoding(it.format.encoding) != null
        }
        // Prefer the narrowest integer container that holds the source, so the DAC sees the
        // original samples; float is the last resort because not every DAC accepts it.
        val ranked = candidates.sortedBy { rank(SampleFormat.fromAndroidEncoding(it.format.encoding)!!, bits) }
        val chosen = ranked.firstOrNull() ?: return null
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
