package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostInstallJournalTest {
    private val oldFingerprint = "a".repeat(64)
    private val newFingerprint = "b".repeat(64)
    private val oldSha = "c".repeat(64)
    private val newSha = "d".repeat(64)
    private val journal = HostInstallJournal(
        cardId = "0123456789abcdef0123456789abcdef",
        oldRegistryFingerprint = oldFingerprint,
        newRegistryFingerprint = newFingerprint,
        oldTemplatePath = "/data/system/templates/reareye_custom_0123456789abcdef0123456789abcdef",
        oldTargetSha256 = oldSha,
        newTargetSha256 = newSha,
    )

    @Test
    fun `old registry always rolls the target back to old content`() {
        assertEquals(
            HostInstallRecovery.KEEP_OLD,
            HostInstallJournalCodec.recovery(journal, oldFingerprint, oldSha, newSha, null),
        )
        assertEquals(
            HostInstallRecovery.RESTORE_OLD,
            HostInstallJournalCodec.recovery(journal, oldFingerprint, newSha, newSha, oldSha),
        )
    }

    @Test
    fun `new registry always keeps or restores new content`() {
        assertEquals(
            HostInstallRecovery.KEEP_NEW,
            HostInstallJournalCodec.recovery(journal, newFingerprint, newSha, null, oldSha),
        )
        assertEquals(
            HostInstallRecovery.RESTORE_NEW,
            HostInstallJournalCodec.recovery(journal, newFingerprint, null, newSha, oldSha),
        )
        assertThrows(IllegalStateException::class.java) {
            HostInstallJournalCodec.recovery(journal, newFingerprint, oldSha, null, oldSha)
        }
    }

    @Test
    fun `exception after durable registry commit follows disk fingerprint and rolls forward`() {
        // The registry writer may throw after its atomic move. Recovery must inspect the
        // durable registry fingerprint, never a caller-side "write returned" boolean.
        assertEquals(
            HostInstallRecovery.RESTORE_NEW,
            HostInstallJournalCodec.recovery(
                journal = journal,
                registryFingerprint = newFingerprint,
                targetSha256 = oldSha,
                stagingSha256 = newSha,
                backupSha256 = oldSha,
            ),
        )
    }

    @Test
    fun `initial install is deleted when registry commit never happened`() {
        val initial = journal.copy(
            oldRegistryFingerprint = null,
            oldTemplatePath = null,
            oldTargetSha256 = null,
        )
        assertEquals(
            HostInstallRecovery.KEEP_OLD,
            HostInstallJournalCodec.recovery(initial, null, null, newSha, null),
        )
        assertEquals(
            HostInstallRecovery.DELETE_NEW_TARGET,
            HostInstallJournalCodec.recovery(initial, null, newSha, newSha, null),
        )
    }


    @Test
    fun `replacement requires no tombstone enabled state or live runtime`() {
        assertEquals(
            HostInstallPrecondition.ALLOW,
            HostInstallPreconditionPolicy.evaluate(
                previousPendingDelete = false,
                previousEnabled = false,
                runtimeAbsenceConfirmed = true,
            ),
        )
        assertEquals(
            HostInstallPrecondition.DELETE_PENDING,
            HostInstallPreconditionPolicy.evaluate(true, false, true),
        )
        assertEquals(
            HostInstallPrecondition.ENABLED,
            HostInstallPreconditionPolicy.evaluate(false, true, true),
        )
        assertEquals(
            HostInstallPrecondition.RUNTIME_NOT_CONFIRMED_ABSENT,
            HostInstallPreconditionPolicy.evaluate(false, false, false),
        )
    }

    @Test
    fun `ambiguous registry or missing rollback material fails closed`() {
        assertThrows(IllegalStateException::class.java) {
            HostInstallJournalCodec.recovery(journal, "e".repeat(64), newSha, newSha, oldSha)
        }
        assertThrows(IllegalStateException::class.java) {
            HostInstallJournalCodec.recovery(journal, oldFingerprint, newSha, newSha, null)
        }
    }

    @Test
    fun `journal codec is strict and registry fingerprint covers state`() {
        assertEquals(journal, HostInstallJournalCodec.decode(HostInstallJournalCodec.encode(journal).toString(Charsets.UTF_8)))
        val snapshot = snapshot()
        assertNotEquals(
            HostInstallJournalCodec.registryFingerprint(snapshot),
            HostInstallJournalCodec.registryFingerprint(snapshot.copy(pendingDelete = true)),
        )
        val extra = HostInstallJournalCodec.encode(journal).toString(Charsets.UTF_8)
            .dropLast(1) + ",\"extra\":true}"
        assertThrows(IllegalArgumentException::class.java) {
            HostInstallJournalCodec.decode(extra)
        }
    }

    private fun snapshot() = HostInstallRegistrySnapshot(
        cardId = journal.cardId,
        business = "${ManagedHostPaths.BusinessPrefix}${journal.cardId}",
        displayName = "Card",
        templatePath = "/data/system/templates/${ManagedHostPaths.BusinessPrefix}${journal.cardId}",
        sha256 = oldSha,
        notificationId = 620_001,
        updatedAt = 1L,
        enabled = false,
        pendingDelete = false,
        rearParam = "{}",
        focusParam = "{}",
    )
}
