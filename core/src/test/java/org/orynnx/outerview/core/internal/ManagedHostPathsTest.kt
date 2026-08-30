package org.orynnx.outerview.core.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManagedHostPathsTest {
    private val base = File(System.getProperty("java.io.tmpdir"), "smart_assistant")
    private val cardId = "fedcba9876543210fedcba9876543210"

    @Test
    fun onlyDedicatedDirectChildIsManaged() {
        val managed = ManagedHostPaths.templateFile(base, cardId)
        val legacy = File(base, "${ManagedHostPaths.LegacyBusinessPrefix}$cardId")

        assertTrue(ManagedHostPaths.isManagedTemplate(base, managed))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, legacy))
        assertTrue(ManagedHostPaths.isTemplateForCard(base, legacy, cardId))
        assertFalse(
            ManagedHostPaths.isTemplateForCard(
                base,
                legacy,
                "0123456789abcdef0123456789abcdef",
            ),
        )
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base, "privacy")))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base.parentFile, managed.name)))
        assertFalse(ManagedHostPaths.isManagedTemplate(base, File(base, "sub/${managed.name}")))
    }

    @Test
    fun businessOwnershipDistinguishesCurrentAndLegacyPrefixes() {
        val current = "${ManagedHostPaths.BusinessPrefix}$cardId"
        val legacy = "${ManagedHostPaths.LegacyBusinessPrefix}$cardId"

        assertTrue(ManagedHostPaths.isCurrentBusiness(current))
        assertFalse(ManagedHostPaths.isCurrentBusiness(legacy))
        assertTrue(ManagedHostPaths.isLegacyBusiness(legacy))
        assertFalse(ManagedHostPaths.isLegacyBusiness(current))
        assertFalse(ManagedHostPaths.isCurrentBusiness("${ManagedHostPaths.BusinessPrefix}short"))
    }

    @Test
    fun unsafeCardIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedHostPaths.templateFile(base, "../escape")
        }
    }

    @Test
    fun symbolicLinksAreNeverManaged() {
        val root = Files.createTempDirectory("outerview-host-paths").toFile()
        val realBase = File(root, "real").apply { mkdirs() }
        val linkedBase = File(root, "linked")
        val target = File(realBase, "outerview_custom_$cardId").apply { writeText("test") }
        val linkedTarget = File(realBase, "outerview_custom_${"1".repeat(32)}")
        try {
            Files.createSymbolicLink(linkedBase.toPath(), realBase.toPath())
            Files.createSymbolicLink(linkedTarget.toPath(), target.toPath())
        } catch (error: Exception) {
            root.deleteRecursively()
            assumeNoException(error)
        }

        assertFalse(ManagedHostPaths.isManagedTemplate(linkedBase, target))
        assertFalse(ManagedHostPaths.isManagedTemplate(realBase, linkedTarget))
        assertThrows(IllegalArgumentException::class.java) {
            ManagedHostPaths.templateFile(linkedBase, cardId)
        }
        root.deleteRecursively()
    }
}
