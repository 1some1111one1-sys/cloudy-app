package com.clouddroid.app.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.clouddroid.app.core.RemoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Outcome messages from Android's installer, shown to the user by the app. */
object InstallEvents {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun post(text: String) { _message.value = text }
    fun consume() { _message.value = null }
}

/**
 * Installs an app from files on the drive onto this phone with Android's own installer. The bytes are read from the
 * drive piece by piece and written straight into the installer's session, so the APK is never saved as a file in
 * this app's storage (and memory use stays small). Android asks the user to confirm the install.
 */
object LocalInstaller {
    const val ACTION = "com.clouddroid.app.INSTALL_RESULT"

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows CloudDroid to install apps. */
    fun openPermissionScreen(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Blocking: call off the main thread. [apks] is the base APK and any splits of one app. */
    @Throws(IOException::class)
    fun install(context: Context, apks: List<RemoteFile>, onProgress: (Float) -> Unit) {
        if (apks.isEmpty()) throw IOException("No APK to install")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apks.sumOf { it.size() })
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val total = apks.sumOf { it.size() }.coerceAtLeast(1)
            var done = 0L
            val buf = ByteArray(256 * 1024)
            apks.forEachIndexed { i, rf ->
                session.openWrite("apk_$i.apk", 0, rf.size()).use { out ->
                    var pos = 0L
                    while (pos < rf.size()) {
                        val n = rf.read(pos, buf, 0, buf.size)
                        if (n <= 0) throw IOException("The drive stopped sending the APK")
                        out.write(buf, 0, n)
                        pos += n; done += n
                        onProgress(done.toFloat() / total)
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw if (e is IOException) e else IOException(e.message ?: e.toString(), e)
        } finally {
            session.close()
        }
    }
}

/** Receives Android's installer result: shows its confirmation screen, then reports success or the reason for failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.post("Installed.")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                InstallEvents.post("Install did not finish" + if (why.isNotBlank()) ": $why" else ".")
            }
        }
    }
}
