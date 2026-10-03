package org.orynnx.outerview.core.ai

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/** A preview token names a frozen private copy, never a caller-supplied filesystem path. */
internal class AiPreviewStore(
    private val now: () -> Long = System::nanoTime,
    private val maxPending: Int = 4,
    private val lifetimeNanos: Long = 30L * 60 * 1_000_000_000,
) {
    internal data class Pending(val file: File, val hash: String, val createdAt: Long)
    private val pending = linkedMapOf<String, Pending>()

    @Synchronized
    fun put(file: File): String {
        prune()
        require(pending.size < maxPending) { "待确认的导入过多，请先取消之前的导入" }
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(file, digest(file), now())
        return token
    }

    @Synchronized
    fun take(token: String): Pending? {
        prune()
        return pending.remove(token)
    }

    @Synchronized
    fun discard(token: String) {
        pending.remove(token)?.file?.delete()
    }

    @Synchronized
    fun prune() {
        val expired = pending.filterValues { now() - it.createdAt >= lifetimeNanos }.keys.toList()
        expired.forEach(::discard)
    }

    companion object {
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.forEachBlock(16 * 1024) { block, count ->
                digest.update(block, 0, count)
            }
            return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
        }
    }
}
