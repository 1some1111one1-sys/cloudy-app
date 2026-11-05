package com.clouddroid.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clouddroid.app.device.InstalledApp

/** SCREEN 3: app library. */
@Composable
fun LibraryScreen(vm: AppViewModel, onLaunch: (String) -> Unit, onUpdate: () -> Unit, onOpenSettings: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshApps() }
    val settings = vm.settings.collectAsStateCompat()
    var uninstall by remember { mutableStateOf<InstalledApp?>(null) }
    var backupFor by remember { mutableStateOf<InstalledApp?>(null) }
    var restoreFor by remember { mutableStateOf<InstalledApp?>(null) }
    var query by remember { mutableStateOf("") }
    val hasServer = settings.serverUrl.isNotBlank() && settings.token.isNotBlank()
    var mode by remember { mutableStateOf("virtual") }

    val shown = vm.apps
        .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
        .sortedWith(compareByDescending<InstalledApp> { it.packageName in settings.pinned }.thenBy { it.label.lowercase() })

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("App library", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "virtual", onClick = { mode = "virtual" }, label = { Text("Inside CloudDroid") })
            FilterChip(selected = mode == "phone", onClick = { mode = "phone" }, label = { Text("Installed on phone") })
            if (hasServer) FilterChip(selected = mode == "server", onClick = { mode = "server" }, label = { Text("Remote device") })
        }
        if (mode == "virtual") { VirtualLibrary(vm, query); return@Column }
        if (!hasServer || mode == "phone") { PhoneLibrary(vm, query); return@Column }
        if (shown.isEmpty()) EmptyHint(if (vm.apps.isEmpty()) "No apps installed on the remote device yet." else "No match.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.packageName }) { app ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppAvatar(app)
                            Column(Modifier.weight(1f)) {
                                Text(app.label.ifBlank { app.packageName }, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text("v${app.versionName.ifBlank { "?" }} · ${formatMb(app.sizeMb)} · ${app.status}" +
                                    (app.lastLaunched?.let { " · last played ${it.take(10)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.togglePinned(app.packageName) }) {
                                Icon(if (app.packageName in settings.pinned) Icons.Filled.Star else Icons.Filled.StarBorder, "Pin")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onLaunch(app.packageName) }, enabled = app.status == "installed") { Text("Launch") }
                            OutlinedButton(onClick = onUpdate) { Text("Update") }
                            OutlinedButton(onClick = { backupFor = app }) { Text("Backup") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { restoreFor = app; vm.loadBackups(app.packageName) }) { Text("Restore") }
                            TextButton(onClick = { vm.stopApp(app.packageName) }) { Text("Stop") }
                            TextButton(onClick = { uninstall = app }) { Text("Uninstall") }
                        }
                    }
                }
            }
        }
    }

    uninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstall = null },
            title = { Text("Uninstall ${app.label.ifBlank { app.packageName }}?") },
            text = { Text("This removes the app and its data from the remote device. Back it up first if you want to keep your progress.") },
            confirmButton = { TextButton(onClick = { vm.uninstall(app.packageName); uninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { uninstall = null }) { Text("Cancel") } },
        )
    }
    backupFor?.let { app ->
        AlertDialog(
            onDismissRequest = { backupFor = null },
            title = { Text("Back up to…") },
            text = { Text("Only changed files are uploaded after the first backup. Apps that block access to their data cannot be backed up.") },
            confirmButton = { Row {
                for (p in PROVIDERS) {
                    if (vm.providers[p]?.connected == true)
                        TextButton(onClick = { vm.backup(app.packageName, p); backupFor = null }) { Text(providerName(p)) }
                }
            } },
            dismissButton = { TextButton(onClick = { backupFor = null }) { Text("Cancel") } },
        )
    }
    restoreFor?.let { app ->
        AlertDialog(
            onDismissRequest = { restoreFor = null },
            title = { Text("Restore ${app.label.ifBlank { app.packageName }}") },
            text = {
                if (vm.backups.isEmpty()) Text("No backups found.")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (b in vm.backups) {
                        TextButton(onClick = { vm.restore(app.packageName, b); restoreFor = null }) {
                            Text("${b.createdAt.take(16).replace('T', ' ')} · ${providerName(b.provider)} · ${b.kind} · ${formatBytes(b.sizeBytes)}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { restoreFor = null }) { Text("Close") } },
        )
    }
}

/** SCREEN 4: add application. Installs on THIS phone from the drive (no server); a server is optional. */
@Composable
fun AddScreen(vm: AppViewModel, onPick: (String) -> Unit, onOpenSettings: () -> Unit) {
    val job = vm.job
    val settings = vm.settings.collectAsStateCompat()
    val hasServer = settings.serverUrl.isNotBlank() && settings.token.isNotBlank()
    val target = if (vm.installTarget == "server" && !hasServer) "virtual" else vm.installTarget
    val onPhone = target != "server"
    LaunchedEffect(Unit) { vm.refreshProvidersAsync(); vm.loadSavedApps() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add application", style = MaterialTheme.typography.headlineMedium)
        Text(when (target) {
            "virtual" -> "Run an app INSIDE CloudDroid without installing it in Android. The APK is streamed from your drive (a single .apk file) into CloudDroid's engine; its data stays inside CloudDroid and can be saved back to the drive to free space. Experimental: some apps cannot run this way."
            "phone" -> "Install an app on this phone from your drive. The APK is read from TeraBox or MEGA piece by piece and handed to Android's installer. Android asks you to confirm."
            else -> "Pick an APK on your drive. Your server downloads it to the remote device and installs it there."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = target == "virtual", onClick = { vm.installTarget = "virtual" }, label = { Text("Run inside CloudDroid") })
            FilterChip(selected = target == "phone", onClick = { vm.installTarget = "phone" }, label = { Text("Install on phone") })
            if (hasServer) FilterChip(selected = target == "server", onClick = { vm.installTarget = "server" }, label = { Text("Remote device") })
        }
        BusyCard(vm)
        for (p in PROVIDERS) {
            val connected = vm.providers[p]?.connected == true
            Button(onClick = { onPick(p) }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Choose an APK on ${providerName(p)}" else "${providerName(p)} not connected")
            }
        }
        Text("To put an APK on the drive from this phone, use Cloud storage → Upload. Apps already installed here can be saved from the App library (Save app).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (onPhone) {
            SectionTitle("Apps saved on your drive")
            if (vm.savedApps.isEmpty()) EmptyHint("Nothing saved yet. Use Save app in the App library.")
            for ((p, saved) in vm.savedApps) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(saved.pkg, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text("${providerName(p)} · version code ${saved.versionCode} · ${formatBytes(saved.sizeBytes)}" +
                                if (saved.apks.size > 1) " · ${saved.apks.size} parts" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (target == "virtual") FilledTonalButton(onClick = { vm.addSavedVirtual(p, saved) }) { Text("Add") }
                        else FilledTonalButton(onClick = { vm.installSaved(p, saved) }) { Text("Install") }
                    }
                }
            }
        }

        if (job != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.label ?: job.packageName ?: "Installing", style = MaterialTheme.typography.titleMedium)
                    job.packageName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(when (job.state) {
                        "queued" -> "Waiting…"
                        "downloading" -> "Downloading to the remote device"
                        "inspecting" -> "Reading APK information"
                        "installing" -> "Installing on the remote device"
                        "done" -> "Installed. Find it in the App library."
                        "failed" -> "Failed"
                        else -> job.state
                    })
                    if (job.state != "done" && job.state != "failed")
                        LinearProgressIndicator(progress = { job.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (job.state == "done" || job.state == "failed")
                        TextButton(onClick = { vm.clearJob() }) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
fun NeedsServerCard(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A server is needed to run apps", style = MaterialTheme.typography.titleMedium)
            Text("TeraBox and MEGA work on their own. Installing and running Android apps needs a CloudDroid server " +
                "(a Linux machine you set up, see documentation/DEPLOYMENT.md): it runs the Android device the apps live on.")
            OutlinedButton(onClick = onOpenSettings) { Text("Add a server") }
        }
    }
}

/** SCREEN 5: cloud device. */
@Composable
fun DeviceScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshDevice() }
    val settings = vm.settings.collectAsStateCompat()
    val d = vm.device
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloud device", style = MaterialTheme.typography.headlineMedium)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) { NeedsServerCard(onOpenSettings); return@Column }
        if (d == null) { EmptyHint("No device information. Check the server address in Settings."); return@Column }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (d.status == "running") Good else Warn)
                    Text(d.status.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge)
                }
                d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                InfoRow("Android version", d.androidVersion.ifBlank { "—" })
                InfoRow("Model", d.model.ifBlank { "—" })
                InfoRow("CPU allocated", "${d.cpuCores} cores" + if (d.status == "running") " · ${"%.0f".format(d.cpuPercent)}% used" else "")
                InfoRow("RAM allocated", "${d.ramMb} MB" + if (d.status == "running") " · ${d.ramUsedMb} MB used" else "")
                InfoRow("Storage allocated", "${d.diskGb} GB · ${formatMb(d.diskUsedMb)} used")
                InfoRow("Device ID", d.id)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.deviceAction("start") }, enabled = d.status == "stopped" || d.status == "error") { Text("Start") }
            OutlinedButton(onClick = { confirm = "restart" }, enabled = d.status == "running") { Text("Restart") }
            OutlinedButton(onClick = { confirm = "stop" }, enabled = d.status == "running") { Text("Shut down") }
        }
        Text("Installed apps and their data are kept on the device's persistent disk, so restarting or shutting down does not erase them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (action == "stop") "Shut down the device?" else "Restart the device?") },
            text = { Text("Running apps are closed. Saved data stays on the persistent disk.") },
            confirmButton = { TextButton(onClick = { vm.deviceAction(action); confirm = null }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
