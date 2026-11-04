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
data class LoginRequest(val provider: String, val email: String, val password: String)

@Serializable
data class InstallRequest(val provider: String, val path: String)

@Serializable
data class InstallJob(
    val id: String,
    val state: String, // downloading | inspecting | installing | done | failed
    val progress: Double = 0.0,
    @SerialName("package_name") val packageName: String? = null,
    val error: String? = null,
)
