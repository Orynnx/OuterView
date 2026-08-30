package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.orynnx.outerview.core.RearCardState
import org.orynnx.outerview.core.hostapi.HostCardInfo

class PendingInstallPolicyTest {
    private val record = CustomCardRecord(
        cardId = "0123456789abcdef0123456789abcdef",
        business = "outerview_custom_0123456789abcdef0123456789abcdef",
        displayName = "Test Card",
        localZipPath = "/app/source.zip",
        sha256 = "ab".repeat(32),
        state = RearCardState.NOT_INSTALLED.value,
        notificationId = 620001,
    )

    @Test
    fun `only an explicit live install intent is replayed`() {
        assertFalse(PendingInstallPolicy.shouldReplay(record))
        assertTrue(PendingInstallPolicy.shouldReplay(record.copy(pendingInstall = true)))
        assertFalse(
            PendingInstallPolicy.shouldReplay(
                record.copy(pendingInstall = true, cleanupPending = true),
            ),
        )
        assertFalse(
            PendingInstallPolicy.shouldReplay(
                record.copy(pendingInstall = true, deleted = true),
            ),
        )
    }

    @Test
    fun `host confirmation requires exact card business and sha`() {
        val exact = hostCard()
        val candidates = listOf(
            exact.copy(cardId = "f".repeat(32)),
            exact.copy(business = "outerview_custom_${"f".repeat(32)}"),
            exact.copy(sha256 = "cd".repeat(32)),
        )

        assertNull(PendingInstallPolicy.exactHostMatch(record, candidates))
        assertEquals(exact, PendingInstallPolicy.exactHostMatch(record, candidates + exact))
    }

    @Test
    fun `exact host confirmation clears the outbox`() {
        val pending = record.copy(pendingInstall = true, state = RearCardState.ERROR.value)

        val confirmed = PendingInstallPolicy.hostConfirmed(pending, hostCard(), now = 123L)

        assertFalse(confirmed.pendingInstall)
        assertEquals(RearCardState.INSTALLED_DISABLED, confirmed.stateEnum)
        assertEquals("/host/template", confirmed.hostTemplatePath)
        assertEquals(123L, confirmed.updatedAt)
    }

    @Test
    fun `failed install retains intent and successful install clears it`() {
        val pending = PendingInstallPolicy.markPending(record, now = 10L)

        val failed = PendingInstallPolicy.installationFinished(
            record = pending,
            success = false,
            message = "RemoteException",
            templatePath = null,
            commandId = "install-1",
            now = 20L,
        )
        val succeeded = PendingInstallPolicy.installationFinished(
            record = failed,
            success = true,
            message = "installed",
            templatePath = "/host/new-template",
            commandId = "install-2",
            now = 30L,
        )

        assertTrue(failed.pendingInstall)
        assertEquals(RearCardState.ERROR, failed.stateEnum)
        assertFalse(succeeded.pendingInstall)
        assertEquals(RearCardState.INSTALLED_DISABLED, succeeded.stateEnum)
        assertEquals("/host/new-template", succeeded.hostTemplatePath)
    }

    private fun hostCard() = HostCardInfo(
        cardId = record.cardId,
        business = record.business,
        displayName = record.displayName,
        templatePath = "/host/template",
        sha256 = record.sha256,
    )
}
