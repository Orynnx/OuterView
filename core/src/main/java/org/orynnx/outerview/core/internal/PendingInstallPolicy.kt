package org.orynnx.outerview.core.internal

import org.orynnx.outerview.core.RearCardState
import org.orynnx.outerview.core.hostapi.HostCardInfo

/** Pure policy for the app-side install outbox stored in the card registry. */
internal object PendingInstallPolicy {
    fun shouldReplay(record: CustomCardRecord): Boolean =
        record.pendingInstall && !record.deleted && !record.cleanupPending

    fun markPending(record: CustomCardRecord, now: Long): CustomCardRecord = record.copy(
        pendingInstall = true,
        updatedAt = now,
    )

    fun exactHostMatch(
        record: CustomCardRecord,
        hostCards: Collection<HostCardInfo>,
    ): HostCardInfo? = hostCards.firstOrNull { host ->
        host.cardId == record.cardId &&
            host.business == record.business &&
            host.sha256 == record.sha256
    }

    fun hostConfirmed(
        record: CustomCardRecord,
        hostCard: HostCardInfo,
        now: Long,
    ): CustomCardRecord {
        require(exactHostMatch(record, listOf(hostCard)) != null) {
            "宿主卡片与待安装意图不匹配"
        }
        return record.copy(
            state = RearCardState.INSTALLED_DISABLED.value,
            pendingInstall = false,
            hostTemplatePath = hostCard.templatePath.takeIf(String::isNotBlank)
                ?: record.hostTemplatePath,
            lastMessage = "宿主已确认模板部署",
            updatedAt = now,
        )
    }

    fun installationFinished(
        record: CustomCardRecord,
        success: Boolean,
        message: String,
        templatePath: String?,
        commandId: String,
        now: Long,
    ): CustomCardRecord = record.copy(
        state = if (success) {
            RearCardState.INSTALLED_DISABLED.value
        } else {
            RearCardState.ERROR.value
        },
        pendingInstall = !success,
        hostTemplatePath = if (success) {
            templatePath ?: record.hostTemplatePath
        } else {
            record.hostTemplatePath
        },
        lastCommandId = commandId,
        lastMessage = message,
        updatedAt = now,
    )
}
