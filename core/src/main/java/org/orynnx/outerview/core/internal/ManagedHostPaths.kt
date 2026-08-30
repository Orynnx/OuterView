package org.orynnx.outerview.core.internal

import java.io.File
import java.nio.file.Files

object ManagedHostPaths {
    private val SafeCardId = Regex("[a-f0-9]{32}")
    const val BusinessPrefix = "outerview_custom_"
    const val LegacyBusinessPrefix = "reareye_custom_"

    fun business(cardId: String): String {
        require(cardId.matches(SafeCardId)) { "cardId 无效" }
        return "$BusinessPrefix$cardId"
    }

    fun matchesBusiness(cardId: String, business: String): Boolean =
        cardId.matches(SafeCardId) &&
            (business == "$BusinessPrefix$cardId" || business == "$LegacyBusinessPrefix$cardId")

    fun isCurrentBusiness(business: String): Boolean =
        business.startsWith(BusinessPrefix) &&
            business.removePrefix(BusinessPrefix).matches(SafeCardId)

    fun isLegacyBusiness(business: String): Boolean =
        business.startsWith(LegacyBusinessPrefix) &&
            business.removePrefix(LegacyBusinessPrefix).matches(SafeCardId)

    fun templateFile(baseDir: File, cardId: String): File {
        require(!Files.isSymbolicLink(baseDir.toPath())) { "template base must not be a symbolic link" }
        val base = baseDir.canonicalFile
        val target = File(base, business(cardId))
        require(!Files.isSymbolicLink(target.toPath())) { "managed template must not be a symbolic link" }
        require(target.canonicalFile.parentFile == base) { "managed template escaped its base directory" }
        return target
    }

    fun isManagedTemplate(baseDir: File, candidate: File): Boolean {
        val target = directSafeChild(baseDir, candidate) ?: return false
        return isCurrentBusiness(target.name)
    }

    /**
     * Accepts the legacy prefix only when a trusted registry record already
     * supplied the matching [cardId].  Shape alone never establishes ownership
     * of another module's legacy template.
     */
    fun isTemplateForCard(baseDir: File, candidate: File, cardId: String): Boolean {
        if (!cardId.matches(SafeCardId)) return false
        val target = directSafeChild(baseDir, candidate) ?: return false
        return target.name == "$BusinessPrefix$cardId" ||
            target.name == "$LegacyBusinessPrefix$cardId"
    }

    private fun directSafeChild(baseDir: File, candidate: File): File? {
        if (Files.isSymbolicLink(baseDir.toPath()) || Files.isSymbolicLink(candidate.toPath())) return null
        val base = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return target.takeIf { it.parentFile == base }
    }
}
