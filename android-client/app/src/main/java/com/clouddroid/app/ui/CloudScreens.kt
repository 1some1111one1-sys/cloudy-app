package com.clouddroid.app.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clouddroid.app.cloud.TeraBoxLoginActivity
import com.clouddroid.app.device.CloudEntry

/** SCREEN 2: the two drive cards. */
@Composable
fun CloudStorageScreen(vm: AppViewModel, onBrowse: (String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshProvidersAsync() }
    var megaDialog by remember { mutableStateOf(false) }
    var uploadTo by remember { mutableStateOf<String?>(null) }

    val teraLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val d = r.data ?: return@rememberLauncherForActivityResult
            val cookie = d.getStringExtra(TeraBoxLoginActivity.EXTRA_COOKIE) ?: return@rememberLauncherForActivityResult
            vm.connectTeraBox(cookie, d.getStringExtra(TeraBoxLoginActivity.EXTRA_SITE) ?: TeraBoxLoginActivity.DEFAULT_SITE)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = uploadTo
        if (uri != null && p != null) vm.upload(p, "/CloudDroid/apks", uri)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloud storage", style = MaterialTheme.typography.headlineMedium)
        Text("APKs and backups live on your TeraBox or MEGA drive. The drive is a file store only; apps run on the remote device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in PROVIDERS) {
            val s = vm.providers[p]
            val connected = s?.connected == true
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(if (connected) Good else MaterialTheme.colorScheme.outline)
                        Text(providerName(p).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(if (connected) "Connected" + (s?.account?.let { " · $it" } ?: "") else "Not connected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!connected && s?.error != null) Text(s.error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (connected) UsageBar(s!!.usedBytes, s.totalBytes)
                    if (p == "terabox" && !connected) {
                        Text("Unofficial: you sign in on TeraBox's own website and CloudDroid keeps only the session cookie. " +
                            "It can stop working if TeraBox changes its site.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!connected) {
                            Button(onClick = {
                                if (p == "terabox") teraLogin.launch(Intent(context, TeraBoxLoginActivity::class.java))
                                else megaDialog = true
                            }) { Text(if (p == "terabox") "Sign in on TeraBox" else "Connect MEGA") }
                        } else {
                            FilledTonalButton(onClick = { onBrowse(p) }) { Icon(Icons.Filled.Folder, null); Text(" Browse") }
                            FilledTonalButton(onClick = { uploadTo = p; picker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.Upload, null); Text(" Upload")
                            }
                            TextButton(onClick = { vm.disconnect(p) }) { Text("Disconnect") }
                        }
                    }
                }
            }
        }
        vm.transfer?.let { (label, progress) ->
            Card { Column(Modifier.padding(16.dp)) {
                Text(label)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } }
        }
    }

    if (megaDialog) MegaDialog(onDismiss = { megaDialog = false }) { email, pw ->
        megaDialog = false
        vm.connectMega(email, pw)
    }
}

@Composable
private fun MegaDialog(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect MEGA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MEGA has no web sign-in token for apps, so your e-mail and password are sent over HTTPS to YOUR " +
                    "CloudDroid server, which logs in with MEGAcmd. They are not stored by this app.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(enabled = email.isNotBlank() && pw.isNotBlank(), onClick = { onConnect(email.trim(), pw) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * File browser for one drive. With [pickApk] a tap on an .apk installs it; otherwise it manages files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, provider: String, pickApk: Boolean, onBack: () -> Unit, onInstall: (String, String) -> Unit) {
    var path by remember { mutableStateOf("/") }
    var confirmDelete by remember { mutableStateOf<CloudEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    LaunchedEffect(path, provider) { vm.browse(provider, path) }
    fun up() { path = if (path == "/") "/" else path.substringBeforeLast('/').ifEmpty { "/" } }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(provider, path, uri) { vm.browse(provider, path) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column {
                Text(providerName(provider) + if (pickApk) " · choose an APK" else "")
                Text(path, style = MaterialTheme.typography.bodySmall)
            } },
            navigationIcon = { IconButton(onClick = { if (path == "/") onBack() else up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                IconButton(onClick = { uploader.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Upload, "Upload here") }
                IconButton(onClick = { vm.browse(provider, path) }) { Icon(Icons.Filled.Refresh, "Refresh") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad)) {
            vm.transfer?.let { (label, progress) ->
                Column(Modifier.padding(16.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (vm.browseLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.browseLoading && vm.browseEntries.isEmpty()) EmptyHint("This folder is empty.")
            LazyColumn {
                items(vm.browseEntries.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })), key = { it.path }) { e ->
                    val isApk = e.name.endsWith(".apk", true)
                    ListItem(
                        modifier = Modifier.clickable {
                            when {
                                e.isDir -> path = e.path
                                isApk && pickApk -> onInstall(provider, e.path)
                            }
                        },
                        leadingContent = { Icon(if (e.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        headlineContent = { Text(e.name) },
                        supportingContent = { if (!e.isDir) Text(formatBytes(e.size)) },
                        trailingContent = {
                            Row {
                                if (isApk && !pickApk) TextButton(onClick = { onInstall(provider, e.path) }) { Text("Install") }
                                IconButton(onClick = { confirmDelete = e }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        },
                    )
                }
            }
        }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text("This deletes it from your ${providerName(provider)} drive.") },
            confirmButton = { TextButton(onClick = { vm.deleteCloudFile(provider, e.path, path); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                vm.makeFolder(provider, (if (path == "/") "" else path) + "/" + name.trim(), path); newFolder = false
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        )
    }
}
