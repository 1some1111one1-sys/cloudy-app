package com.clouddroid.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clouddroid.app.settings.StreamProfile
import java.io.File

private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

/** SCREEN 7: settings. */
@Composable
fun SettingsScreen(vm: AppViewModel, onOpenLibrary: () -> Unit) {
    val s = vm.settings.collectAsStateCompat()
    val context = LocalContext.current
    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var token by remember(s.token) { mutableStateOf(s.token) }
    var cacheBytes by remember { mutableStateOf(dirSize(context.cacheDir)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Remote device server (optional)")
        Text("TeraBox and MEGA need no server. Fill this in only to install and run Android apps on a CloudDroid server you set up.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, label = { Text("Server URL (https://…)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Server access token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveServer(url, token); vm.refreshAll() }) { Text("Save and test") }
            if (url.isNotBlank() || token.isNotBlank()) TextButton(onClick = { url = ""; token = ""; vm.saveServer("", ""); vm.refreshAll() }) { Text("Remove server") }
        }
        if (url.startsWith("http://")) Text("Use https:// — this connection would send your access token unencrypted.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        SectionTitle("Streaming")
        for (p in StreamProfile.entries) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.profile == p, onClick = { vm.setProfile(p) })
                Column {
                    Text(p.name.lowercase().replaceFirstChar { it.uppercase() } + if (p == StreamProfile.BALANCED) " (recommended)" else "")
                    Text("${p.label.substringAfter("· ")} · ~${p.kbps / 1000} Mbps",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SwitchRow("Adjust quality automatically", "Lower the quality when the network gets worse", s.autoQuality) { vm.setAuto(it) }
        SwitchRow("Audio", "Receive the remote device's sound", s.audioEnabled) { vm.setAudio(it) }

        SectionTitle("Cloud saving")
        SwitchRow("Automatic cloud saving", "Back up an app's data to your drive when you leave its session", s.autoBackup) { vm.setAutoBackup(it) }

        SectionTitle("Cloud accounts")
        for (p in PROVIDERS) {
            val st = vm.providers[p]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${providerName(p)}: " + if (st?.connected == true) "connected" else "not connected")
                if (st?.connected == true) TextButton(onClick = { vm.disconnect(p) }) { Text("Disconnect") }
            }
        }

        SectionTitle("Data usage")
        Text("Streamed so far: ${formatBytes(s.dataUsedBytes)}")
        TextButton(onClick = { vm.resetDataUsed() }) { Text("Reset counter") }

        SectionTitle("Cache")
        Text("Temporary files on this phone: ${formatBytes(cacheBytes)}")
        OutlinedButton(onClick = {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            cacheBytes = dirSize(context.cacheDir)
        }) { Text("Clear cache") }

        SectionTitle("Applications")
        OutlinedButton(onClick = onOpenLibrary) { Text("Manage installed applications") }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
