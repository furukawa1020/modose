package com.modose.app.ui.intermission

import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.data.local.SceneSnapshotDao
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class LoadMoveObjectsIntermissionFailure {
    DatabaseUnavailable,
    SceneNotCommitted,
    InvalidObjects,
    ImageUnavailable,
    ImageTooLarge,
    ImageHashMismatch,
}

sealed interface LoadMoveObjectsIntermissionResult {
    data class Loaded(
        val state: MoveObjectsIntermissionState,
        val savedImageBytes: ByteArray,
    ) : LoadMoveObjectsIntermissionResult

    data class Failed(
        val reason: LoadMoveObjectsIntermissionFailure,
    ) : LoadMoveObjectsIntermissionResult
}

class LoadMoveObjectsIntermissionUseCase(
    private val dao: SceneSnapshotDao,
    private val imageRootDirectory: File,
) {
    suspend fun execute(
        sceneId: String,
        anchorState: SceneAnchorState?,
    ): LoadMoveObjectsIntermissionResult {
        val record = try {
            dao.findCommitted(sceneId)
        } catch (_: RuntimeException) {
            return failed(LoadMoveObjectsIntermissionFailure.DatabaseUnavailable)
        } ?: return failed(LoadMoveObjectsIntermissionFailure.SceneNotCommitted)

        val objects = try {
            record.objects
                .sortedBy { it.displayOrder }
                .map {
                    SavedObjectThumbnailModel(
                        objectId = it.objectId,
                        displayName = it.displayName,
                        imageFileName = record.scene.imageFileName,
                        yMin = it.yMin,
                        xMin = it.xMin,
                        yMax = it.yMax,
                        xMax = it.xMax,
                    )
                }
        } catch (_: IllegalArgumentException) {
            return failed(LoadMoveObjectsIntermissionFailure.InvalidObjects)
        }
        if (
            objects.size !in 1..MAX_OBJECT_COUNT ||
            objects.map { it.objectId }.distinct().size != objects.size
        ) {
            return failed(LoadMoveObjectsIntermissionFailure.InvalidObjects)
        }

        val image = readImage(record.scene.imageFileName)
            ?: return failed(LoadMoveObjectsIntermissionFailure.ImageUnavailable)
        if (image.size > MAX_IMAGE_BYTES) {
            return failed(LoadMoveObjectsIntermissionFailure.ImageTooLarge)
        }
        if (image.sha256() != record.scene.imageSha256) {
            return failed(LoadMoveObjectsIntermissionFailure.ImageHashMismatch)
        }

        return LoadMoveObjectsIntermissionResult.Loaded(
            state = MoveObjectsIntermissionState(
                sceneId = record.scene.sceneId,
                objects = objects,
                startAvailability = MoveObjectsIntermissionReducer.availability(
                    anchorState,
                ),
            ),
            savedImageBytes = image,
        )
    }

    private fun readImage(fileName: String): ByteArray? {
        if (
            fileName.isBlank() ||
            fileName.contains('/') ||
            fileName.contains('\\')
        ) {
            return null
        }
        return try {
            val root = imageRootDirectory.canonicalFile
            val image = File(root, fileName).canonicalFile
            if (
                image.parentFile != root ||
                !image.isFile ||
                image.length() > MAX_IMAGE_BYTES
            ) {
                return if (image.isFile && image.length() > MAX_IMAGE_BYTES) {
                    ByteArray(MAX_IMAGE_BYTES + 1)
                } else {
                    null
                }
            }
            image.readBytes()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun failed(
        reason: LoadMoveObjectsIntermissionFailure,
    ) = LoadMoveObjectsIntermissionResult.Failed(reason)

    private companion object {
        const val MAX_OBJECT_COUNT = 5
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
