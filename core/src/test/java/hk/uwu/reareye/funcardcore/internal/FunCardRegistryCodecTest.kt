package hk.uwu.reareye.funcardcore.internal

import hk.uwu.reareye.funcardcore.RearCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunCardRegistryCodecTest {
    private val cardId = "0123456789abcdef0123456789abcdef"

    @Test
    fun schemaV2RoundTripPreservesLifecycleAndPayload() {
        val record = CustomCardRecord(
            cardId = cardId,
            business = "reareye_custom_$cardId",
            displayName = "测试卡片",
            localZipPath = "/data/user/0/test/files/source.zip",
            sha256 = "abc123",
            state = RearCardState.INSTALLED_ENABLED.value,
            notificationId = 620001,
            mamlConfigJson = "{\"title\":\"hello\"}",
            desiredEnabled = true,
            hostTemplatePath = "/data/system/theme_magic/users/0/subscreencenter/smart_assistant/reareye_custom_$cardId",
        )

        val decoded = FunCardRegistryCodec.decode(FunCardRegistryCodec.encode(listOf(record)))

        assertEquals(listOf(record), decoded)
        assertEquals(RearCardState.INSTALLED_ENABLED, decoded.single().stateEnum)
    }

    @Test
    fun rejectsLegacyMalformedAndMismatchedRecords() {
        val legacy = "{\"records\":[{\"cardId\":\"$cardId\",\"business\":\"reareye_custom_$cardId\"}]}"
        val mismatched = """
            {"schemaVersion":2,"records":[
              {"cardId":"$cardId","business":"wrong"},
              {"cardId":"short","business":"reareye_custom_short"}
            ]}
        """.trimIndent()

        assertTrue(FunCardRegistryCodec.decode(legacy).isEmpty())
        assertTrue(FunCardRegistryCodec.decode(mismatched).isEmpty())
        assertTrue(FunCardRegistryCodec.decode("not json").isEmpty())
    }
}
