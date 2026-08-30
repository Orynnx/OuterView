package org.orynnx.outerview.core.internal

/**
 * Caches one connection attempt for a bounded operation such as a repository refresh.
 * A failed attempt is cached too: recovery phases must retain their durable intent
 * instead of serially paying the same connection timeout again.
 */
internal class SingleConnectSession<T : Any>(
    private val connectBlock: () -> T,
    private val disconnectBlock: () -> Unit,
) : AutoCloseable {
    private var attempted = false
    private var closed = false
    private var cachedConnection: Result<T>? = null

    @Synchronized
    fun connect(): Result<T> {
        check(!closed) { "Connection session is already closed" }
        if (!attempted) {
            attempted = true
            cachedConnection = runCatching(connectBlock)
        }
        return checkNotNull(cachedConnection)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (attempted) disconnectBlock()
    }
}
