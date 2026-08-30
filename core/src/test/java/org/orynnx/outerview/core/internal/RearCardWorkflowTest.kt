package org.orynnx.outerview.core.internal

import org.orynnx.outerview.core.RearCardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearCardWorkflowTest {
    @Test
    fun importCommitsThenInstalls() = runBlocking {
        val events = mutableListOf<String>()
        val imported = record(state = RearCardState.NOT_INSTALLED)

        val result = RearCardWorkflow.importAndInstall(
            commit = {
                events += "commit"
                CardOperationResult(true, "imported", RearCardState.NOT_INSTALLED, imported)
            },
            install = {
                events += "install:${it.cardId}"
                CardOperationResult(
                    true,
                    "installed",
                    RearCardState.INSTALLED_DISABLED,
                    it.copy(state = RearCardState.INSTALLED_DISABLED.value),
                )
            },
        )

        assertEquals(listOf("commit", "install:${imported.cardId}"), events)
        assertTrue(result.success)
        assertEquals(RearCardState.INSTALLED_DISABLED, result.state)
        assertEquals("卡片已导入并安装", result.message)
    }

    @Test
    fun importKeepsRecordWhenAutomaticInstallFails() = runBlocking {
        val imported = record(state = RearCardState.NOT_INSTALLED)
        val failed = imported.copy(state = RearCardState.ERROR.value, lastMessage = "Hook 未连接")

        val result = RearCardWorkflow.importAndInstall(
            commit = { CardOperationResult(true, "imported", RearCardState.NOT_INSTALLED, imported) },
            install = { CardOperationResult(false, "Hook 未连接", RearCardState.ERROR, failed) },
        )

        assertFalse(result.success)
        assertEquals(failed.cardId, result.record?.cardId)
        assertEquals(RearCardState.ERROR, result.state)
        assertTrue(result.message.startsWith("卡片已导入，但自动安装失败"))
    }

    @Test
    fun replaceVisibleCardHidesBeforeReplaceAndInstall() = runBlocking {
        val events = mutableListOf<String>()
        val visible = record(state = RearCardState.INSTALLED_ENABLED).copy(desiredEnabled = true)

        val result = RearCardWorkflow.replaceAndInstall(
            initial = visible,
            hide = {
                events += "hide"
                CardOperationResult(
                    true,
                    "hidden",
                    RearCardState.INSTALLED_DISABLED,
                    it.copy(state = RearCardState.INSTALLED_DISABLED.value, desiredEnabled = false),
                )
            },
            replace = {
                events += "replace"
                CardOperationResult(
                    true,
                    "replaced",
                    RearCardState.NOT_INSTALLED,
                    it.copy(state = RearCardState.NOT_INSTALLED.value),
                )
            },
            install = {
                events += "install"
                CardOperationResult(
                    true,
                    "installed",
                    RearCardState.INSTALLED_DISABLED,
                    it.copy(state = RearCardState.INSTALLED_DISABLED.value),
                )
            },
        )

        assertEquals(listOf("hide", "replace", "install"), events)
        assertTrue(result.success)
        assertEquals(RearCardState.INSTALLED_DISABLED, result.state)
    }

    @Test
    fun deletePolicyHandlesEnabledAndOfflineCleanup() {
        val enabled = record(state = RearCardState.INSTALLED_ENABLED).copy(
            desiredEnabled = true,
            pendingInstall = true,
        )
        assertTrue(RearCardWorkflow.shouldHide(enabled, notificationActive = false))
        assertTrue(RearCardWorkflow.needsHostCleanup(enabled))

        val tombstone = RearCardWorkflow.cleanupTombstone(enabled, "pending")
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.cleanupPending)
        assertFalse(tombstone.desiredEnabled)
        assertFalse(tombstone.pendingInstall)
        assertEquals("pending", tombstone.lastMessage)
    }

    @Test
    fun `delete tombstone distinguishes local-only and ambiguous host cleanup`() {
        val localOnly = RearCardWorkflow.deletionTombstone(
            record(RearCardState.NOT_INSTALLED),
            message = "delete",
            now = 10L,
        )
        val ambiguousInstall = RearCardWorkflow.deletionTombstone(
            record(RearCardState.NOT_INSTALLED).copy(pendingInstall = true),
            message = "delete",
            now = 20L,
        )

        assertTrue(localOnly.deleted)
        assertFalse(localOnly.cleanupPending)
        assertFalse(localOnly.pendingInstall)
        assertTrue(ambiguousInstall.deleted)
        assertTrue(ambiguousInstall.cleanupPending)
        assertFalse(ambiguousInstall.pendingInstall)
    }

    @Test
    fun `host deletion replay never resurrects a tombstone`() {
        val pending = RearCardWorkflow.deletionTombstone(
            record(RearCardState.INSTALLED_DISABLED).copy(hostTemplatePath = "/host/template"),
            message = "delete",
            now = 10L,
        )
        val failed = RearCardWorkflow.hostCleanupResult(
            record = pending,
            success = false,
            cleanupStillPending = false,
            message = "offline",
            commandId = "cleanup-1",
            now = 20L,
        )
        val hostAlreadyAbsent = RearCardWorkflow.hostCleanupResult(
            record = failed,
            success = true,
            cleanupStillPending = false,
            message = "absent",
            commandId = "cleanup-2",
            now = 30L,
        )
        val localFailure = RearCardWorkflow.localCleanupFailed(
            hostAlreadyAbsent,
            message = "locked",
            now = 40L,
        )

        assertTrue(failed.deleted)
        assertTrue(failed.cleanupPending)
        assertTrue(hostAlreadyAbsent.deleted)
        assertFalse(hostAlreadyAbsent.cleanupPending)
        assertEquals(null, hostAlreadyAbsent.hostTemplatePath)
        assertTrue(localFailure.deleted)
        assertFalse(localFailure.cleanupPending)
        assertFalse(RearCardWorkflow.needsHostCleanup(localFailure))
    }

    private fun record(state: RearCardState) = CustomCardRecord(
        cardId = "0123456789abcdef0123456789abcdef",
        business = "outerview_custom_0123456789abcdef0123456789abcdef",
        displayName = "Test Card",
        localZipPath = "source.zip",
        sha256 = "a".repeat(64),
        state = state.value,
        notificationId = 620001,
    )
}
