package org.orynnx.outerview.core.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiPreviewStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `fingerprint uses standard SHA256 across multiple blocks`() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(40_000)) }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(40_000)).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, AiPreviewStore.digest(file))
        file.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AiPreviewStore.digest(file))
    }

    @Test fun `tokens are single use and discard cannot address arbitrary filesystem paths`() {
        val store = AiPreviewStore()
        val file = temporary.newFile().apply { writeText("validated") }
        val token = store.put(file)
        store.discard(file.absolutePath)
        assertTrue(file.exists())
        assertEquals(file, store.take(token)?.file)
        assertNull(store.take(token))
    }

    @Test fun `expiry removes private copy and invalidates preview before import`() {
        var clock = 0L
        val store = AiPreviewStore(now = { clock }, lifetimeNanos = 10)
        val file = temporary.newFile().apply { writeText("validated") }
        val token = store.put(file)
        clock = 10
        assertNull(store.take(token))
        assertFalse(file.exists())
    }

    @Test fun `pending capacity is bounded and user discard releases capacity`() {
        val store = AiPreviewStore(maxPending = 1)
        val first = temporary.newFile().apply { writeText("one") }
        val second = temporary.newFile().apply { writeText("two") }
        val token = store.put(first)
        assertThrows(IllegalArgumentException::class.java) { store.put(second) }
        store.discard(token)
        assertFalse(first.exists())
        assertNotNull(store.take(store.put(second)))
    }

    @Test fun `fingerprint detects change after preview without trusting mutable display fields`() {
        val store = AiPreviewStore()
        val file = temporary.newFile().apply { writeText("safe") }
        val token = store.put(file)
        file.writeText("changed")
        val pending = requireNotNull(store.take(token))
        assertNotEquals(pending.hash, AiPreviewStore.digest(pending.file))
    }
}
