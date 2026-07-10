package hk.uwu.reareye.funcardcore.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagedHostPathsTest {
    private val base = File(System.getProperty("java.io.tmpdir"), "smart_assistant")
    private val cardId = "fedcba9876543210fedcba9876543210"

    @Test
    fun onlyDedicatedDirectChildIsManaged() {
        val managed = ManagedHostPaths.templateFile(base, cardId)

        assertTrue(ManagedHostPaths.isManagedTemplate(base, managed))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base, "privacy")))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base.parentFile, managed.name)))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base, "sub/${managed.name}")))
    }

    @Test
    fun unsafeCardIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedHostPaths.templateFile(base, "../escape")
        }
    }
}
