package com.clouddroid.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clouddroid.app.local.PhoneApp

/** The icon of an app installed on this phone. */
@Composable
fun PhoneIcon(pkg: String, size: Int = 40) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96); d.draw(Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size.dp)) else Spacer(Modifier.size(size.dp))
}

/** Progress shown while an app is installed from the drive or a backup runs. */
@Composable
fun BusyCard(vm: AppViewModel) {
    val t = vm.transfer
    val b = vm.busy
    if (t == null && b == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t?.first ?: b.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            if (t != null) LinearProgressIndicator(progress = { t.second.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/**
 * SCREEN 3 without a server: the apps installed on THIS phone. They run on this phone (its own RAM and cache). From
 * here an app's APK and its saved data can be put on the drive, and brought back later.
 */
@Composable
fun PhoneLibrary(vm: AppViewModel, query: String) {
    val context = LocalContext.current
    val settings = vm.settings.collectAsStateCompat()
    LaunchedEffect(Unit) { vm.refreshPhoneApps(); vm.refreshProvidersAsync() }

    val connected = PROVIDERS.filter { vm.providers[it]?.connected == true }
    val shown = vm.phoneApps
        .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedWith(compareByDescending<PhoneApp> { it.pkg in settings.pinned }.thenBy { it.label.lowercase() })

    var saveApkFor by remember { mutableStateOf<PhoneApp?>(null) }
    var saveDataFor by remember { mutableStateOf<PhoneApp?>(null) }
    var restoreFor by remember { mutableStateOf<PhoneApp?>(null) }
    var uninstall by remember { mutableStateOf<PhoneApp?>(null) }
    // what to do once a folder has been chosen
    var afterPick by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var pickPkg by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.vault.setDataFolder(pickPkg, uri)
            afterPick?.invoke(uri)
        }
        afterPick = null
    }
    fun withFolder(pkg: String, action: (Uri) -> Unit) {
        val known = vm.vault.dataFolder(pkg)
        if (known != null) action(known) else { pickPkg = pkg; afterPick = action; picker.launch(null) }
    }

    BusyCard(vm)
    if (connected.isEmpty()) {
        Text("Connect TeraBox or MEGA in the Cloud tab to save apps and their data there.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (shown.isEmpty()) EmptyHint("No apps found.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(shown, key = { it.pkg }) { app ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhoneIcon(app.pkg, 44)
                        Column(Modifier.weight(1f)) {
                            Text(app.label.ifBlank { app.pkg }, fontWeight = FontWeight.SemiBold)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("v${app.versionName.ifBlank { "?" }} · ${formatBytes(app.sizeBytes)}" +
                                if (app.apkFiles.size > 1) " · ${app.apkFiles.size} parts" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.togglePinned(app.pkg) }) {
                            Icon(if (app.pkg in settings.pinned) Icons.Filled.Star else Icons.Filled.StarBorder, "Pin")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.launchLocal(app.pkg) }) { Text("Open") }
                        OutlinedButton(enabled = connected.isNotEmpty(), onClick = { saveApkFor = app }) { Text("Save app") }
                        OutlinedButton(enabled = connected.isNotEmpty(), onClick = { saveDataFor = app }) { Text("Save data") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = connected.isNotEmpty(), onClick = { restoreFor = app; vm.loadDataBackups(app.pkg) }) { Text("Restore data") }
                        TextButton(onClick = { uninstall = app }) { Text("Uninstall") }
                    }
                }
            }
        }
    }

    saveApkFor?.let { app ->
        ProviderDialog("Save ${app.label} to…", "Copies the app's APK file(s) to /CloudDroid/apps on your drive. You can install it again from the Add tab.",
            connected, onDismiss = { saveApkFor = null }) { p -> vm.saveApkToCloud(p, app); saveApkFor = null }
    }
    saveDataFor?.let { app ->
        ProviderDialog("Save ${app.label}'s data to…",
            "Android does not let CloudDroid read another app's private data (that needs a rooted phone). " +
                "This saves a FOLDER YOU CHOOSE, such as the game's save folder in shared storage. You pick it once for this app.",
            connected, onDismiss = { saveDataFor = null }) { p ->
            val a = app; saveDataFor = null
            withFolder(a.pkg) { tree -> vm.saveData(p, a.pkg, tree) }
        }
    }
    restoreFor?.let { app ->
        AlertDialog(
            onDismissRequest = { restoreFor = null },
            title = { Text("Restore ${app.label}'s data") },
            text = {
                if (vm.dataBackups.isEmpty()) Text("No saved data found on your drives.")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Files are written into the folder you chose for this app, replacing files with the same name.",
                        style = MaterialTheme.typography.bodySmall)
                    for ((p, b) in vm.dataBackups) TextButton(onClick = {
                        val a = app; restoreFor = null
                        withFolder(a.pkg) { tree -> vm.restoreData(p, b, tree) }
                    }) { Text("${providerName(p)} · ${b.name.removeSuffix(".zip")} · ${formatBytes(b.size)}") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { restoreFor = null }) { Text("Close") } },
        )
    }
    uninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstall = null },
            title = { Text("Uninstall ${app.label}?") },
            text = { Text("Android removes the app and its private data from this phone. Save the app and its data to your drive first if you want them back.") },
            confirmButton = { TextButton(onClick = { vm.uninstallLocal(app.pkg); uninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { uninstall = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProviderDialog(title: String, text: String, providers: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Row { for (p in providers) TextButton(onClick = { onPick(p) }) { Text(providerName(p)) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Apps running INSIDE CloudDroid (no Android install). The drive is their permanent store: "Save and free space"
 * puts the app's data on the drive and removes the app from this phone; "Run" brings it back first if needed.
 */
@Composable
fun VirtualLibrary(vm: AppViewModel, query: String) {
    LaunchedEffect(Unit) { vm.refreshVirtual(); vm.refreshProvidersAsync() }
    val connected = PROVIDERS.filter { vm.providers[it]?.connected == true }
    val shown = vm.virtualApps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
    var freeFor by remember { mutableStateOf<com.clouddroid.app.virtual.VirtualApp?>(null) }
    var removeFor by remember { mutableStateOf<com.clouddroid.app.virtual.VirtualApp?>(null) }

    BusyCard(vm)
    Text("Apps here run inside CloudDroid and are not installed in Android. Add one from the Add tab. Experimental: apps that check their environment (many games, banking, Google Play apps) may refuse to start.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (shown.isEmpty()) EmptyHint("No apps added yet.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(shown, key = { it.pkg }) { app ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (app.icon != null) Image(app.icon, null, Modifier.size(44.dp)) else Spacer(Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text(if (app.onPhone) "On this phone" else "On your drive only",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.runVirtual(app.pkg) }) { Text("Run") }
                        OutlinedButton(enabled = app.onPhone && connected.isNotEmpty(), onClick = { freeFor = app }) { Text("Save & free space") }
                        TextButton(onClick = { removeFor = app }) { Text("Remove") }
                    }
                }
            }
        }
    }
    freeFor?.let { app ->
        ProviderDialog("Save ${app.label} to…",
            "Saves this app's data (progress, settings) to /CloudDroid/data on your drive, then removes the app and its data from this phone. Run it again any time to bring it back.",
            connected, onDismiss = { freeFor = null }) { p -> vm.freeVirtual(p, app.pkg); freeFor = null }
    }
    removeFor?.let { app ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Remove ${app.label}?") },
            text = { Text("Removes it from CloudDroid, including its data on this phone. Data you saved to the drive stays there.") },
            confirmButton = { TextButton(onClick = { vm.removeVirtual(app.pkg, true); removeFor = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Cancel") } },
        )
    }
}
