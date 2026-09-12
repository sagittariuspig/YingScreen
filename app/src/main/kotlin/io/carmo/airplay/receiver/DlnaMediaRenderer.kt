package io.carmo.airplay.receiver

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.BindException
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
    private val onPlaybackChanged: (Boolean, String) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit
) {
    private val appContext = context.applicationContext
    // v2 identity intentionally invalidates controller caches created by the
    // early dynamic-port builds (0.2.0-0.2.3).
    private val uuid = UUID.nameUUIDFromBytes(("YingScreen-DLNA-v2-" + ReceiverIdentity.receiverId(appContext)).toByteArray())
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var httpServer: ServerSocket? = null
    @Volatile private var ssdpSocket: MulticastSocket? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var exoPlayer: ExoPlayer? = null
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
        exoPlayer?.setVideoSurface(value)
    }

    fun detachSurface() {
        surface = null
        try { player?.setSurface(null) } catch (_: Exception) {}
        exoPlayer?.clearVideoSurface()
    }

    fun refreshAdvertisement() {
        if (running) workers.execute { sendNotify("ssdp:alive") }
    }

    @Synchronized
    fun togglePlayback(): Boolean {
        exoPlayer?.let {
            it.playWhenReady = !it.playWhenReady
            transportState = if (it.playWhenReady) "PLAYING" else "PAUSED_PLAYBACK"
            onPlaybackChanged(true, if (it.playWhenReady) "DLNA playing" else "DLNA paused")
            return true
        }
        val current = player ?: return false
        return try {
            if (current.isPlaying) {
                current.pause()
                transportState = "PAUSED_PLAYBACK"
                onPlaybackChanged(true, "DLNA paused")
            } else {
                current.start()
                transportState = "PLAYING"
                onPlaybackChanged(true, "DLNA playing")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "remote play/pause failed", e)
            false
        }
    }

    @Synchronized
    fun seekBy(deltaMs: Int): Boolean {
        exoPlayer?.let {
            val duration = it.duration.takeIf { value -> value > 0 } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, duration))
            return true
        }
        val current = player ?: return false
        return try {
            val duration = current.duration.takeIf { it > 0 } ?: Int.MAX_VALUE
            current.seekTo((current.currentPosition + deltaMs).coerceIn(0, duration))
            true
        } catch (e: Exception) {
            Log.w(TAG, "remote seek failed", e)
            false
        }
    }

    fun stopFromRemote(): Boolean {
        if (player == null && exoPlayer == null) return false
        stopPlayback()
        return true
    }

    fun playbackProgress(): Pair<Int, Int>? {
        exoPlayer?.let { return it.currentPosition.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() to it.duration.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        val current = player ?: return null
        return try {
            current.currentPosition.coerceAtLeast(0) to current.duration.coerceAtLeast(0)
        } catch (_: Exception) { null }
    }

    private fun runHttpServer() {
        try {
            // Prefer a stable LOCATION because Tencent Video caches it. Some TV
            // firmwares reserve arbitrary high ports, so try deterministic
            // fallbacks before finally asking the OS for an ephemeral port.
            val server = openHttpServer()
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

    private fun openHttpServer(): ServerSocket {
        DLNA_HTTP_PORTS.forEach { port ->
            try {
                return ServerSocket(port)
            } catch (e: BindException) {
                Log.w(TAG, "DLNA port $port is occupied; trying fallback")
            }
        }
        Log.w(TAG, "all preferred DLNA ports occupied; using ephemeral port")
        return ServerSocket(0)
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
        Log.d(TAG, "M-SEARCH st=$requested from=${address.hostAddress}:$port")
        targets().filter { requested.equals("ssdp:all", true) || requested.equals(it.first, true) }
            .forEach { (st, usn) ->
                val body = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: ${location()}\r\nSERVER: Android/6.0 UPnP/1.0 YingScreen/${BuildConfig.VERSION_NAME}\r\nST: $st\r\nUSN: $usn\r\nBOOTID.UPNP.ORG: ${BuildConfig.VERSION_CODE}\r\nCONFIGID.UPNP.ORG: ${BuildConfig.VERSION_CODE}\r\n\r\n"
                try { socket.send(DatagramPacket(body.toByteArray(), body.toByteArray().size, address, port)) } catch (_: Exception) {}
            }
    }

    private fun sendNotify(nts: String) {
        val port = httpServer?.localPort ?: return
        val address = InetAddress.getByName(SSDP_HOST)
        val socket = ssdpSocket ?: MulticastSocket()
        targets().forEach { (nt, usn) ->
            val body = "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://${localIp()}:$port/description.xml\r\nNT: $nt\r\nNTS: $nts\r\nSERVER: Android/6.0 UPnP/1.0 YingScreen/${BuildConfig.VERSION_NAME}\r\nUSN: $usn\r\nBOOTID.UPNP.ORG: ${BuildConfig.VERSION_CODE}\r\nCONFIGID.UPNP.ORG: ${BuildConfig.VERSION_CODE}\r\n\r\n"
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
                Log.d(TAG, "HTTP $method $path ua=${headers["user-agent"].orEmpty()}")
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
                "Pause" -> { exoPlayer?.pause() ?: player?.pause(); transportState = "PAUSED_PLAYBACK"; onPlaybackChanged(true, "DLNA paused"); "" }
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
        if (Uri.parse(currentUri).host?.endsWith("iqiyi.com", true) == true) {
            playWithExoPlayer()
            return
        }
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
            setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) onVideoSizeChanged(width, height)
            }
            setOnCompletionListener { transportState = "STOPPED"; onPlaybackChanged(false, "DLNA finished") }
            setOnErrorListener { _, what, extra -> Log.e(TAG, "MediaPlayer error $what/$extra for $currentUri"); transportState = "STOPPED"; onPlaybackChanged(false, "DLNA playback error"); true }
            setDataSource(appContext, Uri.parse(currentUri), mapOf("User-Agent" to "YingScreen/${BuildConfig.VERSION_NAME} Android", "Referer" to currentUri.substringBeforeLast('/', "")))
            prepareAsync()
        }
    }

    @Synchronized private fun playWithExoPlayer() {
        exoPlayer?.let {
            if (transportState == "PAUSED_PLAYBACK") {
                it.play(); transportState = "PLAYING"; onPlaybackChanged(true, "DLNA playing"); return
            }
        }
        releasePlayer()
        transportState = "TRANSITIONING"
        onPlaybackChanged(true, "DLNA loading")
        exoPlayer = ExoPlayer.Builder(appContext).build().apply {
            this@DlnaMediaRenderer.surface?.let { setVideoSurface(it) }
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> { transportState = "PLAYING"; onPlaybackChanged(true, "DLNA playing") }
                        Player.STATE_ENDED -> { transportState = "STOPPED"; onPlaybackChanged(false, "DLNA finished") }
                    }
                }
                override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) this@DlnaMediaRenderer.onVideoSizeChanged(videoSize.width, videoSize.height)
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error ${error.errorCodeName} for $currentUri", error)
                    transportState = "STOPPED"
                    onPlaybackChanged(false, "DLNA playback error")
                }
            })
            setMediaItem(MediaItem.fromUri(currentUri))
            playWhenReady = true
            prepare()
        }
    }

    @Synchronized private fun stopPlayback() { releasePlayer(); transportState = "STOPPED"; onPlaybackChanged(false, "DLNA stopped") }
    @Synchronized private fun releasePlayer() { try { player?.reset(); player?.release() } catch (_: Exception) {}; player = null; try { exoPlayer?.release() } catch (_: Exception) {}; exoPlayer = null }
    private fun seek(target: String) { val position = parseTime(target); exoPlayer?.seekTo(position.toLong()) ?: player?.seekTo(position); transportState = "PLAYING" }
    private fun parseTime(value: String): Int { val p = value.split(':'); return if (p.size == 3) ((p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60 + (p[2].substringBefore('.').toLongOrNull() ?: 0)).times(1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0 }
    private fun time(ms: Int): String { val s = ms.coerceAtLeast(0) / 1000; return "%02d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) }
    private fun duration() = time(exoPlayer?.duration?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: try { player?.duration ?: 0 } catch (_: Exception) { 0 })
    private fun positionInfo(): String { val p = exoPlayer?.currentPosition?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }; return "<Track>1</Track><TrackDuration>${duration()}</TrackDuration><TrackMetaData>${currentMeta.xmlEscape()}</TrackMetaData><TrackURI>${currentUri.xmlEscape()}</TrackURI><RelTime>${time(p)}</RelTime><AbsTime>${time(p)}</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>" }
    private fun volumePercent(): Int { val a = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC); return if (max == 0) 0 else a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max }
    private fun setVolume(percent: Int) { val a = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; a.setStreamVolume(AudioManager.STREAM_MUSIC, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * percent.coerceIn(0, 100) / 100, 0) }

    private fun deviceDescription() = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>${location().substringBeforeLast('/')}/</URLBase><device><deviceType>$RENDERER_TYPE</deviceType><friendlyName>${name().xmlEscape()}</friendlyName><manufacturer>YingScreen</manufacturer><manufacturerURL>https://github.com/sagittariuspig/YingScreen</manufacturerURL><modelDescription>AirPlay and DLNA receiver for Android TV</modelDescription><modelName>影屏</modelName><modelNumber>${BuildConfig.VERSION_NAME}</modelNumber><serialNumber>${uuid.toString().take(12)}</serialNumber><UDN>uuid:$uuid</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><dlna:X_DLNACAP>av-upload,image-upload,audio-upload</dlna:X_DLNACAP><serviceList>${serviceXml(AV_TRANSPORT, "AVTransport", "avtransport")}${serviceXml(RENDERING_CONTROL, "RenderingControl", "renderingcontrol")}${serviceXml(CONNECTION_MANAGER, "ConnectionManager", "connectionmanager")}</serviceList></device></root>"""
    private fun serviceXml(type: String, id: String, path: String) = "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId><SCPDURL>/$path-scpd.xml</SCPDURL><controlURL>/control/$path</controlURL><eventSubURL>/event/$path</eventSubURL></service>"
    private fun scpd(path: String): String = when {
        path.contains("rendering") -> renderingControlScpd()
        path.contains("connection") -> connectionManagerScpd()
        else -> avTransportScpd()
    }

    private fun arg(name: String, direction: String, state: String) =
        "<argument><name>$name</name><direction>$direction</direction><relatedStateVariable>$state</relatedStateVariable></argument>"
    private fun action(name: String, vararg args: String) =
        "<action><name>$name</name>${if (args.isEmpty()) "" else "<argumentList>${args.joinToString("")}</argumentList>"}</action>"
    private fun state(name: String, type: String, events: Boolean = false, allowed: String = "") =
        "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>$allowed</stateVariable>"
    private fun values(vararg values: String) = "<allowedValueList>${values.joinToString("") { "<allowedValue>$it</allowedValue>" }}</allowedValueList>"
    private fun scpdDocument(actions: String, states: String) =
        "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>$actions</actionList><serviceStateTable>$states</serviceStateTable></scpd>"

    private fun avTransportScpd(): String {
        val instance = arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")
        val actions = listOf(
            action("SetAVTransportURI", instance, arg("CurrentURI", "in", "AVTransportURI"), arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")),
            action("SetNextAVTransportURI", instance, arg("NextURI", "in", "NextAVTransportURI"), arg("NextURIMetaData", "in", "NextAVTransportURIMetaData")),
            action("Play", instance, arg("Speed", "in", "TransportPlaySpeed")), action("Pause", instance), action("Stop", instance),
            action("Seek", instance, arg("Unit", "in", "A_ARG_TYPE_SeekMode"), arg("Target", "in", "A_ARG_TYPE_SeekTarget")),
            action("GetTransportInfo", instance, arg("CurrentTransportState", "out", "TransportState"), arg("CurrentTransportStatus", "out", "TransportStatus"), arg("CurrentSpeed", "out", "TransportPlaySpeed")),
            action("GetPositionInfo", instance, arg("Track", "out", "CurrentTrack"), arg("TrackDuration", "out", "CurrentTrackDuration"), arg("TrackMetaData", "out", "CurrentTrackMetaData"), arg("TrackURI", "out", "CurrentTrackURI"), arg("RelTime", "out", "RelativeTimePosition"), arg("AbsTime", "out", "AbsoluteTimePosition"), arg("RelCount", "out", "RelativeCounterPosition"), arg("AbsCount", "out", "AbsoluteCounterPosition")),
            action("GetMediaInfo", instance, arg("NrTracks", "out", "NumberOfTracks"), arg("MediaDuration", "out", "CurrentMediaDuration"), arg("CurrentURI", "out", "AVTransportURI"), arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"), arg("NextURI", "out", "NextAVTransportURI"), arg("NextURIMetaData", "out", "NextAVTransportURIMetaData"), arg("PlayMedium", "out", "PlaybackStorageMedium"), arg("RecordMedium", "out", "RecordStorageMedium"), arg("WriteStatus", "out", "RecordMediumWriteStatus")),
            action("GetDeviceCapabilities", instance, arg("PlayMedia", "out", "PossiblePlaybackStorageMedia"), arg("RecMedia", "out", "PossibleRecordStorageMedia"), arg("RecQualityModes", "out", "PossibleRecordQualityModes")),
            action("GetTransportSettings", instance, arg("PlayMode", "out", "CurrentPlayMode"), arg("RecQualityMode", "out", "CurrentRecordQualityMode")),
            action("GetCurrentTransportActions", instance, arg("Actions", "out", "CurrentTransportActions"))
        ).joinToString("")
        val states = listOf(
            state("TransportState", "string", true, values("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")), state("TransportStatus", "string", allowed = values("OK", "ERROR_OCCURRED")),
            state("TransportPlaySpeed", "string", allowed = values("1")), state("AVTransportURI", "uri", true), state("AVTransportURIMetaData", "string", true), state("NextAVTransportURI", "uri"), state("NextAVTransportURIMetaData", "string"),
            state("NumberOfTracks", "ui4"), state("CurrentMediaDuration", "string"), state("CurrentTrack", "ui4"), state("CurrentTrackDuration", "string"), state("CurrentTrackMetaData", "string"), state("CurrentTrackURI", "uri"),
            state("RelativeTimePosition", "string"), state("AbsoluteTimePosition", "string"), state("RelativeCounterPosition", "i4"), state("AbsoluteCounterPosition", "i4"), state("CurrentTransportActions", "string"),
            state("PlaybackStorageMedium", "string", allowed = values("NETWORK", "NONE")), state("RecordStorageMedium", "string", allowed = values("NOT_IMPLEMENTED")), state("RecordMediumWriteStatus", "string", allowed = values("NOT_IMPLEMENTED")),
            state("PossiblePlaybackStorageMedia", "string"), state("PossibleRecordStorageMedia", "string"), state("PossibleRecordQualityModes", "string"), state("CurrentPlayMode", "string", allowed = values("NORMAL")), state("CurrentRecordQualityMode", "string"),
            state("A_ARG_TYPE_InstanceID", "ui4"), state("A_ARG_TYPE_SeekMode", "string", allowed = values("REL_TIME", "ABS_TIME", "TRACK_NR")), state("A_ARG_TYPE_SeekTarget", "string")
        ).joinToString("")
        return scpdDocument(actions, states)
    }

    private fun renderingControlScpd(): String {
        val instance = arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")
        val channel = arg("Channel", "in", "A_ARG_TYPE_Channel")
        val actions = listOf(
            action("GetVolume", instance, channel, arg("CurrentVolume", "out", "Volume")), action("SetVolume", instance, channel, arg("DesiredVolume", "in", "Volume")),
            action("GetMute", instance, channel, arg("CurrentMute", "out", "Mute")), action("SetMute", instance, channel, arg("DesiredMute", "in", "Mute"))
        ).joinToString("")
        val states = state("Mute", "boolean", true) + state("Volume", "ui2", true, "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>") + state("A_ARG_TYPE_Channel", "string", allowed = values("Master")) + state("A_ARG_TYPE_InstanceID", "ui4")
        return scpdDocument(actions, states)
    }

    private fun connectionManagerScpd(): String {
        val actions = listOf(
            action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo"), arg("Sink", "out", "SinkProtocolInfo")), action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs")),
            action("GetCurrentConnectionInfo", arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), arg("RcsID", "out", "A_ARG_TYPE_RcsID"), arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"), arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"), arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"), arg("Direction", "out", "A_ARG_TYPE_Direction"), arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"))
        ).joinToString("")
        val states = state("SourceProtocolInfo", "string", true) + state("SinkProtocolInfo", "string", true) + state("CurrentConnectionIDs", "string", true) + state("A_ARG_TYPE_ConnectionStatus", "string", allowed = values("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")) + state("A_ARG_TYPE_ConnectionManager", "string") + state("A_ARG_TYPE_Direction", "string", allowed = values("Input", "Output")) + state("A_ARG_TYPE_ProtocolInfo", "string") + state("A_ARG_TYPE_ConnectionID", "i4") + state("A_ARG_TYPE_AVTransportID", "i4") + state("A_ARG_TYPE_RcsID", "i4")
        return scpdDocument(actions, states)
    }
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
        private val DLNA_HTTP_PORTS = intArrayOf(49222, 49223, 49224, 49225)
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }
}
