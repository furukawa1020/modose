package com.modose.app.flow.baseline

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BaselineSettingsReadTest {
    @Test
    fun emptyInputIsPreserved() {
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBaselineSettings(8192))
    }

    @Test
    fun exactPropertiesLimitIsAccepted() {
        val bytes = ByteArray(8192) { it.toByte() }
        assertArrayEquals(bytes, bytes.inputStream().readBaselineSettings(8192))
    }

    @Test
    fun exactFirebaseLimitIsAccepted() {
        val bytes = ByteArray(65536) { it.toByte() }
        assertArrayEquals(bytes, bytes.inputStream().readBaselineSettings(65536))
    }

    @Test
    fun oneByteBeyondEitherLimitIsRejected() {
        for (limit in listOf(8192, 65536)) {
            assertThrows(BaselineCaptureRejected::class.java) {
                ByteArray(limit + 1).inputStream().readBaselineSettings(limit)
            }
        }
    }

    @Test
    fun shortReadsAreNotMistakenForEndOfFile() {
        val bytes = ByteArray(10000) { it.toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 7))
        }
        assertArrayEquals(bytes, input.readBaselineSettings(65536))
    }

    @Test
    fun readFailurePropagatesWithoutReturningPartialConfiguration() {
        val failure = IOException("broken input")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        assertSame(failure, assertThrows(IOException::class.java) {
            input.readBaselineSettings(8192)
        })
    }

    @Test
    fun callerRetainsOwnershipOnSuccessAndRejection() {
        for (size in listOf(1, 8193)) {
            var closed = false
            val input = object : ByteArrayInputStream(ByteArray(size)) {
                override fun close() { closed = true }
            }
            if (size == 1) {
                input.readBaselineSettings(8192)
            } else {
                assertThrows(BaselineCaptureRejected::class.java) {
                    input.readBaselineSettings(8192)
                }
            }
            assertFalse(closed)
            input.close()
            assertTrue(closed)
        }
    }
}
