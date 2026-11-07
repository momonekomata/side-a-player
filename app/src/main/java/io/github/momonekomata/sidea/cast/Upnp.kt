package io.github.momonekomata.sidea.cast

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The pieces of UPnP AV that casting needs, kept free of Android so they can be unit tested:
 * SSDP search messages and replies, a renderer's device description, SOAP calls and their
 * replies, DIDL-Lite metadata, and UPnP's H:MM:SS times.
 */
object Upnp {
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900

    /** An SSDP search for renderers; [host] is the multicast address, or a device's own for a unicast search. */
    fun mSearch(host: String = "$SSDP_ADDRESS:$SSDP_PORT", st: String = RENDERER) =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $host\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: Android/14 UPnP/1.1 WALKDECK/1.0\r\n\r\n"

    /** The LOCATION of a search reply (or a NOTIFY), if the message has one. */
    fun location(message: String): String? = headers(message)["location"]?.takeIf { it.startsWith("http") }

    /** An HTTP-style message's headers, names lower-cased. */
    fun headers(message: String): Map<String, String> =
        message.split("\r\n", "\n").drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

    /** A renderer as its device description describes it. */
    data class Description(
        val name: String,
        val udn: String,
        val manufacturer: String,
        val model: String,
        val avTransport: Service,
        val renderingControl: Service?,
        val connectionManager: Service?,
    )

    data class Service(val type: String, val controlUrl: URL)

    /**
     * Reads a device description fetched from [location]. Finds the MediaRenderer (top level or
     * embedded) and its AVTransport; null when there's no renderer with one.
     */
    fun parseDescription(xml: ByteArray, location: URL): Description? {
        val doc = parse(xml) ?: return null
        val root = doc.documentElement
        val base = root.child("URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URL(it) }.getOrNull() } ?: location
        val devices = root.getElementsByTagName("device")
        for (i in 0 until devices.length) {
            val d = devices.item(i) as Element
            if (d.child("deviceType")?.textContent?.contains("MediaRenderer") != true) continue
            val services = d.child("serviceList")?.let { list -> list.childElements("service") }.orEmpty()
            fun service(kind: String) = services.firstOrNull { it.child("serviceType")?.textContent?.contains(":service:$kind:") == true }?.let { s ->
                val type = s.child("serviceType")!!.textContent.trim()
                val control = s.child("controlURL")?.textContent?.trim() ?: return@let null
                Service(type, URL(base, control))
            }
            val avt = service("AVTransport") ?: continue
            return Description(
                name = d.child("friendlyName")?.textContent?.trim().orEmpty().ifEmpty { "Renderer" },
                udn = d.child("UDN")?.textContent?.trim().orEmpty(),
                manufacturer = d.child("manufacturer")?.textContent?.trim().orEmpty(),
                model = d.child("modelName")?.textContent?.trim().orEmpty(),
                avTransport = avt,
                renderingControl = service("RenderingControl"),
                connectionManager = service("ConnectionManager"),
            )
        }
        return null
    }

    /** A SOAP request body for [action] on [serviceType]; the argument values are escaped here. */
    fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">" +
            args.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"

    /** A SOAP reply's out-arguments, or the UPnP error it carries. */
    sealed interface SoapResult {
        data class Ok(val values: Map<String, String>) : SoapResult
        data class Fault(val code: String, val description: String) : SoapResult
    }

    fun parseSoap(xml: ByteArray): SoapResult {
        val doc = parse(xml) ?: return SoapResult.Fault("", "Unreadable reply")
        val err = doc.getElementsByTagNameNS("*", "UPnPError").item(0) as? Element
            ?: doc.getElementsByTagName("UPnPError").item(0) as? Element
        if (err != null) {
            return SoapResult.Fault(
                err.childLocal("errorCode")?.textContent?.trim().orEmpty(),
                err.childLocal("errorDescription")?.textContent?.trim().orEmpty(),
            )
        }
        val body = doc.getElementsByTagNameNS("*", "Body").item(0) as? Element ?: return SoapResult.Ok(emptyMap())
        val response = body.childElementsAll().firstOrNull() ?: return SoapResult.Ok(emptyMap())
        return SoapResult.Ok(response.childElementsAll().associate { (it.localName ?: it.tagName.substringAfter(':')) to it.textContent.trim() })
    }

    /** DIDL-Lite for one song, for SetAVTransportURI's CurrentURIMetaData. */
    fun didl(title: String, artist: String, album: String, url: String, mime: String, durationMs: Long, sizeBytes: Long, artUrl: String?): String {
        val res = buildString {
            append("<res protocolInfo=\"http-get:*:").append(escape(mime)).append(":DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\"")
            if (durationMs > 0) append(" duration=\"").append(formatTime(durationMs, millis = true)).append('"')
            if (sizeBytes > 0) append(" size=\"").append(sizeBytes).append('"')
            append('>').append(escape(url)).append("</res>")
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            (if (artist.isNotBlank()) "<dc:creator>${escape(artist)}</dc:creator><upnp:artist>${escape(artist)}</upnp:artist>" else "") +
            (if (album.isNotBlank()) "<upnp:album>${escape(album)}</upnp:album>" else "") +
            (if (artUrl != null) "<upnp:albumArtURI>${escape(artUrl)}</upnp:albumArtURI>" else "") +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            res +
            "</item></DIDL-Lite>"
    }

    /** 83_500 ms → "0:01:23" (or "0:01:23.500" with [millis]). */
    fun formatTime(ms: Long, millis: Boolean = false): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val base = "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        return if (millis) base + ".%03d".format(ms % 1000) else base
    }

    /** "0:01:23", "01:23.5", "NOT_IMPLEMENTED" → ms, or -1 when it isn't a time. */
    fun parseTime(text: String?): Long {
        val t = text?.trim() ?: return -1
        val parts = t.split(':')
        if (parts.size !in 2..3) return -1
        return runCatching {
            val nums = parts.map { it.toDouble() }
            val secs = if (nums.size == 3) nums[0] * 3600 + nums[1] * 60 + nums[2] else nums[0] * 60 + nums[1]
            (secs * 1000).toLong()
        }.getOrDefault(-1)
    }

    fun escape(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    private fun parse(xml: ByteArray) = runCatching {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Device descriptions are plain XML; never fetch a DTD or expand outside entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }.getOrNull()

    private fun Element.childElementsAll(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.childElements(name: String) = childElementsAll().filter { (it.localName ?: it.tagName) == name }
    private fun Element.child(name: String) = childElements(name).firstOrNull()
    private fun Element.childLocal(name: String) = childElementsAll().firstOrNull { (it.localName ?: it.tagName.substringAfter(':')) == name }
}
