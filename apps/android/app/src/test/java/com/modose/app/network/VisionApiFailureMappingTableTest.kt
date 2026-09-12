package com.modose.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionApiFailureMappingTableTest {
    @Test
    fun transportFailuresRemainTypedAtApiBoundary() {
        val cases = listOf(
            VisionTransportResult.TimedOut to VisionApiResult.TimedOut,
            VisionTransportResult.NetworkFailure to
                VisionApiResult.NetworkFailure,
            VisionTransportResult.ResponseTooLarge to
                VisionApiResult.ResponseTooLarge,
        )

        cases.forEach { (transportResult, expected) ->
            val actual = client(transportResult).execute(validRequest())

            assertEquals(transportResult.toString(), expected, actual)
        }
    }

    @Test
    fun everySupportedHttpFailurePreservesStatusAndRetryPolicy() {
        val cases = listOf(
            HttpCase(400, false),
            HttpCase(401, false),
            HttpCase(403, false),
            HttpCase(409, false),
            HttpCase(413, false),
            HttpCase(415, false),
            HttpCase(422, false),
            HttpCase(429, true),
            HttpCase(500, true),
            HttpCase(502, true),
            HttpCase(503, true),
            HttpCase(504, true),
        )

        cases.forEach { case ->
            val result = client(
                VisionTransportResult.Received(
                    VisionTransportResponse(
                        statusCode = case.status,
                        body = ByteArray(0),
                    ),
                ),
            ).execute(validRequest())

            assertEquals(
                "HTTP ${case.status}",
                VisionApiResult.HttpFailure(
                    statusCode = case.status,
                    retryable = case.retryable,
                ),
                result,
            )
        }
    }

    private fun client(
        result: VisionTransportResult,
    ) = AuthenticatedVisionApiClient(
        baseUrl = "https://vision.example",
        clientVersion = "test",
        idTokenProvider = SecurityTokenProvider { "id-token" },
        appCheckTokenProvider = SecurityTokenProvider { "app-check-token" },
        transport = VisionHttpTransport { result },
    )

    private fun validRequest() = VisionApiRequest(
        method = VisionHttpMethod.POST,
        path = "/v1/vision/baseline",
        idempotencyKey = "mapping-table",
    )

    private data class HttpCase(
        val status: Int,
        val retryable: Boolean,
    )
}
