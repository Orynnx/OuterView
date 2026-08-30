package org.orynnx.outerview.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleConnectSessionTest {
    @Test
    fun `all refresh phases share one successful connection attempt`() {
        var connectCalls = 0
        var disconnectCalls = 0
        val connection = Any()
        val session = SingleConnectSession(
            connectBlock = {
                connectCalls++
                connection
            },
            disconnectBlock = { disconnectCalls++ },
        )

        repeat(5) {
            assertSame(connection, session.connect().getOrThrow())
        }
        session.close()
        session.close()

        assertEquals(1, connectCalls)
        assertEquals(1, disconnectCalls)
    }

    @Test
    fun `failed connection is retained instead of retried by later phases`() {
        var connectCalls = 0
        var disconnectCalls = 0
        val failure = IllegalStateException("host offline")
        val session = SingleConnectSession(
            connectBlock = {
                connectCalls++
                throw failure
            },
            disconnectBlock = { disconnectCalls++ },
        )

        repeat(5) {
            assertSame(failure, session.connect().exceptionOrNull())
        }
        session.close()

        assertEquals(1, connectCalls)
        assertEquals(1, disconnectCalls)
    }

    @Test
    fun `unused session does not disconnect an unattempted client`() {
        var disconnected = false
        val session = SingleConnectSession(
            connectBlock = { Any() },
            disconnectBlock = { disconnected = true },
        )

        session.close()

        assertTrue(!disconnected)
    }
}
