package hk.uwu.reareye.funcardcore.internal

import hk.uwu.reareye.funcardcore.RearCardState

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

    fun needsHostCleanup(record: CustomCardRecord): Boolean =
        record.stateEnum != RearCardState.NOT_INSTALLED || record.hostTemplatePath != null

    fun cleanupTombstone(record: CustomCardRecord, message: String): CustomCardRecord = record.copy(
        desiredEnabled = false,
        deleted = true,
        cleanupPending = true,
        lastMessage = message,
        updatedAt = System.currentTimeMillis(),
    )
}
