package io.github.momonekomata.sidea.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class GoogleCastTest {
    @Test fun castMessageRoundTrips() {
        val m = CastWire.Message("sender-0", "receiver-0", CastSession.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1,"name":"Wohnzimmer – ♪"}""")
        val framed = CastWire.encode(m)
        val len = ((framed[0].toInt() and 0xFF) shl 24) or ((framed[1].toInt() and 0xFF) shl 16) or ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF)
        assertEquals(framed.size - 4, len)
        assertEquals(m, CastWire.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test fun castMessageMatchesTheProtobufLayout() {
        // protocol_version=0, source "a", destination "b", namespace "n", payload_type=0, payload "{}".
        val body = CastWire.encode(CastWire.Message("a", "b", "n", "{}")).let { it.copyOfRange(4, it.size) }
        val want = byteArrayOf(0x08, 0, 0x12, 1, 'a'.code.toByte(), 0x1a, 1, 'b'.code.toByte(), 0x22, 1, 'n'.code.toByte(), 0x28, 0, 0x32, 2, '{'.code.toByte(), '}'.code.toByte())
        assertArrayEquals(want, body)
    }

    @Test fun longPayloadsUseMultiByteLengths() {
        val payload = "x".repeat(70_000)
        val m = CastWire.Message("s", "d", CastSession.NS_MEDIA, payload)
        val framed = CastWire.encode(m)
        assertEquals(m, CastWire.decode(framed.copyOfRange(4, framed.size)))
    }

    @Test fun decodeSkipsFieldsItDoesNotKnow() {
        val known = CastWire.encode(CastWire.Message("s", "d", "n", "p")).let { it.copyOfRange(4, it.size) }
        // A binary payload (field 7, bytes) and a fixed32 field 9 the decoder should step over.
        val extra = byteArrayOf(0x3a, 3, 1, 2, 3, 0x4d, 9, 9, 9, 9)
        assertEquals(CastWire.Message("s", "d", "n", "p"), CastWire.decode(known + extra))
    }

    @Test fun mdnsQueryAsksForCastDevices() {
        val q = Mdns.query()
        assertEquals(1, q[5].toInt()) // one question
        val name = String(q, 12, q.size - 12 - 5, Charsets.US_ASCII)
        assertEquals("\u000b_googlecast\u0004_tcp\u0005local", name)
        assertEquals(0, q[q.size - 5].toInt())
        assertEquals(12, q[q.size - 3].toInt()) // PTR
        assertEquals(0x80, q[q.size - 2].toInt() and 0xFF) // unicast answer, please
    }

    @Test fun mdnsAnswerGivesNameModelAndAddress() {
        val found = Mdns.parse(answer(), null)
        assertEquals(1, found.size)
        val f = found[0]
        assertEquals("Living Room TV", f.name)
        assertEquals("Chromecast", f.model)
        assertEquals("abc123", f.id)
        assertEquals("192.168.43.50", f.address.hostAddress)
        assertEquals(8009, f.port)
    }

    @Test fun mdnsAnswerWithoutAddressUsesTheSender() {
        val from = InetAddress.getByName("192.168.43.77") as java.net.Inet4Address
        val f = Mdns.parse(answer(withA = false), from).single()
        assertEquals("192.168.43.77", f.address.hostAddress)
    }

    @Test fun mdnsIgnoresOtherServicesAndJunk() {
        assertTrue(Mdns.parse(ByteArray(5), null).isEmpty())
        assertTrue(Mdns.parse(answer(service = "_airplay._tcp.local"), null).isEmpty())
    }

    @Test fun formatsCastDevicesCannotPlay() {
        assertNotNull(Cast.castUnsupported("ALAC"))
        assertNotNull(Cast.castUnsupported("DSD"))
        assertNotNull(Cast.castUnsupported("AIFF"))
        assertNull(Cast.castUnsupported("FLAC"))
        assertNull(Cast.castUnsupported("AAC"))
        assertNull(Cast.castUnsupported(""))
    }

    /** A Cast device's mDNS answer, with name compression as real devices send it. */
    private fun answer(withA: Boolean = true, service: String = Mdns.SERVICE): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun labels(name: String) { name.split('.').forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) } }
        fun pointer(at: Int) { out.write(0xC0 or (at ushr 8)); out.write(at and 0xFF) }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(if (withA) 3 else 2)
        val serviceAt = out.size()
        labels(service); out.write(0)
        u16(12); u16(1); u32(120)
        val instance = "Chromecast-abc123"
        u16(1 + instance.length + 2)
        val instanceAt = out.size()
        labels(instance); pointer(serviceAt)
        // SRV: priority, weight, port, target "abc123.local"
        pointer(instanceAt); u16(33); u16(0x8001); u32(120)
        u16(6 + 1 + 6 + 1 + 5 + 1)
        u16(0); u16(0); u16(8009)
        val targetAt = out.size()
        labels("abc123.local"); out.write(0)
        // TXT
        val txt = listOf("id=abc123", "md=Chromecast", "fn=Living Room TV")
        pointer(instanceAt); u16(16); u16(0x8001); u32(4500)
        u16(txt.sumOf { it.length + 1 })
        txt.forEach { out.write(it.length); out.write(it.toByteArray()) }
        if (withA) {
            pointer(targetAt); u16(1); u16(0x8001); u32(120); u16(4)
            out.write(byteArrayOf(192.toByte(), 168.toByte(), 43, 50))
        }
        return out.toByteArray()
    }
}
