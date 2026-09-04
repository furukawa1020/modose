package com.modose.app.data.local

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class PreparedSceneImage(
    val sceneId: String,
    val fileName: String,
    val sha256: String,
    val alreadyCommitted: Boolean,
    internal val temporaryFile: File?,
)

enum class SceneImageFailure {
    InvalidSceneId,
    EmptyImage,
    ImageTooLarge,
    ExistingImageConflict,
    AtomicMoveUnsupported,
    IoFailure,
    InvalidHandle,
}

sealed interface SceneImagePrepareResult {
    data class Prepared(val image: PreparedSceneImage) : SceneImagePrepareResult
    data class Rejected(val reason: SceneImageFailure) : SceneImagePrepareResult
}

sealed interface SceneImageCommitResult {
    data class Committed(
        val fileName: String,
        val sha256: String,
        val reusedExisting: Boolean,
    ) : SceneImageCommitResult
    data class Failed(val reason: SceneImageFailure) : SceneImageCommitResult
}

sealed interface SceneImageDeleteResult {
    data object DeletedOrAbsent : SceneImageDeleteResult
    data object Failed : SceneImageDeleteResult
}

fun interface AtomicFileMover {
    @Throws(IOException::class)
    fun move(source: Path, target: Path)
}

class SceneImageFileStore(
    rootDirectory: File,
    private val mover: AtomicFileMover = AtomicFileMover { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    private val rootPath = rootDirectory.toPath().toAbsolutePath().normalize()

    fun prepare(
        sceneId: String,
        jpeg: ByteArray,
    ): SceneImagePrepareResult {
        if (!sceneId.isCanonicalUuid()) {
            return rejected(SceneImageFailure.InvalidSceneId)
        }
        if (jpeg.isEmpty()) {
            return rejected(SceneImageFailure.EmptyImage)
        }
        if (jpeg.size > MAX_IMAGE_BYTES) {
            return rejected(SceneImageFailure.ImageTooLarge)
        }

        val fileName = "$sceneId.jpg"
        val finalPath = childPath(fileName) ?: return rejected(SceneImageFailure.InvalidHandle)
        val sha256 = jpeg.sha256()
        try {
            Files.createDirectories(rootPath)
            if (Files.exists(finalPath)) {
                val existingHash = Files.readAllBytes(finalPath).sha256()
                return if (existingHash == sha256) {
                    SceneImagePrepareResult.Prepared(
                        PreparedSceneImage(
                            sceneId = sceneId,
                            fileName = fileName,
                            sha256 = sha256,
                            alreadyCommitted = true,
                            temporaryFile = null,
                        ),
                    )
                } else {
                    rejected(SceneImageFailure.ExistingImageConflict)
                }
            }

            val temporaryPath = childPath(".$sceneId-${UUID.randomUUID()}.tmp")
                ?: return rejected(SceneImageFailure.InvalidHandle)
            try {
                FileOutputStream(temporaryPath.toFile()).use { output ->
                    output.write(jpeg)
                    output.flush()
                    output.fd.sync()
                }
            } catch (_: IOException) {
                Files.deleteIfExists(temporaryPath)
                return rejected(SceneImageFailure.IoFailure)
            }
            return SceneImagePrepareResult.Prepared(
                PreparedSceneImage(
                    sceneId = sceneId,
                    fileName = fileName,
                    sha256 = sha256,
                    alreadyCommitted = false,
                    temporaryFile = temporaryPath.toFile(),
                ),
            )
        } catch (_: IOException) {
            return rejected(SceneImageFailure.IoFailure)
        } catch (_: SecurityException) {
            return rejected(SceneImageFailure.IoFailure)
        }
    }

    fun commit(prepared: PreparedSceneImage): SceneImageCommitResult {
        val finalPath = childPath(prepared.fileName)
            ?: return failed(SceneImageFailure.InvalidHandle)
        if (prepared.fileName != "${prepared.sceneId}.jpg" ||
            !prepared.sceneId.isCanonicalUuid()
        ) {
            return failed(SceneImageFailure.InvalidHandle)
        }
        if (prepared.alreadyCommitted) {
            return if (existingHashMatches(finalPath, prepared.sha256)) {
                committed(prepared, reusedExisting = true)
            } else {
                failed(SceneImageFailure.ExistingImageConflict)
            }
        }

        val temporaryPath = prepared.temporaryFile?.toPath()?.toAbsolutePath()?.normalize()
            ?: return failed(SceneImageFailure.InvalidHandle)
        if (temporaryPath.parent != rootPath ||
            !temporaryPath.fileName.toString().endsWith(".tmp")
        ) {
            return failed(SceneImageFailure.InvalidHandle)
        }

        return try {
            mover.move(temporaryPath, finalPath)
            committed(prepared, reusedExisting = false)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.deleteIfExists(temporaryPath)
            failed(SceneImageFailure.AtomicMoveUnsupported)
        } catch (_: IOException) {
            val raceResult = if (existingHashMatches(finalPath, prepared.sha256)) {
                committed(prepared, reusedExisting = true)
            } else {
                failed(
                    if (Files.exists(finalPath)) {
                        SceneImageFailure.ExistingImageConflict
                    } else {
                        SceneImageFailure.IoFailure
                    },
                )
            }
            Files.deleteIfExists(temporaryPath)
            raceResult
        } catch (_: SecurityException) {
            failed(SceneImageFailure.IoFailure)
        }
    }

    fun abort(prepared: PreparedSceneImage): SceneImageDeleteResult {
        val path = prepared.temporaryFile?.toPath()?.toAbsolutePath()?.normalize()
            ?: return SceneImageDeleteResult.DeletedOrAbsent
        if (path.parent != rootPath) {
            return SceneImageDeleteResult.Failed
        }
        return delete(path)
    }

    fun deleteCommitted(fileName: String): SceneImageDeleteResult {
        val path = childPath(fileName) ?: return SceneImageDeleteResult.Failed
        return delete(path)
    }

    private fun delete(path: Path): SceneImageDeleteResult = try {
        Files.deleteIfExists(path)
        SceneImageDeleteResult.DeletedOrAbsent
    } catch (_: IOException) {
        SceneImageDeleteResult.Failed
    } catch (_: SecurityException) {
        SceneImageDeleteResult.Failed
    }

    private fun childPath(fileName: String): Path? {
        if (fileName.isBlank() || '/' in fileName || '\\' in fileName) return null
        val candidate = rootPath.resolve(fileName).normalize()
        return candidate.takeIf { it.parent == rootPath }
    }

    private fun existingHashMatches(
        path: Path,
        expected: String,
    ): Boolean = try {
        Files.isRegularFile(path) && Files.readAllBytes(path).sha256() == expected
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun String.isCanonicalUuid(): Boolean = try {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { "%02x".format(it) }

    private fun rejected(reason: SceneImageFailure) =
        SceneImagePrepareResult.Rejected(reason)

    private fun failed(reason: SceneImageFailure) =
        SceneImageCommitResult.Failed(reason)

    private fun committed(
        prepared: PreparedSceneImage,
        reusedExisting: Boolean,
    ) = SceneImageCommitResult.Committed(
        fileName = prepared.fileName,
        sha256 = prepared.sha256,
        reusedExisting = reusedExisting,
    )

    private companion object {
        const val MAX_IMAGE_BYTES = 2_000_000
    }
}
