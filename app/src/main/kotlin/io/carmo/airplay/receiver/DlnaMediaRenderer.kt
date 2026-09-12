package io.carmo.airplay.receiver

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Minimal UPnP AV MediaRenderer for app-to-TV (DLNA) casting. */
class DlnaMediaRenderer(
    context: Context,
    private val name: () -> String,
    private val localIp: () -> String,
    private val onPlaybackChanged: (Boolean, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val uuid = UUID.nameUUIDFromBytes(("YingScreen-DLNA-" + ReceiverIdentity.receiverId(appContext)).toByteArray())
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var httpServer: ServerSocket? = null
    @Volatile private var ssdpSocket: MulticastSocket? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var currentUri = ""
    @Volatile private var currentMeta = ""
    @Volatile private var transportState = "STOPPED"

    fun start() {
        if (running) return
        running = true
        workers.execute(::runHttpServer)
        workers.execute(::runSsdp)
    }

    fun stop() {
        running = false
        try { httpServer?.close() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        releasePlayer()
    }

    fun attachSurface(value: Surface) {
        surface = value
        try { player?.setSurface(value) } catch (e: Exception) { Log.w(TAG, "setSurface failed", e) }
    }

    fun detachSurface() {
        surface = null
        try { player?.setSurface(null) } catch (_: Exception) {}
    }

    fun refreshAdvertisement() {
        if (running) workers.execute { sendNotify("ssdp:alive") }
    }

    private fun runHttpServer() {
        try {
            // Keep LOCATION stable across app/device restarts. Tencent Video and
            // several DLNA controllers cache the descriptor URL for a long time.
            val server = ServerSocket(DLNA_HTTP_PORT)
            httpServer = server
            Log.i(TAG, "DLNA HTTP server on ${server.localPort}")
            sendNotify("ssdp:alive")
            while (running) {
                val socket = server.accept()
                workers.execute { handleHttp(socket) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "DLNA HTTP server failed", e)
        }
    }

    private fun runSsdp() {
        try {
            val group = InetAddress.getByName(SSDP_HOST)
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                timeToLive = 2
                bind(InetSocketAddress(SSDP_PORT))
                joinGroup(group)
            }
            ssdpSocket = socket
            val buffer = ByteArray(8192)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                if (message.startsWith("M-SEARCH", true) && message.contains("ssdp:discover", true)) {
                    respondToSearch(packet.address, packet.port, header(message, "ST") ?: "ssdp:all")
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "SSDP listener failed", e)
        }
    }

    private fun targets(): List<Pair<String, String>> = listOf(
        "upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice",
        "uuid:$uuid" to "uuid:$uuid",
        RENDERER_TYPE to "uuid:$uuid::$RENDERER_TYPE",
        AV_TRANSPORT to "uuid:$uuid::$AV_TRANSPORT",
        RENDERING_CONTROL to "uuid:$uuid::$RENDERING_CONTROL",
        CONNECTION_MANAGER to "uuid:$uuid::$CONNECTION_MANAGER"
    )

    private fun respondToSearch(address: InetAddress, port: Int, requested: String) {
        val socket = ssdpSocket ?: return
        targets().filter { requested.equals("ssdp:all", true) || requested.equals(it.first, true) }
            .forEach { (st, usn) ->
                val body = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: ${location()}\r\nSERVER: Android/6.0 UPnP/1.0 YingScreen/${BuildConfig.VERSION_NAME}\r\nST: $st\r\nUSN: $usn\r\nBOOTID.UPNP.ORG: 1\r\nCONFIGID.UPNP.ORG: 1\r\n\r\n"
                try { socket.send(DatagramPacket(body.toByteArray(), body.toByteArray().size, address, port)) } catch (_: Exception) {}
            }
    }

    private fun sendNotify(nts: String) {
        val port = httpServer?.localPort ?: return
        val address = InetAddress.getByName(SSDP_HOST)
        val socket = ssdpSocket ?: MulticastSocket()
        targets().forEach { (nt, usn) ->
            val body = "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://${localIp()}:$port/description.xml\r\nNT: $nt\r\nNTS: $nts\r\nSERVER: Android/6.0 UPnP/1.0 YingScreen/${BuildConfig.VERSION_NAME}\r\nUSN: $usn\r\nBOOTID.UPNP.ORG: 1\r\nCONFIGID.UPNP.ORG: 1\r\n\r\n"
            try { socket.send(DatagramPacket(body.toByteArray(), body.toByteArray().size, address, SSDP_PORT)) } catch (_: Exception) {}
        }
        if (socket !== ssdpSocket) socket.close()
    }

    private fun location() = "http://${localIp()}:${httpServer?.localPort ?: 0}/description.xml"

    private fun handleHttp(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 5000
                val input = it.getInputStream()
                val headerBytes = ByteArrayOutputStream()
                var matched = 0
                val boundary = byteArrayOf(13, 10, 13, 10)
                while (headerBytes.size() < MAX_HEADER_BYTES) {
                    val value = input.read()
                    if (value < 0) return
                    headerBytes.write(value)
                    matched = if (value.toByte() == boundary[matched]) matched + 1 else if (value == 13) 1 else 0
                    if (matched == boundary.size) break
                }
                if (matched != boundary.size) return
                val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull() ?: return
                val headers = linkedMapOf<String, String>()
                lines.drop(1).forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bodyBytes, read, length - read)
                    if (count <= 0) break
                    read += count
                }
                val body = String(bodyBytes, 0, read, Charsets.UTF_8)
                val parts = requestLine.split(' ')
                val method = parts.getOrElse(0) { "" }.uppercase(Locale.US)
                val path = parts.getOrElse(1) { "/" }.substringBefore('?')
                when {
                    method == "GET" && path == "/description.xml" -> xml(it.getOutputStream(), deviceDescription())
                    method == "GET" && path.endsWith("scpd.xml") -> xml(it.getOutputStream(), scpd(path))
                    method == "POST" && path.startsWith("/control/") -> soap(it.getOutputStream(), headers["soapaction"].orEmpty(), body)
                    method == "SUBSCRIBE" -> subscribe(it.getOutputStream())
                    method == "UNSUBSCRIBE" -> empty(it.getOutputStream(), 200)
                    else -> empty(it.getOutputStream(), 404)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DLNA request failed", e)
            }
        }
    }

    private fun soap(out: OutputStream, soapAction: String, body: String) {
        val action = soapAction.trim('"').substringAfter('#', "")
        Log.i(TAG, "DLNA action=$action")
        try {
            val response = when (action) {
                "SetAVTransportURI" -> {
                    currentUri = xmlValue(body, "CurrentURI").decodeXml().decodeUrl()
                    currentMeta = xmlValue(body, "CurrentURIMetaData").decodeXml()
                    transportState = "STOPPED"
                    ""
                }
                "SetNextAVTransportURI" -> ""
                "Play" -> { play(); "" }
                "Pause" -> { player?.pause(); transportState = "PAUSED_PLAYBACK"; onPlaybackChanged(true, "DLNA paused"); "" }
                "Stop" -> { stopPlayback(); "" }
                "Seek" -> { seek(xmlValue(body, "Target")); "" }
                "GetTransportInfo" -> "<CurrentTransportState>$transportState</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
                "GetPositionInfo" -> positionInfo()
                "GetMediaInfo" -> "<NrTracks>1</NrTracks><MediaDuration>${duration()}</MediaDuration><CurrentURI>${currentUri.xmlEscape()}</CurrentURI><CurrentURIMetaData>${currentMeta.xmlEscape()}</CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>"
                "GetDeviceCapabilities" -> "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>"
                "GetTransportSettings" -> "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>"
                "GetCurrentTransportActions" -> "<Actions>Play,Pause,Stop,Seek</Actions>"
                "GetVolume" -> "<CurrentVolume>${volumePercent()}</CurrentVolume>"
                "SetVolume" -> { setVolume(xmlValue(body, "DesiredVolume").toIntOrNull() ?: 100); "" }
                "GetMute" -> "<CurrentMute>${if (volumePercent() == 0) 1 else 0}</CurrentMute>"
                "SetMute" -> { setVolume(if (xmlValue(body, "DesiredMute") == "1") 0 else 50); "" }
                "GetProtocolInfo" -> "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:video/x-matroska:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:video/vnd.dlna.mpeg-tts:*,http-get:*:audio/mpeg:*</Sink>"
                "GetCurrentConnectionIDs" -> "<ConnectionIDs>0</ConnectionIDs>"
                "GetCurrentConnectionInfo" -> "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>"
                else -> return soapFault(out, 401, "Invalid Action")
            }
            val service = when { soapAction.contains("RenderingControl") -> RENDERING_CONTROL; soapAction.contains("ConnectionManager") -> CONNECTION_MANAGER; else -> AV_TRANSPORT }
            val envelope = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:${action}Response xmlns:u=\"$service\">$response</u:${action}Response></s:Body></s:Envelope>"
            xml(out, envelope)
        } catch (e: Exception) {
            Log.e(TAG, "DLNA action $action failed", e)
            soapFault(out, 501, "Action Failed")
        }
    }

    @Synchronized private fun play() {
        if (currentUri.isBlank()) throw IllegalStateException("No URI")
        val existing = player
        if (existing != null && transportState == "PAUSED_PLAYBACK") {
            existing.start(); transportState = "PLAYING"; onPlaybackChanged(true, "DLNA playing"); return
        }
        releasePlayer()
        transportState = "TRANSITIONING"
        onPlaybackChanged(true, "DLNA loading")
        player = MediaPlayer().apply {
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            this@DlnaMediaRenderer.surface?.let { setSurface(it) }
            setOnPreparedListener { it.start(); transportState = "PLAYING"; onPlaybackChanged(true, "DLNA playing") }
            setOnCompletionListener { transportState = "STOPPED"; onPlaybackChanged(false, "DLNA finished") }
            setOnErrorListener { _, what, extra -> Log.e(TAG, "MediaPlayer error $what/$extra for $currentUri"); transportState = "STOPPED"; onPlaybackChanged(false, "DLNA playback error"); true }
            setDataSource(appContext, Uri.parse(currentUri), mapOf("User-Agent" to "YingScreen/${BuildConfig.VERSION_NAME} Android", "Referer" to currentUri.substringBeforeLast('/', "")))
            prepareAsync()
        }
    }

    @Synchronized private fun stopPlayback() { releasePlayer(); transportState = "STOPPED"; onPlaybackChanged(false, "DLNA stopped") }
    @Synchronized private fun releasePlayer() { try { player?.reset(); player?.release() } catch (_: Exception) {}; player = null }
    private fun seek(target: String) { player?.seekTo(parseTime(target)); transportState = "PLAYING" }
    private fun parseTime(value: String): Int { val p = value.split(':'); return if (p.size == 3) ((p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60 + (p[2].substringBefore('.').toLongOrNull() ?: 0)).times(1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0 }
    private fun time(ms: Int): String { val s = ms.coerceAtLeast(0) / 1000; return "%02d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) }
    private fun duration() = time(try { player?.duration ?: 0 } catch (_: Exception) { 0 })
    private fun positionInfo(): String { val p = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }; return "<Track>1</Track><TrackDuration>${duration()}</TrackDuration><TrackMetaData>${currentMeta.xmlEscape()}</TrackMetaData><TrackURI>${currentUri.xmlEscape()}</TrackURI><RelTime>${time(p)}</RelTime><AbsTime>${time(p)}</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>" }
    private fun volumePercent(): Int { val a = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC); return if (max == 0) 0 else a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max }
    private fun setVolume(percent: Int) { val a = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; a.setStreamVolume(AudioManager.STREAM_MUSIC, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent.coerceIn(0, 100) / 100, 0) }

    private fun deviceDescription() = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>${location().substringBeforeLast('/')}/</URLBase><device><deviceType>$RENDERER_TYPE</deviceType><friendlyName>${name().xmlEscape()}</friendlyName><manufacturer>YingScreen</manufacturer><manufacturerURL>https://github.com/sagittariuspig/YingScreen</manufacturerURL><modelDescription>AirPlay and DLNA receiver for Android TV</modelDescription><modelName>影屏</modelName><modelNumber>${BuildConfig.VERSION_NAME}</modelNumber><serialNumber>${uuid.toString().take(12)}</serialNumber><UDN>uuid:$uuid</UDN><serviceList>${serviceXml(AV_TRANSPORT, "AVTransport", "avtransport")}${serviceXml(RENDERING_CONTROL, "RenderingControl", "renderingcontrol")}${serviceXml(CONNECTION_MANAGER, "ConnectionManager", "connectionmanager")}</serviceList></device></root>"""
    private fun serviceXml(type: String, id: String, path: String) = "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId><SCPDURL>/$path-scpd.xml</SCPDURL><controlURL>/control/$path</controlURL><eventSubURL>/event/$path</eventSubURL></service>"
    private fun scpd(path: String): String { val actions = when { path.contains("rendering") -> listOf("GetVolume", "SetVolume", "GetMute", "SetMute"); path.contains("connection") -> listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo"); else -> listOf("SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo", "GetPositionInfo", "GetMediaInfo", "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions") }; return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>${actions.joinToString("") { "<action><name>$it</name></action>" }}</actionList><serviceStateTable></serviceStateTable></scpd>" }
    private fun subscribe(out: OutputStream) { val response = "HTTP/1.1 200 OK\r\nSID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\nContent-Length: 0\r\n\r\n"; out.write(response.toByteArray()) }
    private fun xml(out: OutputStream, body: String) { val bytes = body.toByteArray(); out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes) }
    private fun empty(out: OutputStream, code: Int) { out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
    private fun soapFault(out: OutputStream, code: Int, description: String) { val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"; xml(out, body) }
    private fun header(message: String, key: String) = message.lineSequence().mapNotNull { val i = it.indexOf(':'); if (i > 0 && it.substring(0, i).trim().equals(key, true)) it.substring(i + 1).trim() else null }.firstOrNull()
    private fun xmlValue(xml: String, tag: String): String { val regex = Regex("<(?:[\\w-]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)); return regex.find(xml)?.groupValues?.get(1)?.trim().orEmpty() }
    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun String.decodeXml() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun String.decodeUrl() = try { URLDecoder.decode(this, "UTF-8") } catch (_: Exception) { this }

    companion object {
        private const val TAG = "Receiver-DLNA"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DLNA_HTTP_PORT = 49152
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }
}
