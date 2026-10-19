package io.github.momonekomata.sidea.audio.usb

/** One playback alternate setting of a USB Audio streaming interface. */
data class UsbAltSetting(
    val interfaceNumber: Int,
    val alternate: Int,
    val uac2: Boolean,
    val channels: Int,
    /** Bytes per sample slot (2, 3 or 4). */
    val subslotBytes: Int,
    /** Bits actually used (16, 24, 32). */
    val bitResolution: Int,
    /** UAC1 lists its rates here; for UAC2 they come from the clock (empty = ask the clock). */
    val rates: List<Int>,
    /** UAC1 continuous range, when given instead of a list. */
    val rateRange: IntRange?,
    val endpointOut: Int,
    val maxPacketBytes: Int,
    val interval: Int,
    /** 1 async, 2 adaptive, 3 synchronous. */
    val syncType: Int,
    val feedbackEndpoint: Int?,
    val feedbackMaxPacket: Int,
    val terminalLink: Int,
)

/** Everything Side A needs from a USB DAC's descriptors. */
data class UsbAudioDescriptors(
    val controlInterface: Int,
    val uac2: Boolean,
    /** UAC2: input terminal ID → clock entity ID. */
    val terminalClock: Map<Int, Int>,
    /** UAC2: clock selector ID → its input clock IDs. */
    val clockSelectors: Map<Int, List<Int>>,
    val clockSources: Set<Int>,
    val playback: List<UsbAltSetting>,
) {
    /** The clock source that drives [alt], following a selector to its first input. */
    fun clockFor(alt: UsbAltSetting): Int? {
        var id = terminalClock[alt.terminalLink] ?: clockSources.firstOrNull() ?: return null
        repeat(4) {
            if (id in clockSources) return id
            id = clockSelectors[id]?.firstOrNull() ?: return clockSources.firstOrNull()
        }
        return clockSources.firstOrNull()
    }

    companion object {
        private const val INTERFACE = 4
        private const val ENDPOINT = 5
        private const val CS_INTERFACE = 0x24
        private const val AUDIO = 1

        /**
         * Parses the raw configuration descriptors (UsbDeviceConnection.getRawDescriptors).
         * Covers UAC1 and UAC2 Type I PCM; native-DSD formats are skipped (DoP still works).
         */
        fun parse(raw: ByteArray): UsbAudioDescriptors? {
            var p = 0
            var ifNum = -1; var alt = 0; var ifClass = 0; var ifSub = 0; var ifProto = 0
            var control = -1
            var uac2 = false
            val terminalClock = mutableMapOf<Int, Int>()
            val selectors = mutableMapOf<Int, List<Int>>()
            val sources = linkedSetOf<Int>()
            val out = mutableListOf<UsbAltSetting>()

            // Per alternate setting, collected while walking its descriptors.
            var asTerminal = 0; var asPcm = false; var asChannels = 0; var subslot = 0; var bits = 0
            var rates = mutableListOf<Int>(); var range: IntRange? = null
            var epOut = -1; var maxPacket = 0; var interval = 1; var sync = 0
            var epFb: Int? = null; var fbMax = 0

            fun flush() {
                if (ifClass == AUDIO && ifSub == 2 && alt > 0 && epOut >= 0 && asPcm && subslot in 2..4 && asChannels > 0) {
                    out += UsbAltSetting(ifNum, alt, uac2, asChannels, subslot, bits.takeIf { it > 0 } ?: (subslot * 8),
                        rates.toList(), range, epOut, maxPacket, interval, sync, epFb, fbMax, asTerminal)
                }
                asTerminal = 0; asPcm = false; asChannels = 0; subslot = 0; bits = 0
                rates = mutableListOf(); range = null
                epOut = -1; maxPacket = 0; interval = 1; sync = 0; epFb = null; fbMax = 0
            }

            while (p + 2 <= raw.size) {
                val len = raw[p].toInt() and 0xFF
                if (len < 2 || p + len > raw.size) break
                val type = raw[p + 1].toInt() and 0xFF
                val d = raw.copyOfRange(p, p + len)
                fun u8(i: Int) = if (i < d.size) d[i].toInt() and 0xFF else 0
                fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
                fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
                when (type) {
                    INTERFACE -> {
                        flush()
                        ifNum = u8(2); alt = u8(3); ifClass = u8(5); ifSub = u8(6); ifProto = u8(7)
                        if (ifClass == AUDIO && ifSub == 1) { control = ifNum; uac2 = ifProto == 0x20 }
                    }
                    CS_INTERFACE -> if (ifClass == AUDIO) {
                        val sub = u8(2)
                        if (ifSub == 1) {
                            when (sub) {
                                0x02 -> if (uac2) terminalClock[u8(3)] = u8(7) // input terminal → bCSourceID
                                0x0A -> if (uac2) sources += u8(3)
                                0x0B -> if (uac2) selectors[u8(3)] = (0 until u8(4)).map { u8(5 + it) }
                            }
                        } else if (ifSub == 2) {
                            when (sub) {
                                0x01 -> if (uac2) {
                                    asTerminal = u8(3)
                                    asPcm = u8(5) == 1 && (u16(6) and 1) != 0 // Type I, PCM
                                    asChannels = u8(10)
                                } else {
                                    asTerminal = u8(3)
                                    asPcm = u16(5) == 0x0001
                                }
                                0x02 -> if (uac2) {
                                    if (u8(3) == 1) { subslot = u8(4); bits = u8(5) }
                                } else if (u8(3) == 1) {
                                    asChannels = u8(4); subslot = u8(5); bits = u8(6)
                                    val n = u8(7)
                                    if (n == 0) range = u24(8)..u24(11)
                                    else for (i in 0 until n) rates += u24(8 + i * 3)
                                }
                            }
                        }
                    }
                    ENDPOINT -> if (ifClass == AUDIO && ifSub == 2) {
                        val addr = u8(2)
                        val attr = u8(3)
                        val wMax = u16(4)
                        val size = (wMax and 0x7FF) * (1 + ((wMax shr 11) and 3))
                        val usage = (attr shr 4) and 3
                        if (attr and 3 == 1) { // isochronous
                            if (addr and 0x80 == 0 && usage == 0) {
                                epOut = addr; maxPacket = size; interval = u8(6).coerceAtLeast(1); sync = (attr shr 2) and 3
                            } else if (addr and 0x80 != 0 && usage == 1) {
                                epFb = addr; fbMax = size
                            }
                        }
                    }
                }
                p += len
            }
            flush()
            if (control < 0 || out.isEmpty()) return null
            return UsbAudioDescriptors(control, uac2, terminalClock, selectors, sources, out)
        }
    }
}
