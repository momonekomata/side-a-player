package io.github.momonekomata.sidea.cast

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Finding Chromecasts (and other Google Cast devices) with mDNS / DNS-SD: a query for
 * "_googlecast._tcp.local", sent on each of the phone's networks by name (as [Lan.search] does
 * for UPnP, and for the same reason: the phone's hotspot), and straight to every address on a
 * small network such as a hotspot. Cast devices answer a query sent from an ordinary port
 * ("legacy unicast") to that port, so no listening on 5353 is needed.
 */
object Mdns {
    const val SERVICE = "_googlecast._tcp.local"
    private const val MDNS_ADDRESS = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    /** A Cast device as its mDNS answer describes it. */
    data class Found(val name: String, val model: String, val id: String, val address: Inet4Address, val port: Int)

    /** A DNS query for [service]'s PTR records, asking for a unicast answer. */
    fun query(service: String = SERVICE): ByteArray {
        val out = ByteArrayOutputStream()
        // ID 0, flags 0 (a standard query), one question.
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, service)
        out.write(byteArrayOf(0, TYPE_PTR.toByte()))
        // Class IN with the top bit set: "unicast answer, please".
        out.write(byteArrayOf(0x80.toByte(), 0x01))
        return out.toByteArray()
    }

    /**
     * Reads an mDNS answer: the Cast instances it names, each with its friendly name (TXT fn),
     * model (md), id, address and port. [from] is the sender, the address when no A record came.
     */
    fun parse(packet: ByteArray, from: Inet4Address?): List<Found> {
        val r = Reader(packet)
        if (packet.size < 12) return emptyList()
        r.pos = 4
        val qd = r.u16()
        val an = r.u16()
        val ns = r.u16()
        val ar = r.u16()
        repeat(qd) { r.name(); r.pos += 4 }
        val ptrs = ArrayList<String>()
        val srv = HashMap<String, Pair<String, Int>>()
        val txt = HashMap<String, Map<String, String>>()
        val a = HashMap<String, Inet4Address>()
        repeat(an + ns + ar) {
            if (r.pos >= packet.size) return@repeat
            val name = r.name()
            val type = r.u16()
            r.u16() // class
            r.pos += 4 // ttl
            val len = r.u16()
            val end = r.pos + len
            when (type) {
                TYPE_PTR -> if (name.equals(SERVICE, ignoreCase = true)) ptrs += r.name()
                TYPE_SRV -> {
                    r.pos += 4 // priority, weight
                    val port = r.u16()
                    srv[name.lowercase()] = r.name() to port
                }
                TYPE_TXT -> {
                    val m = HashMap<String, String>()
                    while (r.pos < end) {
                        val l = packet[r.pos].toInt() and 0xFF
                        val s = String(packet, r.pos + 1, minOf(l, end - r.pos - 1), Charsets.UTF_8)
                        r.pos += 1 + l
                        val i = s.indexOf('=')
                        if (i > 0) m[s.substring(0, i).lowercase()] = s.substring(i + 1)
                    }
                    txt[name.lowercase()] = m
                }
                TYPE_A -> if (len == 4) a[name.lowercase()] = InetAddress.getByAddress(packet.copyOfRange(r.pos, r.pos + 4)) as Inet4Address
            }
            r.pos = end
        }
        // Some devices answer with only SRV/TXT for the instance and no PTR: those count too.
        val instances = (ptrs + srv.keys + txt.keys).map { it.lowercase() }.distinct().filter { it.endsWith(SERVICE.lowercase()) }
        return instances.mapNotNull { inst ->
            val t = txt[inst].orEmpty()
            val (target, port) = srv[inst] ?: (null to 8009)
            val address = target?.let { a[it.lowercase()] } ?: from ?: return@mapNotNull null
            Found(
                name = t["fn"]?.takeIf { it.isNotBlank() } ?: inst.removeSuffix("." + SERVICE.lowercase()),
                model = t["md"].orEmpty(),
                id = t["id"]?.takeIf { it.isNotBlank() } ?: inst,
                address = address,
                port = port,
            )
        }
    }

    /**
     * Asks every local network for Cast devices for [timeoutMs]: to the mDNS group on each
     * interface by name, and straight to every address on small networks (a hotspot). [extra]
     * addresses (typed in) are always asked directly.
     */
    fun search(timeoutMs: Long, extra: List<Inet4Address> = emptyList()): List<Found> {
        val found = Collections.synchronizedMap(LinkedHashMap<String, Found>())
        val q = query()
        val workers = Lan.networks().map { net ->
            thread(name = "mdns-${net.iface.name}", isDaemon = true) {
                runCatching {
                    MulticastSocket(InetSocketAddress(net.address, 0)).use { s ->
                        s.networkInterface = net.iface
                        s.timeToLive = 255
                        s.soTimeout = 250
                        fun send(to: InetAddress) = runCatching { s.send(DatagramPacket(q, q.size, to, MDNS_PORT)) }
                        val group = InetAddress.getByName(MDNS_ADDRESS)
                        send(group)
                        extra.filter { net.contains(it) }.forEach { send(it) }
                        net.hosts().forEach { send(it) }
                        val deadline = System.currentTimeMillis() + timeoutMs
                        val buf = ByteArray(9000)
                        var resent = false
                        while (System.currentTimeMillis() < deadline) {
                            val p = DatagramPacket(buf, buf.size)
                            try {
                                s.receive(p)
                            } catch (_: SocketTimeoutException) {
                                if (!resent && System.currentTimeMillis() > deadline - timeoutMs / 2) { resent = true; send(group) }
                                continue
                            }
                            parse(p.data.copyOf(p.length), p.address as? Inet4Address).forEach { found[it.id] = it }
                        }
                    }
                }
            }
        }
        workers.forEach { it.join(timeoutMs + 1500) }
        return found.values.toList()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        name.trimEnd('.').split('.').forEach { label ->
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    /** Reads DNS names, following compression pointers. */
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u16(): Int = ((b[pos].toInt() and 0xFF) shl 8 or (b[pos + 1].toInt() and 0xFF)).also { pos += 2 }
        fun name(): String {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (p < b.size) {
                val len = b[p].toInt() and 0xFF
                if (len == 0) { p++; break }
                if (len and 0xC0 == 0xC0) {
                    val target = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                    if (!jumped) pos = p + 2
                    jumped = true
                    p = target
                    if (++hops > 20) break
                    continue
                }
                labels += String(b, p + 1, len, Charsets.UTF_8)
                p += 1 + len
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }
}
