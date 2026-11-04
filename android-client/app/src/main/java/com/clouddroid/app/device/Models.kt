package com.clouddroid.app.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val id: String = "",
    val status: String = "unknown", // stopped | starting | running | stopping | error
    @SerialName("android_version") val androidVersion: String = "",
    @SerialName("cpu_cores") val cpuCores: Int = 0,
    @SerialName("ram_mb") val ramMb: Int = 0,
    @SerialName("disk_gb") val diskGb: Int = 0,
    @SerialName("disk_used_mb") val diskUsedMb: Long = 0,
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("ram_used_mb") val ramUsedMb: Long = 0,
    val model: String = "",
    val error: String? = null,
)

@Serializable
data class InstalledApp(
    @SerialName("package_name") val packageName: String,
    val label: String = "",
    @SerialName("version_name") val versionName: String = "",
    @SerialName("size_mb") val sizeMb: Long = 0,
    @SerialName("last_launched") val lastLaunched: String? = null,
    val status: String = "installed", // installing | installed | failed
)

@Serializable
data class CloudProviderStatus(
    val provider: String, // "terabox" | "mega"
    val connected: Boolean = false,
    val account: String? = null,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    val error: String? = null,
)

@Serializable
data class CloudEntry(
    val path: String,
    val name: String,
    @SerialName("is_dir") val isDir: Boolean,
    val size: Long = 0,
)

@Serializable
data class InstallRequest(val provider: String, val path: String)

@Serializable
data class InstallJob(
    val id: String,
    val state: String, // queued | downloading | inspecting | installing | done | failed
    val progress: Double = 0.0,
    @SerialName("package_name") val packageName: String? = null,
    val label: String? = null,
    val error: String? = null,
)

@Serializable
data class BackupInfo(
    val id: String,
    @SerialName("package_name") val packageName: String,
    val provider: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("files_changed") val filesChanged: Int = 0,
    @SerialName("files_total") val filesTotal: Int = 0,
    val kind: String = "incremental", // full | incremental
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class StreamSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class MegaLoginRequest(val email: String, val password: String)

@Serializable
data class StreamSessionRequest(
    @SerialName("package_name") val packageName: String? = null,
    val profile: String = "BALANCED",
)

@Serializable
data class BackupRequest(val provider: String, @SerialName("backup_id") val backupId: String? = null)
