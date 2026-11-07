package io.github.momonekomata.sidea.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The phone's own networks, and talking to devices on them on purpose.
 *
 * The phone's hotspot is why this exists. With the hotspot on, Android's default network is the
 * mobile data, and a socket that isn't told otherwise sends a multicast search out there, where
 * no renderer is: BubbleUPnP and the like then can't find a Poly sitting on the hotspot. Here
 * every search goes out on each local network by name, and every request to a device leaves from
 * the phone's own address on that device's network.
 */
object Lan {
    /** One of the phone's IPv4 networks: its interface, the phone's address there, the prefix. */
    data class Net(val iface: NetworkInterface, val address: Inet4Address, val prefix: Int) {
        private val mask get() = if (prefix == 0) 0 else -1 shl (32 - prefix)
        fun contains(ip: Inet4Address) = (ip.toInt() and mask) == (address.toInt() and mask)
        /** Every other address on it, for a direct search; empty when the network is too big to sweep. */
        fun hosts(): List<Inet4Address> {
            if (prefix < 22) return emptyList()
            val first = address.toInt() and mask
            val count = (1 shl (32 - prefix)) - 2
            return (1..count).map { (first + it).toInet4() }.filter { it != address }
        }
        /** "hotspot", "Wi-Fi" or the interface's own name. */
        val kind get() = kindOf(iface.name)
    }

    fun networks(): List<Net> = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        .filter { ni -> runCatching { ni.isUp && !ni.isLoopback && !ni.isPointToPoint }.getOrDefault(false) && !isMobileData(ni.name) }
        .flatMap { ni -> ni.interfaceAddresses.mapNotNull { ia -> (ia.address as? Inet4Address)?.let { Net(ni, it, ia.networkPrefixLength.toInt()) } } }
        .filter { it.prefix in 8..30 }
        // The hotspot first: that's where a phone-tethered player sits.
        .sortedBy { if (it.kind == "hotspot") 0 else 1 }

    /** The phone's address on the network [remote] is on, to reach it from (and to hand it URLs on). */
    fun localFor(remote: Inet4Address): Inet4Address? = networks().firstOrNull { it.contains(remote) }?.address

    fun kindOf(name: String) = when {
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name == "wlan1" || name.startsWith("wigig") -> "hotspot"
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("eth") -> "Ethernet"
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB tether"
        else -> name
    }

    private fun isMobileData(name: String) =
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("clat") || name.startsWith("v4-") ||
            name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("ipsec") || name.startsWith("seth")

    // ---- SSDP search ----

    /**
     * Searches every local network for renderers, for [timeoutMs]: a multicast search sent out on
     * each interface by name, and, on networks small enough (a hotspot is), the same search sent
     * straight to every address, which finds devices even where multicast is dropped. [extra]
     * addresses (one typed in) are always asked directly. Returns each reply's LOCATION.
     */
    fun search(timeoutMs: Long, extra: List<Inet4Address> = emptyList(), sweep: Boolean = true): Set<String> {
        val found = Collections.synchronizedSet(LinkedHashSet<String>())
        val nets = networks()
        val workers = nets.map { net ->
            thread(name = "ssdp-${net.iface.name}", isDaemon = true) {
                runCatching { searchOn(net, timeoutMs, extra.filter { net.contains(it) }, sweep, found) }
            }
        }
        // A typed address on no network of ours (a routed one): ask it from wherever the phone can.
        val stray = extra.filter { ip -> nets.none { it.contains(ip) } }
        val strayWorker = if (stray.isEmpty()) null else thread(isDaemon = true) {
            runCatching {
                java.net.DatagramSocket().use { s ->
                    s.soTimeout = 250
                    stray.forEach { ip -> send(s, Upnp.mSearch("${ip.hostAddress}:${Upnp.SSDP_PORT}"), ip) }
                    receive(s, System.currentTimeMillis() + timeoutMs, found)
                }
            }
        }
        workers.forEach { it.join(timeoutMs + 1500) }
        strayWorker?.join(timeoutMs + 1500)
        return found.toSet()
    }

    private fun searchOn(net: Net, timeoutMs: Long, extra: List<Inet4Address>, sweep: Boolean, found: MutableSet<String>) {
        MulticastSocket(InetSocketAddress(net.address, 0)).use { s ->
            s.networkInterface = net.iface
            s.timeToLive = 4
            s.soTimeout = 250
            val group = InetAddress.getByName(Upnp.SSDP_ADDRESS)
            val deadline = System.currentTimeMillis() + timeoutMs
            // The multicast search twice (UDP can drop one), the direct ones once.
            send(s, Upnp.mSearch(), group)
            extra.forEach { send(s, Upnp.mSearch("${it.hostAddress}:${Upnp.SSDP_PORT}"), it) }
            if (sweep) net.hosts().forEach { ip -> send(s, Upnp.mSearch("${ip.hostAddress}:${Upnp.SSDP_PORT}"), ip) }
            var resent = false
            while (System.currentTimeMillis() < deadline) {
                receive(s, minOf(deadline, System.currentTimeMillis() + 700), found)
                if (!resent) {
                    resent = true
                    send(s, Upnp.mSearch(), group)
                }
            }
        }
    }

    private fun send(s: java.net.DatagramSocket, msg: String, to: InetAddress) {
        val b = msg.toByteArray()
        runCatching { s.send(DatagramPacket(b, b.size, to, Upnp.SSDP_PORT)) }
    }

    private fun receive(s: java.net.DatagramSocket, until: Long, found: MutableSet<String>) {
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < until) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            if (!text.startsWith("HTTP/1.1 200") && !text.startsWith("NOTIFY")) continue
            val h = Upnp.headers(text)
            val kind = h["st"] ?: h["nt"] ?: ""
            if (!kind.contains("MediaRenderer", ignoreCase = true) && !kind.contains("AVTransport", ignoreCase = true) && kind != "ssdp:all") continue
            Upnp.location(text)?.let { found.add(it) }
        }
    }

    // ---- HTTP to a device ----

    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    /**
     * One HTTP request, over a socket that leaves from the phone's address on the device's own
     * network (see the note at the top). Small and strict: Connection: close, a body by length,
     * chunked, or to the end.
     */
    fun http(url: URL, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, timeoutMs: Int = 6000): Response {
        val host = InetAddress.getByName(url.host)
        val port = if (url.port > 0) url.port else 80
        Socket().use { s ->
            (host as? Inet4Address)?.let { localFor(it) }?.let { s.bind(InetSocketAddress(it, 0)) }
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val path = url.file.ifEmpty { "/" }
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(url.host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
                .append("User-Agent: Android/14 UPnP/1.1 WALKDECK/1.0\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(BufferedInputStream(s.getInputStream()))
        }
    }

    private fun readResponse(input: InputStream): Response {
        val status = readLine(input) ?: throw java.io.IOException("No reply")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("Bad reply: $status")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = ByteArrayOutputStream()
        val length = headers["content-length"]?.toLongOrNull()
        when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> {
                while (true) {
                    val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                    if (size == 0) break
                    out.write(readN(input, size))
                    readLine(input)
                }
            }
            length != null -> out.write(readN(input, length.toInt()))
            else -> input.copyTo(out)
        }
        return Response(code, headers, out.toByteArray())
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = input.read(b, done, n - done)
            if (r < 0) break
            done += r
        }
        return if (done == n) b else b.copyOf(done)
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.write(c)
        }
        return sb.toString("UTF-8")
    }

    private fun Inet4Address.toInt(): Int = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    private fun Int.toInet4(): Inet4Address = InetAddress.getByAddress(byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())) as Inet4Address
}
