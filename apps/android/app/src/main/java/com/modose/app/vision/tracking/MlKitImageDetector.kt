package com.modose.app.vision.tracking

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.Nv21ConversionResult
import com.modose.app.ar.image.Yuv420ToNv21Converter
import com.modose.app.core.NativeImageCapture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One detector per recognition stream. Never reuse it across scene epochs.
 * Caller transfers ownership of the copied CPU planes until detection completes.
 * Raw sensor coordinates are preserved by using rotation zero, without resize/crop.
 */
internal class MlKitImageDetector private constructor(
    private val detector: ObjectDetector,
    private val sink: ImageDetectionSink,
) : AutoCloseable {
    private val flight = DetectionFlight()
    private val disposed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "modose-object-detection")
    }

    /** Suitable for the GL capture callback: no conversion or model work runs here. */
    fun submit(capture: NativeImageCapture, image: CpuCameraImage): Boolean {
        if (!flight.acquire()) return false
        try {
            worker.execute { detect(capture, image) }
        } catch (_: RejectedExecutionException) {
            flight.close()
            flight.finish {}
            dispose()
            return false
        }
        return true
    }

    private fun detect(capture: NativeImageCapture, image: CpuCameraImage) {
        if (image.timestampNanos != capture.imageTimestampNanos ||
            image.widthPx <= 0 || image.heightPx <= 0 ||
            image.widthPx.toLong() * image.heightPx > MAX_PIXELS
        ) {
            finish(capture, ImageDetectionResult.Failed(DetectionFailure.INVALID_IMAGE))
            return
        }
        val task = try {
            val converted = Yuv420ToNv21Converter.convert(image)
            if (converted !is Nv21ConversionResult.Converted) {
                finish(capture, ImageDetectionResult.Failed(DetectionFailure.CONVERSION))
                return
            }
            val nv21 = converted.image
            detector.process(InputImage.fromByteArray(
                nv21.bytes, nv21.widthPx, nv21.heightPx, 0, InputImage.IMAGE_FORMAT_NV21,
            ))
        } catch (_: RuntimeException) {
            finish(capture, ImageDetectionResult.Failed(DetectionFailure.DETECTOR))
            return
        }
        task.addOnCompleteListener(worker) { completed ->
            val result = if (completed.isSuccessful) {
                val objects = completed.result.map { detected ->
                    val box = detected.boundingBox
                    DetectedImageObject(detected.trackingId, box.left, box.top, box.right, box.bottom)
                }
                if (objects.size > 5 || objects.any {
                    it.left < 0 || it.top < 0 || it.right > image.widthPx ||
                        it.bottom > image.heightPx || it.left >= it.right || it.top >= it.bottom
                }) {
                    ImageDetectionResult.Failed(DetectionFailure.DETECTOR)
                } else {
                    ImageDetectionResult.Detected(objects)
                }
            } else {
                // Includes model download failures and cancelled tasks; never treat as an empty scene.
                ImageDetectionResult.Failed(DetectionFailure.DETECTOR)
            }
            finish(capture, result)
        }
    }

    private fun finish(capture: NativeImageCapture, result: ImageDetectionResult) {
        val release = try {
            flight.finish { sink.accept(capture, result) }
        } catch (_: RuntimeException) {
            // A broken consumer terminates this stream, without logging scene data.
            flight.close()
        }
        if (release) dispose()
    }

    override fun close() {
        if (flight.close()) dispose()
    }

    private fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    detector.close()
                } finally {
                    worker.shutdown()
                }
            }
        } catch (_: RejectedExecutionException) {
            try {
                detector.close()
            } finally {
                worker.shutdown()
            }
        }
    }

    companion object {
        private const val MAX_PIXELS = 1920L * 1080L

        fun create(sink: ImageDetectionSink): MlKitImageDetector =
            MlKitImageDetector(
                ObjectDetection.getClient(
                    ObjectDetectorOptions.Builder()
                        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                        .enableMultipleObjects()
                        .build(),
                ),
                sink,
            )
    }
}
