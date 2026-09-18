package com.modose.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreFrameEncoderTest {
    private val detection = CoreDetection(
        0x01020304, CoreVector(1.0, 2.0, 3.0), CoreVector(0.5, -1.0, -0.25), true,
    )
    private val pair = CorePairEvidence(17, detection.currentId, 0.75, 0.5, 1.0, false)

    @Test
    fun encodesExactlyTheSharedRustGoldenPacket() {
        val result = CoreFrameEncoder.encode(true, listOf(detection), listOf(pair))
            as FrameEncoding.Encoded
        assertEquals(98, result.bytes.size)
        assertArrayEquals(golden("frame-v1.hex"), result.bytes)
    }

    @Test
    fun trackingLossUsesSharedEmptyPacketAndFreshStorage() {
        val expected = golden("tracking-lost-v1.hex")
        assertArrayEquals(expected, CoreFrameEncoder.trackingLost())
        val first = CoreFrameEncoder.trackingLost()
        first.fill(0)
        assertArrayEquals(expected, CoreFrameEncoder.trackingLost())
        val encoded = CoreFrameEncoder.encode(false, emptyList(), emptyList())
            as FrameEncoding.Encoded
        assertArrayEquals(expected, encoded.bytes)
    }

    @Test
    fun acceptsMaximumFrameWithAllTwentyFivePairs() {
        val objects = (1..5).map { detection.copy(currentId = it) }
        val pairs = (1..5).flatMap { saved ->
            (1..5).map { current -> pair.copy(savedId = saved, currentId = current) }
        }
        val encoded = CoreFrameEncoder.encode(true, objects, pairs) as FrameEncoding.Encoded
        assertEquals(1102, encoded.bytes.size)
        assertEquals(5, encoded.bytes[6].toInt())
        assertEquals(25, encoded.bytes[7].toInt())
    }

    @Test
    fun rejectsOversizedListsBeforeProcessingRecords() {
        reject(FrameEncodingError.TOO_MANY_OBJECTS, List(6) { detection }, emptyList())
        reject(FrameEncodingError.TOO_MANY_PAIRS, listOf(detection), List(26) { pair })
    }

    @Test
    fun rejectsInvalidAndDuplicateObjectIds() {
        for (id in listOf(0, -1, Int.MIN_VALUE)) {
            reject(FrameEncodingError.INVALID_ID, listOf(detection.copy(currentId = id)), emptyList())
            reject(FrameEncodingError.INVALID_ID, listOf(detection), listOf(pair.copy(savedId = id)))
            reject(FrameEncodingError.INVALID_ID, listOf(detection), listOf(pair.copy(currentId = id)))
        }
        reject(FrameEncodingError.DUPLICATE_ID, listOf(detection, detection), emptyList())
        reject(FrameEncodingError.DUPLICATE_PAIR, listOf(detection), listOf(pair, pair))
        reject(FrameEncodingError.UNKNOWN_CURRENT_ID, listOf(detection), listOf(pair.copy(currentId = 99)))
    }

    @Test
    fun rejectsEveryNonFiniteRayComponentAndZeroDirection() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (vector in listOf(CoreVector(bad, 1.0, 1.0), CoreVector(1.0, bad, 1.0), CoreVector(1.0, 1.0, bad))) {
                reject(FrameEncodingError.INVALID_RAY, listOf(detection.copy(origin = vector)), emptyList())
                reject(FrameEncodingError.INVALID_RAY, listOf(detection.copy(direction = vector)), emptyList())
            }
        }
        reject(
            FrameEncodingError.INVALID_RAY,
            listOf(detection.copy(direction = CoreVector(0.0, -0.0, 0.0))),
            emptyList(),
        )
    }

    @Test
    fun rejectsInvalidScoresInEveryEvidenceField() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01)) {
            for (invalid in listOf(
                pair.copy(semanticScore = bad), pair.copy(embeddingScore = bad), pair.copy(signatureScore = bad),
            )) reject(FrameEncodingError.INVALID_SCORE, listOf(detection), listOf(invalid))
        }
    }

    @Test
    fun acceptsScoreEndpointsAndLargestPositiveId() {
        val result = CoreFrameEncoder.encode(
            true,
            listOf(detection.copy(currentId = Int.MAX_VALUE)),
            listOf(pair.copy(savedId = Int.MAX_VALUE, currentId = Int.MAX_VALUE, semanticScore = 0.0)),
        ) as FrameEncoding.Encoded
        assertEquals(98, result.bytes.size)
    }

    @Test
    fun dispatchesOwnerAndClockWithoutModification() {
        var calls = 0
        val dispatcher = CoreFrameDispatcher(CoreFrameTransport<String> { handle, time, bytes ->
            calls++
            assertEquals(Long.MAX_VALUE, handle)
            assertEquals(800L, time)
            assertArrayEquals(golden("frame-v1.hex"), bytes)
            "guiding"
        })
        assertEquals(
            FrameSubmission.Applied("guiding"),
            dispatcher.submit(Long.MAX_VALUE, 800, true, listOf(detection), listOf(pair)),
        )
        assertEquals(1, calls)
    }

    @Test
    fun invalidFrameDispatchesOnlyTrackingLossToSameOwner() {
        var calls = 0
        val dispatcher = CoreFrameDispatcher(CoreFrameTransport<String> { handle, time, bytes ->
            calls++
            assertEquals(7L, handle)
            assertEquals(900L, time)
            assertArrayEquals(golden("tracking-lost-v1.hex"), bytes)
            "tracking-lost"
        })
        val result = dispatcher.submit(7, 900, true, listOf(detection), listOf(pair.copy(semanticScore = Double.NaN)))
        assertEquals(FrameSubmission.Invalidated(FrameEncodingError.INVALID_SCORE, "tracking-lost"), result)
        assertEquals(1, calls)
    }

    @Test
    fun transportFailureEscapesBothNormalAndInvalidationPaths() {
        val failure = IllegalStateException("native session unavailable")
        val dispatcher = CoreFrameDispatcher(CoreFrameTransport<String> { _, _, _ -> throw failure })
        for (input in listOf(pair, pair.copy(semanticScore = Double.NaN))) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                dispatcher.submit(1, 0, true, listOf(detection), listOf(input))
            })
        }
    }

    @Test
    fun invalidOwnerOrClockNeverCallsTransport() {
        var calls = 0
        val dispatcher = CoreFrameDispatcher(CoreFrameTransport<String> { _, _, _ -> calls++; "unexpected" })
        for ((handle, time) in listOf(0L to 0L, -1L to 0L, 1L to -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                dispatcher.submit(handle, time, true, listOf(detection), listOf(pair))
            }
        }
        assertEquals(0, calls)
    }

    private fun reject(error: FrameEncodingError, objects: List<CoreDetection>, pairs: List<CorePairEvidence>) {
        assertEquals(FrameEncoding.Rejected(error), CoreFrameEncoder.encode(true, objects, pairs))
    }

    private fun golden(name: String): ByteArray {
        val text = requireNotNull(javaClass.getResourceAsStream("/core/$name"))
            .bufferedReader().use { it.readText() }.filterNot { it.isWhitespace() }
        require(text.length % 2 == 0)
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
