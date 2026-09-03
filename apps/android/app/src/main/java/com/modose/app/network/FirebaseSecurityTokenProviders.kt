package com.modose.app.network

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Supplies the current Firebase Authentication ID token to the Vision API client.
 *
 * Token refresh is delegated to the Firebase SDK. Calls must run off the Android main thread
 * because the existing Vision API client is a blocking transport boundary.
 */
class FirebaseIdTokenProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val timeoutMillis: Long = DEFAULT_TOKEN_TIMEOUT_MILLIS,
) : SecurityTokenProvider {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun getToken(): String? {
        val user = auth.currentUser ?: return null
        return TaskSecurityTokenProvider(
            request = { user.getIdToken(false) },
            tokenOf = { result -> result.token },
            timeoutMillis = timeoutMillis,
        ).getToken()
    }
}

/**
 * Supplies a Firebase App Check token for calls to the custom Vision API backend.
 */
class FirebaseAppCheckTokenProvider(
    private val appCheck: FirebaseAppCheck = FirebaseAppCheck.getInstance(),
    private val timeoutMillis: Long = DEFAULT_TOKEN_TIMEOUT_MILLIS,
) : SecurityTokenProvider {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun getToken(): String? = TaskSecurityTokenProvider(
        request = { appCheck.getAppCheckToken(false) },
        tokenOf = { result -> result.token },
        timeoutMillis = timeoutMillis,
    ).getToken()
}

internal class TaskSecurityTokenProvider<T>(
    private val request: () -> Task<T>,
    private val tokenOf: (T) -> String?,
    private val timeoutMillis: Long,
) : SecurityTokenProvider {
    override fun getToken(): String? {
        val result = try {
            Tasks.await(request(), timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return null
        } catch (_: ExecutionException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }

        return tokenOf(result)?.trim()?.takeIf(String::isNotEmpty)
    }
}

private const val DEFAULT_TOKEN_TIMEOUT_MILLIS = 2_000L
