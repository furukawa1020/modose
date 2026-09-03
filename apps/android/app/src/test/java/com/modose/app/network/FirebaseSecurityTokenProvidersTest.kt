package com.modose.app.network

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseSecurityTokenProvidersTest {
    @Test
    fun completedFirebaseTaskReturnsToken() {
        val provider = TaskSecurityTokenProvider(
            request = { Tasks.forResult(TokenResult(" token-123 ")) },
            tokenOf = TokenResult::token,
            timeoutMillis = 100,
        )

        assertEquals("token-123", provider.getToken())
    }

    @Test
    fun blankTokenIsRejected() {
        val provider = TaskSecurityTokenProvider(
            request = { Tasks.forResult(TokenResult("   ")) },
            tokenOf = TokenResult::token,
            timeoutMillis = 100,
        )

        assertNull(provider.getToken())
    }

    @Test
    fun failedFirebaseTaskIsReportedAsUnavailable() {
        val provider = TaskSecurityTokenProvider(
            request = { Tasks.forException<TokenResult>(IllegalStateException("unavailable")) },
            tokenOf = TokenResult::token,
            timeoutMillis = 100,
        )

        assertNull(provider.getToken())
    }

    @Test
    fun incompleteFirebaseTaskStopsAtTokenDeadline() {
        val incompleteTask = TaskCompletionSource<TokenResult>().task
        val provider = TaskSecurityTokenProvider(
            request = { incompleteTask },
            tokenOf = TokenResult::token,
            timeoutMillis = 1,
        )

        assertNull(provider.getToken())
    }

    private data class TokenResult(
        val token: String?,
    )
}
