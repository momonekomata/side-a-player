package io.github.momonekomata.sidea.streaming

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.momonekomata.sidea.audio.DeviceKind
import io.github.momonekomata.sidea.audio.RemoteFacts
import io.github.momonekomata.sidea.audio.SampleFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Android shows about other apps' media output: the device, and a bit-perfect USB format. */
data class OutputSeen(
    val device: DeviceKind? = null,
    val deviceName: String? = null,
    val bitPerfectRate: Int? = null,
    val bitPerfectFormat: SampleFormat? = null,
    val mixerRate: Int? = null,
)

/**
 * Android never tells one app another app's stream format. Two things are visible, though:
 * which device active media playback is routed to (AudioPlaybackConfiguration), and, on
 * Android 14+, the mixer format a player set when it took a USB DAC bit-perfect (the way TIDAL's
 * and others' exclusive USB modes work). That mixer format is the stream's true rate and depth.
 */
class RemoteOutput(context: Context, private val ownsMixer: () -> Boolean) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _seen = MutableStateFlow(OutputSeen())
    val seen: StateFlow<OutputSeen> = _seen.asStateFlow()
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = refresh(configs)
    }

    fun start() {
        am.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        refresh(am.activePlaybackConfigurations)
    }

    fun stop() = am.unregisterAudioPlaybackCallback(callback)

    fun refresh(configs: List<AudioPlaybackConfiguration> = am.activePlaybackConfigurations) {
        val device = configs
            .filter { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
            .firstNotNullOfOrNull { if (Build.VERSION.SDK_INT >= 30) it.audioDeviceInfo else null }
        var rate: Int? = null
        var format: SampleFormat? = null
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        // Side A's own bit-perfect mixer would be mistaken for the streaming app's.
        if (usb != null && Build.VERSION.SDK_INT >= 34 && !ownsMixer()) {
            am.getPreferredMixerAttributes(media, usb)?.let { m ->
                if (m.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                    rate = m.format.sampleRate
                    format = SampleFormat.fromAndroidEncoding(m.format.encoding)
                }
            }
        }
        _seen.value = OutputSeen(
            device = device?.let { kind(it) } ?: usb?.let { DeviceKind.USB },
            deviceName = (device ?: usb)?.takeIf { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.productName?.toString()?.takeIf { it.isNotBlank() },
            bitPerfectRate = rate,
            bitPerfectFormat = format,
            mixerRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull(),
        )
    }

    fun facts(app: StreamingApp, np: RemoteNowPlaying?): RemoteFacts {
        val s = _seen.value
        return RemoteFacts(
            appLabel = app.label,
            ceiling = app.ceiling,
            lossyOnly = app.lossyOnly,
            hint = np?.qualityHint,
            device = s.device,
            deviceName = s.deviceName,
            bitPerfectRate = s.bitPerfectRate,
            bitPerfectFormat = s.bitPerfectFormat,
            mixerRate = s.mixerRate,
            eq = io.github.momonekomata.sidea.Settings.current.eq.takeIf { app.packageName in StreamingEq.activePackages },
        )
    }

    private fun kind(d: AudioDeviceInfo): DeviceKind = when (d.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> DeviceKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> DeviceKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> DeviceKind.WIRED
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DeviceKind.SPEAKER
        else -> DeviceKind.OTHER
    }
}
