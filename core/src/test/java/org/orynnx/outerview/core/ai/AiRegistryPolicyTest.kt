package org.orynnx.outerview.core.ai

import org.junit.Assert.assertThrows
import org.junit.Test

class AiRegistryPolicyTest {
    private fun record(id: String, title: String = id) = AiRegistryRecord(
        id, mapOf("name" to title, "resourcePath" to "/runtime/$id/rearScreen.mrc", "preview" to null),
    )

    @Test fun `record readiness requires matching order and every field`() {
        val before = listOf(record("timer"), record("clock"))
        AiRegistryPolicy.requireReady(before, before.toList())
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireReady(before, before.reversed())
        }
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireReady(before, listOf(record("timer", "changed"), record("clock")))
        }
    }

    @Test fun `new card may appear anywhere while old order and fields remain unchanged`() {
        val before = listOf(record("timer"), record("clock"))
        val added = record("counter")
        for (index in 0..before.size) {
            val after = before.toMutableList().apply { add(index, added) }
            AiRegistryPolicy.requireTransition(before, after, addedId = "counter")
        }
    }

    @Test fun `record transition rejects metadata loss and old reordering`() {
        val before = listOf(record("timer"), record("clock"))
        val added = record("counter")
        val changedPath = before[0].copy(fields = before[0].fields + ("resourcePath" to "/unexpected.mrc"))
        val missingField = before[0].copy(fields = before[0].fields - "preview")
        for (after in listOf(
            listOf(changedPath, before[1], added),
            listOf(missingField, before[1], added),
            before.reversed() + added,
            listOf(before[1], added),
            before + added + record("unknown"),
        )) {
            assertThrows(IllegalStateException::class.java) {
                AiRegistryPolicy.requireTransition(before, after, addedId = "counter")
            }
        }
    }

    @Test fun `record removal retains all unrelated records and allows an already absent target`() {
        val before = listOf(record("timer"), record("clock"), record("counter"))
        AiRegistryPolicy.requireTransition(before, listOf(before[0], before[2]), removedId = "clock")
        AiRegistryPolicy.requireTransition(before, before, removedId = "absent")
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(before, listOf(before[2], before[0]), removedId = "clock")
        }
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(before, listOf(before[0]), removedId = "clock")
        }
    }

    @Test fun `record additions must be genuinely new and present exactly once`() {
        val before = listOf(record("timer"))
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(before, before, addedId = "counter")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRegistryPolicy.requireTransition(before, before, addedId = "timer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRegistryPolicy.requireTransition(before, before, "counter", "counter")
        }
    }

    @Test fun `record guards reject duplicate and blank IDs before comparing snapshots`() {
        for (invalid in listOf(listOf(record("timer"), record("timer")), listOf(record(" ")))) {
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireReady(invalid, invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireTransition(invalid, invalid)
            }
        }
    }

    @Test fun `disk entries with empty runtime do not authorize a mutation`() {
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireReady(setOf("timer", "clock"), emptySet())
        }
    }

    @Test fun `ready requires identical membership regardless of order`() {
        AiRegistryPolicy.requireReady(linkedSetOf("timer", "clock"), linkedSetOf("clock", "timer"))
        AiRegistryPolicy.requireReady(emptySet(), emptySet())
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireReady(setOf("timer"), setOf("clock"))
        }
    }

    @Test fun `adding a card must preserve every previous card`() {
        AiRegistryPolicy.requireTransition(setOf("timer", "clock"), setOf("timer", "clock", "counter"), addedId = "counter")
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(setOf("timer", "clock"), setOf("clock", "counter"), addedId = "counter")
        }
    }

    @Test fun `removal permits only the selected target to disappear`() {
        AiRegistryPolicy.requireTransition(setOf("timer", "clock"), setOf("clock"), removedId = "timer")
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(setOf("timer", "clock"), emptySet(), removedId = "timer")
        }
    }

    @Test fun `unknown additional entries fail even when the requested change exists`() {
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(setOf("timer"), setOf("timer", "counter", "unknown"), addedId = "counter")
        }
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(setOf("timer", "clock"), setOf("clock", "unknown"), removedId = "timer")
        }
    }

    @Test fun `combined transition adds then removes without changing unrelated entries`() {
        AiRegistryPolicy.requireTransition(setOf("timer", "clock"), setOf("clock", "counter"), "counter", "timer")
        AiRegistryPolicy.requireTransition(setOf("timer"), emptySet(), "timer", "timer")
    }

    @Test fun `no requested change requires exact preservation`() {
        AiRegistryPolicy.requireTransition(setOf("timer"), setOf("timer"))
        assertThrows(IllegalStateException::class.java) {
            AiRegistryPolicy.requireTransition(setOf("timer"), emptySet())
        }
    }

    @Test fun `blank IDs cannot make an invalid registry appear consistent`() {
        for (invalidId in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireReady(setOf(invalidId), setOf(invalidId))
            }
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireTransition(setOf(invalidId), setOf(invalidId))
            }
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireTransition(emptySet(), emptySet(), addedId = invalidId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AiRegistryPolicy.requireTransition(emptySet(), emptySet(), removedId = invalidId)
            }
        }
    }
}
