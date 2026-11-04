package com.clouddroid.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.clouddroid.app.device.StreamSessionDto
import com.clouddroid.app.settings.StreamProfile
import com.clouddroid.app.streaming.StreamClient
import com.clouddroid.app.streaming.StreamState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** SCREEN 6: the fullscreen player. The remote Android screen is rendered here from the WebRTC stream. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PlayerScreen(vm: AppViewModel, pkg: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val settings = vm.settings.collectAsStateCompat()
    val holder = remember { arrayOfNulls<StreamClient>(1) }
    val sessionHolder = remember { arrayOfNulls<StreamSessionDto>(1) }
    var client by remember { mutableStateOf<StreamClient?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val closeApp = remember { booleanArrayOf(false) }

    // Immersive fullscreen while the player is open.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (window != null) WindowCompat.setDecorFitsSystemWindows(window, false)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Create the backend session (this also starts the device and launches the app), then connect.
    LaunchedEffect(pkg, attempt) {
        startError = null
        try {
            val s = vm.api().startStream(pkg, settings.profile.name)
            sessionHolder[0] = s
            val c = StreamClient(context, vm.api(), s, settings.profile.name, settings.autoQuality, settings.audioEnabled)
            holder[0] = c
            client = c
            c.start()
        } catch (e: Exception) {
            startError = e.message ?: e.toString()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val c = holder[0]
            val s = sessionHolder[0]
            if (c != null) {
                vm.addDataUsed(c.stats.value.bytesReceived)
                c.close()
            }
            // Leaves the remote app running unless the user chose "Exit and close app".
            if (s != null) vm.endSession(s.sessionId, pkg, closeApp[0])
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val c = client
        if (c != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(c.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() {}
                            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                                if (rotation % 180 == 0) { c.videoWidth = w; c.videoHeight = h }
                                else { c.videoWidth = h; c.videoHeight = w }
                            }
                        })
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        setOnTouchListener { v, ev -> forwardTouch(v, ev, c); true }
                        c.attach(this)
                    }
                },
                onRelease = { c.detach(it); it.release() },
            )

            val state by c.state.collectAsState()
            val stats by c.stats.collectAsState()

            // connection status
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                val (text, color) = when (val st = state) {
                    StreamState.Idle -> "Idle" to Color.Gray
                    StreamState.Connecting -> "Connecting…" to Warn
                    StreamState.Connected -> "${if (stats.rttMs >= 0) "${stats.rttMs} ms · " else ""}${stats.fps} fps · ${"%.1f".format(stats.kbps / 1000.0)} Mbps" to Good
                    is StreamState.Reconnecting -> "Reconnecting (${st.attempt})…" to Warn
                    is StreamState.Failed -> "Disconnected" to Color.Red
                }
                Row(Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(color); Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (state is StreamState.Connecting || state is StreamState.Reconnecting) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(if (state is StreamState.Reconnecting) "Reconnecting to your session…" else "Starting the stream…", color = Color.White)
                }
            }
            (state as? StreamState.Failed)?.let { f ->
                Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connection lost", style = MaterialTheme.typography.titleMedium)
                        Text(f.reason)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { c.retryNow() }) { Text("Retry") }
                            OutlinedButton(onClick = onExit) { Text("Exit") }
                        }
                    }
                }
            }

            // controls
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
                IconButton(onClick = { showControls = !showControls },
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50))) {
                    Icon(if (showControls) Icons.Filled.Close else Icons.Filled.Menu, "Controls", tint = Color.White)
                }
                if (showControls) {
                    Column(Modifier.padding(top = 6.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(4.dp)) {
                        CtlButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { c.key("back") }
                        CtlButton(Icons.Filled.Home, "Home") { c.key("home") }
                        CtlButton(Icons.Filled.Apps, "Recent apps") { c.key("recents") }
                        CtlButton(Icons.Filled.Keyboard, "Keyboard") { showKeyboard = !showKeyboard }
                        CtlButton(Icons.Filled.ScreenRotation, "Rotate") {
                            landscape = !landscape
                            activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            c.orientation(landscape)
                        }
                        Box {
                            CtlButton(Icons.Filled.Tune, "Streaming quality") { qualityMenu = true }
                            DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                                DropdownMenuItem(text = { Text("Automatic") }, onClick = {
                                    c.setQuality(settings.profile.name, true); qualityMenu = false
                                })
                                for (p in StreamProfile.entries) DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = { c.setQuality(p.name, false); qualityMenu = false },
                                )
                            }
                        }
                        CtlButton(Icons.Filled.Close, "Exit session") { closeApp[0] = false; backupIfWanted(vm, pkg); onExit() }
                        CtlButton(Icons.Filled.PowerSettingsNew, "Exit and close app") { closeApp[0] = true; backupIfWanted(vm, pkg); onExit() }
                    }
                }
            }

            if (showKeyboard) {
                var text by remember { mutableStateOf("") }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD000000)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("Type to send") })
                    Button(onClick = { if (text.isNotEmpty()) { c.text(text); text = "" } }) { Text("Send") }
                    IconButton(onClick = { c.key("backspace") }) { Icon(Icons.AutoMirrored.Filled.Backspace, "Backspace", tint = Color.White) }
                    IconButton(onClick = { c.key("enter") }) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", tint = Color.White) }
                }
            }
        }

        startError?.let { e ->
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not start the session", style = MaterialTheme.typography.titleMedium)
                    Text(e)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { attempt++ }) { Text("Try again") }
                        OutlinedButton(onClick = onExit) { Text("Back") }
                    }
                }
            }
        }
        if (client == null && startError == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Starting the remote device and the app…", color = Color.White)
            }
        }
    }
}

@Composable
private fun CtlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

private fun backupIfWanted(vm: AppViewModel, pkg: String) {
    val s = vm.settings.value
    if (!s.autoBackup) return
    val provider = PROVIDERS.firstOrNull { vm.providers[it]?.connected == true } ?: return
    vm.backup(pkg, provider)
}

/** Maps a touch on the (letterboxed) view to 0..1 coordinates on the remote picture and sends it. */
private fun forwardTouch(v: View, ev: MotionEvent, c: StreamClient) {
    val vw = c.videoWidth; val vh = c.videoHeight
    if (vw <= 0 || vh <= 0 || v.width == 0 || v.height == 0) return
    val scale = minOf(v.width.toFloat() / vw, v.height.toFloat() / vh)
    val cw = vw * scale; val ch = vh * scale
    val left = (v.width - cw) / 2f; val top = (v.height - ch) / 2f
    fun nx(x: Float) = ((x - left) / cw).coerceIn(0f, 1f)
    fun ny(y: Float) = ((y - top) / ch).coerceIn(0f, 1f)
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val i = ev.actionIndex
            c.touch("down", ev.getPointerId(i), nx(ev.getX(i)), ny(ev.getY(i)))
        }
        MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount)
            c.touch("move", ev.getPointerId(i), nx(ev.getX(i)), ny(ev.getY(i)))
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val i = ev.actionIndex
            c.touch("up", ev.getPointerId(i), nx(ev.getX(i)), ny(ev.getY(i)))
        }
        MotionEvent.ACTION_CANCEL -> for (i in 0 until ev.pointerCount)
            c.touch("up", ev.getPointerId(i), nx(ev.getX(i)), ny(ev.getY(i)))
    }
}
