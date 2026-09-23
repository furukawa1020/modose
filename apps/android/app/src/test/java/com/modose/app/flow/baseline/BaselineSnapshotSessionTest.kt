package com.modose.app.flow.baseline

import com.modose.app.data.local.SceneSnapshotWrite
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BaselineSnapshotSessionTest {
    private class Backend : BaselineSnapshotBackend {
        val events = mutableListOf<String>()
        var saveResult = true
        var deleteResult = true
        var duringSave: suspend () -> Unit = {}
        override suspend fun save(write: SceneSnapshotWrite): Boolean {
            events.add("save")
            duringSave()
            return saveResult
        }
        override suspend fun delete(sceneId: String): Boolean {
            events.add("delete")
            return deleteResult
        }
    }

    private fun write(id: String = "scene") = SceneSnapshotWrite(
        id, Instant.ofEpochMilli(1000), "model", "baseline-v1", false,
        byteArrayOf(1), emptyList())

    private fun session(backend: Backend, scope: CoroutineScope) =
        BaselineSnapshotSession(backend, scope, Dispatchers.Unconfined)

    @Test fun savesCurrentCaptureThenDeletesOnClose(): Unit = runBlocking {
        val backend = Backend()
        val owner = session(backend, this)
        assertEquals(BaselineSnapshotSave.Saved, owner.save(write()) { true })
        assertEquals(listOf("save"), backend.events)
        owner.close().join()
        assertEquals(listOf("save", "delete"), backend.events)
    }

    @Test fun invalidCaptureNeverWrites(): Unit = runBlocking {
        val backend = Backend()
        assertEquals(BaselineSnapshotSave.Rejected, session(backend, this).save(write()) { false })
        assertTrue(backend.events.isEmpty())
    }

    @Test fun closedSessionNeverWrites(): Unit = runBlocking {
        val backend = Backend()
        val owner = session(backend, this)
        owner.close().join()
        assertEquals(BaselineSnapshotSave.Rejected, owner.save(write()) { true })
        assertTrue(backend.events.isEmpty())
    }

    @Test fun anchorLossDuringSaveDeletesInsteadOfReportingSuccess(): Unit = runBlocking {
        val backend = Backend()
        var current = true
        backend.duringSave = { current = false }
        assertEquals(BaselineSnapshotSave.Rejected, session(backend, this).save(write()) { current })
        assertEquals(listOf("save", "delete"), backend.events)
    }

    @Test fun failedSaveIsCompensated(): Unit = runBlocking {
        val backend = Backend().apply { saveResult = false }
        assertEquals(BaselineSnapshotSave.Rejected, session(backend, this).save(write()) { true })
        assertEquals(listOf("save", "delete"), backend.events)
    }

    @Test fun thrownSaveIsCompensated(): Unit = runBlocking {
        val backend = Backend().apply { duringSave = { error("disk unavailable") } }
        assertEquals(BaselineSnapshotSave.Rejected, session(backend, this).save(write()) { true })
        assertEquals(listOf("save", "delete"), backend.events)
    }

    @Test fun failedCleanupRemainsRetryable(): Unit = runBlocking {
        val backend = Backend().apply { saveResult = false; deleteResult = false }
        val owner = session(backend, this)
        assertEquals(BaselineSnapshotSave.CleanupPending, owner.save(write()) { true })
        backend.deleteResult = true
        owner.close().join()
        assertEquals(listOf("save", "delete", "delete"), backend.events)
    }

    @Test fun differentSceneCannotReplaceOwnedSnapshot(): Unit = runBlocking {
        val backend = Backend()
        val owner = session(backend, this)
        owner.save(write()) { true }
        assertEquals(BaselineSnapshotSave.Rejected, owner.save(write("other")) { true })
        owner.close().join()
        assertEquals(listOf("save", "delete"), backend.events)
    }

    @Test fun closeDuringDiskWriteRejectsDelayedSuccess(): Unit = runBlocking {
        val backend = Backend()
        val owner = session(backend, this)
        var closing: Job? = null
        backend.duringSave = { closing = owner.close() }
        assertEquals(BaselineSnapshotSave.Rejected, owner.save(write()) { true })
        requireNotNull(closing).join()
        assertEquals(listOf("save", "delete"), backend.events)
    }
}
