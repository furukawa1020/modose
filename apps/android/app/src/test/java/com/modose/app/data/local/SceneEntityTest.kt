package com.modose.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneEntityTest {
    @Test
    fun committedSceneRequiresCommitTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            validScene(
                storageState = SceneStorageState.COMMITTED,
                committedAtEpochMillis = null,
            )
        }
    }

    @Test
    fun stagingSceneCannotPretendToBeCommitted() {
        assertThrows(IllegalArgumentException::class.java) {
            validScene(
                storageState = SceneStorageState.STAGING,
                committedAtEpochMillis = 2L,
            )
        }
    }

    @Test
    fun fileNameCannotEscapeApplicationDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            validScene(imageFileName = "../baseline.jpg")
        }
    }

    @Test
    fun objectRequiresOrderedNormalizedBoundingBox() {
        assertThrows(IllegalArgumentException::class.java) {
            validObject(yMin = 500, yMax = 500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validObject(xMin = -1)
        }
    }

    @Test
    fun validEntitiesRetainStorageContract() {
        val scene = validScene()
        val objectValue = validObject()

        assertEquals(SceneStorageState.STAGING, scene.storageState)
        assertEquals(scene.sceneId, objectValue.sceneId)
        assertEquals(0, objectValue.displayOrder)
    }

    private fun validScene(
        storageState: String = SceneStorageState.STAGING,
        committedAtEpochMillis: Long? = null,
        imageFileName: String = "scene-1.jpg",
    ) = SceneEntity(
        sceneId = "018f0f90-1234-7abc-8def-123456789abd",
        schemaVersion = "1.0",
        createdAtEpochMillis = 1L,
        committedAtEpochMillis = committedAtEpochMillis,
        modelId = "gemini-test",
        promptVersion = "baseline-v1",
        vlmRepaired = false,
        imageFileName = imageFileName,
        imageSha256 = SHA,
        contentFingerprint = SHA,
        storageState = storageState,
    )

    private fun validObject(
        yMin: Int = 100,
        xMin: Int = 200,
        yMax: Int = 600,
        xMax: Int = 800,
    ) = SceneObjectEntity(
        sceneId = "018f0f90-1234-7abc-8def-123456789abd",
        objectId = "object-1",
        displayOrder = 0,
        displayName = "鍵",
        appearanceFeaturesJson = "[\"銀色\"]",
        yMin = yMin,
        xMin = xMin,
        yMax = yMax,
        xMax = xMax,
        orientationImportant = true,
        symmetry = "bilateral",
        source = "detected",
    )

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
