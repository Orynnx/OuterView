package org.orynnx.outerview.core.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPendingCleanupPolicyTest {
    private val cardId = "0123456789abcdef0123456789abcdef"
    private val currentBusiness = "${ManagedHostPaths.BusinessPrefix}$cardId"
    private val legacyBusiness = "${ManagedHostPaths.LegacyBusinessPrefix}$cardId"
    private val legacyPath = "/host/templates/$legacyBusiness"

    @Test
    fun `registry deletion waits for a successful or already completed unlink`() {
        assertFalse(
            HostPendingCleanupPolicy.canCommitRegistryDeletion(HostTemplateUnlinkOutcome.FAILED),
        )
        assertTrue(
            HostPendingCleanupPolicy.canCommitRegistryDeletion(HostTemplateUnlinkOutcome.REMOVED),
        )
        // Process death after unlink replays as ABSENT and may finish the
        // pending registry removal without needing an untracked backup file.
        assertTrue(
            HostPendingCleanupPolicy.canCommitRegistryDeletion(HostTemplateUnlinkOutcome.ABSENT),
        )
    }

    @Test
    fun `current namespace pending cleanup remains recoverable without a card record`() {
        assertTrue(
            HostPendingCleanupPolicy.canRestoreBusiness(currentBusiness, emptyList()),
        )
        assertTrue(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = currentBusiness,
                canonicalPath = "/host/templates/$currentBusiness",
                currentNamespaceManaged = true,
                verifiedCards = emptyList(),
            ),
        )
    }

    @Test
    fun `shape-only legacy pending cleanup is discarded`() {
        assertFalse(
            HostPendingCleanupPolicy.canRestoreBusiness(legacyBusiness, emptyList()),
        )
        assertFalse(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = legacyBusiness,
                canonicalPath = legacyPath,
                currentNamespaceManaged = false,
                verifiedCards = emptyList(),
            ),
        )
    }

    @Test
    fun `legacy cleanup requires exact pending registry ownership`() {
        val activeCard = VerifiedHostCardOwnership(
            business = legacyBusiness,
            templatePath = legacyPath,
            pendingDelete = false,
        )
        assertFalse(HostPendingCleanupPolicy.canRestoreBusiness(legacyBusiness, listOf(activeCard)))
        assertFalse(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = legacyBusiness,
                canonicalPath = legacyPath,
                currentNamespaceManaged = false,
                verifiedCards = listOf(activeCard),
            ),
        )

        val pendingCard = activeCard.copy(pendingDelete = true)
        assertTrue(HostPendingCleanupPolicy.canRestoreBusiness(legacyBusiness, listOf(pendingCard)))
        assertTrue(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = legacyBusiness,
                canonicalPath = legacyPath,
                currentNamespaceManaged = false,
                verifiedCards = listOf(pendingCard),
            ),
        )
        assertFalse(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = legacyBusiness,
                canonicalPath = "$legacyPath-other",
                currentNamespaceManaged = false,
                verifiedCards = listOf(pendingCard),
            ),
        )
        assertFalse(
            HostPendingCleanupPolicy.canRestoreTemplate(
                business = "${ManagedHostPaths.LegacyBusinessPrefix}fedcba9876543210fedcba9876543210",
                canonicalPath = legacyPath,
                currentNamespaceManaged = false,
                verifiedCards = listOf(pendingCard),
            ),
        )
    }
}
