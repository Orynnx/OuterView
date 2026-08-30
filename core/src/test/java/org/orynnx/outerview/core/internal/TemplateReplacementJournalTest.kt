package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TemplateReplacementJournalTest {
    private val journal = TemplateReplacementJournal(
        cardId = "0123456789abcdef0123456789abcdef",
        oldSha256 = "a".repeat(64),
        newSha256 = "b".repeat(64),
        hadOriginal = true,
    )

    @Test
    fun `journal round trips with strict fields`() {
        assertEquals(
            journal,
            TemplateReplacementJournalCodec.decode(
                TemplateReplacementJournalCodec.encode(journal).toString(Charsets.UTF_8),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TemplateReplacementJournalCodec.decode(
                """{"schemaVersion":1,"cardId":"${journal.cardId}","oldSha256":"${journal.oldSha256}","newSha256":"${journal.newSha256}","hadOriginal":true,"extra":1}""",
            )
        }
    }

    @Test
    fun `recovery commits only when registry and target contain new template`() {
        assertEquals(
            TemplateReplacementRecovery.COMMIT,
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = journal.newSha256,
                targetSha256 = journal.newSha256,
                backupSha256 = journal.oldSha256,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = journal.newSha256,
                targetSha256 = journal.oldSha256,
                backupSha256 = journal.oldSha256,
            )
        }
    }

    @Test
    fun `recovery restores durable backup before registry commit`() {
        assertEquals(
            TemplateReplacementRecovery.KEEP_ORIGINAL,
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = journal.oldSha256,
                targetSha256 = journal.oldSha256,
                backupSha256 = null,
            ),
        )
        assertEquals(
            TemplateReplacementRecovery.RESTORE_BACKUP,
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = journal.oldSha256,
                targetSha256 = journal.newSha256,
                backupSha256 = journal.oldSha256,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = journal.oldSha256,
                targetSha256 = journal.newSha256,
                backupSha256 = null,
            )
        }
    }

    @Test
    fun `replacement without an original is deleted on rollback`() {
        val missingOriginal = journal.copy(hadOriginal = false)
        assertEquals(
            TemplateReplacementRecovery.DELETE_REPLACEMENT,
            TemplateReplacementJournalCodec.recovery(
                missingOriginal,
                registrySha256 = missingOriginal.oldSha256,
                targetSha256 = missingOriginal.newSha256,
                backupSha256 = null,
            ),
        )
    }

    @Test
    fun `post commit failure follows persisted registry instead of stale old sha`() {
        val stalePreWriteSha256 = journal.oldSha256
        val persistedRegistrySha256 = persistedReplacementSha256(
            journal,
            listOf(
                CustomCardRecord(
                    cardId = journal.cardId,
                    sha256 = journal.newSha256,
                ),
            ),
        )

        assertEquals(
            TemplateReplacementRecovery.COMMIT,
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = persistedRegistrySha256,
                targetSha256 = journal.newSha256,
                backupSha256 = journal.oldSha256,
            ),
        )
        assertEquals(
            TemplateReplacementRecovery.RESTORE_BACKUP,
            TemplateReplacementJournalCodec.recovery(
                journal,
                registrySha256 = stalePreWriteSha256,
                targetSha256 = journal.newSha256,
                backupSha256 = journal.oldSha256,
            ),
        )
    }

    @Test
    fun `replacement recovery rejects a missing persisted registry record`() {
        assertThrows(IllegalStateException::class.java) {
            persistedReplacementSha256(journal, emptyList())
        }
    }

    @Test
    fun `cleanup failure cannot turn a committed move into failure`() {
        val cleanupError = IOException("cleanup failed")
        var observed: Throwable? = null

        val result = withNonThrowingCleanup(
            cleanup = { throw cleanupError },
            onCleanupFailure = { observed = it },
        ) {
            "move committed"
        }

        assertEquals("move committed", result)
        assertSame(cleanupError, observed)
    }

    @Test
    fun `cleanup failure cannot mask the original move failure`() {
        val moveError = IllegalStateException("move failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            withNonThrowingCleanup(
                cleanup = { throw IOException("cleanup failed") },
            ) {
                throw moveError
            }
        }

        assertSame(moveError, thrown)
    }
}
