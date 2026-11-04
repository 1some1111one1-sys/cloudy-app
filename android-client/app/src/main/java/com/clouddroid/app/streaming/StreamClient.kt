package com.clouddroid.app.streaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.clouddroid.app.cloud.BackendApi
import com.clouddroid.app.device.StreamSessionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Connected : StreamState
    data class Reconnecting(val attempt: Int) : StreamState
    data class Failed(val reason: String) : StreamState
}

data class StreamStats(
    val rttMs: Int = -1,
    val lossPercent: Double = 0.0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bytesReceived: Long = 0,
    val profile: String = "",
)

/**
 * WebRTC client for one remote Android session. Signalling goes over a WebSocket to the backend; video/audio are
 * received over WebRTC (hardware-decoded through MediaCodec where the phone supports H.264); touch and keyboard
 * go back over a data channel. If the connection drops it reconnects to the SAME backend session, so the
 * remote app is not restarted.
 */
class StreamClient(
    context: Context,
    private val api: BackendApi,
    private val session: StreamSessionDto,
    private var profile: String,
    private var auto: Boolean,
    private val audioEnabled: Boolean,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val eglBase: EglBase = EglBase.create()
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state
    private val _stats = MutableStateFlow(StreamStats(profile = profile))
    val stats: StateFlow<StreamStats> = _stats

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var ws: WebSocket? = null
    private var video: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var closed = false
    private var attempt = 0
    private var offerSent = false
    private var lastBytes = 0L
    private var lastFrames = 0L
    private var lastStatsAt = 0L
    private var lastLost = 0L
    private var lastRecv = 0L
    private var peerGeneration = 0

    /** Size of the remote picture, used to map touches onto it. */
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    private val statsTask = object : Runnable {
        override fun run() {
            collectStats()
            if (!closed) main.postDelayed(this, 2000)
        }
    }

    fun start() {
        main.post {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                    .createPeerConnectionFactory()
            }
            connect()
            main.postDelayed(statsTask, 2000)
        }
    }

    fun attach(view: SurfaceViewRenderer) {
        renderer = view
        video?.addSink(view)
    }

    fun detach(view: SurfaceViewRenderer) {
        video?.removeSink(view)
        if (renderer === view) renderer = null
    }

    // ---------------------------------------------------------------- connection

    private fun connect() {
        if (closed) return
        teardownPeer()
        offerSent = false
        _state.value = if (attempt == 0) StreamState.Connecting else StreamState.Reconnecting(attempt)
        val generation = ++peerGeneration

        val ice = session.iceServers.map {
            PeerConnection.IceServer.builder(it.urls).apply {
                it.username?.let(::setUsername)
                it.credential?.let(::setPassword)
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}

            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { sendOffer(generation) }
            }

            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track()
                if (track is VideoTrack) main.post {
                    video = track
                    renderer?.let { track.addSink(it) }
                }
            }

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                main.post {
                    if (generation != peerGeneration) return@post
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            attempt = 0
                            _state.value = StreamState.Connected
                        }
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.DISCONNECTED -> scheduleReconnect(generation)
                        else -> {}
                    }
                }
            }
        })
        if (peer == null) {
            _state.value = StreamState.Failed("Could not create the WebRTC connection")
            return
        }
        pc = peer
        peer.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        if (audioEnabled) {
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        dc = peer.createDataChannel("input", DataChannel.Init().apply { ordered = true })

        ws = api.http.newWebSocket(api.signalUrl(session.sessionId), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (generation == peerGeneration) createOffer(peer) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (generation == peerGeneration) handleSignal(peer, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (generation == peerGeneration) scheduleReconnect(generation, t.message) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Normal after the answer: the WebRTC connection carries on without the signalling socket.
            }
        })
    }

    private fun createOffer(peer: PeerConnection) {
        val constraints = MediaConstraints()
        peer.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(object : SimpleSdp() {}, sdp)
                // Non-trickle: wait for gathering to finish (see onIceGatheringChange), with a fallback timer.
                main.postDelayed({ sendOffer(peerGeneration) }, 3000)
            }

            override fun onCreateFailure(error: String?) {
                _state.value = StreamState.Failed("Offer failed: $error")
            }
        }, constraints)
    }

    private fun sendOffer(generation: Int) {
        if (generation != peerGeneration || offerSent || closed) return
        val sdp = pc?.localDescription ?: return
        offerSent = true
        val msg = JSONObject()
            .put("type", "offer")
            .put("sdp", sdp.description)
            .put("profile", profile)
            .put("auto", auto)
            .put("audio", audioEnabled)
        ws?.send(msg.toString())
    }

    private fun handleSignal(peer: PeerConnection, text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("type")) {
            "answer" -> peer.setRemoteDescription(object : SimpleSdp() {
                override fun onSetFailure(error: String?) {
                    _state.value = StreamState.Failed("Answer rejected: $error")
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            "profile" -> _stats.value = _stats.value.copy(profile = m.optString("name", profile))
            "error" -> _state.value = StreamState.Failed(m.optString("message", "Server error"))
        }
    }

    private fun scheduleReconnect(generation: Int, why: String? = null) {
        if (closed || generation != peerGeneration) return
        attempt++
        if (attempt > MAX_ATTEMPTS) {
            _state.value = StreamState.Failed(why ?: "Connection lost")
            return
        }
        _state.value = StreamState.Reconnecting(attempt)
        val backoff = minOf(1000L * attempt, 8000L)
        peerGeneration++ // invalidate callbacks of the broken peer
        main.postDelayed({ connect() }, backoff)
    }

    private fun teardownPeer() {
        video?.let { v -> renderer?.let { v.removeSink(it) } }
        video = null
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        try { dc?.close(); dc?.dispose() } catch (_: Exception) {}
        dc = null
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        pc = null
    }

    fun retryNow() {
        main.post { attempt = 0; connect() }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        main.post {
            teardownPeer()
            factory?.dispose()
            factory = null
            eglBase.release()
            _state.value = StreamState.Idle
        }
    }

    // ---------------------------------------------------------------- input

    private fun sendJson(o: JSONObject) {
        val ch = dc ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray()), false))
    }

    /** action: down | move | up. x and y are 0..1 on the remote picture. */
    fun touch(action: String, id: Int, x: Float, y: Float) =
        sendJson(JSONObject().put("t", "touch").put("a", action).put("id", id).put("x", x.toDouble()).put("y", y.toDouble()))

    /** key: back | home | recents | enter | backspace */
    fun key(key: String) = sendJson(JSONObject().put("t", "key").put("k", key))
    fun text(s: String) = sendJson(JSONObject().put("t", "text").put("s", s))
    fun orientation(landscape: Boolean) =
        sendJson(JSONObject().put("t", "orientation").put("o", if (landscape) "landscape" else "portrait"))

    fun setQuality(newProfile: String, automatic: Boolean) {
        profile = newProfile
        auto = automatic
        sendJson(JSONObject().put("t", "profile").put("name", newProfile).put("auto", automatic))
    }

    // ---------------------------------------------------------------- stats

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    private fun collectStats() {
        val peer = pc ?: return
        peer.getStats { report ->
            var bytes = 0L; var frames = 0L; var lost = 0L; var recv = 0L
            var width = 0; var height = 0; var rtt = -1
            for (s in report.statsMap.values) {
                val m = s.members
                if (s.type == "inbound-rtp" && m["kind"] == "video") {
                    bytes = num(m["bytesReceived"]); frames = num(m["framesDecoded"])
                    lost = num(m["packetsLost"]); recv = num(m["packetsReceived"])
                    width = num(m["frameWidth"]).toInt(); height = num(m["frameHeight"]).toInt()
                } else if (s.type == "candidate-pair" && m["state"] == "succeeded" && m["nominated"] == true) {
                    (m["currentRoundTripTime"] as? Number)?.let { rtt = (it.toDouble() * 1000).toInt() }
                }
            }
            val now = System.currentTimeMillis()
            val dt = (now - lastStatsAt).coerceAtLeast(1)
            val kbps = if (lastStatsAt == 0L) 0 else ((bytes - lastBytes) * 8 / dt).toInt()
            val fps = if (lastStatsAt == 0L) 0 else ((frames - lastFrames) * 1000 / dt).toInt()
            val dLost = (lost - lastLost).coerceAtLeast(0)
            val dRecv = (recv - lastRecv).coerceAtLeast(0)
            val loss = if (dLost + dRecv == 0L) 0.0 else dLost * 100.0 / (dLost + dRecv)
            lastBytes = bytes; lastFrames = frames; lastLost = lost; lastRecv = recv; lastStatsAt = now
            if (width > 0) { videoWidth = width; videoHeight = height }
            _stats.value = _stats.value.copy(
                rttMs = rtt, lossPercent = loss, fps = fps, kbps = kbps,
                width = width, height = height, bytesReceived = bytes,
            )
            // The backend adapts the encoder bitrate from these numbers (adaptive bitrate).
            sendJson(JSONObject().put("t", "stats").put("rtt", rtt).put("loss", loss).put("fps", fps).put("kbps", kbps))
        }
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
    }
}
