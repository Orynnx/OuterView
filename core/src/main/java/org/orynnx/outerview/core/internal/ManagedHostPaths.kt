package org.orynnx.outerview.core.internal

import java.io.File

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

    fun templateFile(baseDir: File, cardId: String): File {
        return File(baseDir, business(cardId))
    }

    fun isManagedTemplate(baseDir: File, candidate: File): Boolean {
        val base = baseDir.canonicalFile
        val target = candidate.canonicalFile
        return target.parentFile == base && listOf(BusinessPrefix, LegacyBusinessPrefix).any { prefix ->
            target.name.startsWith(prefix) && target.name.removePrefix(prefix).matches(SafeCardId)
        }
    }
}
