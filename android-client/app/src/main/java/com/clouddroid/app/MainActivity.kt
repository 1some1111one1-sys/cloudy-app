package com.clouddroid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clouddroid.app.cloud.BackendApi
import com.clouddroid.app.cloud.TeraBoxLoginActivity
import com.clouddroid.app.settings.SettingsStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { padding -> CloudStorageScreen(Modifier.padding(padding)) }
            }
        }
    }
}

/** Interim Cloud Storage screen: server settings and the TeraBox web sign-in. The full UI comes in Stage 1. */
@Composable
fun CloudStorageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = com.clouddroid.app.settings.AppSettings())
    val scope = rememberCoroutineScope()
    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }
    var status by remember { mutableStateOf("TeraBox: not connected") }

    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent = result.data ?: return@rememberLauncherForActivityResult
        val cookie = data.getStringExtra(TeraBoxLoginActivity.EXTRA_COOKIE) ?: return@rememberLauncherForActivityResult
        val site = data.getStringExtra(TeraBoxLoginActivity.EXTRA_SITE) ?: TeraBoxLoginActivity.DEFAULT_SITE
        scope.launch {
            status = try {
                val api = BackendApi(settings.serverUrl, settings.token)
                api.connectTeraBox(cookie, site)
                val s = api.providerStatus("terabox")
                if (s.connected) "TeraBox: connected (${s.usedBytes / 1_000_000} MB of ${s.totalBytes / 1_000_000} MB used)"
                else "TeraBox: backend did not accept the session" + (s.error?.let { " — $it" } ?: "")
            } catch (e: Exception) {
                "TeraBox: failed — ${e.message}"
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CloudDroid", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(url, { url = it }, label = { Text("Backend URL (https://…)") }, singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("Access token") }, singleLine = true)
        Button(onClick = { scope.launch { store.setServer(url, token) } }) { Text("Save server") }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TeraBox", style = MaterialTheme.typography.titleMedium)
                Text(status)
                Button(
                    enabled = settings.serverUrl.isNotBlank(),
                    onClick = { login.launch(Intent(context, TeraBoxLoginActivity::class.java)) },
                ) { Text("Sign in on the TeraBox website") }
            }
        }
    }
}
