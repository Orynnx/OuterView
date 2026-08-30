package org.orynnx.outerview.core.internal

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

data class HostInstallRegistrySnapshot(
    val cardId: String,
    val business: String,
    val displayName: String,
    val templatePath: String,
    val sha256: String,
    val notificationId: Int,
    val updatedAt: Long,
    val enabled: Boolean,
    val pendingDelete: Boolean,
    val rearParam: String,
    val focusParam: String,
)

data class HostInstallJournal(
    val cardId: String,
    val oldRegistryFingerprint: String?,
    val newRegistryFingerprint: String,
    val oldTemplatePath: String?,
    val oldTargetSha256: String?,
    val newTargetSha256: String,
)

enum class HostInstallPrecondition {
    ALLOW,
    DELETE_PENDING,
    ENABLED,
    RUNTIME_NOT_CONFIRMED_ABSENT,
}

object HostInstallPreconditionPolicy {
    fun evaluate(
        previousPendingDelete: Boolean,
        previousEnabled: Boolean,
        runtimeAbsenceConfirmed: Boolean,
    ): HostInstallPrecondition = when {
        previousPendingDelete -> HostInstallPrecondition.DELETE_PENDING
        previousEnabled -> HostInstallPrecondition.ENABLED
        !runtimeAbsenceConfirmed -> HostInstallPrecondition.RUNTIME_NOT_CONFIRMED_ABSENT
        else -> HostInstallPrecondition.ALLOW
    }
}

enum class HostInstallRecovery {
    KEEP_NEW,
    RESTORE_NEW,
    KEEP_OLD,
    RESTORE_OLD,
    DELETE_NEW_TARGET,
}

object HostInstallJournalCodec {
    private const val SchemaVersion = 1
    private val CardId = Regex("[a-f0-9]{32}")
    private val Sha256 = Regex("[a-f0-9]{64}")
    private val JournalKeys = setOf(
        "schemaVersion",
        "cardId",
        "oldRegistryFingerprint",
        "newRegistryFingerprint",
        "oldTemplatePath",
        "oldTargetSha256",
        "newTargetSha256",
    )

    fun encode(journal: HostInstallJournal): ByteArray {
        validate(journal)
        return JsonObject().apply {
            addProperty("schemaVersion", SchemaVersion)
            addProperty("cardId", journal.cardId)
            addNullableString("oldRegistryFingerprint", journal.oldRegistryFingerprint)
            addProperty("newRegistryFingerprint", journal.newRegistryFingerprint)
            addNullableString("oldTemplatePath", journal.oldTemplatePath)
            addNullableString("oldTargetSha256", journal.oldTargetSha256)
            addProperty("newTargetSha256", journal.newTargetSha256)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(raw: String): HostInstallJournal {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.keySet() == JournalKeys) { "Host install journal fields are invalid" }
        val schema = root.get("schemaVersion").takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(schema?.isNumber == true && schema.asInt == SchemaVersion) {
            "Host install journal schema is invalid"
        }
        return HostInstallJournal(
            cardId = root.requiredString("cardId"),
            oldRegistryFingerprint = root.optionalString("oldRegistryFingerprint"),
            newRegistryFingerprint = root.requiredString("newRegistryFingerprint"),
            oldTemplatePath = root.optionalString("oldTemplatePath"),
            oldTargetSha256 = root.optionalString("oldTargetSha256"),
            newTargetSha256 = root.requiredString("newTargetSha256"),
        ).also(::validate)
    }

    fun registryFingerprint(snapshot: HostInstallRegistrySnapshot): String {
        validateSnapshot(snapshot)
        val canonical = JsonObject().apply {
            addProperty("cardId", snapshot.cardId)
            addProperty("business", snapshot.business)
            addProperty("displayName", snapshot.displayName)
            addProperty("templatePath", snapshot.templatePath)
            addProperty("sha256", snapshot.sha256)
            addProperty("notificationId", snapshot.notificationId)
            addProperty("updatedAt", snapshot.updatedAt)
            addProperty("enabled", snapshot.enabled)
            addProperty("pendingDelete", snapshot.pendingDelete)
            addProperty("rearParam", snapshot.rearParam)
            addProperty("focusParam", snapshot.focusParam)
        }.toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString(separator = "") { byte ->
                byte.toUByte().toString(16).padStart(2, '0')
            }
    }

    fun recovery(
        journal: HostInstallJournal,
        registryFingerprint: String?,
        targetSha256: String?,
        stagingSha256: String?,
        backupSha256: String?,
    ): HostInstallRecovery {
        validate(journal)
        return when (registryFingerprint) {
            journal.newRegistryFingerprint -> when {
                targetSha256 == journal.newTargetSha256 -> HostInstallRecovery.KEEP_NEW
                stagingSha256 == journal.newTargetSha256 -> HostInstallRecovery.RESTORE_NEW
                else -> error("Committed Host install has no valid new template")
            }
            journal.oldRegistryFingerprint -> {
                val oldSha256 = journal.oldTargetSha256
                if (oldSha256 == null) {
                    when (targetSha256) {
                        null -> HostInstallRecovery.KEEP_OLD
                        journal.newTargetSha256 -> HostInstallRecovery.DELETE_NEW_TARGET
                        else -> error("Uncommitted Host install target is ambiguous")
                    }
                } else {
                    when {
                        targetSha256 == oldSha256 -> HostInstallRecovery.KEEP_OLD
                        backupSha256 == oldSha256 -> HostInstallRecovery.RESTORE_OLD
                        else -> error("Uncommitted Host install has no valid old template backup")
                    }
                }
            }
            else -> error("Host registry does not match the install journal")
        }
    }

    private fun validate(journal: HostInstallJournal) {
        require(journal.cardId.matches(CardId)) { "Host install journal cardId is invalid" }
        require(journal.oldRegistryFingerprint == null || journal.oldRegistryFingerprint.matches(Sha256)) {
            "Host install journal old registry fingerprint is invalid"
        }
        require(journal.newRegistryFingerprint.matches(Sha256)) {
            "Host install journal new registry fingerprint is invalid"
        }
        require((journal.oldRegistryFingerprint == null) == (journal.oldTemplatePath == null)) {
            "Host install journal old registry path is inconsistent"
        }
        require(journal.oldTemplatePath == null || journal.oldTemplatePath.length in 1..4096) {
            "Host install journal old template path is invalid"
        }
        require(journal.oldTargetSha256 == null || journal.oldTargetSha256.matches(Sha256)) {
            "Host install journal old target digest is invalid"
        }
        require(journal.newTargetSha256.matches(Sha256)) {
            "Host install journal new target digest is invalid"
        }
    }

    private fun validateSnapshot(snapshot: HostInstallRegistrySnapshot) {
        require(snapshot.cardId.matches(CardId)) { "Host registry snapshot cardId is invalid" }
        require(ManagedHostPaths.matchesBusiness(snapshot.cardId, snapshot.business)) {
            "Host registry snapshot business is invalid"
        }
        require(snapshot.displayName.codePointCount(0, snapshot.displayName.length) in 1..80) {
            "Host registry snapshot display name is invalid"
        }
        require(snapshot.displayName.none(::isUnsafeDisplayCharacter)) {
            "Host registry snapshot display name is unsafe"
        }
        require(snapshot.templatePath.length in 1..4096) { "Host registry snapshot path is invalid" }
        require(snapshot.sha256.matches(Sha256)) { "Host registry snapshot digest is invalid" }
        require(snapshot.notificationId in 620_000..719_999) {
            "Host registry snapshot notificationId is invalid"
        }
        require(snapshot.updatedAt >= 0L) { "Host registry snapshot timestamp is invalid" }
        require(snapshot.rearParam.toByteArray().size + snapshot.focusParam.toByteArray().size <= 128 * 1024) {
            "Host registry snapshot payload is too large"
        }
        require(JsonParser.parseString(snapshot.rearParam).isJsonObject) {
            "Host registry snapshot rear payload is invalid"
        }
        require(JsonParser.parseString(snapshot.focusParam).isJsonObject) {
            "Host registry snapshot focus payload is invalid"
        }
    }

    private fun isUnsafeDisplayCharacter(character: Char): Boolean =
        character.isISOControl() || character in '\u202a'..'\u202e' ||
            character in '\u2066'..'\u2069' || character == '\u200e' ||
            character == '\u200f' || character == '\u061c'

    private fun JsonObject.addNullableString(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name).takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(value?.isString == true) { "Host install journal $name is invalid" }
        return value.asString
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name)
        if (value == null || value.isJsonNull) return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isString == true) { "Host install journal $name is invalid" }
        return primitive.asString
    }
}
