package com.modose.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SceneEntity::class,
        SceneObjectEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ModoseDatabase : RoomDatabase() {
    abstract fun sceneSnapshotDao(): SceneSnapshotDao

    companion object {
        const val FILE_NAME = "modose.db"
    }
}
