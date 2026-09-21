package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.core.NativeImageCapture

/** The detector worker owns callback execution and close, including during cancellation. */
internal interface CpuImageDetectionSink : ImageDetectionSink, AutoCloseable {
    fun accept(capture: NativeImageCapture, image: CpuCameraImage, result: ImageDetectionResult)

    override fun accept(capture: NativeImageCapture, result: ImageDetectionResult) {
        error("CPU image is required for this consumer")
    }
}
