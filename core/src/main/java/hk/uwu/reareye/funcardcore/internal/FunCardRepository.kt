package hk.uwu.reareye.funcardcore.internal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import hk.uwu.reareye.funcardcore.hostapi.FunCardHostClient
import hk.uwu.reareye.funcardcore.hostapi.FunCardHostContract
import hk.uwu.reareye.funcardcore.hostapi.HostActionResult
import hk.uwu.reareye.funcardcore.hostapi.HostCapabilities
import hk.uwu.reareye.funcardcore.hostapi.HostCardDiagnostics
import hk.uwu.reareye.funcardcore.hostapi.SystemTemplateInfo
import hk.uwu.reareye.funcardcore.RearCardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

object FunCardRepository {
    private const val Tag = "FunCardManager"
    private const val RegistryName = "custom_cards_registry_v2.json"
    private const val LegacyRegistryName = "fun_cards_registry.json"
    private const val CardsDirName = "custom_cards_v2"
    private val gson = Gson()

    suspend fun loadCards(context: Context): List<CustomCardRecord> = withContext(Dispatchers.IO) {
        processPendingCleanup(context)
        repairIncompleteDeployments(context)
        synchronizeHostCards(context)
        reconcile(context, loadAll(context)).filterNot { it.deleted }
    }

    private suspend fun repairIncompleteDeployments(context: Context) {
        loadAll(context)
            .filter {
                !it.deleted &&
                    it.stateEnum == RearCardState.NOT_INSTALLED &&
                    it.hostTemplatePath != null &&
                    it.localFile.isFile
            }
            .forEach { installCard(context, it) }
    }

    private fun synchronizeHostCards(context: Context) {
        val client = FunCardHostClient()
        val caps = runCatching { client.connect(context) }.getOrNull()
        try {
            if (caps?.compatible == true && caps.managerCaptured) client.synchronizeCards()
        } finally {
            client.disconnect()
        }
    }

    fun hasLegacyRegistry(context: Context): Boolean =
        File(context.filesDir, LegacyRegistryName).isFile

    suspend fun inspectImport(
        context: Context,
        uri: Uri,
        displayNameHint: String?,
    ): Result<PendingCardImport> = withContext(Dispatchers.IO) {
        runCatching {
            val stagingDir = File(context.cacheDir, "fun_card_import").apply { mkdirs() }
            val staged = File(stagingDir, "pending_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= SmartAssistantTemplateValidator.MaxCompressedBytes) {
                            "ZIP 超过 16 MB"
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取所选文件")
            val inspection = SmartAssistantTemplateValidator.inspect(staged)
            val metadataName = inspection.metadata?.name?.trim()?.takeIf { it.isNotBlank() }
            PendingCardImport(
                stagedFile = staged,
                suggestedName = metadataName
                    ?: displayNameHint?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() }
                    ?: "自定义背屏卡片",
                inspection = inspection,
            )
        }
    }

    suspend fun commitImport(
        context: Context,
        pending: PendingCardImport,
        systemTemplateHashes: Set<String> = emptySet(),
    ): CardOperationResult = withContext(Dispatchers.IO) {
        val records = loadAll(context)
        records.firstOrNull { !it.deleted && it.sha256 == pending.inspection.sha256 }?.let {
            pending.stagedFile.delete()
            return@withContext CardOperationResult(
                false,
                "该模板已经导入：${it.displayName}",
                it.stateEnum,
                it,
            )
        }
        if (pending.inspection.sha256 in systemTemplateHashes) {
            pending.stagedFile.delete()
            return@withContext CardOperationResult(false, "系统已经提供相同模板，不重复添加", RearCardState.NOT_INSTALLED)
        }

        val cardId = UUID.randomUUID().toString().replace("-", "").lowercase()
        val cardDir = cardDir(context, cardId).apply { mkdirs() }
        val target = File(cardDir, "source.zip")
        pending.stagedFile.copyTo(target, overwrite = true)
        pending.stagedFile.delete()
        val metadata = pending.inspection.metadata
        val notificationId = allocateNotificationId(cardId, records)
        val record = CustomCardRecord(
            cardId = cardId,
            business = "reareye_custom_$cardId",
            displayName = pending.suggestedName,
            author = metadata?.author,
            templateVersion = metadata?.version,
            localZipPath = target.absolutePath,
            sha256 = pending.inspection.sha256,
            notificationId = notificationId,
            mamlConfigJson = gson.toJson(metadata?.defaultMamlConfig ?: emptyMap<String, Any?>()),
            state = RearCardState.NOT_INSTALLED.value,
            lastMessage = "已导入，等待安装",
            updatedAt = System.currentTimeMillis(),
        )
        saveAll(context, records + record)
        log("import", record, true, record.lastMessage.orEmpty())
        CardOperationResult(true, "模板导入成功", RearCardState.NOT_INSTALLED, record)
    }

    suspend fun replaceTemplate(
        context: Context,
        record: CustomCardRecord,
        pending: PendingCardImport,
    ): CardOperationResult = withContext(Dispatchers.IO) {
        if (record.stateEnum == RearCardState.INSTALLED_ENABLED) {
            return@withContext CardOperationResult(false, "请先关闭显示到背屏", record.stateEnum, record)
        }
        val target = record.localFile
        writeAtomically(target, pending.stagedFile.readBytes())
        pending.stagedFile.delete()
        val next = record.copy(
            displayName = pending.inspection.metadata?.name?.takeIf { it.isNotBlank() } ?: record.displayName,
            author = pending.inspection.metadata?.author ?: record.author,
            templateVersion = pending.inspection.metadata?.version ?: record.templateVersion,
            sha256 = pending.inspection.sha256,
            state = RearCardState.NOT_INSTALLED.value,
            hostTemplatePath = null,
            lastMessage = "模板已替换，请重新安装",
            updatedAt = System.currentTimeMillis(),
        )
        update(context, next)
        CardOperationResult(true, next.lastMessage.orEmpty(), next.stateEnum, next)
    }

    suspend fun installCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            if (!record.localFile.isFile) {
                return@withContext CardOperationResult(false, "本地 ZIP 不存在", RearCardState.ERROR, record)
            }
            val commandId = commandId("install", record.cardId)
            val result = withHost(context) { client ->
                ParcelFileDescriptor.open(record.localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    client.installCard(request(record, commandId), fd)
                }
            }
            val nextState = if (result.success) RearCardState.INSTALLED_DISABLED else RearCardState.ERROR
            val next = record.copy(
                state = nextState.value,
                hostTemplatePath = result.templatePath ?: record.hostTemplatePath,
                lastCommandId = commandId,
                lastMessage = result.message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("install", next, result.success, result.message)
            CardOperationResult(result.success, result.message, nextState, next)
        }

    suspend fun showCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            if (record.stateEnum == RearCardState.NOT_INSTALLED) {
                return@withContext CardOperationResult(false, "请先安装模板", record.stateEnum, record)
            }
            val commandId = commandId("show", record.cardId)
            val client = FunCardHostClient()
            val capabilities = client.connect(context)
            if (!capabilities.compatible) {
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    capabilities.error ?: "Hook 未连接",
                    desiredEnabled = false,
                )
            }
            val beforeActivate = client.diagnostics(record.cardId, record.business, record.notificationId)
            if (!beforeActivate.templateReadable) {
                if (!record.localFile.isFile) {
                    client.disconnect()
                    return@withContext failAndPersist(
                        context,
                        record,
                        commandId,
                        "宿主模板丢失，且本地 ZIP 不存在",
                        desiredEnabled = false,
                    )
                }
                val redeployed = ParcelFileDescriptor.open(
                    record.localFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { fd -> client.installCard(request(record, commandId), fd) }
                if (!redeployed.success) {
                    client.disconnect()
                    return@withContext failAndPersist(
                        context,
                        record,
                        commandId,
                        "自动重新部署失败：${redeployed.message}",
                        desiredEnabled = false,
                    )
                }
            }
            FunCardNotificationController.cancel(context, record.notificationId)
            val activated = client.activateCard(request(record, commandId, includePayload = true))
            if (!activated.success) {
                client.disconnect()
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    activated.message,
                    desiredEnabled = false,
                )
            }
            val diagnostics = awaitDiagnostics(client, record, visible = true)
            client.disconnect()
            val verified = diagnostics.managerListContains &&
                diagnostics.templateReadable &&
                (diagnostics.loadSucceeded || diagnostics.liveWidgetContains)
            val nextState = if (verified) RearCardState.INSTALLED_ENABLED else RearCardState.ERROR
            val message = if (verified) "卡片已由原生 MAML loader 加载" else
                diagnostics.lastError ?: "5 秒内未观察到完整加载证据"
            val next = record.copy(
                state = nextState.value,
                desiredEnabled = verified,
                lastCommandId = commandId,
                lastMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("show", next, verified, message)
            CardOperationResult(verified, message, nextState, next)
        }

    suspend fun hideCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            val commandId = commandId("hide", record.cardId)
            FunCardNotificationController.cancel(context, record.notificationId)
            val client = FunCardHostClient()
            val capabilities = client.connect(context)
            if (!capabilities.compatible) {
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    capabilities.error ?: "Hook 未连接",
                    desiredEnabled = false,
                )
            }
            val deactivated = client.deactivateCard(request(record, commandId))
            if (!deactivated.success) {
                client.disconnect()
                return@withContext failAndPersist(
                    context,
                    record,
                    commandId,
                    deactivated.message,
                    desiredEnabled = false,
                )
            }
            val diagnostics = awaitDiagnostics(client, record, visible = false)
            client.disconnect()
            val verified = !diagnostics.managerListContains && !diagnostics.liveWidgetContains
            val nextState = if (verified) RearCardState.INSTALLED_DISABLED else RearCardState.ERROR
            val message = if (verified) "卡片已从背屏隐藏" else "5 秒内未确认卡片移除"
            val next = record.copy(
                state = nextState.value,
                desiredEnabled = false,
                lastCommandId = commandId,
                lastMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("hide", next, verified, message)
            CardOperationResult(verified, message, nextState, next)
        }

    suspend fun uninstallCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            if (RearCardWorkflow.shouldHide(record, notificationActive = false)) {
                return@withContext CardOperationResult(false, "请先关闭显示到背屏", record.stateEnum, record)
            }
            val commandId = commandId("uninstall", record.cardId)
            val result = withHost(context) { it.uninstallCard(request(record, commandId)) }
            val nextState = if (result.success) RearCardState.NOT_INSTALLED else RearCardState.ERROR
            val next = record.copy(
                state = nextState.value,
                hostTemplatePath = if (result.success) null else record.hostTemplatePath,
                lastCommandId = commandId,
                lastMessage = result.message,
                updatedAt = System.currentTimeMillis(),
            )
            update(context, next)
            log("uninstall", next, result.success, result.message)
            CardOperationResult(result.success, result.message, nextState, next)
        }

    suspend fun deleteCard(context: Context, record: CustomCardRecord): CardOperationResult =
        withContext(Dispatchers.IO) {
            var current = record
            if (record.stateEnum != RearCardState.NOT_INSTALLED || record.hostTemplatePath != null) {
                val hidden = hideCard(context, record)
                current = (hidden.record ?: record).copy(desiredEnabled = false)
                update(context, current)
            } else {
                FunCardNotificationController.cancel(context, record.notificationId)
            }
            if (RearCardWorkflow.needsHostCleanup(current)) {
                val uninstall = uninstallCard(context, current)
                current = uninstall.record ?: current
                if (!uninstall.success) {
                    current.localFile.delete()
                    cardDir(context, current.cardId).deleteRecursively()
                    val tombstone = RearCardWorkflow.cleanupTombstone(
                        current,
                        "本地记录已删除，等待 Hook 清理宿主残留",
                    )
                    update(context, tombstone)
                    return@withContext CardOperationResult(true, tombstone.lastMessage.orEmpty(), RearCardState.ERROR, tombstone)
                }
            }
            current.localFile.delete()
            cardDir(context, current.cardId).deleteRecursively()
            saveAll(context, loadAll(context).filterNot { it.cardId == current.cardId })
            CardOperationResult(true, "卡片已永久删除", RearCardState.NOT_INSTALLED)
        }

    suspend fun deleteAllCards(context: Context): CardOperationResult = withContext(Dispatchers.IO) {
        val records = loadAll(context)
        val commandId = commandId("delete_all", "all")
        val client = FunCardHostClient()
        val capabilities = client.connect(context)
        val result = if (capabilities.compatible) {
            client.deleteAllCards(Bundle().apply {
                putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
            })
        } else {
            HostActionResult(false, capabilities.error ?: "Hook 未连接", "HOOK_UNAVAILABLE")
        }
        client.disconnect()

        records.forEach { record ->
            FunCardNotificationController.cancel(context, record.notificationId)
            record.localFile.delete()
            cardDir(context, record.cardId).deleteRecursively()
        }
        if (result.success) {
            saveAll(context, emptyList())
            CardOperationResult(true, result.message, RearCardState.NOT_INSTALLED)
        } else {
            val tombstones = records.map { record ->
                RearCardWorkflow.cleanupTombstone(
                    record,
                    "本地记录已删除，等待 Hook 批量清理宿主残留",
                )
            }
            saveAll(context, tombstones)
            CardOperationResult(
                true,
                "本地卡片已移除；宿主连接恢复后继续清理",
                RearCardState.ERROR,
            )
        }
    }

    suspend fun savePayload(
        context: Context,
        record: CustomCardRecord,
        advanced: Boolean,
        mamlConfig: String,
        rearParam: String,
        focusParam: String,
    ): CardOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            if (advanced) {
                JSONObject(rearParam.ifBlank { "{}" })
                JSONObject(focusParam.ifBlank { "{}" })
            } else {
                JSONObject(mamlConfig.ifBlank { "{}" })
            }
        }.getOrElse {
            return@withContext CardOperationResult(false, "JSON 无效：${it.message}", record.stateEnum, record)
        }
        val next = record.copy(
            advancedPayload = advanced,
            mamlConfigJson = mamlConfig.ifBlank { "{}" },
            advancedRearParamJson = rearParam.ifBlank { null },
            advancedFocusParamJson = focusParam.ifBlank { null },
            updatedAt = System.currentTimeMillis(),
            lastMessage = "Payload 已保存",
        )
        update(context, next)
        CardOperationResult(true, "Payload 已保存", next.stateEnum, next)
    }

    suspend fun capabilities(context: Context): HostCapabilities = withContext(Dispatchers.IO) {
        val client = FunCardHostClient()
        try { client.connect(context) } finally { client.disconnect() }
    }

    suspend fun diagnostics(context: Context, record: CustomCardRecord): HostCardDiagnostics =
        withContext(Dispatchers.IO) {
            val client = FunCardHostClient()
            val caps = client.connect(context)
            if (!caps.compatible) {
                client.disconnect()
                return@withContext HostCardDiagnostics(record.cardId, record.business, lastError = caps.error)
            }
            try { client.diagnostics(record.cardId, record.business, record.notificationId) }
            finally { client.disconnect() }
        }

    suspend fun listSystemTemplates(context: Context): List<SystemTemplateInfo> = withContext(Dispatchers.IO) {
        val client = FunCardHostClient()
        val caps = client.connect(context)
        if (!caps.compatible) {
            client.disconnect()
            return@withContext emptyList()
        }
        try { client.listSystemTemplates() } finally { client.disconnect() }
    }

    private fun reconcile(
        context: Context,
        records: List<CustomCardRecord>,
    ): List<CustomCardRecord> {
        if (records.none { !it.deleted }) return records
        val client = FunCardHostClient()
        val caps = runCatching { client.connect(context) }.getOrElse { return records }
        if (!caps.compatible || !caps.managerCaptured) {
            client.disconnect()
            return records
        }
        val next = records.map { record ->
            if (record.deleted) return@map record
            val legacyNotificationActive = FunCardNotificationController.isActive(context, record.notificationId)
            if (legacyNotificationActive) {
                if (record.desiredEnabled) {
                    val migrated = client.activateCard(
                        request(record, commandId("migrate", record.cardId), includePayload = true),
                    )
                    if (migrated.success) FunCardNotificationController.cancel(context, record.notificationId)
                } else {
                    FunCardNotificationController.cancel(context, record.notificationId)
                }
            }
            val diagnostics = client.diagnostics(record.cardId, record.business, record.notificationId)
            val state = when {
                !diagnostics.hostRegistryContains -> RearCardState.NOT_INSTALLED
                diagnostics.managerListContains &&
                    (diagnostics.loadSucceeded || diagnostics.liveWidgetContains) -> RearCardState.INSTALLED_ENABLED
                !diagnostics.managerListContains -> RearCardState.INSTALLED_DISABLED
                else -> RearCardState.ERROR
            }
            record.copy(
                state = state.value,
                desiredEnabled = state == RearCardState.INSTALLED_ENABLED ||
                    (state == RearCardState.ERROR && record.desiredEnabled),
                hostTemplatePath = diagnostics.actualTemplatePath ?: record.hostTemplatePath,
                lastMessage = if (state == RearCardState.ERROR) {
                    diagnostics.lastError ?: "宿主 runtime 状态不一致"
                } else {
                    record.lastMessage
                },
            )
        }
        client.disconnect()
        if (next != records) saveAll(context, next)
        return next
    }

    private suspend fun awaitDiagnostics(
        client: FunCardHostClient,
        record: CustomCardRecord,
        visible: Boolean,
    ): HostCardDiagnostics {
        var latest = HostCardDiagnostics(record.cardId, record.business)
        repeat(25) {
            latest = client.diagnostics(record.cardId, record.business, record.notificationId)
            val reached = if (visible) {
                latest.managerListContains && (latest.loadSucceeded || latest.liveWidgetContains)
            } else {
                !latest.managerListContains && !latest.liveWidgetContains
            }
            if (reached) return latest
            delay(200)
        }
        return latest
    }

    private suspend fun processPendingCleanup(context: Context) {
        val pending = loadAll(context).filter { it.cleanupPending }
        if (pending.isEmpty()) return
        pending.forEach { record ->
            val result = withHost(context) {
                it.uninstallCard(request(record, commandId("cleanup", record.cardId)))
            }
            if (result.success) {
                saveAll(context, loadAll(context).filterNot { it.cardId == record.cardId })
            }
        }
    }

    private fun withHost(context: Context, block: (FunCardHostClient) -> HostActionResult): HostActionResult {
        val client = FunCardHostClient()
        val caps = runCatching { client.connect(context) }.getOrElse {
            return HostActionResult(false, it.message ?: "Hook 连接失败", "CONNECT_ERROR")
        }
        if (!caps.compatible) {
            client.disconnect()
            return HostActionResult(false, caps.error ?: "Host API 不兼容", "INCOMPATIBLE_API")
        }
        return try { block(client) } finally { client.disconnect() }
    }

    private fun failAndPersist(
        context: Context,
        record: CustomCardRecord,
        commandId: String,
        message: String,
        desiredEnabled: Boolean = record.desiredEnabled,
    ): CardOperationResult {
        val next = record.copy(
            state = RearCardState.ERROR.value,
            desiredEnabled = desiredEnabled,
            lastCommandId = commandId,
            lastMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        update(context, next)
        return CardOperationResult(false, message, RearCardState.ERROR, next)
    }

    private fun request(
        record: CustomCardRecord,
        commandId: String,
        includePayload: Boolean = false,
    ): Bundle = Bundle().apply {
        putString(FunCardHostContract.Keys.CARD_ID, record.cardId)
        putString(FunCardHostContract.Keys.BUSINESS, record.business)
        putString(FunCardHostContract.Keys.DISPLAY_NAME, record.displayName)
        putString(FunCardHostContract.Keys.TEMPLATE_SHA256, record.sha256)
        putInt(FunCardHostContract.Keys.NOTIFICATION_ID, record.notificationId)
        putString(FunCardHostContract.Keys.COMMAND_ID, commandId)
        if (includePayload) {
            val payload = FunCardNotificationController.buildRuntimePayload(record)
            putString(FunCardHostContract.Keys.REAR_PARAM, payload.rearParam)
            putString(FunCardHostContract.Keys.FOCUS_PARAM, payload.focusParam)
        }
    }

    private fun loadAll(context: Context): List<CustomCardRecord> {
        val file = registryFile(context)
        return if (file.isFile) FunCardRegistryCodec.decode(file.readText()) else emptyList()
    }

    private fun update(context: Context, record: CustomCardRecord) {
        saveAll(context, loadAll(context).filterNot { it.cardId == record.cardId } + record)
    }

    private fun saveAll(context: Context, records: List<CustomCardRecord>) {
        writeAtomically(registryFile(context), FunCardRegistryCodec.encode(records).toByteArray())
    }

    private fun registryFile(context: Context) = File(context.filesDir, RegistryName)

    private fun cardDir(context: Context, cardId: String): File {
        require(cardId.matches(Regex("[a-f0-9]{32}"))) { "cardId 不安全" }
        return File(File(context.filesDir, CardsDirName), cardId)
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (file.exists()) file.delete()
        check(temp.renameTo(file) || runCatching { temp.copyTo(file, overwrite = true); temp.delete(); true }.getOrDefault(false)) {
            "写入 ${file.name} 失败"
        }
    }

    private fun allocateNotificationId(cardId: String, records: List<CustomCardRecord>): Int {
        val used = records.map { it.notificationId }.toSet()
        var candidate = 620_000 + (cardId.take(8).toLong(16) % 100_000).toInt()
        while (candidate in used) candidate++
        return candidate
    }

    private fun commandId(operation: String, key: String) =
        "${operation}_${System.currentTimeMillis()}_${Integer.toHexString(key.hashCode())}"

    private fun log(operation: String, record: CustomCardRecord, success: Boolean, message: String) {
        Log.i(
            Tag,
            "operation=$operation commandId=${record.lastCommandId.orEmpty()} cardId=${record.cardId} " +
                "business=${record.business} notificationId=${record.notificationId} state=${record.state} " +
                "result=$success template=${record.hostTemplatePath.orEmpty()} message=$message",
        )
    }
}
