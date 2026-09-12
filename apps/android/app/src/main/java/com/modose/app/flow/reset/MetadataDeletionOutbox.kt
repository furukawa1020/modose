package com.modose.app.flow.reset

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class MetadataDeletionJob(
    val sceneId: String,
    val resetKey: String,
    val attemptsUsed: Int,
)

enum class MetadataOutboxFailure {
    InvalidJob,
    AtomicMoveUnsupported,
    IoFailure,
}

sealed interface MetadataOutboxWriteResult {
    data object Stored : MetadataOutboxWriteResult
    data class Failed(val reason: MetadataOutboxFailure) :
        MetadataOutboxWriteResult
}

sealed interface MetadataOutboxListResult {
    data class Loaded(
        val jobs: List<MetadataDeletionJob>,
        val malformedMarkerNames: List<String>,
    ) : MetadataOutboxListResult
    data class Failed(val reason: MetadataOutboxFailure) :
        MetadataOutboxListResult
}

sealed interface MetadataOutboxClearResult {
    data object ClearedOrAbsent : MetadataOutboxClearResult
    data class Failed(val reason: MetadataOutboxFailure) :
        MetadataOutboxClearResult
}

class MetadataDeletionOutbox(
    private val rootDirectory: File,
) {
    fun store(job: MetadataDeletionJob): MetadataOutboxWriteResult {
        if (!isValid(job)) {
            return MetadataOutboxWriteResult.Failed(
                MetadataOutboxFailure.InvalidJob,
            )
        }

        val marker = markerFile(job.sceneId)
        val temporary = File(rootDirectory, marker.name + ".tmp")
        return try {
            ensureRoot()
            FileOutputStream(temporary).use { stream ->
                val body = job.resetKey + "\n" + job.attemptsUsed
                stream.write(body.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            MetadataOutboxWriteResult.Stored
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            MetadataOutboxWriteResult.Failed(
                MetadataOutboxFailure.AtomicMoveUnsupported,
            )
        } catch (_: IOException) {
            temporary.delete()
            MetadataOutboxWriteResult.Failed(
                MetadataOutboxFailure.IoFailure,
            )
        } catch (_: SecurityException) {
            temporary.delete()
            MetadataOutboxWriteResult.Failed(
                MetadataOutboxFailure.IoFailure,
            )
        }
    }

    fun list(): MetadataOutboxListResult {
        if (!rootDirectory.exists()) {
            return MetadataOutboxListResult.Loaded(emptyList(), emptyList())
        }
        val markers = rootDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(MARKER_SUFFIX)
        } ?: return MetadataOutboxListResult.Failed(
            MetadataOutboxFailure.IoFailure,
        )

        val jobs = mutableListOf<MetadataDeletionJob>()
        val malformed = mutableListOf<String>()
        markers.sortedBy(File::getName).forEach { marker ->
            val sceneId = marker.name.removeSuffix(MARKER_SUFFIX)
            val lines = try {
                marker.readLines(StandardCharsets.UTF_8)
            } catch (_: IOException) {
                malformed += marker.name
                return@forEach
            } catch (_: SecurityException) {
                malformed += marker.name
                return@forEach
            }
            val job = MetadataDeletionJob(
                sceneId = sceneId,
                resetKey = lines.getOrNull(0).orEmpty(),
                attemptsUsed = lines.getOrNull(1)?.toIntOrNull() ?: -1,
            )
            if (lines.size != 2 || !isValid(job)) {
                malformed += marker.name
            } else {
                jobs += job
            }
        }
        return MetadataOutboxListResult.Loaded(jobs, malformed)
    }

    fun clear(sceneId: String): MetadataOutboxClearResult {
        if (!SCENE_ID_PATTERN.matches(sceneId)) {
            return MetadataOutboxClearResult.Failed(
                MetadataOutboxFailure.InvalidJob,
            )
        }
        return try {
            val marker = markerFile(sceneId)
            if (!marker.exists() || marker.delete()) {
                MetadataOutboxClearResult.ClearedOrAbsent
            } else {
                MetadataOutboxClearResult.Failed(
                    MetadataOutboxFailure.IoFailure,
                )
            }
        } catch (_: SecurityException) {
            MetadataOutboxClearResult.Failed(
                MetadataOutboxFailure.IoFailure,
            )
        }
    }

    private fun isValid(job: MetadataDeletionJob): Boolean =
        SCENE_ID_PATTERN.matches(job.sceneId) &&
            job.attemptsUsed in 0..MAX_ATTEMPTS &&
            isCanonicalUuidV7(job.resetKey)

    private fun isCanonicalUuidV7(value: String): Boolean = try {
        val parsed = UUID.fromString(value)
        parsed.version() == 7 && parsed.toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun ensureRoot() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw IOException("Could not create metadata deletion outbox")
        }
        if (!rootDirectory.isDirectory) {
            throw IOException("Metadata deletion outbox is not a directory")
        }
    }

    private fun markerFile(sceneId: String) =
        File(rootDirectory, sceneId + MARKER_SUFFIX)

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val MARKER_SUFFIX = ".metadata-delete"
        private val SCENE_ID_PATTERN =
            Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
