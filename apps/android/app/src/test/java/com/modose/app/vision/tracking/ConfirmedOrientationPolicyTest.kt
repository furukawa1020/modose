package com.modose.app.vision.tracking

import com.modose.app.network.baseline.*
import org.junit.Assert.*
import org.junit.Test

class ConfirmedOrientationPolicyTest {
    private fun item(id: String, important: Boolean, symmetry: ObjectSymmetry = ObjectSymmetry.None) =
        BaselineObject(id, id, listOf("feature"), NormalizedBoundingBox(0, 0, 100, 100), important, symmetry)

    @Test
    fun onlyExplicitlyUnimportantObjectsSatisfyTheRequirement() {
        val policy = ConfirmedOrientationPolicy.create("scene",
            listOf(item("cup", false), item("card", true)), mapOf("cup" to 7, "card" to 8))
        assertTrue(policy.satisfiedWithoutMeasurement("scene", 7))
        assertFalse(policy.satisfiedWithoutMeasurement("scene", 8))
        assertFalse(policy.satisfiedWithoutMeasurement("scene", 9))
        assertFalse(policy.satisfiedWithoutMeasurement("other", 7))
    }

    @Test
    fun symmetryNeverOverridesAnImportantOrientation() {
        for (symmetry in ObjectSymmetry.entries) {
            val policy = ConfirmedOrientationPolicy.create("scene",
                listOf(item("object", true, symmetry)), mapOf("object" to 1))
            assertFalse(policy.satisfiedWithoutMeasurement("scene", 1))
        }
    }

    @Test
    fun ownsACopyOfRequirementsAndExternalIdMapping() {
        val objects = mutableListOf(item("cup", false))
        val ids = mutableMapOf("cup" to 7)
        val policy = ConfirmedOrientationPolicy.create("scene", objects, ids)
        objects[0] = item("cup", true)
        ids["cup"] = 8
        objects.clear()
        ids.clear()
        assertTrue(policy.satisfiedWithoutMeasurement("scene", 7))
        assertFalse(policy.satisfiedWithoutMeasurement("scene", 8))
    }

    @Test
    fun objectOrderCannotChangeStableIds() {
        val policy = ConfirmedOrientationPolicy.create("scene",
            listOf(item("card", true), item("cup", false)), linkedMapOf("cup" to 7, "card" to 8))
        assertTrue(policy.satisfiedWithoutMeasurement("scene", 7))
        assertFalse(policy.satisfiedWithoutMeasurement("scene", 8))
    }

    @Test
    fun missingExtraDuplicateAndNonpositiveIdsAreRejected() {
        val objects = listOf(item("cup", false), item("card", true))
        for (ids in listOf(mapOf("cup" to 7), mapOf("cup" to 7, "card" to 8, "extra" to 9),
            mapOf("cup" to 7, "card" to 7), mapOf("cup" to 0, "card" to 8))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConfirmedOrientationPolicy.create("scene", objects, ids)
            }
        }
    }

    @Test
    fun invalidSceneAndObjectSetsAreRejected() {
        for (scene in listOf("", " ", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConfirmedOrientationPolicy.create(scene, listOf(item("cup", false)), mapOf("cup" to 1))
            }
        }
        for (objects in listOf(emptyList(), listOf(item("cup", true), item("cup", false)),
            List(6) { item("item-$it", false) })) {
            assertThrows(IllegalArgumentException::class.java) {
                ConfirmedOrientationPolicy.create("scene", objects,
                    objects.mapIndexed { i, o -> o.id to i + 1 }.toMap())
            }
        }
    }
}
