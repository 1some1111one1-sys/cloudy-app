package com.clouddroid.app.ui

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.clouddroid.app.core.RemoteFile
import com.clouddroid.app.stream.StreamHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plays a video or audio file straight from TeraBox / MEGA without saving it on the phone. Only the parts being
 * played (and a few blocks ahead) are fetched; memory and cache use are capped (Settings → Streaming cache).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(vm: AppViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val entry = vm.streamEntry
    var error by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var fetched by remember { mutableLongStateOf(0L) }
    var remote by remember { mutableStateOf<RemoteFile?>(null) }
    var surface by remember { mutableStateOf<SurfaceHolder?>(null) }
    val player = remember { MediaPlayer() }
    var seeking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(entry) {
        if (entry == null) { error = "No file chosen."; preparing = false; return@LaunchedEffect }
        try {
            val file = vm.openRemote()
            remote = file
            val pfd = withContext(Dispatchers.IO) { StreamHub.openDescriptor(context, file) }
            player.setOnPreparedListener { durationMs = it.duration; preparing = false; it.start(); playing = true }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, what, extra ->
                error = "Cannot play this file here (media error $what/$extra). Not every format or codec is supported."
                preparing = false; true
            }
            player.setDataSource(pfd.fileDescriptor)
            player.prepareAsync()
        } catch (e: Exception) {
            error = e.message ?: e.toString(); preparing = false
        }
    }

    LaunchedEffect(surface) { surface?.let { runCatching { player.setDisplay(it) } } }

    LaunchedEffect(playing, preparing) {
        while (true) {
            if (!preparing && !seeking) runCatching { positionMs = player.currentPosition }
            fetched = remote?.networkBytes() ?: 0L
            delay(500)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(entry?.name ?: "Stream", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { surface = h }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            if (preparing && error == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Reading the start of the file from ${providerName(vm.streamProvider)}…")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!preparing && error == null) {
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = { seeking = true; positionMs = it.toInt() },
                    onValueChangeFinished = { runCatching { player.seekTo(positionMs) }; seeking = false },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${clock(positionMs)} / ${clock(durationMs)}")
                    FilledIconButton(onClick = {
                        runCatching { if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } }
                    }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
                }
            }
            val stats = StreamHub.stats()
            Text(
                "Fetched from ${providerName(vm.streamProvider)}: ${formatBytes(fetched)} of ${formatBytes(entry?.size ?: 0)}" +
                    (stats?.let { "\nIn memory: ${formatBytes(it.ramBytes)} · Cache on disk: ${formatBytes(it.diskBytes)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
