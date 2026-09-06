package com.modose.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneDeletionTombstoneStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recordListAndClearRoundTrip() {
        val root = temporaryFolder.newFolder("tombstones")
        val store = SceneDeletionTombstoneStore(root)
        val tombstone = SceneDeletionTombstone(
            sceneId = "scene-001",
            imageFileName = "scene-001.jpg",
        )

        assertEquals(SceneTombstoneWriteResult.Recorded, store.record(tombstone))

        val loaded = store.list()
        assertTrue(loaded is SceneTombstoneListResult.Loaded)
        loaded as SceneTombstoneListResult.Loaded
        assertEquals(listOf(tombstone), loaded.tombstones)
        assertTrue(loaded.malformedMarkerNames.isEmpty())

        assertEquals(
            SceneTombstoneClearResult.ClearedOrAbsent,
            store.clear(tombstone.sceneId),
        )
        assertEquals(
            SceneTombstoneClearResult.ClearedOrAbsent,
            store.clear(tombstone.sceneId),
        )

        val empty = store.list() as SceneTombstoneListResult.Loaded
        assertTrue(empty.tombstones.isEmpty())
    }

    @Test
    fun recordingSameSceneAgainReplacesImageName() {
        val store = SceneDeletionTombstoneStore(
            temporaryFolder.newFolder("replace"),
        )

        assertEquals(
            SceneTombstoneWriteResult.Recorded,
            store.record(SceneDeletionTombstone("scene-002", "old.jpg")),
        )
        assertEquals(
            SceneTombstoneWriteResult.Recorded,
            store.record(SceneDeletionTombstone("scene-002", "new.jpg")),
        )

        val loaded = store.list() as SceneTombstoneListResult.Loaded
        assertEquals(
            listOf(SceneDeletionTombstone("scene-002", "new.jpg")),
            loaded.tombstones,
        )
    }

    @Test
    fun malformedMarkerIsReportedAndPreserved() {
        val root = temporaryFolder.newFolder("malformed")
        val marker = root.resolve("scene-003.delete")
        marker.writeText("../outside.jpg")
        val store = SceneDeletionTombstoneStore(root)

        val loaded = store.list() as SceneTombstoneListResult.Loaded

        assertTrue(loaded.tombstones.isEmpty())
        assertEquals(listOf(marker.name), loaded.malformedMarkerNames)
        assertTrue(marker.exists())
    }

    @Test
    fun traversalAndInvalidSceneIdAreRejected() {
        val store = SceneDeletionTombstoneStore(
            temporaryFolder.newFolder("invalid"),
        )

        assertEquals(
            SceneTombstoneWriteResult.Failed(
                SceneTombstoneFailure.INVALID_SCENE_ID,
            ),
            store.record(SceneDeletionTombstone("../scene", "scene.jpg")),
        )
        assertEquals(
            SceneTombstoneWriteResult.Failed(
                SceneTombstoneFailure.INVALID_IMAGE_FILE_NAME,
            ),
            store.record(SceneDeletionTombstone("scene-004", "../scene.jpg")),
        )

        val loaded = store.list() as SceneTombstoneListResult.Loaded
        assertTrue(loaded.tombstones.isEmpty())
    }
}
