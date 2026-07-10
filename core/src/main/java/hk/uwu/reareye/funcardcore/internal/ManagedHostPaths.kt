package hk.uwu.reareye.funcardcore.internal

import java.io.File

object ManagedHostPaths {
    private val SafeCardId = Regex("[a-f0-9]{32}")
    private const val Prefix = "reareye_custom_"

    fun templateFile(baseDir: File, cardId: String): File {
        require(cardId.matches(SafeCardId)) { "cardId 无效" }
        return File(baseDir, "$Prefix$cardId")
    }

    fun isManagedTemplate(baseDir: File, candidate: File): Boolean {
        val base = baseDir.canonicalFile
        val target = candidate.canonicalFile
        return target.parentFile == base && target.name.startsWith(Prefix) &&
            target.name.removePrefix(Prefix).matches(SafeCardId)
    }
}
