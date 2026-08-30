package org.orynnx.outerview.core.wallpaperapi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class RearWallpaperHostInfo(val wallpaperId: Int, val resId: String, val name: String, val managed: Boolean, val current: Boolean)

class RearWallpaperHostClient {
    @Volatile
    private var remote: IRearWallpaperHostService? = null

    fun connect(context: Context, timeoutMs: Long = 3000): Boolean {
        val latch = CountDownLatch(1)
        remote = null
        var candidate: IRearWallpaperHostService? = null
        val callback = object : IRearWallpaperHostConnection.Stub() {
            override fun onServiceConnected(service: IRearWallpaperHostService?) { candidate = service; latch.countDown() }
        }
        val extras = Bundle().apply { putBinder(RearWallpaperHostContract.EXTRA_CALLBACK, callback.asBinder()) }
        context.sendBroadcast(Intent(RearWallpaperHostContract.ACTION_REQUEST_SERVICE)
            .setPackage(RearWallpaperHostContract.HOST_PACKAGE)
            .putExtra(RearWallpaperHostContract.EXTRA_BUNDLE, extras))
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        val service = candidate ?: return false
        val capabilities = runCatching { service.getCapabilities() }.getOrNull() ?: return false
        val compatible = capabilities.getInt(RearWallpaperHostContract.Keys.API_VERSION) == RearWallpaperHostContract.API_VERSION &&
            capabilities.getString(RearWallpaperHostContract.Keys.PROVIDER_PACKAGE) == RearWallpaperHostContract.PROVIDER_PACKAGE &&
            !capabilities.getString(RearWallpaperHostContract.Keys.PROVIDER_INSTANCE_ID).isNullOrBlank()
        if (compatible) remote = service
        return compatible
    }

    fun list(): List<RearWallpaperHostInfo> = remote?.listWallpapers()
        ?.getParcelableArrayList(RearWallpaperHostContract.Keys.ITEMS, Bundle::class.java).orEmpty().map {
            RearWallpaperHostInfo(it.getInt(RearWallpaperHostContract.Keys.WALLPAPER_ID), it.getString(RearWallpaperHostContract.Keys.RES_ID).orEmpty(), it.getString(RearWallpaperHostContract.Keys.NAME).orEmpty(), it.getBoolean(RearWallpaperHostContract.Keys.MANAGED), it.getBoolean(RearWallpaperHostContract.Keys.CURRENT))
        }

    fun import(fd: ParcelFileDescriptor, displayName: String): Bundle = requireNotNull(remote).importWallpaper(fd, displayName) ?: Bundle.EMPTY
    fun apply(id: Int): Bundle = requireNotNull(remote).applyWallpaper(id) ?: Bundle.EMPTY
    fun rename(id: Int, displayName: String): Bundle = requireNotNull(remote).renameWallpaper(id, displayName) ?: Bundle.EMPTY
    fun delete(id: Int): Bundle = requireNotNull(remote).deleteWallpaper(id) ?: Bundle.EMPTY
}
