package hk.uwu.reareye.funcardcore.wallpaperapi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import hk.uwu.reareye.funcardcore.hostapi.FunCardHostContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class RearWallpaperHostInfo(val wallpaperId: Int, val resId: String, val name: String, val managed: Boolean, val current: Boolean)

class RearWallpaperHostClient {
    private var remote: IRearWallpaperHostService? = null

    fun connect(context: Context, timeoutMs: Long = 3000): Boolean {
        val latch = CountDownLatch(1)
        val callback = object : IRearWallpaperHostConnection.Stub() {
            override fun onServiceConnected(service: IRearWallpaperHostService?) { remote = service; latch.countDown() }
        }
        val extras = Bundle().apply { putBinder(RearWallpaperHostContract.EXTRA_CALLBACK, callback.asBinder()) }
        context.sendBroadcast(Intent(RearWallpaperHostContract.ACTION_REQUEST_SERVICE)
            .setPackage(FunCardHostContract.HOST_PACKAGE)
            .putExtra(RearWallpaperHostContract.EXTRA_BUNDLE, extras))
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && remote != null
    }

    fun list(): List<RearWallpaperHostInfo> = remote?.listWallpapers()
        ?.getParcelableArrayList<Bundle>(RearWallpaperHostContract.Keys.ITEMS).orEmpty().map {
            RearWallpaperHostInfo(it.getInt(RearWallpaperHostContract.Keys.WALLPAPER_ID), it.getString(RearWallpaperHostContract.Keys.RES_ID).orEmpty(), it.getString(RearWallpaperHostContract.Keys.NAME).orEmpty(), it.getBoolean(RearWallpaperHostContract.Keys.MANAGED), it.getBoolean(RearWallpaperHostContract.Keys.CURRENT))
        }

    fun import(fd: ParcelFileDescriptor, displayName: String): Bundle = requireNotNull(remote).importWallpaper(fd, displayName) ?: Bundle.EMPTY
    fun apply(id: Int): Bundle = requireNotNull(remote).applyWallpaper(id) ?: Bundle.EMPTY
    fun rename(id: Int, displayName: String): Bundle = requireNotNull(remote).renameWallpaper(id, displayName) ?: Bundle.EMPTY
    fun delete(id: Int): Bundle = requireNotNull(remote).deleteWallpaper(id) ?: Bundle.EMPTY
}
