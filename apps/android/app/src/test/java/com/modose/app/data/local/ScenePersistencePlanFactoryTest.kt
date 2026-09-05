package com.modose.app.data.local

import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.baseline.ObjectSymmetry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScenePersistencePlanFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsConfirmedObjectsToOrderedStagingEntities() {
        val write = validWrite()
        val prepared = prepare(write)

        val plan = ScenePersistencePlanFactory.create(write, prepared).plan()

        assertEquals(SceneStorageState.STAGING, plan.scene.storageState)
        assertEquals(null, plan.scene.committedAtEpochMillis)
        assertEquals(prepared.sha256, plan.scene.imageSha256)
        assertEquals(listOf(0, 1), plan.objects.map { it.displayOrder })
        assertEquals(listOf("detected", "manual"), plan.objects.map { it.source })
        assertEquals("[\"銀色\"]", plan.objects.first().appearanceFeaturesJson)
    }

    @Test
    fun sameContentProducesSameFingerprint() {
        val write = validWrite()
        val prepared = prepare(write)

        val first = ScenePersistencePlanFactory.create(write, prepared).plan()
        val second = ScenePersistencePlanFactory.create(write, prepared).plan()

        assertEquals(first.scene.contentFingerprint, second.scene.contentFingerprint)
    }

    @Test
    fun objectOrderChangesFingerprint() {
        val write = validWrite()
        val prepared = prepare(write)
        val reordered = write.copy(objects = write.objects.reversed())

        val first = ScenePersistencePlanFactory.create(write, prepared).plan()
        val second = ScenePersistencePlanFactory.create(reordered, prepared).plan()

        assertNotEquals(first.scene.contentFingerprint, second.scene.contentFingerprint)
    }

    @Test
    fun rejectsPreparedImageForDifferentBytes() {
        val write = validWrite()
        val prepared = prepare(write)
        val changed = write.copy(jpeg = byteArrayOf(9, 9, 9))

        assertEquals(
            ScenePlanRejection.ImageMismatch,
            (ScenePersistencePlanFactory.create(changed, prepared) as
                ScenePlanResult.Rejected).reason,
        )
    }

    @Test
    fun rejectsDuplicateObjectsAndEmptySelection() {
        val write = validWrite()
        val prepared = prepare(write)
        val duplicate = write.copy(objects = listOf(write.objects.first(), write.objects.first()))

        assertEquals(
            ScenePlanRejection.DuplicateObjectId,
            (ScenePersistencePlanFactory.create(duplicate, prepared) as
                ScenePlanResult.Rejected).reason,
        )
        assertEquals(
            ScenePlanRejection.InvalidObjectCount,
            (
                ScenePersistencePlanFactory.create(
                    write.copy(objects = emptyList()),
                    prepared,
                ) as ScenePlanResult.Rejected
            ).reason,
        )
    }

    private fun prepare(write: SceneSnapshotWrite): PreparedSceneImage =
        (
            SceneImageFileStore(temporaryFolder.newFolder())
                .prepare(write.sceneId, write.jpeg) as SceneImagePrepareResult.Prepared
        ).image

    private fun validWrite() = SceneSnapshotWrite(
        sceneId = SCENE_ID,
        createdAt = Instant.parse("2026-09-05T00:00:00Z"),
        modelId = "gemini-test",
        promptVersion = "baseline-v1",
        repaired = false,
        jpeg = byteArrayOf(1, 2, 3),
        objects = listOf(
            SceneObjectWrite(objectValue("object-1", "鍵"), SceneObjectOrigin.Detected),
            SceneObjectWrite(objectValue("object-2", "財布"), SceneObjectOrigin.Manual),
        ),
    )

    private fun objectValue(
        id: String,
        name: String,
    ) = BaselineObject(
        id = id,
        displayName = name,
        appearanceFeatures = listOf("銀色"),
        boundingBox = NormalizedBoundingBox(100, 200, 600, 800),
        orientationImportant = true,
        symmetry = ObjectSymmetry.Bilateral,
    )

    private fun ScenePlanResult.plan(): ScenePersistencePlan =
        (this as ScenePlanResult.Planned).plan

    private companion object {
        const val SCENE_ID = "018f0f90-1234-7abc-8def-123456789abd"
    }
}
