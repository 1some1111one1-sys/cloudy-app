package com.clouddroid.app.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** An app installed on this phone that has a launcher icon. */
data class PhoneApp(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** The base APK followed by any split APKs (what the system installed from). */
    val apkFiles: List<File>,
) {
    val sizeBytes: Long get() = apkFiles.sumOf { it.length() }
}

/** The apps installed on this phone, read from Android's own package list. Nothing here needs a server. */
object LocalApps {
    /** Launchable apps the user installed (or updated system apps such as Chrome), without CloudDroid itself. */
    fun list(context: Context): List<PhoneApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<PhoneApp>()
        for (r in pm.queryIntentActivities(launcher, 0)) {
            val pkg = r.activityInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val info = try { pm.getPackageInfo(pkg, 0) } catch (e: Exception) { continue }
            val ai = info.applicationInfo ?: continue
            val system = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            val updated = ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (system && !updated) continue
            val files = ArrayList<File>()
            files += File(ai.sourceDir)
            ai.splitSourceDirs?.forEach { files += File(it) }
            out += PhoneApp(
                pkg, r.loadLabel(pm).toString(), info.versionName.orEmpty(), info.longVersionCode, files,
            )
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return true
    }

    /** Asks Android to remove the app (the system shows its own confirmation). */
    fun uninstall(context: Context, pkg: String) {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        try { context.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
}
