package com.modose.app.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

fun interface SecurityTokenProvider {
    fun getToken(): String?
}

enum class VisionHttpMethod {
    POST,
    DELETE,
}

data class VisionApiRequest(
    val method: VisionHttpMethod,
    val path: String,
    val idempotencyKey: String,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
)

data class VisionTransportRequest(
    val method: VisionHttpMethod,
    val url: URL,
    val headers: Map<String, String>,
    val body: ByteArray,
    val contentType: String?,
    val timeoutMillis: Int,
    val maximumResponseBytes: Int,
)

data class VisionTransportResponse(
    val statusCode: Int,
    val body: ByteArray,
)

sealed interface VisionTransportResult {
    data class Received(val response: VisionTransportResponse) : VisionTransportResult
    data object TimedOut : VisionTransportResult
    data object NetworkFailure : VisionTransportResult
    data object ResponseTooLarge : VisionTransportResult
}

fun interface VisionHttpTransport {
    fun execute(request: VisionTransportRequest): VisionTransportResult
}

sealed interface VisionApiResult {
    data class Success(
        val statusCode: Int,
        val body: ByteArray,
    ) : VisionApiResult

    data object IDTokenUnavailable : VisionApiResult
    data object AppCheckTokenUnavailable : VisionApiResult
    data object InvalidRequest : VisionApiResult
    data object TimedOut : VisionApiResult
    data object NetworkFailure : VisionApiResult
    data object ResponseTooLarge : VisionApiResult

    data class HttpFailure(
        val statusCode: Int,
        val retryable: Boolean,
    ) : VisionApiResult
}

class AuthenticatedVisionApiClient(
    baseUrl: String,
    private val clientVersion: String,
    private val idTokenProvider: SecurityTokenProvider,
    private val appCheckTokenProvider: SecurityTokenProvider,
    private val transport: VisionHttpTransport = UrlConnectionVisionHttpTransport(),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        require(normalizedBaseUrl.startsWith("https://"))
        require(clientVersion.isNotBlank())
        require(timeoutMillis > 0)
        require(maximumResponseBytes > 0)
    }

    fun execute(request: VisionApiRequest): VisionApiResult {
        if (
            !request.path.startsWith("/v1/") ||
            request.path.contains("://") ||
            request.idempotencyKey.isBlank() ||
            (request.body.isNotEmpty() && request.contentType.isNullOrBlank())
        ) {
            return VisionApiResult.InvalidRequest
        }

        val idToken = idTokenProvider.getToken()
            ?.takeIf(String::isNotBlank)
            ?: return VisionApiResult.IDTokenUnavailable
        val appCheckToken = appCheckTokenProvider.getToken()
            ?.takeIf(String::isNotBlank)
            ?: return VisionApiResult.AppCheckTokenUnavailable

        val transportRequest = VisionTransportRequest(
            method = request.method,
            url = URL(normalizedBaseUrl + request.path),
            headers = mapOf(
                "Authorization" to "Bearer $idToken",
                "X-Firebase-AppCheck" to appCheckToken,
                "Idempotency-Key" to request.idempotencyKey,
                "X-Client-Version" to clientVersion,
                "X-Schema-Version" to SCHEMA_VERSION,
                "Accept" to "application/json",
            ),
            body = request.body,
            contentType = request.contentType,
            timeoutMillis = timeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
        )

        return when (val result = transport.execute(transportRequest)) {
            is VisionTransportResult.Received -> {
                val response = result.response
                if (response.statusCode in 200..299) {
                    VisionApiResult.Success(response.statusCode, response.body)
                } else {
                    VisionApiResult.HttpFailure(
                        statusCode = response.statusCode,
                        retryable = response.statusCode in RETRYABLE_STATUS_CODES,
                    )
                }
            }
            VisionTransportResult.TimedOut -> VisionApiResult.TimedOut
            VisionTransportResult.NetworkFailure -> VisionApiResult.NetworkFailure
            VisionTransportResult.ResponseTooLarge -> VisionApiResult.ResponseTooLarge
        }
    }

    private companion object {
        const val SCHEMA_VERSION = "1.0"
        const val DEFAULT_TIMEOUT_MILLIS = 12_000
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 1_048_576
        val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
    }
}

class UrlConnectionVisionHttpTransport : VisionHttpTransport {
    override fun execute(request: VisionTransportRequest): VisionTransportResult {
        val connection = try {
            request.url.openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return VisionTransportResult.NetworkFailure
        }

        return try {
            connection.requestMethod = request.method.name
            connection.connectTimeout = request.timeoutMillis
            connection.readTimeout = request.timeoutMillis
            connection.instanceFollowRedirects = false
            request.headers.forEach(connection::setRequestProperty)
            request.contentType?.let { connection.setRequestProperty("Content-Type", it) }

            if (request.body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { output -> output.write(request.body) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use {
                readAtMost(it, request.maximumResponseBytes)
            } ?: ByteArray(0)
            if (body == null) {
                VisionTransportResult.ResponseTooLarge
            } else {
                VisionTransportResult.Received(
                    VisionTransportResponse(statusCode, body),
                )
            }
        } catch (_: SocketTimeoutException) {
            VisionTransportResult.TimedOut
        } catch (_: IOException) {
            VisionTransportResult.NetworkFailure
        } finally {
            connection.disconnect()
        }
    }

    private fun readAtMost(
        input: java.io.InputStream,
        maximumBytes: Int,
    ): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
