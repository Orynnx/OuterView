package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmartAssistantTemplateValidatorTest {
    @Test
    fun acceptsTopLevelWidgetV2AndReportsCommands() {
        val manifest = """
            <Widget version="2" screenWidth="1080">
              <IntentCommand package="com.example.target" action="example.ACTION"/>
              <ExternCommand command="oneTrack"/>
            </Widget>
        """.trimIndent()
        val file = zip(
            mapOf(
                "manifest.xml" to manifest,
                "assets/bg.txt" to "asset",
            )
        )

        val inspection = SmartAssistantTemplateValidator.inspect(file)

        assertEquals(2, inspection.entryCount)
        assertEquals((manifest.length + "asset".length).toLong(), inspection.expandedBytes)
        assertEquals(2, inspection.securityFindings.size)
        assertEquals(64, inspection.sha256.length)
    }

    @Test
    fun reportsCommandsFromEveryXmlEntryRegardlessOfCaseOrNamespacePrefix() {
        val file = zip(
            mapOf(
                "manifest.xml" to "<Widget version=\"2\"/>",
                "screens/details.XML" to """
                    <m:Group xmlns:m="urn:outerview:test">
                      <m:ExternCommand command="oneTrack"/>
                      <m:IntentCommand package="com.example.target" action="example.ACTION"/>
                    </m:Group>
                """.trimIndent(),
            ),
        )

        val findings = SmartAssistantTemplateValidator.inspect(file).securityFindings

        assertEquals(listOf("ExternCommand", "IntentCommand"), findings.map { it.type })
        assertTrue(findings.all { it.detail.startsWith("screens/details.XML:") })
    }

    @Test
    fun reportsReflectionExternalDataAndSystemControlCommands() {
        val file = zip(
            mapOf(
                "manifest.xml" to """
                    <Widget version="2">
                      <MethodCommand targetType="variable" method="run"/>
                      <ContentProviderBinder uri="content://example/private"/>
                      <Command target="wifi.toggle"/>
                      <m:Command xmlns:m="urn:outerview:test" m:targetExp="dynamicTarget"/>
                    </Widget>
                """.trimIndent(),
            ),
        )

        val findings = SmartAssistantTemplateValidator.inspect(file).securityFindings

        assertEquals(
            listOf("MethodCommand", "ContentProviderBinder", "Command", "Command"),
            findings.map { it.type },
        )
    }

    @Test
    fun countsActualExpandedBytesInsteadOfCentralDirectoryClaim() {
        val manifest = "<Widget version=\"2\"/>"
        val asset = "x".repeat(64 * 1024)
        val file = zip(mapOf("manifest.xml" to manifest, "assets/data.bin" to asset))
        rewriteCentralDirectorySize(file, "assets/data.bin", 1)

        val inspection = SmartAssistantTemplateValidator.inspect(file)

        assertEquals((manifest.length + asset.length).toLong(), inspection.expandedBytes)
    }

    @Test
    fun rejectsOrdinaryRootMamlButAcceptsMissingAndOtherWidgetVersions() {
        val root = zip(mapOf("manifest.xml" to "<Root version=\"2\"/>"))
        val missingVersion = zip(mapOf("manifest.xml" to "<Widget/>"))
        val version = zip(mapOf("manifest.xml" to "<Widget version=\"1\"/>"))

        assertThrows(IllegalArgumentException::class.java) {
            SmartAssistantTemplateValidator.inspect(root)
        }
        SmartAssistantTemplateValidator.inspect(missingVersion)
        SmartAssistantTemplateValidator.inspect(version)
    }

    @Test
    fun rejectsTraversalDoctypeAndMissingManifest() {
        val traversal = zip(mapOf("../manifest.xml" to "<Widget version=\"2\"/>"))
        val doctype = zip(mapOf("manifest.xml" to "<!DOCTYPE x><Widget version=\"2\"/>"))
        val entity = zip(mapOf("manifest.xml" to "<!ENTITY x SYSTEM \"file:///data/local/tmp/x\"><Widget version=\"2\"/>"))
        val missing = zip(mapOf("assets/a" to "x"))

        assertThrows(IllegalArgumentException::class.java) { SmartAssistantTemplateValidator.inspect(traversal) }
        assertThrows(IllegalArgumentException::class.java) { SmartAssistantTemplateValidator.inspect(doctype) }
        assertThrows(IllegalArgumentException::class.java) { SmartAssistantTemplateValidator.inspect(entity) }
        assertThrows(IllegalStateException::class.java) { SmartAssistantTemplateValidator.inspect(missing) }
    }

    @Test
    fun rejectsAmbiguousAbsoluteAndControlCharacterPaths() {
        val manifest = "manifest.xml" to "<Widget version=\"2\"/>"
        val duplicate = zipEntries(
            listOf(manifest, "assets/value" to "a", "assets\\value" to "b"),
        )
        val dotSegment = zipEntries(listOf(manifest, "assets/./value" to "a"))
        val absolute = zipEntries(listOf(manifest, "C:/assets/value" to "a"))
        val control = zipEntries(listOf(manifest, "assets/\u0001value" to "a"))

        listOf(duplicate, dotSegment, absolute, control).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                SmartAssistantTemplateValidator.inspect(file)
            }
        }
    }

    @Test
    fun rejectsOversizedManifestButIgnoresOversizedOptionalMetadata() {
        val oversizedManifest = zip(
            mapOf("manifest.xml" to "x".repeat(SmartAssistantTemplateValidator.MaxManifestBytes + 1)),
        )
        val oversizedMetadata = zip(
            mapOf(
                "manifest.xml" to "<Widget version=\"2\"/>",
                "outerview-card.json" to " ".repeat(SmartAssistantTemplateValidator.MaxMetadataBytes + 1),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SmartAssistantTemplateValidator.inspect(oversizedManifest)
        }
        assertEquals(null, SmartAssistantTemplateValidator.inspect(oversizedMetadata).metadata)
    }

    @Test
    fun acceptsMalformedOptionalMetadataAndWarnsForUnparseableSupplementaryXml() {
        val file = zip(
            mapOf(
                "manifest.xml" to "<Widget/>",
                "outerview-card.json" to "{not-json",
                "config/legacy.xml" to "not xml",
            ),
        )

        val inspection = SmartAssistantTemplateValidator.inspect(file)

        assertEquals(null, inspection.metadata)
        assertTrue(inspection.securityFindings.any { it.type == "无法扫描附属 XML" })
    }

    @Test
    fun computesSha256WithoutChangingDigestSemantics() {
        val file = File.createTempFile("fun-card-digest-", ".bin").apply {
            writeText("abc")
            deleteOnExit()
        }

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SmartAssistantTemplateValidator.sha256(file),
        )
    }

    private fun zip(entries: Map<String, String>): File {
        return zipEntries(entries.map { it.key to it.value })
    }

    private fun zipEntries(entries: List<Pair<String, String>>): File {
        val file = File.createTempFile("fun-card-test-", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        assertTrue(file.isFile)
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
