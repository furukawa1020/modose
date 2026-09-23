package com.modose.app.flow.baseline

import android.content.Context
import androidx.room.Room
import com.modose.app.data.local.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned storage: UI cancellation cannot cancel deletion work. */
internal class AndroidBaselineSnapshots private constructor(context: Context) : BaselineSnapshotBackend {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val database by lazy {
        Room.databaseBuilder(application, ModoseDatabase::class.java,
            File(application.noBackupFilesDir, "baseline-snapshots.db").absolutePath).build()
    }
    private val images by lazy { SceneImageFileStore(File(application.noBackupFilesDir, "baseline-images")) }
    private val markers by lazy { SceneDeletionTombstoneStore(File(application.noBackupFilesDir, "baseline-deletions")) }
    private val owned = mutableSetOf<String>()
    private var ready = false

    fun openSession() = BaselineSnapshotSession(this, scope)

    override suspend fun save(write: SceneSnapshotWrite): Boolean = mutex.withLock {
        if (!initialize()) return@withLock false
        val dao = database.sceneSnapshotDao()
        if (write.sceneId !in owned) {
            // Never take ownership of a snapshot left by another caller.
            if (dao.findAnyState(write.sceneId) != null) return@withLock false
            val marker = SceneDeletionTombstone(write.sceneId, write.sceneId + ".jpg")
            if (markers.record(marker) !is SceneTombstoneWriteResult.Recorded) return@withLock false
            owned.add(write.sceneId)
        }
        when (SceneSnapshotRepository(dao, images).save(write)) {
            is SceneSaveResult.Saved, is SceneSaveResult.AlreadySaved -> true
            else -> false
        }
    }

    override suspend fun delete(sceneId: String): Boolean = mutex.withLock {
        if (!initialize()) return@withLock false
        if (sceneId !in owned) return@withLock true
        // Keep the marker until both resources are gone, including an image without a DB row.
        database.sceneSnapshotDao().deleteScene(sceneId)
        if (images.deleteCommitted(sceneId + ".jpg") !is SceneImageDeleteResult.DeletedOrAbsent) {
            return@withLock false
        }
        if (markers.clear(sceneId) !is SceneTombstoneClearResult.ClearedOrAbsent) return@withLock false
        owned.remove(sceneId)
        true
    }

    private suspend fun initialize(): Boolean {
        if (ready) return true
        val result = SceneDeletionCoordinator(database.sceneSnapshotDao(), images, markers).resumePending()
        val report = (result as? SceneDeletionRecoveryResult.Completed)?.report ?: return false
        ready = report.problems.isEmpty() && report.malformedMarkerNames.isEmpty() &&
            report.completedCount == report.pendingCount
        return ready
    }

    companion object {
        @Volatile private var instance: AndroidBaselineSnapshots? = null

        fun get(context: Context): AndroidBaselineSnapshots = instance ?: synchronized(this) {
            instance ?: AndroidBaselineSnapshots(context).also { instance = it }
        }
    }
}
