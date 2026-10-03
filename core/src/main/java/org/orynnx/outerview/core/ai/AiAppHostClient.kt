package org.orynnx.outerview.core.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Called on Dispatchers.IO by AiAppManager. Receiver authentication is also enforced by the host. */
internal class AiAppHostClient {
    private var remote: IAiAppHostService? = null
    var message: String = "尚未连接主题宿主"
        private set

    fun connect(context: Context): Boolean {
        remote = null
        val hostUid = try {
            context.packageManager.getApplicationInfo(
                AiAppHostContract.HOST_PACKAGE, PackageManager.ApplicationInfoFlags.of(0),
            ).uid
        } catch (_: PackageManager.NameNotFoundException) {
            message = "找不到系统主题应用"
            return false
        }
        val candidate = AtomicReference<IAiAppHostService?>()
        val open = AtomicBoolean(true)
        val latch = CountDownLatch(1)
        val callback = object : IAiAppHostConnection.Stub() {
            override fun onServiceConnected(service: IAiAppHostService?) {
                if (Binder.getCallingUid() != hostUid || !open.get() || service == null) return
                if (candidate.compareAndSet(null, service)) latch.countDown()
            }
        }
        try {
            val extras = Bundle().apply { putBinder(AiAppHostContract.EXTRA_CALLBACK, callback.asBinder()) }
            context.sendBroadcast(Intent(AiAppHostContract.ACTION_REQUEST_SERVICE)
                .setPackage(AiAppHostContract.HOST_PACKAGE)
                .putExtra(AiAppHostContract.EXTRA_BUNDLE, extras))
            if (!latch.await(3, TimeUnit.SECONDS)) {
                message = "未连接智能应用宿主，请确认模块已启用且作用域包含系统主题应用"
                return false
            }
            val service = candidate.get() ?: return false
            val capabilities = service.getCapabilities() ?: run {
                message = "宿主没有返回能力信息"
                return false
            }
            val compatible = capabilities.getInt(AiAppHostContract.Keys.API_VERSION) == AiAppHostContract.API_VERSION &&
                capabilities.getString(AiAppHostContract.Keys.PROVIDER_PACKAGE) == AiAppHostContract.PROVIDER_PACKAGE
            if (!compatible) {
                message = "智能应用宿主协议版本不匹配"
                return false
            }
            if (!capabilities.getBoolean(AiAppHostContract.Keys.READY)) {
                message = capabilities.getString(AiAppHostContract.Keys.MESSAGE).orEmpty().ifBlank { "智能应用宿主尚未就绪" }
                return false
            }
            remote = service
            message = capabilities.getString(AiAppHostContract.Keys.MESSAGE).orEmpty().ifBlank { "已连接系统主题智能应用宿主" }
            return true
        } finally {
            open.set(false)
        }
    }

    fun list(): List<AiCard> {
        val response = requireRemote().listCards() ?: error("宿主没有返回应用列表")
        message = response.getString(AiAppHostContract.Keys.MESSAGE).orEmpty().ifBlank { "宿主没有返回应用状态说明" }
        require(response.getBoolean(AiAppHostContract.Keys.SUCCESS)) {
            message
        }
        return response.getParcelableArrayList(AiAppHostContract.Keys.ITEMS, Bundle::class.java).orEmpty().map { row ->
            AiCard(
                row.getString(AiAppHostContract.Keys.ID).orEmpty(),
                row.getString(AiAppHostContract.Keys.NAME).orEmpty(),
                row.getString(AiAppHostContract.Keys.RESOURCE_PATH).orEmpty(),
                row.getBoolean(AiAppHostContract.Keys.MANAGED),
                row.getBoolean(AiAppHostContract.Keys.REGISTERED, true),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun import(fd: ParcelFileDescriptor, name: String): AiActionResult = result(requireRemote().importCard(fd, name))
    fun remove(id: String): AiActionResult = result(requireRemote().removeCard(id))
    fun restore(id: String): AiActionResult = result(requireRemote().restoreCard(id))
    fun openSystemManager(): AiActionResult = result(requireRemote().openSystemManager())
    private fun requireRemote(): IAiAppHostService = checkNotNull(remote) { "智能应用宿主未连接" }
    private fun result(bundle: Bundle?): AiActionResult = AiActionResult(
        bundle?.getBoolean(AiAppHostContract.Keys.SUCCESS) == true,
        bundle?.getString(AiAppHostContract.Keys.MESSAGE).orEmpty().ifBlank { "宿主没有返回操作说明" },
        bundle?.getBoolean(AiAppHostContract.Keys.PENDING) == true,
    )
}
