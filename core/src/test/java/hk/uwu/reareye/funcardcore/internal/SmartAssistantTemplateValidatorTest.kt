package hk.uwu.reareye.funcardcore.internal

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
        val file = zip(
            mapOf(
                "manifest.xml" to """
                    <Widget version="2" screenWidth="1080">
                      <IntentCommand package="com.example.target" action="example.ACTION"/>
                      <ExternCommand command="oneTrack"/>
                    </Widget>
                """.trimIndent(),
                "assets/bg.txt" to "asset",
            )
        )

        val inspection = SmartAssistantTemplateValidator.inspect(file)

        assertEquals(2, inspection.entryCount)
        assertEquals(2, inspection.securityFindings.size)
        assertEquals(64, inspection.sha256.length)
    }

    @Test
    fun rejectsOrdinaryRootMamlAndWrongVersion() {
        val root = zip(mapOf("manifest.xml" to "<Root version=\"2\"/>"))
        val version = zip(mapOf("manifest.xml" to "<Widget version=\"1\"/>"))

        assertThrows(IllegalArgumentException::class.java) {
            SmartAssistantTemplateValidator.inspect(root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartAssistantTemplateValidator.inspect(version)
        }
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

    private fun zip(entries: Map<String, String>): File {
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
}
