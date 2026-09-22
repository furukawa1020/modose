package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.UprightRotation
import com.modose.app.ar.image.VlmImageEncodingPlan
import com.modose.app.ar.image.VlmImageEncodingPlanner
import com.modose.app.ar.image.VlmImagePlanResult
import com.modose.app.network.baseline.NormalizedBoundingBox

/** Inverts the exact crop/rotation plan used for the baseline JPEG, not display coordinates. */
internal object BaselineCpuBoxMapper {
    fun map(
        image: CpuCameraImage,
        plan: VlmImageEncodingPlan,
        box: NormalizedBoundingBox,
    ): DetectedImageObject? {
        if (image.widthPx <= 0 || image.heightPx <= 0 ||
            image.widthPx.toLong() * image.heightPx > 1920L * 1080L
        ) return null
        val roi = plan.roi
        // NV21 compressToJpeg may align the crop to chroma boundaries.
        // Do not silently interpret its adjusted crop as the requested crop.
        if (listOf(roi.left, roi.top, roi.rightExclusive, roi.bottomExclusive).any { it % 2 != 0 }) return null
        val canonical = VlmImageEncodingPlanner.create(
            image.widthPx, image.heightPx, roi, plan.rotation.degreesClockwise,
        ) as? VlmImagePlanResult.Planned ?: return null
        if (canonical.plan != plan) return null
        if (box.xMin !in 0..1000 || box.xMax !in 0..1000 ||
            box.yMin !in 0..1000 || box.yMax !in 0..1000 ||
            box.xMin >= box.xMax || box.yMin >= box.yMax
        ) return null

        val bounds = when (plan.rotation) {
            UprightRotation.Degrees0 -> intArrayOf(box.xMin, box.yMin, box.xMax, box.yMax)
            UprightRotation.Degrees90 ->
                intArrayOf(box.yMin, 1000 - box.xMax, box.yMax, 1000 - box.xMin)
            UprightRotation.Degrees180 ->
                intArrayOf(1000 - box.xMax, 1000 - box.yMax, 1000 - box.xMin, 1000 - box.yMin)
            UprightRotation.Degrees270 ->
                intArrayOf(1000 - box.yMax, box.xMin, 1000 - box.yMin, box.xMax)
        }
        // Outward rounding retains the complete normalized rectangle.
        fun lower(value: Int, size: Int) = (value.toLong() * size / 1000L).toInt()
        fun upper(value: Int, size: Int) = ((value.toLong() * size + 999L) / 1000L).toInt()
        return DetectedImageObject(
            null,
            roi.left + lower(bounds[0], roi.width),
            roi.top + lower(bounds[1], roi.height),
            roi.left + upper(bounds[2], roi.width),
            roi.top + upper(bounds[3], roi.height),
        )
    }
}
