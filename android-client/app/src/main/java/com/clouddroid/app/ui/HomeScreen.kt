package com.clouddroid.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onAdd: () -> Unit,
    onLaunch: (String) -> Unit,
    onOpenDevice: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.refreshAll() }
    val settings = vm.settings.collectAsStateCompat()
    val dev = vm.device
    val recent = vm.apps.filter { it.lastLaunched != null }.sortedByDescending { it.lastLaunched }.take(8)
    val pinned = vm.apps.filter { it.packageName in settings.pinned }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Logo()
            IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your apps run on a remote Android device. This phone only shows the screen and sends your touches.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (settings.serverUrl.isBlank() || settings.token.isBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                onClick = onOpenSettings) {
                Column(Modifier.padding(16.dp)) {
                    Text("Connect to your CloudDroid server", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text("Open Settings and enter the backend URL and access token.")
                }
            }
        }

        // remote device status
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDevice)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (dev?.status == "running") Good else if (dev == null) MaterialTheme.colorScheme.outline else Warn)
                    Text("Remote device", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    when {
                        dev == null -> "Not reachable"
                        dev.status == "running" -> "Running · Android ${dev.androidVersion} · ${dev.cpuCores} CPU · ${dev.ramMb} MB RAM"
                        else -> dev.status.replaceFirstChar { it.uppercase() }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (dev != null && dev.diskGb > 0) UsageBar(dev.diskUsedMb * 1_000_000, dev.diskGb * 1_000_000_000L)
            }
        }

        // cloud storage usage
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenCloud)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud storage", style = MaterialTheme.typography.titleMedium)
                for (p in PROVIDERS) {
                    val s = vm.providers[p]
                    Text(providerName(p) + if (s?.connected == true) "" else " — not connected",
                        style = MaterialTheme.typography.bodyMedium)
                    if (s?.connected == true) UsageBar(s.usedBytes, s.totalBytes)
                }
            }
        }

        Button(onClick = onAdd, Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add APK")
        }

        if (recent.isNotEmpty()) {
            SectionTitle("Recently played")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.packageName }) { app ->
                    Card(Modifier.width(120.dp).clickable { onLaunch(app.packageName) }) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppAvatar(app, 48)
                            Spacer(Modifier.height(6.dp))
                            Text(app.label.ifBlank { app.packageName }, maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        SectionTitle("Installed apps")
        if (vm.apps.isEmpty()) EmptyHint("Nothing installed yet. Add an APK from TeraBox or MEGA.")
        val quick = if (pinned.isNotEmpty()) pinned else vm.apps.take(6)
        for (app in quick) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppAvatar(app)
                    Column(Modifier.weight(1f)) {
                        Text(app.label.ifBlank { app.packageName }, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    FilledTonalButton(onClick = { onLaunch(app.packageName) }) {
                        Icon(Icons.Filled.PlayArrow, null); Text("Launch")
                    }
                }
            }
        }
    }
}
