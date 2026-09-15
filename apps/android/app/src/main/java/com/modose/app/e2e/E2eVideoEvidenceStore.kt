package com.modose.app.e2e

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class E2eVideoEvidence(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val reusedExisting: Boolean,
)

enum class E2eVideoFailure {
    InvalidRunId,
    SourceMissing,
    EmptyVideo,
    VideoTooLarge,
    InvalidMp4,
    ExistingVideoConflict,
    AtomicMoveUnsupported,
    IoFailure,
}

sealed interface E2eVideoResult {
    data class Attached(val evidence: E2eVideoEvidence) : E2eVideoResult
    data class Rejected(val reason: E2eVideoFailure) : E2eVideoResult
}

fun interface E2eVideoAtomicMover {
    @Throws(IOException::class)
    fun move(source: Path, target: Path)
}

class E2eVideoEvidenceStore(
    rootDirectory: File,
    private val mover: E2eVideoAtomicMover = E2eVideoAtomicMover { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    private val rootPath = rootDirectory.toPath().toAbsolutePath().normalize()

    fun attach(
        runId: String,
        source: File,
    ): E2eVideoResult {
        if (!SAFE_ID.matches(runId)) {
            return rejected(E2eVideoFailure.InvalidRunId)
        }
        if (!source.isFile) {
            return rejected(E2eVideoFailure.SourceMissing)
        }
        val size = source.length()
        if (size == 0L) {
            return rejected(E2eVideoFailure.EmptyVideo)
        }
        if (size > MAX_VIDEO_BYTES) {
            return rejected(E2eVideoFailure.VideoTooLarge)
        }
        if (!source.hasMp4FileTypeBox()) {
            return rejected(E2eVideoFailure.InvalidMp4)
        }

        val fileName = "$runId.mp4"
        val target = rootPath.resolve(fileName).normalize()
        return try {
            Files.createDirectories(rootPath)
            val sourceHash = source.sha256()
            if (Files.exists(target)) {
                return if (
                    Files.isRegularFile(target) &&
                    target.toFile().sha256() == sourceHash
                ) {
                    attached(fileName, size, sourceHash, reused = true)
                } else {
                    rejected(E2eVideoFailure.ExistingVideoConflict)
                }
            }

            val temporary = rootPath.resolve(
                ".$runId-${UUID.randomUUID()}.tmp",
            )
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(temporary.toFile()).use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                mover.move(temporary, target)
                attached(fileName, size, sourceHash, reused = false)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.deleteIfExists(temporary)
                rejected(E2eVideoFailure.AtomicMoveUnsupported)
            } catch (_: IOException) {
                Files.deleteIfExists(temporary)
                rejected(E2eVideoFailure.IoFailure)
            }
        } catch (_: IOException) {
            rejected(E2eVideoFailure.IoFailure)
        } catch (_: SecurityException) {
            rejected(E2eVideoFailure.IoFailure)
        }
    }

    private fun File.hasMp4FileTypeBox(): Boolean =
        try {
            FileInputStream(this).use { input ->
                val header = ByteArray(MP4_HEADER_BYTES)
                input.read(header) == header.size &&
                    header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun attached(
        fileName: String,
        size: Long,
        sha256: String,
        reused: Boolean,
    ) = E2eVideoResult.Attached(
        E2eVideoEvidence(fileName, size, sha256, reused),
    )

    private fun rejected(reason: E2eVideoFailure) =
        E2eVideoResult.Rejected(reason)

    companion object {
        const val MAX_VIDEO_BYTES = 500_000_000L
        private const val MP4_HEADER_BYTES = 12
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
