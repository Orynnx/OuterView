package hk.uwu.reareye.funcardcore.internal

import hk.uwu.reareye.funcardcore.RearCardState
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
        val enabled = record(state = RearCardState.INSTALLED_ENABLED).copy(desiredEnabled = true)
        assertTrue(RearCardWorkflow.shouldHide(enabled, notificationActive = false))
        assertTrue(RearCardWorkflow.needsHostCleanup(enabled))

        val tombstone = RearCardWorkflow.cleanupTombstone(enabled, "pending")
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.cleanupPending)
        assertFalse(tombstone.desiredEnabled)
        assertEquals("pending", tombstone.lastMessage)
    }

    private fun record(state: RearCardState) = CustomCardRecord(
        cardId = "0123456789abcdef0123456789abcdef",
        business = "reareye_custom_0123456789abcdef0123456789abcdef",
        displayName = "Test Card",
        localZipPath = "source.zip",
        sha256 = "a".repeat(64),
        state = state.value,
        notificationId = 620001,
    )
}
