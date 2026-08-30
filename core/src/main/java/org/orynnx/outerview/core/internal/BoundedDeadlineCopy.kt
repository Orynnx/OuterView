package org.orynnx.outerview.core.internal

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Copies an untrusted stream without allowing unbounded bytes or elapsed time. */
object BoundedDeadlineCopy {
    private const val BufferSize = 16 * 1024
    private const val WorkerTerminationTimeoutSeconds = 5L
    internal const val WorkerThreadName = "OuterView-bounded-copy"

    class LimitExceededException(message: String) : IOException(message)

    class DeadlineExceededException(message: String) : InterruptedIOException(message)

    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        timeoutNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): Long {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        require(timeoutNanos > 0L) { "timeoutNanos must be positive" }

        val startedAt = nanoTime()
        val buffer = ByteArray(BufferSize)
        var copied = 0L
        while (true) {
            checkCancelledOrExpired(startedAt, timeoutNanos, nanoTime)
            val read = input.read(buffer)
            checkCancelledOrExpired(startedAt, timeoutNanos, nanoTime)
            if (read < 0) return copied
            if (read == 0) continue
            if (read.toLong() > maxBytes - copied) {
                throw LimitExceededException("stream exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, read)
            copied += read
        }
    }

    /**
     * Runs [copy] on a worker while the calling thread enforces the same hard deadline.
     *
     * The second deadline is intentional: [InputStream.read] can block forever, so checks
     * around the read are insufficient on their own. On failure, [closeSource] runs on the
     * supervising caller and must close the underlying descriptor so a blocked read wakes.
     */
    fun copyWithSupervisor(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        timeoutNanos: Long,
        closeSource: () -> Unit,
    ): Long = runWithSupervisor(timeoutNanos, closeSource) {
        copy(input, output, maxBytes, timeoutNanos)
    }

    /** Supervises setup plus I/O when opening the untrusted source may also block. */
    fun <T> runWithSupervisor(
        timeoutNanos: Long,
        closeSource: () -> Unit,
        task: () -> T,
    ): T {
        require(timeoutNanos > 0L) { "timeoutNanos must be positive" }

        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, WorkerThreadName).apply { isDaemon = true }
        }
        val future: Future<T> = try {
            executor.submit<T> { task() }
        } catch (error: Throwable) {
            executor.shutdownNow()
            throw error
        }

        var result: T? = null
        var failure: Throwable? = null
        var restoreInterrupt = false
        try {
            result = future.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            failure = DeadlineExceededException("stream copy exceeded its deadline").apply {
                initCause(error)
            }
        } catch (error: InterruptedException) {
            restoreInterrupt = true
            failure = InterruptedIOException("stream copy supervisor was interrupted").apply {
                initCause(error)
            }
        } catch (error: CancellationException) {
            failure = InterruptedIOException("stream copy was cancelled").apply {
                initCause(error)
            }
        } catch (error: ExecutionException) {
            failure = error.cause ?: error
        }

        if (failure != null) {
            val closeFailure = runCatching(closeSource).exceptionOrNull()
            if (closeFailure != null) {
                requireNotNull(failure).addSuppressed(closeFailure)
            }
        }
        if (failure != null) future.cancel(true)
        executor.shutdownNow()

        val termination = awaitTerminationUninterruptibly(executor)
        restoreInterrupt = restoreInterrupt || termination.interrupted
        if (!termination.terminated) {
            val terminationFailure = IOException("bounded copy worker did not terminate")
            val primary = failure
            if (primary == null) failure = terminationFailure else primary.addSuppressed(terminationFailure)
        }
        if (restoreInterrupt) Thread.currentThread().interrupt()

        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun checkCancelledOrExpired(
        startedAt: Long,
        timeoutNanos: Long,
        nanoTime: () -> Long,
    ) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("stream copy was cancelled")
        }
        if (nanoTime() - startedAt >= timeoutNanos) {
            throw DeadlineExceededException("stream copy exceeded its deadline")
        }
    }

    private fun awaitTerminationUninterruptibly(
        executor: java.util.concurrent.ExecutorService,
    ): TerminationResult {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(WorkerTerminationTimeoutSeconds)
        var interrupted = false
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return TerminationResult(false, interrupted)
            try {
                return TerminationResult(
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS),
                    interrupted,
                )
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    private data class TerminationResult(
        val terminated: Boolean,
        val interrupted: Boolean,
    )
}
