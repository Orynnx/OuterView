package hk.uwu.reareye.funcardcore.internal

import com.google.gson.Gson
import com.google.gson.JsonParser

data class CustomCardRegistryEnvelope(
    val schemaVersion: Int = 2,
    val records: List<CustomCardRecord> = emptyList(),
)

object FunCardRegistryCodec {
    private val gson = Gson()

    fun encode(records: List<CustomCardRecord>): String = gson.toJson(
        CustomCardRegistryEnvelope(records = records.sortedBy { it.cardId })
    )

    fun decode(raw: String?): List<CustomCardRecord> {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank()) return emptyList()
        return runCatching {
            val root = JsonParser.parseString(normalized).asJsonObject
            if (!root.has("schemaVersion") || root.get("schemaVersion").asInt != 2) return emptyList()
            val envelope = gson.fromJson(normalized, CustomCardRegistryEnvelope::class.java)
            if (envelope?.schemaVersion != 2) return emptyList()
            envelope.records
                .filter { it.cardId.matches(Regex("[a-f0-9]{32}")) }
                .filter { it.business == "reareye_custom_${it.cardId}" }
                .distinctBy { it.cardId }
        }.getOrDefault(emptyList())
    }
}
