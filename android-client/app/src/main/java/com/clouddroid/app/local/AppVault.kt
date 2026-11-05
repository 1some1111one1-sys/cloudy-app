package com.clouddroid.app.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.clouddroid.app.cloud.LocalCloud
import com.clouddroid.app.core.ZipPack
import com.clouddroid.app.device.CloudEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps apps and their saved data on the user's TeraBox or MEGA drive and brings them back to this phone on demand.
 *
 *     /CloudDroid/apps/<package>/<versionCode>/base.apk (+ split APKs)
 *     /CloudDroid/data/<package>/<yyyyMMdd-HHmmss>.zip
 *
 * Everything runs on this phone; there is no server.
 */
class AppVault(private val context: Context, private val cloud: LocalCloud, private val diskMb: () -> Int) {
    companion object {
        const val ROOT = "/CloudDroid"
        const val APPS = "$ROOT/apps"
        const val DATA = "$ROOT/data"
    }

    data class SavedApp(val pkg: String, val versionCode: Long, val dir: String, val apks: List<CloudEntry>) {
        val sizeBytes get() = apks.sumOf { it.size }
    }

    private val folderPrefs = context.getSharedPreferences("clouddroid_datafolders", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ apps (APK files)

    /** Copies the app's APK file(s) to the drive. Skips files that are already there with the same size. */
    suspend fun saveApk(provider: String, app: PhoneApp): String {
        val dir = "$APPS/${app.pkg}/${app.versionCode}"
        cloud.ensureDirs(provider, dir)
        val existing = cloud.list(provider, dir).associate { it.name to it.size }
        var uploaded = 0
        for (f in app.apkFiles) {
            if (existing[f.name] == f.length()) continue
            cloud.uploadFile(provider, "$dir/${f.name}", f)
            uploaded++
        }
        return if (uploaded == 0) "${app.label} was already saved on ${providerName(provider)}."
        else "Saved ${app.label} (${app.apkFiles.size} file(s)) to ${providerName(provider)}."
    }

    /** Apps saved on the drive, newest version of each. Empty if nothing was saved yet. */
    suspend fun savedApps(provider: String): List<SavedApp> {
        val pkgs = try { cloud.list(provider, APPS) } catch (e: Exception) {
            if (e.message.orEmpty().contains("not found", true) || e.message.orEmpty().contains("not a folder", true)) return emptyList()
            throw e
        }.filter { it.isDir }
        val out = ArrayList<SavedApp>()
        for (p in pkgs) {
            val newest = cloud.list(provider, p.path).filter { it.isDir && it.name.toLongOrNull() != null }
                .maxByOrNull { it.name.toLong() } ?: continue
            val apks = cloud.list(provider, newest.path).filter { !it.isDir && it.name.endsWith(".apk", true) }
            if (apks.isNotEmpty()) out += SavedApp(p.name, newest.name.toLong(), newest.path, apks)
        }
        return out.sortedBy { it.pkg }
    }

    /** Installs APK file(s) from the drive on this phone. Android asks the user to confirm. */
    suspend fun install(provider: String, apks: List<CloudEntry>, onProgress: (Float) -> Unit) {
        val files = apks.map { cloud.openRemote(provider, it.path, it.size, diskMb()) }
        withContext(Dispatchers.IO) { LocalInstaller.install(context, files, onProgress) }
    }

    // ------------------------------------------------------------------ app data (a folder the user chooses)

    fun dataFolder(pkg: String): Uri? = folderPrefs.getString(pkg, null)?.let { Uri.parse(it) }

    fun setDataFolder(pkg: String, uri: Uri) {
        folderPrefs.edit().putString(pkg, uri.toString()).apply()
    }

    suspend fun listData(provider: String, pkg: String): List<CloudEntry> = try {
        cloud.list(provider, "$DATA/$pkg").filter { !it.isDir && it.name.endsWith(".zip") }.sortedByDescending { it.name }
    } catch (e: Exception) {
        if (e.message.orEmpty().contains("not found", true) || e.message.orEmpty().contains("not a folder", true)) emptyList() else throw e
    }

    /**
     * Zips the chosen folder into the app's cache, uploads it, and deletes the zip. Needs free cache space roughly
     * equal to the folder's compressed size while it runs.
     */
    suspend fun saveData(provider: String, pkg: String, tree: Uri): String {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Cannot open the chosen folder")
        val tmpDir = File(context.cacheDir, "databackup").apply { mkdirs() }
        val tmp = File(tmpDir, "$pkg-${System.nanoTime()}.zip")
        try {
            val count = withContext(Dispatchers.IO) {
                tmp.outputStream().buffered().use { out ->
                    ZipPack.Writer(out).use { w ->
                        addTree(w, root, "")
                        w.count()
                    }
                }
            }
            if (count == 0) throw IOException("The chosen folder has no files to save")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            cloud.ensureDirs(provider, "$DATA/$pkg")
            cloud.uploadFile(provider, "$DATA/$pkg/$stamp.zip", tmp)
            return "Saved $count file(s) to ${providerName(provider)}."
        } finally {
            tmp.delete()
        }
    }

    private fun addTree(w: ZipPack.Writer, dir: DocumentFile, prefix: String) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) addTree(w, child, "$prefix$name/")
            else context.contentResolver.openInputStream(child.uri)?.use { w.add("$prefix$name", it) }
        }
    }

    /** Downloads a data backup into the cache, writes its files into the chosen folder, and deletes the download. */
    suspend fun restoreData(provider: String, backup: CloudEntry, tree: Uri): String {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Cannot open the chosen folder")
        val tmpDir = File(context.cacheDir, "databackup").apply { mkdirs() }
        val tmp = File(tmpDir, "restore-${System.nanoTime()}.zip")
        try {
            cloud.downloadTo(provider, backup.path, tmp)
            val n = withContext(Dispatchers.IO) {
                val dirs = HashMap<String, DocumentFile>()
                dirs[""] = root
                fun dirFor(rel: String): DocumentFile = dirs.getOrPut(rel) {
                    val parent = dirFor(rel.substringBeforeLast('/', ""))
                    val name = rel.substringAfterLast('/')
                    parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
                        ?: throw IOException("Cannot create the folder $name")
                }
                tmp.inputStream().buffered().use { input ->
                    ZipPack.unpack(input) { rel ->
                        val parent = dirFor(rel.substringBeforeLast('/', ""))
                        val name = rel.substringAfterLast('/')
                        parent.findFile(name)?.delete()
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                        val doc = parent.createFile(mime, name) ?: throw IOException("Cannot create $name")
                        context.contentResolver.openOutputStream(doc.uri, "wt") as OutputStream?
                            ?: throw IOException("Cannot write $name")
                    }
                }
            }
            return "Restored $n file(s)."
        } finally {
            tmp.delete()
        }
    }

    private fun providerName(p: String) = if (p == "terabox") "TeraBox" else "MEGA"
}
