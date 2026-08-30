package org.orynnx.outerview.core.internal

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.orynnx.outerview.core.RearCardState

data class CustomCardRegistryEnvelope(
    val schemaVersion: Int = 2,
    val records: List<CustomCardRecord> = emptyList(),
)

object FunCardRegistryCodec {
    private const val MaxRecords = 1_024
    private const val MaxPayloadBytes = 128 * 1_024
    private const val MaxPathCharacters = 4_096
    private val SafeCardId = Regex("[a-f0-9]{32}")
    private val SafeSha256 = Regex("[a-f0-9]{64}")
    private val ValidStates = RearCardState.entries.mapTo(mutableSetOf()) { it.value }
    private val BidiControlCharacters = setOf(
        '\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
    private val gson = Gson()

    fun encode(records: List<CustomCardRecord>): String {
        validateRecords(records)
        return gson.toJson(CustomCardRegistryEnvelope(records = records.sortedBy { it.cardId }))
    }

    fun decode(raw: String?): List<CustomCardRecord> {
        return runCatching { decodeStrict(raw) }.getOrDefault(emptyList())
    }

    fun decodeStrict(raw: String?): List<CustomCardRecord> {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank()) return emptyList()
        val parsed = JsonParser.parseString(normalized)
        require(parsed.isJsonObject) { "卡片 registry 根节点必须是对象" }
        val root = parsed.asJsonObject
        val schemaVersion = root.get("schemaVersion")
        require(
            schemaVersion?.isJsonPrimitive == true &&
                schemaVersion.asJsonPrimitive.isNumber &&
                schemaVersion.asInt == 2,
        ) {
            "不支持的卡片 registry 版本"
        }
        val recordsElement = root.get("records")
        require(recordsElement?.isJsonArray == true) { "卡片 registry 缺少 records 数组" }
        require(recordsElement.asJsonArray.size() <= MaxRecords) { "卡片 registry 记录超过 1024 条" }
        require(recordsElement.asJsonArray.all { it.isJsonObject }) { "卡片 registry 包含无效记录" }
        val envelope = requireNotNull(gson.fromJson(normalized, CustomCardRegistryEnvelope::class.java)) {
            "卡片 registry 为空"
        }
        require(envelope.schemaVersion == 2) { "不支持的卡片 registry 版本" }
        val normalizedRecords = envelope.records.map { record ->
            record.copy(
                mamlConfigJson = if (record.advancedPayload) {
                    record.mamlConfigJson.takeIf { value ->
                        value.toByteArray(Charsets.UTF_8).size <= MaxPayloadBytes && isJsonObject(value)
                    } ?: "{}"
                } else {
                    record.mamlConfigJson.ifBlank { "{}" }
                },
                advancedRearParamJson = if (record.advancedPayload) {
                    record.advancedRearParamJson?.takeIf(String::isNotBlank) ?: "{}"
                } else {
                    null
                },
                advancedFocusParamJson = if (record.advancedPayload) {
                    record.advancedFocusParamJson?.takeIf(String::isNotBlank) ?: "{}"
                } else {
                    null
                },
            )
        }
        validateRecords(normalizedRecords)
        return normalizedRecords
    }

    private fun validateRecords(records: List<CustomCardRecord>) {
        require(records.size <= MaxRecords) { "卡片 registry 记录超过 1024 条" }
        require(records.all { it.cardId.matches(SafeCardId) }) { "卡片 registry 包含无效 cardId" }
        require(records.all { ManagedHostPaths.matchesBusiness(it.cardId, it.business) }) {
            "卡片 registry 包含不匹配的 business"
        }
        require(records.map { it.cardId }.distinct().size == records.size) {
            "卡片 registry 包含重复 cardId"
        }
        require(records.all { it.notificationId in 620_000..719_999 }) {
            "卡片 registry 包含越界 notificationId"
        }
        require(records.map { it.notificationId }.distinct().size == records.size) {
            "卡片 registry 包含重复 notificationId"
        }
        require(records.all { it.sha256.matches(SafeSha256) }) { "卡片 registry 包含无效 SHA-256" }
        require(records.all { it.state in ValidStates }) { "卡片 registry 包含无效状态" }
        require(records.all { isSafeDisplayText(it.displayName, 80) }) {
            "卡片 registry 包含无效显示名称"
        }
        require(records.all { it.author == null || isSafeDisplayText(it.author, 80) }) {
            "卡片 registry 包含无效作者名称"
        }
        require(records.all { it.templateVersion == null || isSafeDisplayText(it.templateVersion, 32) }) {
            "卡片 registry 包含无效模板版本"
        }
        require(records.all {
            it.localZipPath.isNotBlank() &&
                it.localZipPath.length <= MaxPathCharacters &&
                '\u0000' !in it.localZipPath
        }) { "卡片 registry 包含无效本地路径" }
        require(records.map { it.localZipPath }.distinct().size == records.size) {
            "卡片 registry 包含重复本地路径"
        }
        require(records.all {
            it.hostTemplatePath == null ||
                (it.hostTemplatePath.length <= MaxPathCharacters && '\u0000' !in it.hostTemplatePath)
        }) { "卡片 registry 包含无效宿主路径" }
        records.forEach(::validatePayload)
    }

    private fun validatePayload(record: CustomCardRecord) {
        val mamlBytes = record.mamlConfigJson.toByteArray(Charsets.UTF_8).size
        require(mamlBytes <= MaxPayloadBytes && isJsonObject(record.mamlConfigJson)) {
            "卡片 registry 包含无效 MAML Payload"
        }
        val rear = record.advancedRearParamJson
        val focus = record.advancedFocusParamJson
        if (record.advancedPayload) {
            require(rear != null && focus != null) { "卡片 registry 缺少高级 Payload" }
            require(
                rear.toByteArray(Charsets.UTF_8).size + focus.toByteArray(Charsets.UTF_8).size <=
                    MaxPayloadBytes,
            ) { "卡片 registry 的高级 Payload 超过 128 KB" }
            require(isJsonObject(rear) && isJsonObject(focus)) { "卡片 registry 包含无效高级 Payload" }
        } else {
            require(rear == null && focus == null) { "卡片 registry 包含未启用的高级 Payload" }
        }
    }

    private fun isJsonObject(value: String): Boolean = runCatching {
        JsonParser.parseString(value).isJsonObject
    }.getOrDefault(false)

    private fun isSafeDisplayText(value: String, maxCodePoints: Int): Boolean {
        if (value.isBlank() || value.any { it.isISOControl() || it in BidiControlCharacters }) return false
        return value.codePointCount(0, value.length) <= maxCodePoints
    }
}
