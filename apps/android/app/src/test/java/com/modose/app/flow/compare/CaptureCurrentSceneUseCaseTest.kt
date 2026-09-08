package com.modose.app.flow.compare

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImagePlane
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.image.CpuImageRuntimeSkipReason
import com.modose.app.ar.image.VlmImageEncodingPlan
import com.modose.app.ar.image.VlmJpegEncodingResult
import com.modose.app.ar.image.VlmJpegImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCurrentSceneUseCaseTest {
    @Test
    fun duplicateSuccessfulRequestCapturesAndEncodesOnce() = runBlocking {
        val source = QueueImageSource(
            mutableListOf(CpuImageAcquisitionResult.Acquired(cpuImage())),
        )
        val encoder = CountingEncoder()
        val useCase = CaptureCurrentSceneUseCase(source, encoder)

        val first = useCase.execute(request())
        val second = useCase.execute(request())

        assertTrue(first is CaptureCurrentSceneResult.Captured)
        assertTrue(second is CaptureCurrentSceneResult.AlreadyCaptured)
        assertEquals(1, source.callCount)
        assertEquals(1, encoder.callCount)
    }

    @Test
    fun skippedCaptureIsNotCachedAndCanRetry() = runBlocking {
        val source = QueueImageSource(
            mutableListOf(
                CpuImageAcquisitionResult.Skipped(
                    CpuImageRuntimeSkipReason.NotAvailable,
                ),
                CpuImageAcquisitionResult.Acquired(cpuImage()),
            ),
        )
        val encoder = CountingEncoder()
        val useCase = CaptureCurrentSceneUseCase(source, encoder)

        val first = useCase.execute(request())
        val second = useCase.execute(request())

        assertEquals(
            CaptureCurrentSceneResult.Failed(
                CaptureCurrentSceneFailure.CaptureSkipped(
                    CpuImageRuntimeSkipReason.NotAvailable,
                ),
            ),
            first,
        )
        assertTrue(second is CaptureCurrentSceneResult.Captured)
        assertEquals(2, source.callCount)
        assertEquals(1, encoder.callCount)
    }

    @Test
    fun anotherKeyCannotReplaceCompletedCapture() = runBlocking {
        val source = QueueImageSource(
            mutableListOf(CpuImageAcquisitionResult.Acquired(cpuImage())),
        )
        val encoder = CountingEncoder()
        val useCase = CaptureCurrentSceneUseCase(source, encoder)

        useCase.execute(request())
        val conflict = useCase.execute(request().copy(captureKey = "capture-2"))

        assertEquals(
            CaptureCurrentSceneResult.Failed(
                CaptureCurrentSceneFailure.CaptureConflict,
            ),
            conflict,
        )
        assertEquals(1, source.callCount)
        assertEquals(1, encoder.callCount)
    }

    @Test
    fun invalidRequestNeverTouchesImageSource() = runBlocking {
        val source = QueueImageSource(mutableListOf())
        val useCase = CaptureCurrentSceneUseCase(source, CountingEncoder())

        val result = useCase.execute(request().copy(sceneId = ""))

        assertEquals(
            CaptureCurrentSceneResult.Failed(
                CaptureCurrentSceneFailure.InvalidRequest,
            ),
            result,
        )
        assertEquals(0, source.callCount)
    }

    private class QueueImageSource(
        private val results: MutableList<CpuImageAcquisitionResult>,
    ) : CurrentCpuImageSource {
        var callCount = 0

        override suspend fun capture(): CpuImageAcquisitionResult {
            callCount += 1
            return results.removeFirst()
        }
    }

    private class CountingEncoder : CurrentSceneJpegEncoder {
        var callCount = 0

        override fun encode(
            source: CpuCameraImage,
            plan: VlmImageEncodingPlan,
        ): VlmJpegEncodingResult {
            callCount += 1
            return VlmJpegEncodingResult.Encoded(
                VlmJpegImage(
                    bytes = byteArrayOf(1, 2, 3),
                    widthPx = plan.outputWidthPx,
                    heightPx = plan.outputHeightPx,
                ),
            )
        }
    }

    private companion object {
        fun request() = CaptureCurrentSceneRequest(
            sceneId = "scene-001",
            captureKey = "capture-1",
            rotationDegreesClockwise = 0,
        )

        fun cpuImage() = CpuCameraImage(
            widthPx = 2,
            heightPx = 2,
            timestampNanos = 10L,
            planes = listOf(
                CpuCameraImagePlane(byteArrayOf(1, 2, 3, 4), 2, 1),
                CpuCameraImagePlane(byteArrayOf(5), 1, 1),
                CpuCameraImagePlane(byteArrayOf(6), 1, 1),
            ),
        )
    }
}
