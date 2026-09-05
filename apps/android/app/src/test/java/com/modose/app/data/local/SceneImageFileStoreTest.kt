package com.modose.app.data.local

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneImageFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun prepareWritesOnlyTemporaryFileThenCommitPublishesImage() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(root)
        val jpeg = byteArrayOf(1, 2, 3)
        val prepared = store.prepare(SCENE_ID, jpeg).prepared()

        assertFalse(root.resolve(prepared.fileName).exists())
        assertTrue(prepared.temporaryFile!!.exists())

        val committed = store.commit(prepared) as SceneImageCommitResult.Committed

        assertFalse(prepared.temporaryFile.exists())
        assertArrayEquals(jpeg, root.resolve(committed.fileName).readBytes())
        assertFalse(committed.reusedExisting)
    }

    @Test
    fun identicalExistingImageIsIdempotent() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(root)
        val first = store.prepare(SCENE_ID, byteArrayOf(4, 5, 6)).prepared()
        store.commit(first)

        val second = store.prepare(SCENE_ID, byteArrayOf(4, 5, 6)).prepared()
        val committed = store.commit(second) as SceneImageCommitResult.Committed

        assertTrue(second.alreadyCommitted)
        assertTrue(committed.reusedExisting)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun differentExistingImageIsNeverOverwritten() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(root)
        store.commit(store.prepare(SCENE_ID, byteArrayOf(1)).prepared())

        val result = store.prepare(SCENE_ID, byteArrayOf(2)) as
            SceneImagePrepareResult.Rejected

        assertEquals(SceneImageFailure.ExistingImageConflict, result.reason)
        assertArrayEquals(byteArrayOf(1), root.resolve("$SCENE_ID.jpg").readBytes())
    }

    @Test
    fun unsupportedAtomicMoveDeletesTemporaryFile() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(
            rootDirectory = root,
            mover = AtomicFileMover { source, target ->
                throw AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "unsupported",
                )
            },
        )
        val prepared = store.prepare(SCENE_ID, byteArrayOf(1, 2)).prepared()

        val result = store.commit(prepared) as SceneImageCommitResult.Failed

        assertEquals(SceneImageFailure.AtomicMoveUnsupported, result.reason)
        assertFalse(prepared.temporaryFile!!.exists())
        assertFalse(root.resolve(prepared.fileName).exists())
    }

    @Test
    fun ioFailureDoesNotPublishFinalImage() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(
            rootDirectory = root,
            mover = AtomicFileMover { _, _ -> throw IOException("disk failure") },
        )
        val prepared = store.prepare(SCENE_ID, byteArrayOf(9)).prepared()

        val result = store.commit(prepared) as SceneImageCommitResult.Failed

        assertEquals(SceneImageFailure.IoFailure, result.reason)
        assertFalse(root.resolve(prepared.fileName).exists())
    }

    @Test
    fun rejectsInvalidAndOversizedInputBeforeWriting() {
        val root = temporaryFolder.newFolder("images")
        val store = SceneImageFileStore(root)

        assertEquals(
            SceneImageFailure.InvalidSceneId,
            (store.prepare("../scene", byteArrayOf(1)) as SceneImagePrepareResult.Rejected).reason,
        )
        assertEquals(
            SceneImageFailure.EmptyImage,
            (store.prepare(SCENE_ID, ByteArray(0)) as SceneImagePrepareResult.Rejected).reason,
        )
        assertEquals(
            SceneImageFailure.ImageTooLarge,
            (store.prepare(SCENE_ID, ByteArray(2_000_001)) as
                SceneImagePrepareResult.Rejected).reason,
        )
        assertTrue(Files.list(root.toPath()).use { it.findAny().isEmpty })
    }

    private fun SceneImagePrepareResult.prepared(): PreparedSceneImage =
        (this as SceneImagePrepareResult.Prepared).image

    private companion object {
        const val SCENE_ID = "018f0f90-1234-7abc-8def-123456789abd"
    }
}
