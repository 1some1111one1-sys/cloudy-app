package com.clouddroid.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clouddroid.app.cloud.BackendApi
import com.clouddroid.app.cloud.BackendException
import com.clouddroid.app.cloud.LocalCloud
import com.clouddroid.app.local.AppVault
import com.clouddroid.app.local.InstallEvents
import com.clouddroid.app.local.LocalApps
import com.clouddroid.app.local.LocalInstaller
import com.clouddroid.app.local.PhoneApp
import com.clouddroid.app.core.RemoteFile
import com.clouddroid.app.stream.StreamHub
import com.clouddroid.app.device.*
import com.clouddroid.app.settings.AppSettings
import com.clouddroid.app.settings.SettingsStore
import com.clouddroid.app.settings.StreamProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val PROVIDERS = listOf("terabox", "mega")
fun providerName(p: String) = if (p == "terabox") "TeraBox" else "MEGA"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SettingsStore(app)
    /** TeraBox and MEGA are used directly from the phone; no server is needed for them. */
    val cloud = LocalCloud(app)
    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    var device by mutableStateOf<DeviceInfo?>(null)
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var providers by mutableStateOf<Map<String, CloudProviderStatus>>(emptyMap())
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    /** Set when MEGA asked for a two-factor code, so the sign-in dialog shows that field. */
    var megaNeedsCode by mutableStateOf(false)

    // file browser
    var browseEntries by mutableStateOf<List<CloudEntry>>(emptyList())
    var browseLoading by mutableStateOf(false)

    // streaming a file from the drive without downloading it
    var streamProvider by mutableStateOf("")
    var streamEntry by mutableStateOf<CloudEntry?>(null)
    suspend fun openRemote(): RemoteFile {
        val e = streamEntry ?: throw IllegalStateException("No file chosen")
        return cloud.openRemote(streamProvider, e.path, e.size, settings.value.streamDiskMb)
    }
    fun streamFile(provider: String, entry: CloudEntry) { streamProvider = provider; streamEntry = entry }
    fun setStreamDiskMb(mb: Int) = viewModelScope.launch { store.setStreamDiskMb(mb) }
    fun clearStreamCache() { StreamHub.clear() }

    // install
    var job by mutableStateOf<InstallJob?>(null)
    var transfer by mutableStateOf<Pair<String, Float>?>(null) // label to progress 0..1
    var backups by mutableStateOf<List<BackupInfo>>(emptyList())

    // ------------------------------------------------------------ apps on this phone (no server)
    private val appContext: Application = app
    val vault = AppVault(app, cloud) { settings.value.streamDiskMb }
    var phoneApps by mutableStateOf<List<PhoneApp>>(emptyList())
    /** Apps whose APKs are saved on a drive: provider to saved app. */
    var savedApps by mutableStateOf<List<Pair<String, AppVault.SavedApp>>>(emptyList())
    /** Data backups of one app on the drives: provider to zip file. */
    var dataBackups by mutableStateOf<List<Pair<String, CloudEntry>>>(emptyList())
    /** "phone" installs on this phone (default); "server" installs on a CloudDroid server, if one is set up. */
    var installTarget by mutableStateOf("phone")
    var busy by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            InstallEvents.message.collect {
                if (it != null) { message = it; InstallEvents.consume(); refreshPhoneApps() }
            }
        }
    }

    fun refreshPhoneApps() { viewModelScope.launch { phoneApps = withContext(Dispatchers.IO) { LocalApps.list(appContext) } } }

    fun launchLocal(pkg: String) {
        if (!LocalApps.launch(appContext, pkg)) message = "This app cannot be opened."
    }

    fun uninstallLocal(pkg: String) = LocalApps.uninstall(appContext, pkg)

    private fun connected(provider: String) = providers[provider]?.connected == true

    fun saveApkToCloud(provider: String, app: PhoneApp) = launchSafe(provider) {
        busy = "Saving ${app.label} to ${providerName(provider)}…"
        try { message = vault.saveApk(provider, app) } finally { busy = null }
    }

    fun loadSavedApps() = launchSafe {
        val out = ArrayList<Pair<String, AppVault.SavedApp>>()
        for (p in PROVIDERS) if (connected(p)) {
            try { vault.savedApps(p).forEach { out += p to it } } catch (e: Exception) { fail(e, p) }
        }
        savedApps = out
    }

    private suspend fun ensureCanInstall(): Boolean {
        if (LocalInstaller.canInstall(appContext)) return true
        message = "Allow CloudDroid to install apps in the screen that just opened, then try again."
        LocalInstaller.openPermissionScreen(appContext)
        return false
    }

    fun installSaved(provider: String, saved: AppVault.SavedApp) = launchSafe(provider) {
        if (!ensureCanInstall()) return@launchSafe
        transfer = "Installing ${saved.pkg}" to 0f
        try { vault.install(provider, saved.apks) { transfer = "Installing ${saved.pkg}" to it } } finally { transfer = null }
    }

    private suspend fun installLocalFile(provider: String, path: String) {
        if (!ensureCanInstall()) return
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val entry = cloud.list(provider, parent).firstOrNull { it.path == path }
            ?: throw IllegalStateException("Cannot find that file on the drive")
        val name = entry.name
        transfer = "Installing $name" to 0f
        try { vault.install(provider, listOf(entry)) { transfer = "Installing $name" to it } } finally { transfer = null }
    }

    fun loadDataBackups(pkg: String) = launchSafe {
        val out = ArrayList<Pair<String, CloudEntry>>()
        for (p in PROVIDERS) if (connected(p)) {
            try { vault.listData(p, pkg).forEach { out += p to it } } catch (e: Exception) { fail(e, p) }
        }
        dataBackups = out
        if (out.isEmpty()) message = "No saved data for this app on your drives yet."
    }

    fun saveData(provider: String, pkg: String, tree: android.net.Uri) = launchSafe(provider) {
        busy = "Saving data to ${providerName(provider)}…"
        try { message = vault.saveData(provider, pkg, tree) } finally { busy = null }
    }

    fun restoreData(provider: String, backup: CloudEntry, tree: android.net.Uri) = launchSafe(provider) {
        busy = "Restoring data from ${providerName(provider)}…"
        try { message = vault.restoreData(provider, backup, tree) } finally { busy = null }
    }

    fun api() = BackendApi(settings.value.serverUrl, settings.value.token)

    fun toast(text: String?) { message = text }

    private fun fail(e: Throwable, provider: String? = null) {
        message = when {
            e is BackendException && e.authExpired && provider == null ->
                "The server rejected the access token, or the account session expired."
            else -> e.message ?: e.toString()
        }
    }

    private fun launchSafe(provider: String? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (e: Exception) { fail(e, provider) }
    }

    // ------------------------------------------------------------ refresh
    fun refreshAll() = launchSafe {
        loading = true
        try {
            val a = api()
            if (a.configured) {
                runCatching { device = a.device() }.onFailure { fail(it) }
                runCatching { apps = a.apps() }
            } else {
                device = null
                apps = emptyList()
            }
            refreshProviders()
        } finally { loading = false }
    }

    /** True when a CloudDroid server is set up. Cloud storage works without one; running apps does not. */
    val hasServer get() = api().configured

    fun refreshDevice() = launchSafe { if (hasServer) device = api().device() }

    fun refreshApps() = launchSafe { if (hasServer) apps = api().apps() }

    suspend fun refreshProviders() {
        val out = mutableMapOf<String, CloudProviderStatus>()
        for (p in PROVIDERS) {
            out[p] = try { cloud.status(p) } catch (e: Exception) {
                if (e is BackendException && e.authExpired) message = "${providerName(p)} session expired. Sign in again."
                CloudProviderStatus(p, connected = false, error = if (e is BackendException && e.authExpired) null else e.message)
            }
        }
        providers = out
    }

    fun refreshProvidersAsync() = launchSafe { refreshProviders() }

    // ------------------------------------------------------------ cloud accounts (direct from the phone)
    fun connectTeraBox(cookie: String, site: String) = launchSafe("terabox") {
        cloud.connectTeraBox(cookie, site)
        refreshProviders()
        message = "TeraBox connected."
        shareWithServer("terabox") { api().connectTeraBox(cookie, site) }
    }

    fun connectMega(email: String, password: String, code: String, appKey: String) = launchSafe("mega") {
        try {
            cloud.connectMega(email, password, code, appKey)
        } catch (e: BackendException) {
            megaNeedsCode = e.message.orEmpty().contains("two-factor", ignoreCase = true)
            throw e
        }
        megaNeedsCode = false
        refreshProviders()
        message = "MEGA connected."
        shareWithServer("mega") { api().connectMega(email, password) }
    }

    /** If a server is set up it also needs the drive (it downloads APKs and stores backups). Failure is not fatal. */
    private suspend fun shareWithServer(provider: String, call: suspend () -> Unit) {
        if (!hasServer) return
        try { call() } catch (e: Exception) {
            message = "${providerName(provider)} is connected on this phone, but your server could not use it: ${e.message}"
        }
    }

    fun disconnect(provider: String) = launchSafe(provider) {
        cloud.disconnect(provider)
        if (hasServer) runCatching { api().disconnect(provider) }
        refreshProviders()
    }

    fun browse(provider: String, path: String) = launchSafe(provider) {
        browseLoading = true
        try { browseEntries = cloud.list(provider, path) } catch (e: Exception) {
            browseEntries = emptyList(); throw e
        } finally { browseLoading = false }
    }

    fun deleteCloudFile(provider: String, path: String, parent: String) = launchSafe(provider) {
        val isDir = browseEntries.firstOrNull { it.path == path }?.isDir ?: false
        cloud.delete(provider, path, isDir)
        browseEntries = cloud.list(provider, parent)
    }

    fun makeFolder(provider: String, path: String, parent: String) = launchSafe(provider) {
        cloud.mkdir(provider, path)
        browseEntries = cloud.list(provider, parent)
    }

    fun displayName(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "upload.apk"; var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0) size = c.getLong(s)
            }
        }
        return name to size
    }

    /** Uploads a file from the phone into [folder] on the drive. */
    fun upload(provider: String, folder: String, uri: Uri, done: (String) -> Unit = {}) = launchSafe(provider) {
        val resolver = getApplication<Application>().contentResolver
        val (name, size) = displayName(resolver, uri)
        val dest = (if (folder.endsWith("/")) folder else "$folder/") + name
        transfer = "Uploading $name" to 0f
        try {
            cloud.upload(provider, dest, uri, size) { transfer = "Uploading $name" to it }
            message = "Uploaded $name to ${providerName(provider)}."
            done(dest)
        } finally { transfer = null }
    }

    // ------------------------------------------------------------ device
    fun deviceAction(action: String) = launchSafe {
        if (!hasServer) { message = "Add a CloudDroid server in Settings first."; return@launchSafe }
        device = api().deviceAction(action)
        // starting takes a while: poll until it settles
        var tries = 0
        while (device?.status in listOf("starting", "stopping") && tries++ < 120) {
            delay(3000)
            device = api().device()
        }
    }

    // ------------------------------------------------------------ apps
    fun install(provider: String, path: String) = launchSafe(provider) {
        if (installTarget == "phone" || !hasServer) {
            installLocalFile(provider, path)
            return@launchSafe
        }
        var j = api().install(provider, path)
        job = j
        while (j.state !in listOf("done", "failed")) {
            delay(1500)
            j = api().job(j.id)
            job = j
        }
        if (j.state == "done") apps = api().apps()
    }

    fun clearJob() { job = null }

    fun uninstall(pkg: String) = launchSafe {
        api().uninstall(pkg)
        apps = api().apps()
    }

    fun stopApp(pkg: String) = launchSafe { api().stopApp(pkg); message = "Stopped $pkg." }

    fun backup(pkg: String, provider: String) = launchSafe(provider) {
        message = "Backing up $pkg…"
        val b = api().backup(pkg, provider)
        message = "Backup done: ${b.filesChanged} of ${b.filesTotal} files uploaded (${b.kind})."
    }

    fun loadBackups(pkg: String) = launchSafe {
        backups = api().backups(pkg)
        if (backups.isEmpty()) message = "No backups for $pkg yet."
    }

    fun restore(pkg: String, b: BackupInfo) = launchSafe(b.provider) {
        message = "Restoring $pkg…"
        api().restore(pkg, b)
        message = "Restore finished. Not every app can be restored perfectly; launch it to check."
    }

    fun endSession(sessionId: String, pkg: String, closeApp: Boolean) {
        val a = api()
        viewModelScope.launch {
            try {
                a.endStream(sessionId)
                if (closeApp) a.stopApp(pkg)
            } catch (_: Exception) { /* the backend also expires idle sessions */ }
        }
    }

    fun togglePinned(pkg: String) = viewModelScope.launch { store.togglePinned(pkg) }

    // ------------------------------------------------------------ settings
    fun saveServer(url: String, token: String) = viewModelScope.launch {
        store.setServer(url, token)
        val tb = cloud.teraBoxSession()
        if (url.isNotBlank() && token.isNotBlank() && tb != null) {
            try { BackendApi(url, token).connectTeraBox(tb.first, tb.second) } catch (_: Exception) {}
        }
        if (url.isNotBlank() && cloud.isConnected("mega"))
            message = "To let your server use MEGA, disconnect and connect MEGA again (the password is not stored)."
    }
    fun setProfile(p: StreamProfile) = viewModelScope.launch { store.setProfile(p) }
    fun setAuto(v: Boolean) = viewModelScope.launch { store.setAutoQuality(v) }
    fun setAudio(v: Boolean) = viewModelScope.launch { store.setAudio(v) }
    fun setAutoBackup(v: Boolean) = viewModelScope.launch { store.setAutoBackup(v) }
    fun addDataUsed(bytes: Long) = viewModelScope.launch { store.addDataUsed(bytes) }
    fun resetDataUsed() = viewModelScope.launch { store.resetDataUsed() }
}
