package com.modose.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

data class SceneSnapshotRecord(
    @Embedded
    val scene: SceneEntity,
    @Relation(
        parentColumn = "scene_id",
        entityColumn = "scene_id",
    )
    val objects: List<SceneObjectEntity>,
)

@Dao
interface SceneSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScene(scene: SceneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObjects(objects: List<SceneObjectEntity>)

    @Transaction
    suspend fun insertStagingSnapshot(
        scene: SceneEntity,
        objects: List<SceneObjectEntity>,
    ) {
        require(scene.storageState == SceneStorageState.STAGING)
        require(objects.size in 1..5)
        require(objects.all { it.sceneId == scene.sceneId })
        require(objects.map { it.objectId }.toSet().size == objects.size)
        insertScene(scene)
        insertObjects(objects)
    }

    @Transaction
    @Query(
        """
        SELECT * FROM scene_snapshots
        WHERE scene_id = :sceneId AND storage_state = 'COMMITTED'
        LIMIT 1
        """,
    )
    suspend fun findCommitted(sceneId: String): SceneSnapshotRecord?

    @Query(
        """
        SELECT * FROM scene_snapshots
        WHERE scene_id = :sceneId
        LIMIT 1
        """,
    )
    suspend fun findAnyState(sceneId: String): SceneEntity?

    @Query(
        """
        SELECT * FROM scene_snapshots
        WHERE storage_state = 'STAGING'
        ORDER BY created_at_epoch_millis ASC
        """,
    )
    suspend fun listStaging(): List<SceneEntity>

    @Query(
        """
        UPDATE scene_snapshots
        SET storage_state = 'COMMITTED',
            committed_at_epoch_millis = :committedAtEpochMillis
        WHERE scene_id = :sceneId AND storage_state = 'STAGING'
        """,
    )
    suspend fun markCommitted(
        sceneId: String,
        committedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM scene_snapshots WHERE scene_id = :sceneId")
    suspend fun deleteScene(sceneId: String): Int
}
