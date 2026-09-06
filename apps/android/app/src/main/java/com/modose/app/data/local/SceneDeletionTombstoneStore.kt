package com.modose.app.data.local

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SceneDeletionTombstone(
    val sceneId: String,
    val imageFileName: String,
)

enum class SceneTombstoneFailure {
    INVALID_SCENE_ID,
    INVALID_IMAGE_FILE_NAME,
    ATOMIC_MOVE_UNSUPPORTED,
    IO_FAILURE,
}

sealed interface SceneTombstoneWriteResult {
    data object Recorded : SceneTombstoneWriteResult

    data class Failed(val reason: SceneTombstoneFailure) : SceneTombstoneWriteResult
}

sealed interface SceneTombstoneListResult {
    data class Loaded(
        val tombstones: List<SceneDeletionTombstone>,
        val malformedMarkerNames: List<String>,
    ) : SceneTombstoneListResult

    data class Failed(val reason: SceneTombstoneFailure) : SceneTombstoneListResult
}

sealed interface SceneTombstoneClearResult {
    data object ClearedOrAbsent : SceneTombstoneClearResult

    data class Failed(val reason: SceneTombstoneFailure) : SceneTombstoneClearResult
}

class SceneDeletionTombstoneStore(
    private val rootDirectory: File,
) {
    fun record(tombstone: SceneDeletionTombstone): SceneTombstoneWriteResult {
        if (!isValidSceneId(tombstone.sceneId)) {
            return SceneTombstoneWriteResult.Failed(SceneTombstoneFailure.INVALID_SCENE_ID)
        }
        if (!isValidImageFileName(tombstone.imageFileName)) {
            return SceneTombstoneWriteResult.Failed(
                SceneTombstoneFailure.INVALID_IMAGE_FILE_NAME,
            )
        }

        val marker = markerFile(tombstone.sceneId)
        val temporary = File(rootDirectory, marker.name + ".tmp")
        return try {
            ensureRoot()
            FileOutputStream(temporary).use { stream ->
                stream.write(tombstone.imageFileName.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            SceneTombstoneWriteResult.Recorded
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            SceneTombstoneWriteResult.Failed(
                SceneTombstoneFailure.ATOMIC_MOVE_UNSUPPORTED,
            )
        } catch (_: IOException) {
            temporary.delete()
            SceneTombstoneWriteResult.Failed(SceneTombstoneFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            temporary.delete()
            SceneTombstoneWriteResult.Failed(SceneTombstoneFailure.IO_FAILURE)
        }
    }

    fun list(): SceneTombstoneListResult {
        if (!rootDirectory.exists()) {
            return SceneTombstoneListResult.Loaded(emptyList(), emptyList())
        }

        val markers = rootDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(MARKER_SUFFIX)
        } ?: return SceneTombstoneListResult.Failed(SceneTombstoneFailure.IO_FAILURE)

        val tombstones = mutableListOf<SceneDeletionTombstone>()
        val malformed = mutableListOf<String>()
        for (marker in markers.sortedBy(File::getName)) {
            val sceneId = marker.name.removeSuffix(MARKER_SUFFIX)
            val imageFileName = try {
                marker.readText(StandardCharsets.UTF_8)
            } catch (_: IOException) {
                malformed += marker.name
                continue
            } catch (_: SecurityException) {
                malformed += marker.name
                continue
            }

            if (!isValidSceneId(sceneId) || !isValidImageFileName(imageFileName)) {
                malformed += marker.name
                continue
            }
            tombstones += SceneDeletionTombstone(sceneId, imageFileName)
        }
        return SceneTombstoneListResult.Loaded(tombstones, malformed)
    }

    fun clear(sceneId: String): SceneTombstoneClearResult {
        if (!isValidSceneId(sceneId)) {
            return SceneTombstoneClearResult.Failed(
                SceneTombstoneFailure.INVALID_SCENE_ID,
            )
        }

        val marker = markerFile(sceneId)
        return try {
            if (!marker.exists() || marker.delete()) {
                SceneTombstoneClearResult.ClearedOrAbsent
            } else {
                SceneTombstoneClearResult.Failed(SceneTombstoneFailure.IO_FAILURE)
            }
        } catch (_: SecurityException) {
            SceneTombstoneClearResult.Failed(SceneTombstoneFailure.IO_FAILURE)
        }
    }

    private fun ensureRoot() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IOException("Could not create tombstone directory")
        }
        if (!rootDirectory.isDirectory) {
            throw IOException("Tombstone root is not a directory")
        }
    }

    private fun markerFile(sceneId: String): File =
        File(rootDirectory, sceneId + MARKER_SUFFIX)

    private fun isValidSceneId(sceneId: String): Boolean =
        SCENE_ID_PATTERN.matches(sceneId)

    private fun isValidImageFileName(fileName: String): Boolean =
        fileName.length in 1..MAX_IMAGE_FILE_NAME_LENGTH &&
            fileName != "." &&
            fileName != ".." &&
            !fileName.contains('/') &&
            !fileName.contains('\\') &&
            !fileName.contains('\n') &&
            !fileName.contains('\r')

    private companion object {
        const val MARKER_SUFFIX = ".delete"
        const val MAX_IMAGE_FILE_NAME_LENGTH = 160
        val SCENE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
