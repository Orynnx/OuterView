package hk.uwu.reareye.funcardcore.hostapi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FunCardHostClient {
    @Volatile
    private var remote: IFunCardHostService? = null

    fun connect(context: Context, timeoutMs: Long = 3000L): HostCapabilities {
        val latch = CountDownLatch(1)
        val callback = object : IFunCardHostConnection.Stub() {
            override fun onServiceConnected(service: IFunCardHostService?) {
                if (service != null && remote == null) remote = service
                latch.countDown()
            }
        }
        val callbackExtras = Bundle().apply {
            putBinder(FunCardHostContract.EXTRA_CALLBACK, callback.asBinder())
        }
        val intent = Intent(FunCardHostContract.ACTION_REQUEST_SERVICE)
            .setPackage(FunCardHostContract.HOST_PACKAGE)
            .putExtra(FunCardHostContract.EXTRA_BUNDLE, callbackExtras)
        context.applicationContext.sendBroadcast(intent)
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return HostCapabilities(error = "未连接到 FunCard Host Hook")
        }
        val capabilities = runCatching { remote?.getCapabilities() }.getOrNull()
            ?: return HostCapabilities(error = "Hook 返回了空能力信息")
        val parsed = HostCapabilities(
            connected = true,
            apiVersion = capabilities.getInt(FunCardHostContract.Keys.API_VERSION),
            providerPackage = capabilities.getString(FunCardHostContract.Keys.PROVIDER_PACKAGE).orEmpty(),
            providerInstanceId = capabilities.getString(FunCardHostContract.Keys.PROVIDER_INSTANCE_ID).orEmpty(),
            hostVersion = capabilities.getString(FunCardHostContract.Keys.HOST_VERSION).orEmpty(),
            hookReady = capabilities.getBoolean(FunCardHostContract.Keys.HOOK_READY),
            managerCaptured = capabilities.getBoolean(FunCardHostContract.Keys.MANAGER_CAPTURED),
        )
        return if (parsed.compatible) parsed else parsed.copy(error = "Host API 版本或提供者不匹配")
    }

    fun disconnect() {
        remote = null
    }

    fun listSystemTemplates(): List<SystemTemplateInfo> {
        val bundle = requireRemote().listSystemTemplates()
        return bundle.getParcelableArrayList<Bundle>(FunCardHostContract.Keys.ITEMS).orEmpty().map {
            SystemTemplateInfo(
                business = it.getString(FunCardHostContract.Keys.BUSINESS).orEmpty(),
                displayName = it.getString(FunCardHostContract.Keys.DISPLAY_NAME).orEmpty(),
                pathName = it.getString(FunCardHostContract.Keys.SYSTEM_PATH_NAME).orEmpty(),
                active = it.getBoolean(FunCardHostContract.Keys.ACTIVE),
                readable = it.getBoolean(FunCardHostContract.Keys.TEMPLATE_READABLE),
                sha256 = it.getString(FunCardHostContract.Keys.TEMPLATE_SHA256).orEmpty(),
            )
        }
    }

    fun listHostCards(): List<HostCardInfo> {
        val bundle = requireRemote().listHostCards()
        return bundle.getParcelableArrayList<Bundle>(FunCardHostContract.Keys.ITEMS).orEmpty().map {
            HostCardInfo(
                cardId = it.getString(FunCardHostContract.Keys.CARD_ID).orEmpty(),
                business = it.getString(FunCardHostContract.Keys.BUSINESS).orEmpty(),
                displayName = it.getString(FunCardHostContract.Keys.DISPLAY_NAME).orEmpty(),
                templatePath = it.getString(FunCardHostContract.Keys.TEMPLATE_PATH).orEmpty(),
                sha256 = it.getString(FunCardHostContract.Keys.TEMPLATE_SHA256).orEmpty(),
            )
        }
    }

    fun installCard(request: Bundle, fd: ParcelFileDescriptor): HostActionResult =
        runCatching { HostActionResult.fromBundle(requireRemote().installCard(request, fd)) }
            .getOrElse { HostActionResult(false, it.message ?: "安装失败", "REMOTE_ERROR") }

    fun activateCard(request: Bundle): HostActionResult =
        runCatching { HostActionResult.fromBundle(requireRemote().activateCard(request)) }
            .getOrElse { HostActionResult(false, it.message ?: "显示失败", "REMOTE_ERROR") }

    fun deactivateCard(request: Bundle): HostActionResult =
        runCatching { HostActionResult.fromBundle(requireRemote().deactivateCard(request)) }
            .getOrElse { HostActionResult(false, it.message ?: "隐藏失败", "REMOTE_ERROR") }

    fun uninstallCard(request: Bundle): HostActionResult =
        runCatching { HostActionResult.fromBundle(requireRemote().uninstallCard(request)) }
            .getOrElse { HostActionResult(false, it.message ?: "卸载失败", "REMOTE_ERROR") }

    fun deleteAllCards(request: Bundle): HostActionResult =
        runCatching { HostActionResult.fromBundle(requireRemote().deleteAllCards(request)) }
            .getOrElse { HostActionResult(false, it.message ?: "全部删除失败", "REMOTE_ERROR") }

    fun diagnostics(cardId: String, business: String, notificationId: Int): HostCardDiagnostics =
        runCatching {
            HostCardDiagnostics.fromBundle(
                requireRemote().getCardDiagnostics(cardId, business, notificationId)
            ) ?: HostCardDiagnostics(cardId, business, lastError = "诊断结果为空")
        }.getOrElse { HostCardDiagnostics(cardId, business, lastError = it.message) }

    private fun requireRemote(): IFunCardHostService = remote ?: error("Host Hook 未连接")
}
