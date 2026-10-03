package org.orynnx.outerview.core.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiPackageParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manifest = """<Widget version="2"><JsCanvas name="main"><Script><![CDATA[let count = 0; function tap() { count++; }]]></Script></JsCanvas></Widget>""".toByteArray()

    @Test fun `raw script card preserves payload and explicitly warns about executable code`() {
        val bytes = archive("manifest.xml" to manifest, "assets/index.html" to "<canvas></canvas>".toByteArray())
        val parsed = AiPackageParser.parse(save(bytes))
        assertArrayEquals(bytes, parsed.mamlZipBytes)
        assertEquals("智能应用", parsed.name)
        assertNull(parsed.appIconBytes)
        assertTrue(parsed.warnings.any { it.contains("可执行 JavaScript") })
    }

    @Test fun `theme wrapper resolves friendly name and returns only inner maml archive`() {
        val inner = archive("manifest.xml" to manifest)
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)
        val outer = archive(
            "description.xml" to "<theme><resourceType>rearscreen</resourceType><appName>计数器</appName></theme>".toByteArray(),
            "rearscreen" to inner,
            "app/app_icon.png" to png,
            "preview/preview_dark.png" to png,
            "preview/preview_light.png" to (png + 1.toByte()),
        )
        val parsed = AiPackageParser.parse(save(outer))
        assertEquals("计数器", parsed.name)
        assertArrayEquals(inner, parsed.mamlZipBytes)
        assertArrayEquals(png, parsed.appIconBytes)
        assertArrayEquals(png + 1.toByte(), parsed.previewBytes)
        assertEquals(1, parsed.warnings.size)
    }

    @Test fun `MIUI Theme learning package and rearScreen mrc wrapper are supported`() {
        val inner = archive("manifest.xml" to manifest)
        val file = save(archive(
            "rearScreen.mrc" to inner,
            "description.xml" to "<MIUI-Theme><title>轻触计数器 - 实验样例</title><version>0.1.0</version></MIUI-Theme>".toByteArray(),
        ))
        val parsed = AiPackageParser.parse(file)
        assertEquals("轻触计数器 - 实验样例", parsed.name)
        assertArrayEquals(inner, parsed.mamlZipBytes)
    }

    @Test fun `rejects traversal absolute windows and backslash paths without creating files`() {
        for (unsafe in listOf("../escape", "/absolute", "C:/absolute", "assets/../../escape", "assets\\..\\escape", "assets//a", "assets/a:b")) {
            invalid(archive("manifest.xml" to manifest, unsafe to byteArrayOf(1)))
        }
        assertFalse(File(temporary.root.parentFile, "escape").exists())
    }

    @Test fun `rejects case aliases and file directory collisions`() {
        invalid(archive("manifest.xml" to manifest, "MANIFEST.XML" to manifest))
        invalid(archive("manifest.xml" to manifest, "assets" to byteArrayOf(1), "assets/a" to byteArrayOf(2)))
        invalid(archive("manifest.xml" to manifest, "assets/a" to byteArrayOf(2), "assets" to byteArrayOf(1)))
    }

    @Test fun `rejects DOCTYPE and external entities in any xml including metadata`() {
        val entity = """<!DOCTYPE Widget [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><Widget><JsCanvas><Script>&xxe;</Script></JsCanvas></Widget>""".toByteArray()
        invalid(archive("manifest.xml" to entity))
        invalid(archive("manifest.xml" to manifest, "strings/x.xml" to entity))
        val utf16 = ("\uFEFF" + String(entity)).toByteArray(Charsets.UTF_16LE)
        invalid(archive("manifest.xml" to utf16))
    }

    @Test fun `Credex provider and refresh commands preserve the native package`() {
        val provider = """
            <VariableBinders>
                <ContentProviderBinder name="credex" uri="content://com.nickwoluff.credex/quota/assistant" columns="five_hour_remaining" countName="rows">
                    <Variable name="quota" type="int" column="five_hour_remaining" row="0"/>
                </ContentProviderBinder>
            </VariableBinders>
            <ExternalCommands><Trigger action="init,resume"><BinderCommand name="credex" command="refresh"/></Trigger></ExternalCommands>
        """.trimIndent()
        val nativeManifest = String(manifest).replace("</Widget>", "$provider</Widget>")
        val bytes = archive("manifest.xml" to nativeManifest.toByteArray())
        assertArrayEquals(bytes, AiPackageParser.parse(save(bytes)).mamlZipBytes)
    }

    @Test fun `native MAML commands are passed through without a capability blacklist`() {
        val commands = """
            <VariableBinders><WebServiceBinder name="service" uri="https://example.invalid/status"/><BroadcastBinder name="events" action="example.STATE"/></VariableBinders>
            <CommandTriggers><Trigger action="up">
                <IntentCommand action="android.intent.action.MAIN" package="com.nickwoluff.credex"/>
                <MethodCommand target="view" method="requestUpdate"/>
                <ExternCommand command="refresh"/>
                <Command target="wifi" value="toggle"/>
            </Trigger></CommandTriggers>
        """.trimIndent()
        val bytes = archive("manifest.xml" to String(manifest).replace("</Widget>", "$commands</Widget>").toByteArray())
        assertArrayEquals(bytes, AiPackageParser.parse(save(bytes)).mamlZipBytes)
    }

    @Test fun `rejects extra nested archives even when hidden as image data`() {
        val inner = archive("manifest.xml" to manifest)
        val middle = archive("rearscreen" to inner)
        invalid(archive("rearscreen" to middle))
        invalid(archive("manifest.xml" to manifest, "assets/hidden.png" to inner))
        invalid(archive("rearscreen" to inner, "extras.zip" to inner))
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.startsWith("ai-validate-") })
    }

    @Test fun `rejects ambiguous entry points missing scripts and malformed xml`() {
        val inner = archive("manifest.xml" to manifest)
        invalid(archive("manifest.xml" to manifest, "rearscreen" to inner))
        invalid(archive("rearscreen" to inner, "rearScreen.mrc" to inner))
        invalid(archive("manifest.xml" to "<Widget><Text text=\"hello\"/></Widget>".toByteArray()))
        invalid(archive("manifest.xml" to "<Widget><JsCanvas><Script/></JsCanvas></Widget>".toByteArray()))
        invalid(archive("manifest.xml" to "<Widget>".toByteArray()))
    }

    @Test fun `enforces entry count decompressed aggregate and compression ratio limits`() {
        val many = (0..AiPackageParser.MAX_ENTRIES).map { "asset/$it" to byteArrayOf(0) }.toTypedArray()
        invalid(archive("manifest.xml" to manifest, *many))
        val aggregate = (0..32).map { "asset/$it" to ByteArray(1024 * 1024) }.toTypedArray()
        invalid(archive("manifest.xml" to manifest, *aggregate))
        invalid(archive("manifest.xml" to manifest, "bomb" to ByteArray(2 * 1024 * 1024)))
    }

    @Test fun `rejects malformed and oversized input and keeps original bytes untouched`() {
        invalid("not a zip".toByteArray())
        val oversized = temporary.newFile()
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(AiPackageParser.MAX_PACKAGE_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { AiPackageParser.parse(oversized) }
        val outer = archive("rearscreen" to archive("manifest.xml" to manifest))
        val file = save(outer)
        AiPackageParser.parse(file)
        assertArrayEquals(outer, file.readBytes())
    }

    private fun invalid(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { AiPackageParser.parse(save(bytes)) }
    }
    private fun save(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        buffer.toByteArray()
    }
}
