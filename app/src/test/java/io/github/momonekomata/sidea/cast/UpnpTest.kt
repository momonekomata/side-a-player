package io.github.momonekomata.sidea.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class UpnpTest {
    @Test fun searchMessageAsksForRenderers() {
        val m = Upnp.mSearch()
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(m.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
        assertTrue(Upnp.mSearch("192.168.43.17:1900").contains("HOST: 192.168.43.17:1900\r\n"))
    }

    @Test fun readsLocationFromSearchReply() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://192.168.43.17:49152/uuid-abc/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.43.17:49152/uuid-abc/description.xml", Upnp.location(reply))
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }

    // A Poly-like description (upmpdcli): the renderer at the top level, relative control URLs.
    private val polyLike = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Chord Poly</friendlyName>
            <manufacturer>Chord Electronics</manufacturer>
            <modelName>Poly</modelName>
            <UDN>uuid:5b1c6a2e-poly</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/ctl/RenderingControl</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/ctl/AVTransport</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent().toByteArray()

    @Test fun parsesRendererDescription() {
        val d = Upnp.parseDescription(polyLike, URL("http://192.168.43.17:49152/uuid-5b1c/description.xml"))
        assertNotNull(d)
        d!!
        assertEquals("Chord Poly", d.name)
        assertEquals("Poly", d.model)
        assertEquals("uuid:5b1c6a2e-poly", d.udn)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", d.avTransport.type)
        assertEquals("http://192.168.43.17:49152/ctl/AVTransport", d.avTransport.controlUrl.toString())
        assertEquals("http://192.168.43.17:49152/ctl/RenderingControl", d.renderingControl!!.controlUrl.toString())
    }

    @Test fun findsEmbeddedRendererAndHonoursUrlBase() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://10.0.0.5:8080/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>Box</friendlyName>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Box Renderer</friendlyName>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
                        <controlURL>upnp/avt</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent().toByteArray()
        val d = Upnp.parseDescription(xml, URL("http://10.0.0.5:1400/desc.xml"))!!
        assertEquals("Box Renderer", d.name)
        assertEquals("http://10.0.0.5:8080/upnp/avt", d.avTransport.controlUrl.toString())
        assertNull(d.renderingControl)
    }

    @Test fun noRendererNoDescription() {
        val xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType></device></root>".toByteArray()
        assertNull(Upnp.parseDescription(xml, URL("http://x/")))
        assertNull(Upnp.parseDescription("not xml".toByteArray(), URL("http://x/")))
    }

    @Test fun soapEnvelopeEscapesArguments() {
        val e = Upnp.soapEnvelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURIMetaData" to "<DIDL-Lite a=\"b\">&</DIDL-Lite>"))
        assertTrue(e.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(e.contains("<InstanceID>0</InstanceID>"))
        assertTrue(e.contains("<CurrentURIMetaData>&lt;DIDL-Lite a=&quot;b&quot;&gt;&amp;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"))
    }

    @Test fun parsesSoapReplyAndFault() {
        val ok = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:03:58</TrackDuration><RelTime>0:01:23</RelTime><TrackURI>http://h/song/s1.flac</TrackURI>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>""".toByteArray()
        val r = Upnp.parseSoap(ok) as Upnp.SoapResult.Ok
        assertEquals("0:01:23", r.values["RelTime"])
        assertEquals("http://h/song/s1.flac", r.values["TrackURI"])

        val fault = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>""".toByteArray()
        val f = Upnp.parseSoap(fault) as Upnp.SoapResult.Fault
        assertEquals("714", f.code)
        assertEquals("Illegal MIME-type", f.description)
    }

    @Test fun didlCarriesTheSong() {
        val d = Upnp.didl("Here & There", "3 Doors Down", "Away", "http://192.168.43.1:52525/song/s1.flac", "audio/flac", 238_500, 30_000_000, "http://192.168.43.1:52525/art/s1.jpg")
        assertTrue(d.contains("<dc:title>Here &amp; There</dc:title>"))
        assertTrue(d.contains("<upnp:artist>3 Doors Down</upnp:artist>"))
        assertTrue(d.contains("protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01;"))
        assertTrue(d.contains("duration=\"0:03:58.500\""))
        assertTrue(d.contains("size=\"30000000\""))
        assertTrue(d.contains(">http://192.168.43.1:52525/song/s1.flac</res>"))
        assertTrue(d.contains("<upnp:albumArtURI>http://192.168.43.1:52525/art/s1.jpg</upnp:albumArtURI>"))
    }

    @Test fun times() {
        assertEquals("0:01:23", Upnp.formatTime(83_500))
        assertEquals("1:00:00", Upnp.formatTime(3_600_000))
        assertEquals(83_000L, Upnp.parseTime("0:01:23"))
        assertEquals(83_500L, Upnp.parseTime("00:01:23.500"))
        assertEquals(83_000L, Upnp.parseTime("01:23"))
        assertEquals(-1L, Upnp.parseTime("NOT_IMPLEMENTED"))
        assertEquals(-1L, Upnp.parseTime(null))
    }

    @Test fun sniffsMimeTypes() {
        fun h(vararg s: Any): ByteArray = s.flatMap { if (it is String) it.toByteArray().toList() else listOf((it as Int).toByte()) }.toByteArray()
        assertEquals("audio/flac", MediaServer.mimeFromHeader(h("fLaC", 0, 0, 0, 34)))
        assertEquals("audio/wav", MediaServer.mimeFromHeader(h("RIFF", 1, 2, 3, 4, "WAVEfmt ")))
        assertEquals("audio/aiff", MediaServer.mimeFromHeader(h("FORM", 1, 2, 3, 4, "AIFF")))
        assertEquals("audio/mp4", MediaServer.mimeFromHeader(h(0, 0, 0, 32, "ftypM4A ")))
        assertEquals("audio/ogg", MediaServer.mimeFromHeader(h("OggS", 0)))
        assertEquals("audio/dsf", MediaServer.mimeFromHeader(h("DSD ", 28, 0)))
        assertEquals("audio/mpeg", MediaServer.mimeFromHeader(h("ID3", 4, 0)))
        assertEquals("audio/mpeg", MediaServer.mimeFromHeader(h(0xFF, 0xFB, 0x90)))
        assertEquals("audio/aac", MediaServer.mimeFromHeader(h(0xFF, 0xF1, 0x50)))
    }

    @Test fun hotspotNamesAreRecognised() {
        assertEquals("hotspot", Lan.kindOf("ap0"))
        assertEquals("hotspot", Lan.kindOf("swlan0"))
        assertEquals("hotspot", Lan.kindOf("wlan1"))
        assertEquals("Wi-Fi", Lan.kindOf("wlan0"))
    }
}
