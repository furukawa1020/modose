package com.modose.app.network

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedVisionApiClientTest {
    @Test
    fun `sends protected contract headers with a twelve second timeout`() {
        var captured: VisionTransportRequest? = null
        val client = client(
            transport = VisionHttpTransport { request ->
                captured = request
                VisionTransportResult.Received(
                    VisionTransportResponse(200, "{}".toByteArray()),
                )
            },
        )

        val result = client.execute(validRequest())
        val sent = checkNotNull(captured)

        assertTrue(result is VisionApiResult.Success)
        assertEquals("Bearer id-token", sent.headers["Authorization"])
        assertEquals("app-check-token", sent.headers["X-Firebase-AppCheck"])
        assertEquals("operation-1", sent.headers["Idempotency-Key"])
        assertEquals("1.0", sent.headers["X-Schema-Version"])
        assertEquals("0.1.0", sent.headers["X-Client-Version"])
        assertEquals(12_000, sent.timeoutMillis)
        assertEquals(URL("https://vision.example/v1/vision/baseline"), sent.url)
    }

    @Test
    fun `does not send when id token is unavailable`() {
        var calls = 0
        val client = client(
            idToken = null,
            transport = VisionHttpTransport {
                calls += 1
                VisionTransportResult.NetworkFailure
            },
        )

        assertEquals(
            VisionApiResult.IDTokenUnavailable,
            client.execute(validRequest()),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `does not send when app check token is unavailable`() {
        var calls = 0
        val client = client(
            appCheckToken = "",
            transport = VisionHttpTransport {
                calls += 1
                VisionTransportResult.NetworkFailure
            },
        )

        assertEquals(
            VisionApiResult.AppCheckTokenUnavailable,
            client.execute(validRequest()),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `classifies retryable and terminal http failures`() {
        val statuses = listOf(401, 403, 409, 413, 415, 422, 429, 500, 503)
        val failures = statuses.map { status ->
            client(
                transport = VisionHttpTransport {
                    VisionTransportResult.Received(
                        VisionTransportResponse(status, ByteArray(0)),
                    )
                },
            ).execute(validRequest()) as VisionApiResult.HttpFailure
        }

        failures.take(6).forEach { assertFalse(it.retryable) }
        failures.drop(6).forEach { assertTrue(it.retryable) }
    }

    @Test
    fun `rejects an invalid path before reading credentials`() {
        var tokenReads = 0
        val client = AuthenticatedVisionApiClient(
            baseUrl = "https://vision.example",
            clientVersion = "0.1.0",
            idTokenProvider = SecurityTokenProvider {
                tokenReads += 1
                "id-token"
            },
            appCheckTokenProvider = SecurityTokenProvider { "app-check-token" },
            transport = VisionHttpTransport { VisionTransportResult.NetworkFailure },
        )

        val result = client.execute(
            validRequest().copy(path = "https://attacker.example/v1/vision/baseline"),
        )

        assertEquals(VisionApiResult.InvalidRequest, result)
        assertEquals(0, tokenReads)
    }

    private fun client(
        idToken: String? = "id-token",
        appCheckToken: String? = "app-check-token",
        transport: VisionHttpTransport,
    ) = AuthenticatedVisionApiClient(
        baseUrl = "https://vision.example/",
        clientVersion = "0.1.0",
        idTokenProvider = SecurityTokenProvider { idToken },
        appCheckTokenProvider = SecurityTokenProvider { appCheckToken },
        transport = transport,
    )

    private fun validRequest() = VisionApiRequest(
        method = VisionHttpMethod.POST,
        path = "/v1/vision/baseline",
        idempotencyKey = "operation-1",
        body = "{}".toByteArray(),
        contentType = "application/json",
    )
}
