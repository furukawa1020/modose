package com.modose.app.network.compare

import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.baseline.ObjectSymmetry

internal object ConfirmedObjectsFixture {
    fun json(vararg ids: String = arrayOf("cup")): String = requireNotNull(
        ConfirmedObjectsEncoder.encode(
            ids.map { id ->
                BaselineObject(
                    id = id,
                    displayName = "Cup",
                    appearanceFeatures = listOf("white"),
                    boundingBox = NormalizedBoundingBox(100, 200, 500, 600),
                    orientationImportant = false,
                    symmetry = ObjectSymmetry.Rotational,
                )
            },
        ),
    )
}
