package com.clouddroid.app.stream

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.clouddroid.app.core.BlockCache
import com.clouddroid.app.core.CloudClient
import com.clouddroid.app.core.RemoteFile
import java.io.File
import java.io.IOException

/**
 * Files from TeraBox / MEGA that are read on demand instead of downloaded. Memory use is capped (a small share of
 * the app's heap) and so is the disk use (a folder inside the app's cache, which Android may also clear at any time).
 * Nothing is ever kept that is not a disposable copy of a block that still exists in the cloud.
 */
object StreamHub {
    private var cache: BlockCache? = null
    private var cacheDiskMb = -1
    private val prefetcher by lazy { RemoteFile.newPrefetcher() }

    /** Memory for cached blocks: at most 64 MB, and never more than an eighth of the heap this app may use. */
    private fun ramBytes() = minOf(64L shl 20, Runtime.getRuntime().maxMemory() / 8)

    @Synchronized
    fun cache(context: Context, diskMb: Int): BlockCache {
        val cur = cache
        if (cur != null && cacheDiskMb == diskMb) return cur
        cur?.clear()
        val c = BlockCache(File(context.cacheDir, "stream"), ramBytes(), diskMb.toLong() shl 20)
        cache = c; cacheDiskMb = diskMb
        return c
    }

    @Synchronized fun stats(): BlockCache.Stats? = cache?.stats()

    @Synchronized fun clear() { cache?.clear() }

    fun open(context: Context, client: CloudClient, provider: String, path: String, size: Long, diskMb: Int): RemoteFile =
        RemoteFile(client, path, size, "$provider:$path:$size", cache(context, diskMb),
            RemoteFile.DEFAULT_BLOCK, 3, prefetcher)

    /**
     * A real file descriptor whose bytes are fetched from the cloud when something reads them (Android's
     * proxy-file-descriptor mechanism, API 26+). Any API that takes a FileDescriptor can use it: media players,
     * PDF renderers, image decoders. Close it when done.
     */
    fun openDescriptor(context: Context, file: RemoteFile): ParcelFileDescriptor {
        val thread = HandlerThread("clouddroid-proxyfd").also { it.start() }
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = file.size()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    try {
                        val n = file.read(offset, data, 0, size)
                        return if (n < 0) 0 else n
                    } catch (e: IOException) {
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }
                }

                override fun onRelease() { thread.quitSafely() }
            },
            Handler(thread.looper),
        )
    }
}
