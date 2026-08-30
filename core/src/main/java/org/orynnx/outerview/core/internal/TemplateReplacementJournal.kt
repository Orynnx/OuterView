package org.orynnx.outerview.core.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class TemplateReplacementJournal(
    val cardId: String,
    val oldSha256: String,
    val newSha256: String,
    val hadOriginal: Boolean,
)

internal enum class TemplateReplacementRecovery {
    COMMIT,
    KEEP_ORIGINAL,
    RESTORE_BACKUP,
    DELETE_REPLACEMENT,
}

internal object TemplateReplacementJournalCodec {
    private const val SchemaVersion = 1
    private val CardId = Regex("[a-f0-9]{32}")
    private val Sha256 = Regex("[a-f0-9]{64}")
    private val Keys = setOf("schemaVersion", "cardId", "oldSha256", "newSha256", "hadOriginal")

    fun encode(journal: TemplateReplacementJournal): ByteArray {
        validate(journal)
        return JsonObject().apply {
            addProperty("schemaVersion", SchemaVersion)
            addProperty("cardId", journal.cardId)
            addProperty("oldSha256", journal.oldSha256)
            addProperty("newSha256", journal.newSha256)
            addProperty("hadOriginal", journal.hadOriginal)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(raw: String): TemplateReplacementJournal {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.keySet() == Keys) { "卡片替换事务字段无效" }
        val schema = root.get("schemaVersion").takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(schema?.isNumber == true && schema.asInt == SchemaVersion) { "卡片替换事务版本无效" }
        val hadOriginal = root.get("hadOriginal").takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(hadOriginal?.isBoolean == true) { "卡片替换事务原文件标记无效" }
        return TemplateReplacementJournal(
            cardId = root.requiredString("cardId"),
            oldSha256 = root.requiredString("oldSha256"),
            newSha256 = root.requiredString("newSha256"),
            hadOriginal = hadOriginal.asBoolean,
        ).also(::validate)
    }

    fun recovery(
        journal: TemplateReplacementJournal,
        registrySha256: String,
        targetSha256: String?,
        backupSha256: String?,
    ): TemplateReplacementRecovery {
        validate(journal)
        return when (registrySha256) {
            journal.newSha256 -> {
                require(targetSha256 == journal.newSha256) {
                    "registry 已提交新模板，但本地 ZIP 不匹配"
                }
                TemplateReplacementRecovery.COMMIT
            }
            journal.oldSha256 -> when {
                targetSha256 == journal.oldSha256 -> TemplateReplacementRecovery.KEEP_ORIGINAL
                journal.hadOriginal -> {
                    require(backupSha256 == journal.oldSha256) { "旧模板备份缺失或已损坏" }
                    TemplateReplacementRecovery.RESTORE_BACKUP
                }
                else -> TemplateReplacementRecovery.DELETE_REPLACEMENT
            }
            else -> error("registry 与卡片替换事务均不匹配")
        }
    }

    private fun validate(journal: TemplateReplacementJournal) {
        require(journal.cardId.matches(CardId)) { "卡片替换事务 cardId 无效" }
        require(journal.oldSha256.matches(Sha256)) { "卡片替换事务旧摘要无效" }
        require(journal.newSha256.matches(Sha256)) { "卡片替换事务新摘要无效" }
        require(journal.oldSha256 != journal.newSha256) { "新旧模板摘要相同，无需替换" }
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name).takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(value?.isString == true) { "卡片替换事务 $name 无效" }
        return value.asString
    }
}
