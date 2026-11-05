package com.clouddroid.app

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Starts the virtualization engine. Apps run "inside CloudDroid" in extra processes of this application; each of
 * those processes also starts this class, so it must stay light and must never throw.
 */
class CloudDroidApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try { BlackBoxCore.get().closeCodeInit() } catch (e: Throwable) { Log.w(TAG, "closeCodeInit: $e") }
        try { BlackBoxCore.get().onBeforeMainApplicationAttach(this, base) } catch (e: Throwable) { Log.w(TAG, "before attach: $e") }
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
            })
        } catch (e: Throwable) { Log.e(TAG, "Engine attach failed: $e") }
        try { BlackBoxCore.get().onAfterMainApplicationAttach(this, base) } catch (e: Throwable) { Log.w(TAG, "after attach: $e") }
    }

    override fun onCreate() {
        super.onCreate()
        try { BlackBoxCore.get().doCreate() } catch (e: Throwable) { Log.e(TAG, "Engine start failed: $e") }
    }

    private companion object { const val TAG = "CloudDroidApp" }
}
