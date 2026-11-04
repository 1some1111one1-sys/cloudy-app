package com.clouddroid.app.cloud

import com.clouddroid.app.device.CloudEntry
import com.clouddroid.app.device.CloudProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendException(val status: Int, message: String) : IOException(message) {
    /** The cloud account's session expired; the UI should offer "Sign in again". */
    val authExpired get() = status == 401
}

@Serializable
private data class TeraBoxSessionRequest(val cookie: String, val site: String)

/** REST client for the CloudDroid backend. Only the cloud-storage calls needed for sign-in and browsing so far. */
class BackendApi(private val baseUrl: String, private val token: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw BackendException(r.code, body.ifBlank { "HTTP ${r.code}" })
            body
        }
    }

    private fun builder(path: String) = Request.Builder()
        .url("$baseUrl$path")
        .header("Authorization", "Bearer $token")

    /** Hands the TeraBox website session cookie (not the password) to the backend, over HTTPS. */
    suspend fun connectTeraBox(cookie: String, site: String) {
        val body = json.encodeToString(TeraBoxSessionRequest.serializer(), TeraBoxSessionRequest(cookie, site))
        call(builder("/api/storage/terabox/session").post(body.toRequestBody(jsonType)).build())
    }

    suspend fun providerStatus(provider: String): CloudProviderStatus =
        json.decodeFromString(call(builder("/api/storage/$provider/status").get().build()))

    suspend fun list(provider: String, path: String): List<CloudEntry> {
        val p = java.net.URLEncoder.encode(path, "UTF-8")
        return json.decodeFromString(ListSerializer(CloudEntry.serializer()),
            call(builder("/api/storage/$provider/list?path=$p").get().build()))
    }

    suspend fun disconnect(provider: String) {
        call(builder("/api/storage/$provider/session").delete().build())
    }
}
