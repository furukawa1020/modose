package com.modose.app.flow.baseline

import com.modose.app.data.local.SceneSnapshotWrite
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface BaselineSnapshotBackend {
    suspend fun save(write: SceneSnapshotWrite): Boolean
    suspend fun delete(sceneId: String): Boolean
}

internal enum class BaselineSnapshotSave { Saved, Rejected, CleanupPending }

/** One owner per capture; closing is immediate even if disk work is still in progress. */
internal class BaselineSnapshotSession(
    private val backend: BaselineSnapshotBackend,
    private val cleanupScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var sceneId: String? = null

    suspend fun save(write: SceneSnapshotWrite, isCurrent: () -> Boolean): BaselineSnapshotSave =
        withContext(NonCancellable + dispatcher) {
            mutex.withLock {
                if (closed.get() || !isCurrent()) return@withLock BaselineSnapshotSave.Rejected
                if (sceneId != null && sceneId != write.sceneId) {
                    return@withLock BaselineSnapshotSave.Rejected
                }
                sceneId = write.sceneId
                val saved = try {
                    backend.save(write)
                } catch (_: RuntimeException) {
                    false
                }
                if (saved && !closed.get() && isCurrent()) {
                    BaselineSnapshotSave.Saved
                } else if (cleanup()) {
                    BaselineSnapshotSave.Rejected
                } else {
                    BaselineSnapshotSave.CleanupPending
                }
            }
        }

    fun close(): Job {
        closed.set(true)
        return cleanupScope.launch {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock { cleanup() }
            }
        }
    }

    private suspend fun cleanup(): Boolean {
        val id = sceneId ?: return true
        val removed = try { backend.delete(id) } catch (_: RuntimeException) { false }
        if (removed) sceneId = null
        return removed
    }
}
