package com.modose.app.ui.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualBoxGestureMapperTest {
    private val transform = ImageFitTransform(
        contentRect = Rect(100f, 200f, 900f, 600f),
    )

    @Test
    fun mapsDragInEitherDirectionToNormalizedBox() {
        val forward = ManualBoxGestureMapper.map(
            transform,
            start = Offset(300f, 300f),
            end = Offset(700f, 500f),
        )
        val reverse = ManualBoxGestureMapper.map(
            transform,
            start = Offset(700f, 500f),
            end = Offset(300f, 300f),
        )

        val expected = com.modose.app.network.baseline.NormalizedBoundingBox(
            yMin = 250,
            xMin = 250,
            yMax = 750,
            xMax = 750,
        )
        assertEquals(expected, (forward as ManualBoxMappingResult.Mapped).boundingBox)
        assertEquals(expected, (reverse as ManualBoxMappingResult.Mapped).boundingBox)
    }

    @Test
    fun rejectsDragThatStartsOrEndsOutsideImage() {
        assertEquals(
            ManualBoxRejection.OutsideImage,
            (
                ManualBoxGestureMapper.map(
                    transform,
                    Offset(99f, 300f),
                    Offset(700f, 500f),
                ) as ManualBoxMappingResult.Rejected
            ).reason,
        )
        assertEquals(
            ManualBoxRejection.OutsideImage,
            (
                ManualBoxGestureMapper.map(
                    transform,
                    Offset(300f, 300f),
                    Offset(901f, 500f),
                ) as ManualBoxMappingResult.Rejected
            ).reason,
        )
    }

    @Test
    fun rejectsZeroAreaDragAfterNormalization() {
        val result = ManualBoxGestureMapper.map(
            transform,
            start = Offset(300f, 300f),
            end = Offset(300f, 500f),
        ) as ManualBoxMappingResult.Rejected

        assertEquals(ManualBoxRejection.EmptyBox, result.reason)
    }
}
