package com.clouddroid.app.cloud

import android.content.ContentResolver
import android.net.Uri
import com.clouddroid.app.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BackendException(val status: Int, message: String) : IOException(message) {
    val authExpired get() = status == 401
}

@Serializable
private data class TeraBoxSessionRequest(val cookie: String, val site: String)

/** REST client for the CloudDroid backend. Every call needs the server URL and access token from Settings. */
class BackendApi(baseUrl: String, private val token: String) {
    val baseUrl = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    val configured get() = baseUrl.isNotBlank() && token.isNotBlank()

    private fun builder(path: String): Request.Builder {
        if (!configured) throw BackendException(0, "Set the backend URL and access token in Settings first.")
        return Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $token")
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw BackendException(r.code, errorText(body, r.code))
                body
            }
        } catch (e: BackendException) {
            throw e
        } catch (e: IOException) {
            throw BackendException(0, "Cannot reach the backend: ${e.message}")
        }
    }

    private fun errorText(body: String, code: Int): String {
        val detail = Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
        return detail?.replace("\\\"", "\"") ?: body.ifBlank { "HTTP $code" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private inline fun <reified T> post(body: T): RequestBody =
        json.encodeToString(kotlinx.serialization.serializer<T>(), body).toRequestBody(jsonType)
    private val empty get() = "{}".toRequestBody(jsonType)

    // ---- cloud storage
    suspend fun connectTeraBox(cookie: String, site: String) {
        call(builder("/api/storage/terabox/session").post(post(TeraBoxSessionRequest(cookie, site))).build())
    }

    suspend fun connectMega(email: String, password: String) {
        call(builder("/api/storage/mega/session").post(post(MegaLoginRequest(email, password))).build())
    }

    suspend fun disconnect(provider: String) {
        call(builder("/api/storage/$provider/session").delete().build())
    }

    suspend fun providerStatus(provider: String): CloudProviderStatus =
        json.decodeFromString(call(builder("/api/storage/$provider/status").get().build()))

    suspend fun list(provider: String, path: String): List<CloudEntry> =
        json.decodeFromString(ListSerializer(CloudEntry.serializer()),
            call(builder("/api/storage/$provider/list?path=${enc(path)}").get().build()))

    suspend fun mkdir(provider: String, path: String) {
        call(builder("/api/storage/$provider/mkdir?path=${enc(path)}").post(empty).build())
    }

    suspend fun deleteFile(provider: String, path: String) {
        call(builder("/api/storage/$provider/file?path=${enc(path)}").delete().build())
    }

    /** Streams a file from the phone to the cloud drive through the backend. */
    suspend fun uploadFile(
        provider: String, path: String, resolver: ContentResolver, uri: Uri, size: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                input.source().use { src ->
                    var sent = 0L
                    while (true) {
                        val n = src.read(sink.buffer, 256 * 1024)
                        if (n < 0) break
                        sent += n
                        sink.flush()
                        onProgress(sent, size)
                    }
                }
            }
        }
        call(builder("/api/storage/$provider/file?path=${enc(path)}").put(body).build())
    }

    // ---- device
    suspend fun device(): DeviceInfo = json.decodeFromString(call(builder("/api/device").get().build()))
    suspend fun deviceAction(action: String): DeviceInfo =
        json.decodeFromString(call(builder("/api/device/$action").post(empty).build()))

    // ---- apps
    suspend fun apps(): List<InstalledApp> =
        json.decodeFromString(ListSerializer(InstalledApp.serializer()), call(builder("/api/apps").get().build()))

    suspend fun install(provider: String, path: String): InstallJob =
        json.decodeFromString(call(builder("/api/apps/install").post(post(InstallRequest(provider, path))).build()))

    suspend fun job(id: String): InstallJob = json.decodeFromString(call(builder("/api/jobs/$id").get().build()))

    suspend fun stopApp(pkg: String) {
        call(builder("/api/apps/${enc(pkg)}/stop").post(empty).build())
    }

    suspend fun uninstall(pkg: String) {
        call(builder("/api/apps/${enc(pkg)}").delete().build())
    }

    suspend fun backup(pkg: String, provider: String): BackupInfo =
        json.decodeFromString(call(builder("/api/apps/${enc(pkg)}/backup").post(post(BackupRequest(provider))).build()))

    suspend fun backups(pkg: String): List<BackupInfo> =
        json.decodeFromString(ListSerializer(BackupInfo.serializer()),
            call(builder("/api/apps/${enc(pkg)}/backups").get().build()))

    suspend fun restore(pkg: String, backup: BackupInfo) {
        call(builder("/api/apps/${enc(pkg)}/restore").post(post(BackupRequest(backup.provider, backup.id))).build())
    }

    // ---- streaming
    suspend fun startStream(pkg: String?, profile: String): StreamSessionDto =
        json.decodeFromString(call(builder("/api/stream/session")
            .post(post(StreamSessionRequest(pkg, profile))).build()))

    suspend fun endStream(sessionId: String) {
        call(builder("/api/stream/session/${enc(sessionId)}").delete().build())
    }

    fun signalUrl(sessionId: String): Request {
        val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return Request.Builder().url("$ws/ws/signal/${enc(sessionId)}?token=${enc(token)}").build()
    }
}
