package com.modose.app.e2e

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eVideoEvidenceStoreTest {
    @Test
    fun attach_commitsMp4UsingCanonicalRunFileName() {
        val root = directory("e2e-video-root")
        val source = mp4("captured-video")

        val result = E2eVideoEvidenceStore(root).attach("run-001", source)

        assertTrue(result is E2eVideoResult.Attached)
        val evidence = (result as E2eVideoResult.Attached).evidence
        assertEquals("run-001.mp4", evidence.fileName)
        assertEquals(source.length(), evidence.sizeBytes)
        assertEquals(64, evidence.sha256.length)
        assertFalse(evidence.reusedExisting)
        assertTrue(File(root, evidence.fileName).isFile)
    }

    @Test
    fun attach_isIdempotentForSameVideo() {
        val root = directory("e2e-video-reuse")
        val source = mp4("same-video")
        val store = E2eVideoEvidenceStore(root)
        store.attach("run-001", source)

        val result = store.attach("run-001", source)

        assertTrue(result is E2eVideoResult.Attached)
        assertTrue(
            (result as E2eVideoResult.Attached).evidence.reusedExisting,
        )
    }

    @Test
    fun attach_rejectsConflictingVideoForRun() {
        val root = directory("e2e-video-conflict")
        val store = E2eVideoEvidenceStore(root)
        store.attach("run-001", mp4("first"))

        assertEquals(
            E2eVideoResult.Rejected(
                E2eVideoFailure.ExistingVideoConflict,
            ),
            store.attach("run-001", mp4("second")),
        )
    }

    @Test
    fun attach_rejectsUnsafeRunIdAndInvalidMp4() {
        val store = E2eVideoEvidenceStore(directory("e2e-video-invalid"))
        val plain = Files.createTempFile("not-video", ".mp4").toFile()
        plain.writeText("not an mp4")

        assertEquals(
            E2eVideoResult.Rejected(E2eVideoFailure.InvalidRunId),
            store.attach("../run", mp4("valid")),
        )
        assertEquals(
            E2eVideoResult.Rejected(E2eVideoFailure.InvalidMp4),
            store.attach("run-001", plain),
        )
    }

    @Test
    fun attach_rejectsOversizedVideoBeforeReadingPayload() {
        val oversized = mp4("oversized")
        RandomAccessFile(oversized, "rw").use {
            it.setLength(E2eVideoEvidenceStore.MAX_VIDEO_BYTES + 1)
        }

        assertEquals(
            E2eVideoResult.Rejected(E2eVideoFailure.VideoTooLarge),
            E2eVideoEvidenceStore(directory("e2e-video-large"))
                .attach("run-001", oversized),
        )
    }

    @Test
    fun attach_removesTemporaryFileWhenAtomicMoveUnsupported() {
        val root = directory("e2e-video-atomic")
        val store = E2eVideoEvidenceStore(
            root,
            E2eVideoAtomicMover { source, target ->
                throw AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "test",
                )
            },
        )

        val result = store.attach("run-001", mp4("atomic"))

        assertEquals(
            E2eVideoResult.Rejected(
                E2eVideoFailure.AtomicMoveUnsupported,
            ),
            result,
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun directory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun mp4(payload: String): File {
        val file = Files.createTempFile("e2e-video", ".mp4").toFile()
        val header = byteArrayOf(
            0, 0, 0, 24,
            'f'.code.toByte(),
            't'.code.toByte(),
            'y'.code.toByte(),
            'p'.code.toByte(),
            'i'.code.toByte(),
            's'.code.toByte(),
            'o'.code.toByte(),
            'm'.code.toByte(),
        )
        file.writeBytes(header + payload.encodeToByteArray())
        return file
    }
}
