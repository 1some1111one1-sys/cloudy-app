package com.clouddroid.app.virtual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.clouddroid.app.cloud.LocalCloud
import com.clouddroid.app.core.ZipPack
import com.clouddroid.app.device.CloudEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An app that runs inside CloudDroid (it is NOT installed in Android). */
data class VirtualApp(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    /** True when the app is on this phone now; false when it only lives on the drive (it comes back when you run it). */
    val onPhone: Boolean,
)

/**
 * Runs APKs inside CloudDroid with the embedded virtualization engine, using the drive as the permanent store:
 *
 *  - Adding an app streams its APK from the drive into the engine (a temporary file in the cache, deleted right after).
 *  - "Save and free space" zips the app's private data (readable here, because the app runs inside CloudDroid) to
 *    /CloudDroid/data/<package>/ on the drive and removes the app and its data from the phone.
 *  - "Run" brings the app back from the drive (APK + latest saved data) if it is not on the phone, then starts it.
 *
 * Experimental: the engine uses hidden Android APIs and some apps refuse to run in it.
 */
class VirtualApps(private val context: Context, private val cloud: LocalCloud, private val diskMb: () -> Int) {
    private val userId = 0
    private val prefs = context.getSharedPreferences("clouddroid_virtual", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ list / run / remove

    suspend fun list(): List<VirtualApp> = withContext(Dispatchers.IO) {
        val core = BlackBoxCore.get()
        val pm = BlackBoxCore.getPackageManager()
        val out = ArrayList<VirtualApp>()
        for (ai in core.getInstalledApplications(0, userId)) {
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Throwable) { ai.packageName }
            val icon = try {
                val d = pm.getApplicationIcon(ai)
                val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96); d.draw(Canvas(b)); b.asImageBitmap()
            } catch (e: Throwable) { null }
            out += VirtualApp(ai.packageName, label, icon, true)
        }
        // apps added earlier and since freed from the phone: they still live on the drive
        val here = out.map { it.pkg }.toSet()
        for (key in prefs.all.keys) if (key.startsWith("src:")) {
            val pkg = key.removePrefix("src:")
            if (pkg !in here) out += VirtualApp(pkg, prefs.getString("label:$pkg", pkg) ?: pkg, null, false)
        }
        out.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(pkg: String) = try { BlackBoxCore.get().isInstalled(pkg, userId) } catch (e: Throwable) { false }

    /** Starts the app inside CloudDroid. Call on the main thread. */
    fun launch(pkg: String): Boolean = BlackBoxCore.get().launchApk(pkg, userId)

    fun stop(pkg: String) = BlackBoxCore.get().stopPackage(pkg, userId)

    /** Removes the app and its data from this phone (the drive copy, if any, stays). */
    fun remove(pkg: String) {
        BlackBoxCore.get().uninstallPackageAsUser(pkg, userId)
    }

    // ------------------------------------------------------------------ add from the drive

    /**
     * Streams a single APK from the drive into the engine. Split/bundle apps (several APK files, .xapk, .apks) are not
     * supported by the engine. Returns the package name.
     */
    suspend fun addFromCloud(provider: String, entry: CloudEntry, onProgress: (Float) -> Unit): String {
        if (!entry.name.endsWith(".apk", true)) throw IOException("Only single .apk files can run inside CloudDroid (not .xapk / .apks bundles).")
        val remote = cloud.openRemote(provider, entry.path, entry.size, diskMb())
        val dir = File(context.cacheDir, "guest").apply { mkdirs() }
        val tmp = File(dir, "g-${System.nanoTime()}.apk")
        try {
            withContext(Dispatchers.IO) {
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var pos = 0L
                    while (pos < remote.size()) {
                        val n = remote.read(pos, buf, 0, buf.size)
                        if (n <= 0) throw IOException("The drive stopped sending the APK")
                        out.write(buf, 0, n); pos += n
                        onProgress(pos.toFloat() / remote.size().coerceAtLeast(1))
                    }
                }
            }
            val r = withContext(Dispatchers.IO) { BlackBoxCore.get().installPackageAsUser(tmp, userId) }
            if (!r.success) throw IOException(r.msg ?: "The engine could not add this app")
            val pkg = r.packageName ?: throw IOException("The engine did not report the app's package name")
            val label = try {
                val ai = BlackBoxCore.get().getInstalledApplications(0, userId).first { it.packageName == pkg }
                BlackBoxCore.getPackageManager().getApplicationLabel(ai).toString()
            } catch (e: Throwable) { pkg }
            prefs.edit().putString("src:$pkg", "$provider|${entry.path}|${entry.size}").putString("label:$pkg", label).apply()
            return pkg
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ data on the drive

    private fun dataRoots(pkg: String) = listOf(
        "data" to BEnvironment.getDataDir(pkg, userId),
        "ext" to BEnvironment.getExternalDataDir(pkg, userId),
    )

    private fun skip(rel: String) = rel == "lib" || rel.startsWith("lib/") || rel == "cache" || rel.startsWith("cache/") ||
        rel == "code_cache" || rel.startsWith("code_cache/")

    private fun addDir(w: ZipPack.Writer, base: String, dir: File, rel: String) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (base == "data" && skip(r)) continue
            if (f.isDirectory) addDir(w, base, f, r)
            else if (f.isFile) f.inputStream().use { w.add("$base/$r", it) }
        }
    }

    /** Zips the app's data into the cache, uploads it to the drive, deletes the zip. */
    suspend fun saveData(provider: String, pkg: String): String {
        val dir = File(context.cacheDir, "databackup").apply { mkdirs() }
        val tmp = File(dir, "v-$pkg-${System.nanoTime()}.zip")
        try {
            val count = withContext(Dispatchers.IO) {
                tmp.outputStream().buffered().use { out ->
                    ZipPack.Writer(out).use { w ->
                        for ((base, d) in dataRoots(pkg)) if (d.isDirectory) addDir(w, base, d, "")
                        w.count()
                    }
                }
            }
            if (count == 0) return "Nothing to save yet: this app has not stored any data."
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            cloud.ensureDirs(provider, "/CloudDroid/data/$pkg")
            cloud.uploadFile(provider, "/CloudDroid/data/$pkg/$stamp.zip", tmp)
            return "Saved $count file(s) to ${if (provider == "terabox") "TeraBox" else "MEGA"}."
        } finally {
            tmp.delete()
        }
    }

    suspend fun latestBackup(provider: String, pkg: String): CloudEntry? = try {
        cloud.list(provider, "/CloudDroid/data/$pkg").filter { !it.isDir && it.name.endsWith(".zip") }.maxByOrNull { it.name }
    } catch (e: Exception) { null }

    /** Downloads a data zip to the cache and writes its files back into the app's private folders. */
    suspend fun restoreData(provider: String, pkg: String, backup: CloudEntry): Int {
        val dir = File(context.cacheDir, "databackup").apply { mkdirs() }
        val tmp = File(dir, "r-$pkg-${System.nanoTime()}.zip")
        try {
            cloud.downloadTo(provider, backup.path, tmp)
            return withContext(Dispatchers.IO) {
                val roots = dataRoots(pkg).toMap()
                tmp.inputStream().buffered().use { input ->
                    ZipPack.unpack(input) { rel ->
                        val base = rel.substringBefore('/')
                        val root = roots[base] ?: throw IOException("Unknown folder in the backup: $base")
                        val f = File(root, rel.substringAfter('/'))
                        f.parentFile?.mkdirs()
                        f.outputStream()
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ the full cycle

    /** Saves the app's data to the drive, then removes the app and its data from the phone. */
    suspend fun saveAndFree(provider: String, pkg: String): String {
        val msg = saveData(provider, pkg)
        withContext(Dispatchers.IO) { remove(pkg) }
        return "$msg Removed from this phone; run it again to bring it back from the drive."
    }

    /** Brings an app back from the drive (APK, then its latest saved data) if it is not on the phone. */
    suspend fun ensureOnPhone(pkg: String, onProgress: (Float) -> Unit) {
        if (isInstalled(pkg)) return
        val (provider, path, size) = sourceOf(pkg) ?: throw IOException("There is no copy of this app on your drive.")
        val entry = CloudEntry(path, path.substringAfterLast('/'), false, size)
        addFromCloud(provider, entry, onProgress)
        latestBackup(provider, pkg)?.let { restoreData(provider, pkg, it) }
    }

    /** Forgets an app completely on this phone (the drive keeps its files). */
    fun forget(pkg: String) {
        prefs.edit().remove("src:$pkg").remove("label:$pkg").apply()
    }

    /** What the drive holds for an app that was added earlier: where its APK is. */
    fun sourceOf(pkg: String): Triple<String, String, Long>? {
        val p = prefs.getString("src:$pkg", null)?.split("|") ?: return null
        return if (p.size == 3) Triple(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
    }
}
