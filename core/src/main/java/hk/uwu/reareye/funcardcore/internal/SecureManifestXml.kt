package hk.uwu.reareye.funcardcore.internal

import org.w3c.dom.Document
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

object SecureManifestXml {
    fun parse(bytes: ByteArray): Document {
        val declarationScan = String(bytes, StandardCharsets.ISO_8859_1)
            .replace("\u0000", "")
            .uppercase(Locale.ROOT)
        require("<!DOCTYPE" !in declarationScan) { "manifest.xml 禁止 DOCTYPE" }
        require("<!ENTITY" !in declarationScan) { "manifest.xml 禁止 ENTITY" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("manifest.xml 禁止外部实体") }
        }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, enabled: Boolean) {
        runCatching { setFeature(name, enabled) }
    }
}
