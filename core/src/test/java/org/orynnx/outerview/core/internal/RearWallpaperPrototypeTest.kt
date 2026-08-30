package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RearWallpaperPrototypeTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "outerview-wallpaper-runtime")

    @Test
    fun `runtime codec preserves foreign records and rejects duplicates`() {
        val foreign = """[{"resId":"system","applyId":"default","unknown":7}]"""
        val record = record()
        val encoded = RearWallpaperRuntimeCodec.append(foreign, record)
        assertEquals(listOf("system", record.resId), RearWallpaperRuntimeCodec.decode(encoded).map { it.resId })
        assertTrue(encoded.contains("\"resSnapshotPath\""))
        assertTrue(encoded.contains("\"metaSnapshotPath\""))
        assertTrue(encoded.contains("\"isThirdParties\""))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RearWallpaperRuntimeCodec.append(encoded, record)
        }
    }

    @Test
    fun `runtime codec persists and decodes friendly display name`() {
        val record = record().copy(displayName = "深空时钟卡片")
        val decoded = RearWallpaperRuntimeCodec.decode(RearWallpaperRuntimeCodec.append("[]", record)).single()
        assertEquals("深空时钟卡片", decoded.displayName)
    }

    @Test
    fun `runtime codec restores only explicit boolean current markers`() {
        val raw = """[
          {"resId":"outerview_wallpaper_a","applyId":"1","outerviewCurrent":true},
          {"resId":"outerview_wallpaper_b","applyId":"2","outerviewCurrent":false},
          {"resId":"outerview_wallpaper_c","applyId":"3","outerviewCurrent":"true"}
        ]""".trimIndent()

        assertEquals(
            listOf("outerview_wallpaper_a"),
            RearWallpaperRuntimeCodec.decodeMarkedCurrent(raw).map { it.resId },
        )
    }

    @Test
    fun `stale persisted marker never authorizes wallpaper deletion`() {
        val staleMarkedId = 101
        val actuallyCurrentId = 202

        assertFalse(
            RearWallpaperSelectionPolicy.canDelete(
                targetId = actuallyCurrentId,
                observationKnown = false,
                observedCurrentId = staleMarkedId,
            ),
        )
        assertFalse(
            RearWallpaperSelectionPolicy.canDelete(
                targetId = actuallyCurrentId,
                observationKnown = true,
                observedCurrentId = actuallyCurrentId,
            ),
        )
        assertTrue(
            RearWallpaperSelectionPolicy.canDelete(
                targetId = staleMarkedId,
                observationKnown = true,
                observedCurrentId = actuallyCurrentId,
            ),
        )
    }

    @Test
    fun `unrecognized widget and in-flight selection keep deletion fail closed`() {
        val managedId = 101
        val foreignId = 202
        val unknown = RearWallpaperSelectionPolicy.observe(
            reflectedIds = emptyList(),
            runtimeIds = listOf(managedId, foreignId),
            managedIds = setOf(managedId),
            createdManagedId = null,
        )
        assertFalse(unknown.known)
        assertFalse(
            RearWallpaperSelectionPolicy.canDelete(
                targetId = managedId,
                observationKnown = unknown.known,
                observedCurrentId = unknown.managedCurrentId,
            ),
        )

        val observedForeign = RearWallpaperSelectionPolicy.observe(
            reflectedIds = listOf(foreignId),
            runtimeIds = listOf(managedId, foreignId),
            managedIds = setOf(managedId),
            createdManagedId = null,
        )
        assertTrue(observedForeign.known)
        assertEquals(null, observedForeign.managedCurrentId)
        assertFalse(
            RearWallpaperSelectionPolicy.canDelete(
                targetId = managedId,
                observationKnown = true,
                observedCurrentId = null,
                pendingApplyId = managedId,
            ),
        )
    }

    @Test
    fun `managed ownership requires prefix and canonical managed directory`() {
        val record = record()
        assertTrue(ManagedRearWallpaperPaths.isManagedResource(root, record))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(resId = "reareye_import_1")))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(metaPath = File(root.parentFile, "escape.mrm").path)))
        val directory = ManagedRearWallpaperPaths.resourceDirectory(root, record.resId, record.applyId)
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(resLocalPath = directory.path)))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(resLocalPath = File(directory, "nested/wallpaper.mrc").path)))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(metaPath = null)))
        assertEquals(directory.canonicalFile, ManagedRearWallpaperPaths.managedResourceDirectory(root, record))
    }

    @Test
    fun `managed ownership rejects a symlinked resource directory`() {
        val record = record().copy(
            resId = "outerview_wallpaper_symlink",
            applyId = "apply_symlink",
        )
        root.mkdirs()
        val target = File(root, "real_symlink_target").apply { mkdirs() }
        val link = ManagedRearWallpaperPaths.resourceDirectory(root, record.resId, record.applyId)
        val created = runCatching {
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }.isSuccess
        if (!created) {
            target.delete()
            return
        }
        try {
            assertEquals(null, ManagedRearWallpaperPaths.managedResourceDirectory(root, record))
        } finally {
            Files.deleteIfExists(link.toPath())
            target.delete()
        }
    }

    @Test
    fun `valid wallpaper package is inspected`() {
        val descriptor = "<Widget version=\"1\" type=\"awesome\"/>"
        val file = zip("manifest.xml" to descriptor, "assets/data.bin" to "x".repeat(32 * 1024))
        rewriteCentralDirectorySize(file, "assets/data.bin", 1)
        val result = RearWallpaperPackageValidator.inspect(file)
        assertEquals("manifest.xml", result.descriptorName)
        assertEquals("Widget", result.rootElement)
        assertEquals((descriptor.length + 32 * 1024).toLong(), result.expandedBytes)
    }

    @Test
    fun `unsafe and malformed packages are rejected`() {
        val traversal = zip("../manifest.xml" to "<Widget/>")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RearWallpaperPackageValidator.inspect(traversal)
        }
        val wrongRoot = zip("manifest.xml" to "<NotWallpaper/>")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RearWallpaperPackageValidator.inspect(wrongRoot)
        }
    }

    @Test
    fun `executable MAML commands are rejected in every XML entry`() {
        val descriptorCommand = zip(
            "manifest.xml" to "<Widget><IntentCommand action=\"android.intent.action.VIEW\"/></Widget>",
        )
        val nestedCommand = zip(
            "manifest.xml" to "<Widget version=\"1\"/>",
            "screens/details.xml" to "<Group><ExternCommand command=\"dangerous\"/></Group>",
        )
        val prefixedCommand = zip(
            "manifest.xml" to "<Widget version=\"1\"/>",
            "screens/commands.XML" to
                "<m:Group xmlns:m=\"urn:maml\"><m:IntentCommand action=\"dangerous\"/></m:Group>",
        )
        val reflectionCommand = zip(
            "manifest.xml" to
                "<Widget><MethodCommand targetType=\"ctor\" class=\"java.lang.ProcessBuilder\"/></Widget>",
        )
        val externalData = zip(
            "manifest.xml" to "<Widget version=\"1\"/>",
            "bindings/data.xml" to "<ContentProviderBinder uri=\"content://private/data\"/>",
        )
        val systemCommand = zip(
            "manifest.xml" to
                "<Widget><Trigger action=\"resume\"><Command target=\"WiFi\" value=\"off\"/></Trigger></Widget>",
        )
        val dynamicSystemCommand = zip(
            "manifest.xml" to
                "<Widget><m:Command xmlns:m=\"urn:maml\" targetExp=\"'Wifi'\" value=\"'off'\"/></Widget>",
        )

        listOf(
            descriptorCommand,
            nestedCommand,
            prefixedCommand,
            reflectionCommand,
            externalData,
            systemCommand,
            dynamicSystemCommand,
        ).forEach { file ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                RearWallpaperPackageValidator.inspect(file)
            }
        }
    }

    @Test
    fun `ambiguous absolute control and oversized descriptor packages are rejected`() {
        val descriptor = "manifest.xml" to "<Widget version=\"1\"/>"
        val duplicate = zip(
            descriptor,
            "assets/value" to "a",
            "assets\\value" to "b",
        )
        val dotSegment = zip(descriptor, "assets/./value" to "a")
        val absolute = zip(descriptor, "/assets/value" to "a")
        val control = zip(descriptor, "assets/\u007fvalue" to "a")
        val oversized = zip(
            "manifest.xml" to "x".repeat(RearWallpaperPackageValidator.MaxDescriptorBytes + 1),
        )

        listOf(duplicate, dotSegment, absolute, control, oversized).forEach { file ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                RearWallpaperPackageValidator.inspect(file)
            }
        }
    }

    private fun record(): RearWallpaperRuntimeRecord {
        val resId = "outerview_wallpaper_demo"
        val applyId = "apply1"
        val dir = File(root, "${resId}_${applyId}")
        return RearWallpaperRuntimeRecord(
            resId, applyId, File(dir, "wallpaper.mrc").path, File(dir, "metadata.mrm").path, null, 1,
        )
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = kotlin.io.path.createTempFile("wallpaper", ".mrc").toFile()
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name))
                output.write(value.toByteArray())
                output.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun rewriteCentralDirectorySize(file: File, entryName: String, declaredSize: Int) {
        val bytes = file.readBytes()
        var offset = 0
        while (offset <= bytes.size - CentralDirectoryHeaderSize) {
            if (uint32(bytes, offset) != CentralDirectorySignature) {
                offset++
                continue
            }
            val nameLength = uint16(bytes, offset + 28)
            val extraLength = uint16(bytes, offset + 30)
            val commentLength = uint16(bytes, offset + 32)
            val nameStart = offset + CentralDirectoryHeaderSize
            val name = bytes.copyOfRange(nameStart, nameStart + nameLength).toString(Charsets.UTF_8)
            if (name == entryName) {
                repeat(4) { index ->
                    bytes[offset + 24 + index] = (declaredSize ushr (index * 8)).toByte()
                }
                file.writeBytes(bytes)
                return
            }
            offset = nameStart + nameLength + extraLength + commentLength
        }
        error("central directory entry not found: $entryName")
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { result, index ->
            result or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }

    private companion object {
        const val CentralDirectorySignature = 0x02014b50L
        const val CentralDirectoryHeaderSize = 46
    }
}
