package org.orynnx.outerview.core.internal

data class VerifiedHostCardOwnership(
    val business: String,
    val templatePath: String,
    val pendingDelete: Boolean,
)

enum class HostTemplateUnlinkOutcome {
    ABSENT,
    REMOVED,
    FAILED,
}

/**
 * Decides which persisted bulk-cleanup entries are still authoritative after an
 * upgrade. The current OuterView namespace is exclusively owned by this module.
 * A legacy namespace is shared history, so its shape alone is never ownership
 * evidence: it must match an already validated registry card that is itself
 * marked for deletion.
 */
object HostPendingCleanupPolicy {
    fun canCommitRegistryDeletion(outcome: HostTemplateUnlinkOutcome): Boolean =
        outcome == HostTemplateUnlinkOutcome.ABSENT ||
            outcome == HostTemplateUnlinkOutcome.REMOVED

    fun canRestoreBusiness(
        business: String,
        verifiedCards: Collection<VerifiedHostCardOwnership>,
    ): Boolean = ManagedHostPaths.isCurrentBusiness(business) ||
        verifiedCards.any { card ->
            card.pendingDelete && card.business == business
        }

    fun canRestoreTemplate(
        business: String,
        canonicalPath: String,
        currentNamespaceManaged: Boolean,
        verifiedCards: Collection<VerifiedHostCardOwnership>,
    ): Boolean = currentNamespaceManaged || verifiedCards.any { card ->
        card.pendingDelete &&
            card.business == business &&
            card.templatePath == canonicalPath
    }
}
