package hk.uwu.reareye.funcardcore.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
    fun `managed ownership requires prefix and canonical managed directory`() {
        val record = record()
        assertTrue(ManagedRearWallpaperPaths.isManagedResource(root, record))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(resId = "reareye_import_1")))
        assertFalse(ManagedRearWallpaperPaths.isManagedResource(root, record.copy(metaPath = File(root.parentFile, "escape.mrm").path)))
    }

    @Test
    fun `valid wallpaper package is inspected`() {
        val file = zip("manifest.xml" to "<Widget version=\"1\" type=\"awesome\"/>")
        val result = RearWallpaperPackageValidator.inspect(file)
        assertEquals("manifest.xml", result.descriptorName)
        assertEquals("Widget", result.rootElement)
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
}
