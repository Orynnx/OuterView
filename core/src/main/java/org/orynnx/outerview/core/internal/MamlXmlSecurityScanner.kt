package org.orynnx.outerview.core.internal

import org.w3c.dom.Document
import org.w3c.dom.Element

internal data class MamlXmlInspection(
    val document: Document,
    val securityFindings: List<TemplateSecurityFinding>,
)

internal object MamlXmlSecurityScanner {
    private val externalCapabilityElements = setOf(
        "ContentProviderBinder",
        "WebServiceBinder",
        "BroadcastBinder",
    )
    private val systemCommandTargets = setOf("bluetooth", "data", "ringmode", "wifi")

    fun inspect(bytes: ByteArray, sourceName: String): MamlXmlInspection {
        val document = SecureManifestXml.parse(bytes)
        val findings = mutableListOf<TemplateSecurityFinding>()
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            when (val name = normalizedName(element)) {
                "IntentCommand" -> {
                    val target = listOf("package", "action", "class").mapNotNull { key ->
                        element.getAttribute(key).takeIf(String::isNotBlank)?.let { "$key=$it" }
                    }.joinToString(", ")
                    findings += finding(name, sourceName, target.ifBlank { "未声明目标" })
                }
                "ExternCommand" -> findings += finding(
                    name,
                    sourceName,
                    element.getAttribute("command").ifBlank { "未声明命令" },
                )
                "MethodCommand" -> {
                    val target = listOf("targetType", "class", "method").mapNotNull { key ->
                        element.getAttribute(key).takeIf(String::isNotBlank)?.let { "$key=$it" }
                    }.joinToString(", ")
                    findings += finding(name, sourceName, target.ifBlank { "未声明反射目标" })
                }
                in externalCapabilityElements -> findings += finding(
                    name,
                    sourceName,
                    listOf("uri", "service", "action").firstNotNullOfOrNull { key ->
                        element.getAttribute(key).takeIf(String::isNotBlank)?.let { "$key=$it" }
                    } ?: "外部数据入口",
                )
                "Command" -> genericSystemCommandFinding(element, sourceName)?.let(findings::add)
            }
        }
        return MamlXmlInspection(document, findings.distinct())
    }

    private fun genericSystemCommandFinding(
        element: Element,
        sourceName: String,
    ): TemplateSecurityFinding? {
        val attributes = element.attributes
        val dynamicTarget = (0 until attributes.length).map { attributes.item(it) }
            .firstOrNull { attribute ->
                val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
                name.equals("targetExp", ignoreCase = true) ||
                    name.equals("targetExpression", ignoreCase = true)
            }
            ?.nodeValue
            .orEmpty()
        if (dynamicTarget.isNotBlank()) {
            return finding("Command", sourceName, "动态 target=$dynamicTarget")
        }
        val target = element.getAttribute("target").trim()
        if (target.startsWith('@') || target.startsWith('#')) {
            return finding("Command", sourceName, "动态 target=$target")
        }
        val targetType = target.substringBefore('.').lowercase()
        return targetType.takeIf(systemCommandTargets::contains)?.let { blocked ->
            finding("Command", sourceName, "系统 target=$blocked")
        }
    }

    private fun normalizedName(element: Element): String =
        element.localName ?: element.nodeName.substringAfter(':')

    private fun finding(type: String, sourceName: String, detail: String) =
        TemplateSecurityFinding(type, "$sourceName: $detail")
}
