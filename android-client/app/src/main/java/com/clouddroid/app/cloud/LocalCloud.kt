package com.clouddroid.app.cloud

import android.content.Context
import android.net.Uri
import com.clouddroid.app.core.CloudClient
import com.clouddroid.app.core.CloudException
import com.clouddroid.app.core.RemoteFile
import com.clouddroid.app.stream.StreamHub
import com.clouddroid.app.core.MegaClient
import com.clouddroid.app.core.Paths
import com.clouddroid.app.core.TeraBoxClient
import com.clouddroid.app.device.CloudEntry
import com.clouddroid.app.device.CloudProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Talks to TeraBox and MEGA directly from the phone: no CloudDroid server is needed to sign in, browse, upload or
 * delete. Sign-ins are stored encrypted with a key held by the Android Keystore.
 *
 * - MEGA: e-mail + password (and a 6-digit code if the account has two-factor). The password is used once to sign in;
 *   only MEGA's session blob is kept, so the password is never stored.
 * - TeraBox: sign in on TeraBox's own website in a WebView; only the session cookie is kept. Unofficial.
 */
class LocalCloud(private val context: Context) {
    private val prefs = context.getSharedPreferences("clouddroid_accounts", Context.MODE_PRIVATE)
    private val transport = OkHttpTransport()
    private val cache = HashMap<String, CloudClient>()

    // ------------------------------------------------------------------ stored accounts
    private fun load(provider: String): JSONObject? {
        val enc = prefs.getString(provider, null) ?: return null
        return try { JSONObject(SecretBox.decrypt(enc)) } catch (e: Exception) { null }
    }

    private fun save(provider: String, json: JSONObject) {
        prefs.edit().putString(provider, SecretBox.encrypt(json.toString())).apply()
        synchronized(cache) { cache.remove(provider) }
    }

    fun isConnected(provider: String) = load(provider) != null

    /** The TeraBox cookie and site, for handing to an optional CloudDroid server. */
    fun teraBoxSession(): Pair<String, String>? = load("terabox")?.let { it.getString("cookie") to it.getString("site") }

    fun disconnect(provider: String) {
        prefs.edit().remove(provider).apply()
        synchronized(cache) { cache.remove(provider) }
    }

    private fun client(provider: String): CloudClient {
        synchronized(cache) { cache[provider]?.let { return it } }
        val a = load(provider) ?: throw BackendException(401, "${if (provider == "mega") "MEGA" else "TeraBox"} is not connected.")
        val c: CloudClient = try {
            if (provider == "mega") MegaClient(transport, a.getString("email"), "", a.optString("appKey"), a.getString("session"))
            else TeraBoxClient(transport, a.getString("site"), a.getString("cookie"))
        } catch (e: IllegalArgumentException) {
            throw BackendException(400, e.message ?: "Invalid account")
        }
        synchronized(cache) { cache[provider] = c }
        return c
    }

    // ------------------------------------------------------------------ error handling
    private suspend fun <T> io(provider: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CloudException) {
            if (e.status == 401 && !e.message.orEmpty().contains("two-factor")) disconnect(provider)
            throw BackendException(e.status, e.message ?: "Request failed")
        } catch (e: BackendException) {
            throw e
        } catch (e: IOException) {
            throw BackendException(0, "Network problem: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BackendException(400, e.message ?: "Invalid request")
        }
    }

    // ------------------------------------------------------------------ connect
    /** @throws BackendException with a message containing "two-factor" when a code is needed. */
    suspend fun connectMega(email: String, password: String, twoFactor: String, appKey: String) {
        val session = io("mega") { MegaClient.signIn(transport, email.trim(), password, twoFactor, appKey) }
        save("mega", JSONObject().put("email", email.trim().lowercase()).put("session", session).put("appKey", appKey.trim()))
    }

    /** Checks the cookie really works (reads storage usage) before keeping it. */
    suspend fun connectTeraBox(cookie: String, site: String) {
        val probe = try { TeraBoxClient(transport, site, cookie) } catch (e: IllegalArgumentException) {
            throw BackendException(400, e.message ?: "Invalid TeraBox sign-in")
        }
        io("terabox") { probe.quota() }
        save("terabox", JSONObject().put("cookie", cookie).put("site", site))
    }

    // ------------------------------------------------------------------ operations
    suspend fun status(provider: String): CloudProviderStatus {
        val a = load(provider) ?: return CloudProviderStatus(provider, connected = false)
        val q = io(provider) { client(provider).quota() }
        val account = if (provider == "mega") a.optString("email") else a.optString("site").removePrefix("https://")
        return CloudProviderStatus(provider, connected = true, account = account,
            usedBytes = q?.used?.coerceAtLeast(0) ?: 0, totalBytes = q?.total?.coerceAtLeast(0) ?: 0)
    }

    suspend fun list(provider: String, path: String): List<CloudEntry> = io(provider) {
        client(provider).list(Paths.normalize(path)).map {
            CloudEntry(it.path, it.name(), it.dir, if (it.dir) 0 else it.size.coerceAtLeast(0))
        }
    }

    /** A file read on demand from the drive (only the parts that are read are fetched). */
    suspend fun openRemote(provider: String, path: String, size: Long, diskMb: Int): RemoteFile = io(provider) {
        StreamHub.open(context, client(provider), provider, Paths.normalize(path), size, diskMb)
    }

    /** Creates the folder and any missing parents; a folder that already exists is fine. */
    suspend fun ensureDirs(provider: String, path: String) = io(provider) {
        val c = client(provider)
        var cur = ""
        for (part in Paths.normalize(path).split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            try { c.mkdir(cur) } catch (e: CloudException) {
                if (e.status != 405 && !e.message.orEmpty().contains("already exists")) throw e
            }
        }
    }

    suspend fun uploadFile(provider: String, dest: String, file: File) =
        io(provider) { client(provider).upload(Paths.normalize(dest), file, Paths.mimeFor(Paths.name(dest))) }

    /** Downloads a whole file into [dest] (used for small things such as a data backup, then deleted). */
    suspend fun downloadTo(provider: String, path: String, dest: File) =
        io(provider) { client(provider).download(Paths.normalize(path), dest) }

    suspend fun mkdir(provider: String, path: String) = io(provider) { client(provider).mkdir(Paths.normalize(path)) }

    suspend fun delete(provider: String, path: String, isDir: Boolean) =
        io(provider) { client(provider).delete(Paths.normalize(path), isDir) }

    /**
     * Uploads a file picked on the phone. It is first copied to the app's cache (the clients upload from a file),
     * so free space equal to the file size is needed temporarily; the copy is deleted afterwards.
     */
    suspend fun upload(
        provider: String, destPath: String, uri: Uri, size: Long, onProgress: (Float) -> Unit,
    ) {
        val dir = File(context.cacheDir, "upload").apply { mkdirs() }
        val tmp = File(dir, "u-${System.nanoTime()}")
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (size > 0) onProgress(0.5f * done / size)
                        }
                    }
                } ?: throw BackendException(0, "Cannot open the selected file")
            }
            onProgress(0.5f)
            io(provider) { client(provider).upload(Paths.normalize(destPath), tmp, Paths.mimeFor(Paths.name(destPath))) }
            onProgress(1f)
        } finally {
            tmp.delete()
        }
    }
}
