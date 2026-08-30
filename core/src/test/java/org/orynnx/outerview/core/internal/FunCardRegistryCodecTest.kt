package org.orynnx.outerview.core.internal

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.orynnx.outerview.core.RearCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FunCardRegistryCodecTest {
    private val cardId = "0123456789abcdef0123456789abcdef"
    private val sha256 = "ab".repeat(32)

    private fun validRecord(
        id: String = cardId,
        notificationId: Int = 620001,
    ) = CustomCardRecord(
        cardId = id,
        business = "outerview_custom_$id",
        displayName = "测试卡片",
        localZipPath = "/data/user/0/test/files/custom_cards_v2/$id/source.zip",
        sha256 = sha256,
        state = RearCardState.INSTALLED_ENABLED.value,
        notificationId = notificationId,
        mamlConfigJson = "{\"title\":\"hello\"}",
        desiredEnabled = true,
        hostTemplatePath = "/data/system/theme_magic/users/0/subscreencenter/smart_assistant/outerview_custom_$id",
    )

    @Test
    fun schemaV2RoundTripPreservesLifecycleAndPayload() {
        val record = validRecord().copy(pendingInstall = true)

        val decoded = FunCardRegistryCodec.decode(FunCardRegistryCodec.encode(listOf(record)))

        assertEquals(listOf(record), decoded)
        assertEquals(RearCardState.INSTALLED_ENABLED, decoded.single().stateEnum)
        assertTrue(decoded.single().pendingInstall)
    }

    @Test
    fun schemaV2WithoutPendingInstallRemainsBackwardCompatible() {
        val root = JsonParser.parseString(
            Gson().toJson(CustomCardRegistryEnvelope(records = listOf(validRecord()))),
        ).asJsonObject
        root.getAsJsonArray("records").single().asJsonObject.remove("pendingInstall")

        val decoded = FunCardRegistryCodec.decodeStrict(root.toString())

        assertFalse(decoded.single().pendingInstall)
    }

    @Test
    fun schemaV2RoundTripPreservesDeleteTombstone() {
        val tombstone = validRecord().copy(
            state = RearCardState.ERROR.value,
            desiredEnabled = false,
            pendingInstall = false,
            cleanupPending = true,
            deleted = true,
            lastMessage = "等待清理",
        )

        val decoded = FunCardRegistryCodec.decodeStrict(
            FunCardRegistryCodec.encode(listOf(tombstone)),
        ).single()

        assertTrue(decoded.deleted)
        assertTrue(decoded.cleanupPending)
        assertFalse(decoded.pendingInstall)
    }

    @Test
    fun rejectsLegacyMalformedAndMismatchedRecords() {
        val legacySchema = "{\"records\":[{\"cardId\":\"$cardId\",\"business\":\"reareye_custom_$cardId\"}]}"
        val mismatched = """
            {"schemaVersion":2,"records":[
              {"cardId":"$cardId","business":"wrong"},
              {"cardId":"short","business":"reareye_custom_short"}
            ]}
        """.trimIndent()

        assertTrue(FunCardRegistryCodec.decode(legacySchema).isEmpty())
        assertTrue(FunCardRegistryCodec.decode(mismatched).isEmpty())
        assertTrue(FunCardRegistryCodec.decode("not json").isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            FunCardRegistryCodec.decodeStrict(legacySchema)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunCardRegistryCodec.decodeStrict(mismatched)
        }
        assertThrows(RuntimeException::class.java) {
            FunCardRegistryCodec.decodeStrict("not json")
        }
    }

    @Test
    fun strictDecodeRejectsDuplicateCardIds() {
        val duplicate = """
            {"schemaVersion":2,"records":[
              {"cardId":"$cardId","business":"outerview_custom_$cardId"},
              {"cardId":"$cardId","business":"outerview_custom_$cardId"}
            ]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            FunCardRegistryCodec.decodeStrict(duplicate)
        }
        assertTrue(FunCardRegistryCodec.decode(duplicate).isEmpty())
    }

    @Test
    fun strictDecodeRequiresBoundedRecordArray() {
        val missing = "{\"schemaVersion\":2}"
        val notArray = "{\"schemaVersion\":2,\"records\":{}}"
        val tooMany = buildString {
            append("{\"schemaVersion\":2,\"records\":[")
            repeat(1_025) { index ->
                if (index > 0) append(',')
                append("{}")
            }
            append("]}")
        }

        listOf(missing, notArray, tooMany).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                FunCardRegistryCodec.decodeStrict(raw)
            }
        }
    }

    @Test
    fun encodeRejectsInvalidIdentityAndPayloadFields() {
        val secondId = "1".repeat(32)
        val invalidRecords = listOf(
            validRecord(notificationId = 719_999).copy(notificationId = 720_000),
            validRecord().copy(sha256 = "abc123"),
            validRecord().copy(state = "UNKNOWN"),
            validRecord().copy(displayName = "unsafe\u202ename"),
            validRecord().copy(mamlConfigJson = "[]"),
            validRecord().copy(advancedPayload = true),
        )
        invalidRecords.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                FunCardRegistryCodec.encode(listOf(record))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunCardRegistryCodec.encode(
                listOf(validRecord(), validRecord(secondId, notificationId = 620001)),
            )
        }
    }

    @Test
    fun strictDecodeNormalizesLegacyPayloadFields() {
        val basic = validRecord().copy(
            advancedPayload = false,
            advancedRearParamJson = "{\"stale\":true}",
            advancedFocusParamJson = "{}",
        )
        val advanced = validRecord("1".repeat(32), 620002).copy(
            advancedPayload = true,
            mamlConfigJson = "[]",
            advancedRearParamJson = null,
            advancedFocusParamJson = "",
        )
        val raw = Gson().toJson(CustomCardRegistryEnvelope(records = listOf(basic, advanced)))

        val decoded = FunCardRegistryCodec.decodeStrict(raw)

        assertEquals(null, decoded[0].advancedRearParamJson)
        assertEquals(null, decoded[0].advancedFocusParamJson)
        assertEquals("{}", decoded[1].mamlConfigJson)
        assertEquals("{}", decoded[1].advancedRearParamJson)
        assertEquals("{}", decoded[1].advancedFocusParamJson)
    }
}
