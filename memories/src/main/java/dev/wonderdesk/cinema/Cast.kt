package dev.wonderdesk.cinema

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.*
import java.util.Collections

/** A TV or other screen that accepts DLNA / UPnP video. */
data class Tv(val name: String, val control: String)

/**
 * Casting with no Google services: the TV is found with SSDP, and told to play a link to a small web server inside Cinema
 * that hands out the video (or to play a network stream's own address). Works with most smart TVs, game consoles and media boxes.
 */
object Cast {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Looks for screens on the Wi-Fi for [ms] milliseconds, or asks one address directly when [address] is given. */
    suspend fun discover(c: Context, address: String? = null, ms: Int = 4500, onFound: (Tv) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("cinema-cast").apply { setReferenceCounted(false); runCatching { acquire() } }
        val seen = Collections.synchronizedSet(HashSet<String>())
        try {
            if (address != null && address.trim().startsWith("http")) { describe(address.trim())?.let(onFound); return@withContext }
            DatagramSocket().use { s ->
                s.soTimeout = 700
                val target = InetAddress.getByName(address?.trim()?.takeIf { it.isNotEmpty() } ?: "239.255.255.250")
                val port = 1900
                fun ask(st: String) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                    runCatching { s.send(DatagramPacket(msg, msg.size, target, port)) }
                }
                ask(AVT); ask("urn:schemas-upnp-org:device:MediaRenderer:1")
                val end = System.currentTimeMillis() + ms; val buf = ByteArray(4096); var again = 0
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p)
                        val text = String(p.data, 0, p.length)
                        val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                        if (!seen.add(loc)) continue
                        describe(loc)?.let(onFound)
                    } catch (_: SocketTimeoutException) { if (again++ < 3) { ask(AVT) } }
                }
            }
        } finally { runCatching { lock.release() } }
    }

    private fun describe(location: String): Tv? = runCatching {
        val xml = get(location)
        if (!xml.contains("AVTransport")) return null
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: "TV"
        val svc = Regex("<service>(?:(?!</service>).)*?AVTransport(?:(?!</service>).)*?</service>", RegexOption.DOT_MATCHES_ALL).find(xml)?.value ?: return null
        val ctl = Regex("<controlURL>(.*?)</controlURL>").find(svc)?.groupValues?.get(1)?.trim() ?: return null
        Tv(unxml(name), URL(URL(location), ctl).toString())
    }.getOrNull()

    private fun get(url: String): String { val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 3000; c.readTimeout = 3000; return c.inputStream.bufferedReader().use { it.readText() } }
    private fun unxml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun soap(tv: Tv, action: String, args: String = ""): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val c = URL(tv.control).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\""); c.setRequestProperty("SOAPACTION", "\"$AVT#$action\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) throw java.io.IOException(Regex("<errorDescription>(.*?)</errorDescription>").find(text)?.groupValues?.get(1) ?: "The TV refused ($action, ${c.responseCode})")
        return text
    }

    private fun hms(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
    private fun parseHms(t: String): Long = runCatching { t.split(":").map { it.toDouble() }.let { ((it[0] * 3600 + it[1] * 60 + it[2]) * 1000).toLong() } }.getOrDefault(0L)

    /** What is on the TV now: position and length in ms, and whether it is playing. */
    data class State(val pos: Long, val dur: Long, val playing: Boolean)

    class Session(val tv: Tv, private val c: Context, private val source: Uri, private val title: String) {
        private var server: Server? = null
        suspend fun start(atMs: Long) = withContext(Dispatchers.IO) {
            val url = if (source.scheme == "http" || source.scheme == "https") source.toString() else { val s = Server(c, source).also { it.start() }; server = s; s.url ?: throw java.io.IOException("This phone isn't on Wi-Fi") }
            val mime = if (source.scheme == "http" || source.scheme == "https") "video/mp4" else (c.contentResolver.getType(source) ?: "video/mp4")
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${xml(url)}</res></item></DIDL-Lite>"
            runCatching { soap(tv, "Stop") }
            soap(tv, "SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl)}</CurrentURIMetaData>")
            soap(tv, "Play", "<Speed>1</Speed>")
            if (atMs > 2000) { repeat(5) { runCatching { kotlinx.coroutines.delay(700); soap(tv, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(atMs)}</Target>"); return@withContext } } }
        }
        suspend fun play() = withContext(Dispatchers.IO) { soap(tv, "Play", "<Speed>1</Speed>"); Unit }
        suspend fun pause() = withContext(Dispatchers.IO) { soap(tv, "Pause"); Unit }
        suspend fun seek(ms: Long) = withContext(Dispatchers.IO) { soap(tv, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms.coerceAtLeast(0))}</Target>"); Unit }
        suspend fun state(): State? = withContext(Dispatchers.IO) { runCatching {
            val p = soap(tv, "GetPositionInfo"); val t = soap(tv, "GetTransportInfo")
            State(parseHms(Regex("<RelTime>(.*?)</RelTime>").find(p)?.groupValues?.get(1) ?: "0:00:00"), parseHms(Regex("<TrackDuration>(.*?)</TrackDuration>").find(p)?.groupValues?.get(1) ?: "0:00:00"),
                Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(t)?.groupValues?.get(1) == "PLAYING")
        }.getOrNull() }
        suspend fun stop() = withContext(Dispatchers.IO) { runCatching { soap(tv, "Stop") }; server?.stop(); server = null }
    }

    /** Hands the video to the TV in ranges so it can seek. Only reachable on the local network, and only while casting. */
    private class Server(val c: Context, val uri: Uri) {
        private var sock: ServerSocket? = null
        var url: String? = null
        fun start() {
            val s = ServerSocket(0); sock = s
            val ip = localIp() ?: return
            url = "http://$ip:${s.localPort}/video"
            Thread({ while (!s.isClosed) { val cl = runCatching { s.accept() }.getOrNull() ?: break; Thread({ runCatching { serve(cl) }; runCatching { cl.close() } }, "cast-conn").start() } }, "cast-server").start()
        }
        fun stop() { runCatching { sock?.close() } }
        private fun serve(cl: Socket) {
            val inp = cl.getInputStream().bufferedReader(); val first = inp.readLine() ?: return
            var range: String? = null
            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(":").trim() }
            val head = first.startsWith("HEAD")
            c.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val total = pfd.statSize.takeIf { it >= 0 } ?: return
                var from = 0L; var to = total - 1
                range?.let { r -> Regex("bytes=(\\d*)-(\\d*)").find(r)?.let { m -> val a = m.groupValues[1]; val b = m.groupValues[2]; if (a.isNotEmpty()) { from = a.toLong(); if (b.isNotEmpty()) to = minOf(b.toLong(), total - 1) } else if (b.isNotEmpty()) from = maxOf(0, total - b.toLong()) } }
                if (from >= total) { cl.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray()); return }
                val mime = c.contentResolver.getType(uri) ?: "video/mp4"
                val partial = range != null
                val h = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: $mime\r\nContent-Length: ${to - from + 1}\r\nAccept-Ranges: bytes\r\n" +
                    (if (partial) "Content-Range: bytes $from-$to/$total\r\n" else "") + "transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\nConnection: close\r\n\r\n"
                val out = cl.getOutputStream(); out.write(h.toByteArray())
                if (head) return
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    ch.position(from); val buf = java.nio.ByteBuffer.allocate(64 * 1024); var left = to - from + 1
                    while (left > 0) { buf.clear(); if (left < buf.capacity()) buf.limit(left.toInt()); val n = ch.read(buf); if (n <= 0) break; out.write(buf.array(), 0, n); left -= n }
                }
                out.flush()
            }
        }
        private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }
}
