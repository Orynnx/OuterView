package org.orynnx.outerview.core.internal

import org.orynnx.outerview.core.RearCardState

internal object RearCardWorkflow {
    suspend fun importAndInstall(
        commit: suspend () -> CardOperationResult,
        install: suspend (CustomCardRecord) -> CardOperationResult,
    ): CardOperationResult {
        val imported = commit()
        if (!imported.success) return imported
        val record = imported.record ?: return CardOperationResult(
            false,
            "导入成功，但没有生成卡片记录",
            RearCardState.ERROR,
        )
        val installed = install(record)
        return installed.copy(
            message = if (installed.success) {
                "卡片已导入并安装"
            } else {
                "卡片已导入，但自动安装失败：${installed.message}"
            },
        )
    }

    suspend fun replaceAndInstall(
        initial: CustomCardRecord,
        hide: suspend (CustomCardRecord) -> CardOperationResult,
        replace: suspend (CustomCardRecord) -> CardOperationResult,
        install: suspend (CustomCardRecord) -> CardOperationResult,
    ): CardOperationResult {
        var card = initial
        if (shouldHide(card, notificationActive = false)) {
            val hidden = hide(card)
            if (!hidden.success) return hidden.copy(message = "替换前隐藏失败：${hidden.message}")
            card = hidden.record ?: card
        }
        val replaced = replace(card)
        if (!replaced.success) return replaced
        val replacement = replaced.record ?: return CardOperationResult(
            false,
            "模板已替换，但卡片记录缺失",
            RearCardState.ERROR,
            card,
        )
        val installed = install(replacement)
        return installed.copy(
            message = if (installed.success) {
                "模板已替换并安装"
            } else {
                "模板已替换，但自动安装失败：${installed.message}"
            },
        )
    }

    fun shouldHide(record: CustomCardRecord, notificationActive: Boolean): Boolean =
        record.desiredEnabled || record.stateEnum == RearCardState.INSTALLED_ENABLED || notificationActive

    fun needsHostCleanup(record: CustomCardRecord): Boolean = if (record.deleted) {
        record.cleanupPending
    } else {
        record.desiredEnabled ||
            record.pendingInstall ||
            record.cleanupPending ||
            record.stateEnum != RearCardState.NOT_INSTALLED ||
            record.hostTemplatePath != null
    }

    fun deletionTombstone(
        record: CustomCardRecord,
        message: String,
        now: Long = System.currentTimeMillis(),
    ): CustomCardRecord = record.copy(
        desiredEnabled = false,
        pendingInstall = false,
        deleted = true,
        cleanupPending = needsHostCleanup(record),
        lastMessage = message,
        updatedAt = now,
    )

    fun hostCleanupResult(
        record: CustomCardRecord,
        success: Boolean,
        cleanupStillPending: Boolean,
        message: String,
        commandId: String,
        now: Long = System.currentTimeMillis(),
    ): CustomCardRecord = record.copy(
        state = when {
            !success -> RearCardState.ERROR.value
            cleanupStillPending -> RearCardState.INSTALLED_DISABLED.value
            else -> RearCardState.NOT_INSTALLED.value
        },
        desiredEnabled = false,
        pendingInstall = false,
        deleted = true,
        cleanupPending = !success || cleanupStillPending,
        hostTemplatePath = if (success && !cleanupStillPending) null else record.hostTemplatePath,
        lastCommandId = commandId,
        lastMessage = message,
        updatedAt = now,
    )

    fun localCleanupFailed(
        record: CustomCardRecord,
        message: String,
        now: Long = System.currentTimeMillis(),
    ): CustomCardRecord = record.copy(
        state = RearCardState.ERROR.value,
        desiredEnabled = false,
        pendingInstall = false,
        deleted = true,
        cleanupPending = false,
        lastMessage = message,
        updatedAt = now,
    )

    fun cleanupTombstone(record: CustomCardRecord, message: String): CustomCardRecord =
        deletionTombstone(record, message).copy(cleanupPending = true)
}
