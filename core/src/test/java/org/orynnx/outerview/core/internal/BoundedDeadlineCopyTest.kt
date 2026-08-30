package org.orynnx.outerview.core.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedDeadlineCopyTest {
    @Test
    fun `copies exactly the byte limit`() {
        val input = "wallpaper".toByteArray()
        val output = ByteArrayOutputStream()

        val copied = BoundedDeadlineCopy.copy(
            input = ByteArrayInputStream(input),
            output = output,
            maxBytes = input.size.toLong(),
            timeoutNanos = 1_000L,
            nanoTime = { 0L },
        )

        assertEquals(input.size.toLong(), copied)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test
    fun `rejects data beyond the byte limit before writing the oversized chunk`() {
        val output = ByteArrayOutputStream()

        assertThrows(BoundedDeadlineCopy.LimitExceededException::class.java) {
            BoundedDeadlineCopy.copy(
                input = ByteArrayInputStream("12345".toByteArray()),
                output = output,
                maxBytes = 4L,
                timeoutNanos = 1_000L,
                nanoTime = { 0L },
            )
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `rejects a read that completes after the deadline`() {
        val ticks = ArrayDeque(listOf(0L, 0L, 11L))
        val output = ByteArrayOutputStream()

        assertThrows(BoundedDeadlineCopy.DeadlineExceededException::class.java) {
            BoundedDeadlineCopy.copy(
                input = ByteArrayInputStream("late".toByteArray()),
                output = output,
                maxBytes = 16L,
                timeoutNanos = 10L,
                nanoTime = { if (ticks.isEmpty()) 11L else ticks.removeFirst() },
            )
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `honors task interruption`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                BoundedDeadlineCopy.copy(
                    input = ByteArrayInputStream(byteArrayOf(1)),
                    output = ByteArrayOutputStream(),
                    maxBytes = 1L,
                    timeoutNanos = 1_000L,
                    nanoTime = { 0L },
                )
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `supervisor closes a no-data source from outside the copy worker`() {
        val supervisorThread = Thread.currentThread()
        val readerThread = AtomicReference<Thread>()
        val closerThread = AtomicReference<Thread>()
        val readExited = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val input = object : InputStream() {
            @Volatile
            private var closed = false

            override fun read(): Int = error("bulk read expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readerThread.set(Thread.currentThread())
                try {
                    releaseRead.await()
                } finally {
                    readExited.countDown()
                }
                if (closed) throw IOException("source closed")
                return -1
            }

            override fun close() {
                closerThread.set(Thread.currentThread())
                closed = true
                releaseRead.countDown()
            }
        }

        assertThrows(BoundedDeadlineCopy.DeadlineExceededException::class.java) {
            BoundedDeadlineCopy.copyWithSupervisor(
                input = input,
                output = ByteArrayOutputStream(),
                maxBytes = 32L,
                timeoutNanos = TimeUnit.MILLISECONDS.toNanos(250L),
                closeSource = input::close,
            )
        }

        assertEquals(supervisorThread, closerThread.get())
        assertNotSame(readerThread.get(), closerThread.get())
        assertTrue(readExited.await(1L, TimeUnit.SECONDS))
        readerThread.get().join(1_000L)
        assertFalse(readerThread.get().isAlive)
    }
}
