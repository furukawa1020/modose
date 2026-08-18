package com.modose.app.ar.coordinates

import kotlin.math.hypot

object ImageViewCoordinateBatch {
    fun imageToView(
        snapshot: ImageViewTransformSnapshot,
        points: List<ImagePixelPoint>,
    ): CoordinateTransformResult<List<ViewPixelPoint>> {
        if (points.any { point -> !point.isWithin(snapshot.imageWidthPx, snapshot.imageHeightPx) }) {
            return CoordinateTransformResult.Rejected(CoordinateTransformFailureReason.OutOfBounds)
        }
        val transformed = ArrayList<ViewPixelPoint>(points.size)
        for (point in points) {
            when (val result = snapshot.transform.imageToView(point)) {
                is CoordinateTransformResult.Transformed -> transformed += result.value
                is CoordinateTransformResult.Rejected -> return result
            }
        }
        return CoordinateTransformResult.Transformed(transformed)
    }

    fun viewToImage(
        snapshot: ImageViewTransformSnapshot,
        points: List<ViewPixelPoint>,
    ): CoordinateTransformResult<List<ImagePixelPoint>> {
        if (points.any { point -> !point.isWithin(snapshot.viewWidthPx, snapshot.viewHeightPx) }) {
            return CoordinateTransformResult.Rejected(CoordinateTransformFailureReason.OutOfBounds)
        }
        val transformed = ArrayList<ImagePixelPoint>(points.size)
        for (point in points) {
            when (val result = snapshot.transform.viewToImage(point)) {
                is CoordinateTransformResult.Transformed -> transformed += result.value
                is CoordinateTransformResult.Rejected -> return result
            }
        }
        return CoordinateTransformResult.Transformed(transformed)
    }

    fun maximumImageRoundTripErrorPx(
        snapshot: ImageViewTransformSnapshot,
        points: List<ImagePixelPoint>,
    ): CoordinateTransformResult<Float> {
        val viewPoints = when (val result = imageToView(snapshot, points)) {
            is CoordinateTransformResult.Transformed -> result.value
            is CoordinateTransformResult.Rejected -> return result
        }
        val returnedPoints = ArrayList<ImagePixelPoint>(viewPoints.size)
        for (point in viewPoints) {
            when (val result = snapshot.transform.viewToImage(point)) {
                is CoordinateTransformResult.Transformed -> returnedPoints += result.value
                is CoordinateTransformResult.Rejected -> return result
            }
        }
        val maximum = points.zip(returnedPoints).maxOfOrNull { (original, returned) ->
            hypot(returned.x - original.x, returned.y - original.y)
        } ?: 0f
        return CoordinateTransformResult.Transformed(maximum)
    }

    private fun ImagePixelPoint.isWithin(width: Int, height: Int): Boolean =
        x.isFinite() && y.isFinite() &&
            x >= 0f && y >= 0f && x <= width.toFloat() && y <= height.toFloat()

    private fun ViewPixelPoint.isWithin(width: Int, height: Int): Boolean =
        x.isFinite() && y.isFinite() &&
            x >= 0f && y >= 0f && x <= width.toFloat() && y <= height.toFloat()
}
