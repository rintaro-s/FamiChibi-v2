package com.daijobu.famichibi.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds FamiChibi hosts on the LAN via UDP broadcast (server listens on 47810). */
object Discovery {
    private const val PORT = 47810

    suspend fun scan(timeoutMs: Int = 1500): List<HostInfo> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, HostInfo>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val payload = "FAMICHIBI_DISCOVER".toByteArray()
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                repeat(2) {
                    for (t in targets) runCatching { sock.send(DatagramPacket(payload, payload.size, t, PORT)) }
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val o = JsonCfg.parseToJsonElement(String(p.data, 0, p.length)).jsonObject
                        if (o.str("app") == "famichibi") {
                            val info = HostInfo(app = "famichibi", name = o.str("name").orEmpty(), version = o.str("version").orEmpty(), url = o.str("url").orEmpty())
                            found[info.url] = info
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    suspend fun probe(url: String): HostInfo? = runCatching {
        val r = Api(url).get("/api/info")
        HostInfo(app = r.str("app").orEmpty(), name = r.str("name").orEmpty(), version = r.str("version").orEmpty(), url = Api.normalizeBase(url))
    }.getOrNull()?.takeIf { it.app == "famichibi" }
}
