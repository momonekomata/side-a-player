package io.github.momonekomata.sidea.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.momonekomata.sidea.audio.SampleFormat
import java.io.IOException

/** One stream to a DAC Side A holds, opened for one format. */
class ExclusiveOutput(
    val stream: UsbStream,
    val format: SampleFormat,
    val rate: Int,
    val channels: Int,
    val deviceName: String,
    val bits: Int,
)

/**
 * Side A's own USB audio driver, for phones whose Android has no bit-perfect USB mode (most
 * Samsungs). With the user's permission it takes the DAC's audio interfaces from the kernel
 * driver, selects the alternate setting for the file's format, sets the DAC's clock to the
 * file's rate and streams the samples itself. While it holds the DAC, other apps can't use it;
 * closing hands it back to Android.
 */
object UsbExclusive {
    private const val TAG = "SideA-usb"
    private const val EBUSY = 16
    private const val ACTION_PERMISSION = "io.github.momonekomata.sidea.USB_PERMISSION"

    private lateinit var appContext: Context
    private lateinit var usb: UsbManager
    /** Called when the user answers the permission prompt with yes. */
    var onPermissionGranted: (() -> Unit)? = null
    /** Called when the DAC Side A held was unplugged. */
    var onDetached: (() -> Unit)? = null
    @Volatile var active: ExclusiveOutput? = null
        private set

    /** True while Side A is opening or holding a DAC; the DAC vanishing from Android is expected then. */
    @Volatile var busyUntil = 0L

    fun init(context: Context) {
        if (::usb.isInitialized) return
        appContext = context.applicationContext
        usb = appContext.getSystemService(UsbManager::class.java)
        ContextCompat.registerReceiver(appContext, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == ACTION_PERMISSION && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    onPermissionGranted?.invoke()
                }
            }
        }, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                detached(d)
                onDetached?.invoke()
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
    }

    /** The first USB device with an audio playback interface. */
    fun audioDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    /** Shows the system's "Allow Side A to access …?" prompt. */
    fun requestPermission(d: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_PERMISSION).setPackage(appContext.packageName), flags)
        usb.requestPermission(d, pi)
    }

    private val formatsCache = mutableMapOf<String, Pair<UsbAudioDescriptors, Map<Int, List<Int>>>>()

    /**
     * The alternate setting and container for [rate]/[channels]/[bits], or null if the DAC
     * can't take it. Reads descriptors (and UAC2 clock ranges) once per device.
     */
    fun choose(d: UsbDevice, rate: Int, channels: Int, bits: Int, integerOnly: Boolean = false): Pair<UsbAltSetting, SampleFormat>? {
        val (desc, clockRates) = describe(d) ?: return null
        val fits = desc.playback.filter { alt ->
            alt.channels == channels && supportsRate(desc, alt, clockRates, rate)
        }
        // The narrowest slot that carries every bit; else the widest there is.
        val best = fits.filter { it.bitResolution >= bits }.minByOrNull { it.subslotBytes * 100 + it.bitResolution }
            ?: fits.maxByOrNull { it.bitResolution } ?: return null
        val fmt = when (best.subslotBytes) {
            2 -> SampleFormat.S16
            3 -> SampleFormat.S24_PACKED
            else -> SampleFormat.S32
        }
        if (integerOnly && best.bitResolution < bits) return null
        return best to fmt
    }

    /** Rates the DAC accepts, for the signal path. */
    fun rates(d: UsbDevice): List<Int> {
        val (desc, clockRates) = describe(d) ?: return emptyList()
        return desc.playback.flatMap { alt ->
            if (alt.uac2) desc.clockFor(alt)?.let { clockRates[it] }.orEmpty() else alt.rates
        }.distinct().sorted()
    }

    private fun supportsRate(desc: UsbAudioDescriptors, alt: UsbAltSetting, clockRates: Map<Int, List<Int>>, rate: Int): Boolean {
        if (!alt.uac2) return rate in alt.rates || alt.rateRange?.contains(rate) == true
        val r = desc.clockFor(alt)?.let { clockRates[it] } ?: return true // couldn't ask: try it
        return rate in r
    }

    private fun describe(d: UsbDevice): Pair<UsbAudioDescriptors, Map<Int, List<Int>>>? {
        formatsCache[d.deviceName]?.let { return it }
        if (!usb.hasPermission(d)) return null
        val conn = usb.openDevice(d) ?: return null
        try {
            val desc = UsbAudioDescriptors.parse(conn.rawDescriptors ?: return null) ?: return null
            val rates = mutableMapOf<Int, List<Int>>()
            if (desc.uac2) for (clock in desc.clockSources) clockRanges(conn, desc.controlInterface, clock)?.let { rates[clock] = it }
            return (desc to rates).also { formatsCache[d.deviceName] = it }
        } finally {
            conn.close()
        }
    }

    /** UAC2 RANGE request on a clock source's frequency control. */
    private fun clockRanges(conn: UsbDeviceConnection, acIface: Int, clock: Int): List<Int>? {
        val head = ByteArray(2)
        val wIndex = (clock shl 8) or acIface
        if (conn.controlTransfer(0xA1, 0x02, 0x0100, wIndex, head, 2, 500) < 2) return null
        val n = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
        if (n !in 1..64) return null
        val buf = ByteArray(2 + n * 12)
        if (conn.controlTransfer(0xA1, 0x02, 0x0100, wIndex, buf, buf.size, 500) < buf.size) return null
        fun u32(i: Int) = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8) or ((buf[i + 2].toInt() and 0xFF) shl 16) or ((buf[i + 3].toInt() and 0xFF) shl 24)
        val out = mutableListOf<Int>()
        for (k in 0 until n) {
            val min = u32(2 + k * 12); val max = u32(6 + k * 12); val res = u32(10 + k * 12)
            if (res <= 0 || min == max) out += min
            else STANDARD_RATES.filter { it in min..max && (it - min) % res == 0 }.forEach { out += it }
        }
        return out.distinct()
    }

    private val STANDARD_RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000)

    /** The DAC while Side A holds it: the connection and the interfaces taken from Android. */
    private class Held(val device: UsbDevice, val conn: UsbDeviceConnection, val desc: UsbAudioDescriptors, val ac: UsbInterface, val claimed: MutableSet<Int>)

    private var held: Held? = null

    val holding: Boolean get() = held != null

    /**
     * Streams to [d] at [rate] in [alt]'s format, taking the DAC from Android first if Side A
     * doesn't hold it yet. Between songs the DAC stays held and only the rate and format change.
     * Throws with a reason the UI can show.
     */
    @Synchronized
    fun open(d: UsbDevice, alt: UsbAltSetting, format: SampleFormat, rate: Int): ExclusiveOutput {
        active?.stream?.release()
        active = null
        busyUntil = System.currentTimeMillis() + 5_000
        val h = held?.takeIf { it.device.deviceName == d.deviceName } ?: run {
            releaseAll()
            val (desc, _) = describe(d) ?: throw IOException("Can't read the DAC's descriptors")
            val conn = usb.openDevice(d) ?: throw IOException("Can't open the DAC")
            val ac = findInterface(d, desc.controlInterface, 0)
            if (ac == null) { conn.close(); throw IOException("DAC control interface not found") }
            // force = true detaches Android's USB audio driver; that's the exclusive part.
            if (!conn.claimInterface(ac, true)) { conn.close(); throw IOException("Another app holds the DAC") }
            val claimed = linkedSetOf(desc.controlInterface)
            // Every audio interface goes, not just the one we stream to: Linux keeps the sound
            // card half-alive while any of them stays bound, and then can't bring it back for
            // Android when Side A lets go.
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                if (intf.alternateSetting != 0 || intf.interfaceClass != UsbConstants.USB_CLASS_AUDIO || intf.id in claimed) continue
                if (conn.claimInterface(intf, true)) claimed += intf.id
            }
            Held(d, conn, desc, ac, claimed).also { held = it }
        }
        val conn = h.conn
        val asAlt = findInterface(d, alt.interfaceNumber, alt.alternate)
        val as0 = findInterface(d, alt.interfaceNumber, 0)
        if (asAlt == null || as0 == null) throw IOException("DAC streaming interface not found")
        if (alt.interfaceNumber !in h.claimed) {
            if (!conn.claimInterface(as0, true)) throw IOException("Another app holds the DAC")
            h.claimed += alt.interfaceNumber
        }
        conn.setInterface(as0) // idle while the clock changes
        if (alt.uac2) {
            val clock = h.desc.clockFor(alt) ?: throw IOException("DAC has no clock to set")
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte())
            if (conn.controlTransfer(0x21, 0x01, 0x0100, (clock shl 8) or h.desc.controlInterface, data, 4, 1000) < 0) {
                throw IOException("DAC refused ${rate / 1000.0} kHz")
            }
            if (!conn.setInterface(asAlt)) throw IOException("DAC refused the format")
        } else {
            if (!conn.setInterface(asAlt)) throw IOException("DAC refused the format")
            val data = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
            conn.controlTransfer(0x22, 0x01, 0x0100, alt.endpointOut, data, 3, 1000) // some UAC1 DACs have one fixed rate
        }
        val fd = conn.fileDescriptor
        val speed = UsbStream.nativeSpeed(fd)
        val high = speed >= 3 || (speed < 0 && alt.uac2)
        // High speed: 8000 microframes/s, bInterval is an exponent. Full speed: 1000 frames/s.
        val microframesPerPacket = if (high) 1 shl (alt.interval - 1).coerceIn(0, 3) else 1
        val packetsPerSecond = if (high) 8000 / microframesPerPacket else 1000
        val stream = UsbStream.open(
            fd, alt.endpointOut, alt.maxPacketBytes, alt.feedbackEndpoint ?: 0, alt.feedbackMaxPacket,
            packetsPerSecond, microframesPerPacket, rate, alt.subslotBytes * alt.channels,
        )
        val name = d.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        return ExclusiveOutput(stream, format, rate, alt.channels, name, alt.bitResolution).also { active = it }
    }

    /** Stops streaming and hands the DAC back to Android. */
    @Synchronized
    fun releaseAll() {
        active?.stream?.release()
        active = null
        val h = held ?: return
        held = null
        busyUntil = System.currentTimeMillis() + 5_000
        val fd = h.conn.fileDescriptor
        for (id in h.claimed) {
            findInterface(h.device, id, 0)?.let { intf ->
                if (id != h.desc.controlInterface) runCatching { h.conn.setInterface(intf) }
                h.conn.releaseInterface(intf)
            }
        }
        // A USB reset, always: Linux unbinds and probes every interface afresh, as if the DAC were
        // replugged, and Android sets its audio up from scratch. Only reattaching the driver
        // rebuilds Linux's sound card, but Android keeps talking to the old one: the DAC is still
        // listed, and other apps play into silence.
        val r = UsbStream.nativeReset(fd)
        handBack = if (r == 0) {
            "reset"
        } else {
            // No reset allowed: rebind the driver ourselves, control interface first (binding it
            // rebuilds the whole card). 1 means a driver took it; EBUSY that one already has it.
            var bound = UsbStream.nativeReattachKernel(fd, h.desc.controlInterface)
            for (attempt in 0 until 3) {
                if (bound > 0 || bound == -EBUSY) break
                Thread.sleep(100)
                bound = UsbStream.nativeReattachKernel(fd, h.desc.controlInterface)
            }
            for (id in h.claimed) if (id != h.desc.controlInterface) UsbStream.nativeReattachKernel(fd, id)
            Log.w(TAG, "USB reset refused ($r); driver reattach: $bound")
            if (bound > 0 || bound == -EBUSY) "reattached" else "failed ($r, $bound)"
        }
        h.conn.close()
        onReleased?.invoke()
    }

    /** Called after Side A let go of a DAC it held. */
    var onReleased: (() -> Unit)? = null

    /** How the last hand-back went, for the check that Android really took the DAC back. */
    @Volatile var handBack: String? = null
        private set

    /** The DAC was unplugged: forget it without talking to it. */
    @Synchronized
    fun detached(d: UsbDevice) {
        if (held?.device?.deviceName != d.deviceName) return
        active?.stream?.release()
        active = null
        held?.conn?.close()
        held = null
        formatsCache.remove(d.deviceName)
    }

    private fun findInterface(d: UsbDevice, id: Int, alt: Int): UsbInterface? =
        (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id == id && it.alternateSetting == alt }
}
