package com.modose.app.network

import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RetryingVisionHttpTransportTest {
    @Test
    fun transientServiceFailureIsRetriedAndThenSucceeds() {
        val clock = FakeClock()
        val delegate = QueueTransport(
            mutableListOf(
                response(503),
                response(200, "ok"),
            ),
        )
        val transport = transport(delegate, clock)

        val result = transport.execute(request())

        assertEquals(2, delegate.requests.size)
        val received = result as VisionTransportResult.Received
        assertEquals(200, received.response.statusCode)
        assertArrayEquals("ok".toByteArray(), received.response.body)
        assertEquals(listOf(250L), clock.sleeps)
    }

    @Test
    fun authenticationFailureIsNotRetried() {
        val delegate = QueueTransport(mutableListOf(response(401)))
        val transport = transport(delegate, FakeClock())

        val result = transport.execute(request())

        assertEquals(1, delegate.requests.size)
        val received = result as VisionTransportResult.Received
        assertEquals(401, received.response.statusCode)
        assertArrayEquals(ByteArray(0), received.response.body)
    }

    @Test
    fun responseTooLargeIsNotRetried() {
        val delegate = QueueTransport(
            mutableListOf(
                VisionTransportResult.ResponseTooLarge,
                response(200),
            ),
        )
        val transport = transport(delegate, FakeClock())

        val result = transport.execute(request())

        assertEquals(1, delegate.requests.size)
        assertSame(VisionTransportResult.ResponseTooLarge, result)
    }

    @Test
    fun idempotencyKeyHeadersAndBodyArePreservedAcrossAttempts() {
        val clock = FakeClock()
        val delegate = QueueTransport(
            mutableListOf(
                VisionTransportResult.NetworkFailure,
                VisionTransportResult.TimedOut,
                response(200),
            ),
        )
        val original = request()
        val transport = transport(delegate, clock)

        transport.execute(original)

        assertEquals(3, delegate.requests.size)
        delegate.requests.forEach { attempted ->
            assertEquals(original.url, attempted.url)
            assertEquals(original.headers, attempted.headers)
            assertEquals("scene-save-42", attempted.headers["Idempotency-Key"])
            assertArrayEquals(original.body, attempted.body)
            assertEquals(original.contentType, attempted.contentType)
            assertEquals(original.maximumResponseBytes, attempted.maximumResponseBytes)
        }
        assertEquals(listOf(12_000, 11_750, 11_250), delegate.requests.map { it.timeoutMillis })
        assertEquals(listOf(250L, 500L), clock.sleeps)
    }

    @Test
    fun exhaustedDeadlineStopsBeforeAnotherAttempt() {
        val clock = FakeClock()
        val delegate = object : VisionHttpTransport {
            var calls = 0

            override fun execute(request: VisionTransportRequest): VisionTransportResult {
                calls += 1
                clock.now += 12_000
                return VisionTransportResult.NetworkFailure
            }
        }
        val transport = transport(delegate, clock)

        val result = transport.execute(request())

        assertEquals(1, delegate.calls)
        assertSame(VisionTransportResult.TimedOut, result)
    }

    private fun transport(
        delegate: VisionHttpTransport,
        clock: FakeClock,
    ) = RetryingVisionHttpTransport(
        delegate = delegate,
        maxAttempts = 3,
        totalDeadlineMillis = 12_000,
        initialBackoffMillis = 250,
        maximumBackoffMillis = 1_000,
        clock = clock,
        sleeper = clock,
    )

    private fun request() = VisionTransportRequest(
        method = VisionHttpMethod.POST,
        url = URL("https://vision.example.test/v1/scenes/baseline"),
        headers = mapOf(
            "Authorization" to "Bearer id-token",
            "X-Firebase-AppCheck" to "app-check-token",
            "Idempotency-Key" to "scene-save-42",
        ),
        body = """{"sceneId":"scene-42"}""".toByteArray(),
        contentType = "application/json",
        timeoutMillis = 12_000,
        maximumResponseBytes = 1_048_576,
    )

    private fun response(
        statusCode: Int,
        body: String = "",
    ) = VisionTransportResult.Received(
        VisionTransportResponse(statusCode, body.toByteArray()),
    )

    private class QueueTransport(
        private val results: MutableList<VisionTransportResult>,
    ) : VisionHttpTransport {
        val requests = mutableListOf<VisionTransportRequest>()

        override fun execute(request: VisionTransportRequest): VisionTransportResult {
            requests += request
            return results.removeAt(0)
        }
    }

    private class FakeClock(
        var now: Long = 0,
    ) : MonotonicClock, RetrySleeper {
        val sleeps = mutableListOf<Long>()

        override fun nowMillis(): Long = now

        override fun sleep(delayMillis: Long) {
            sleeps += delayMillis
            now += delayMillis
        }
    }
}
