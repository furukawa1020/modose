package com.modose.app.ar.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedArSessionReaderTest {
    @Test
    fun readsStrictNumericFrameFixture() {
        val result = RecordedArSessionReader.read(VALID)

        assertTrue(result is ReplayReadResult.Accepted)
        val session = (result as ReplayReadResult.Accepted).session
        assertEquals("replay-1", session.sessionId)
        assertEquals(1, session.frames.size)
        assertEquals(ReplayTrackingState.Tracking, session.frames[0].trackingState)
        assertEquals("wallet", session.frames[0].objects.single().objectId)
        assertEquals(0.18, session.frames[0].objects.single().currentX, 0.0)
    }

    @Test
    fun rejectsMalformedJson() {
        assertRejected(
            ReplayReadRejection.MalformedJson,
            RecordedArSessionReader.read("{"),
        )
    }

    @Test
    fun rejectsUnknownRootField() {
        val raw = VALID.replace(
            "\"frames\":",
            "\"unexpected\":true,\"frames\":",
        )

        assertRejected(
            ReplayReadRejection.UnknownField,
            RecordedArSessionReader.read(raw),
        )
    }

    @Test
    fun rejectsNonSequentialFrameIndex() {
        val raw = VALID.replace("\"index\":0", "\"index\":1")

        assertRejected(
            ReplayReadRejection.NonSequentialFrame,
            RecordedArSessionReader.read(raw),
        )
    }

    @Test
    fun rejectsUnsupportedSchemaBeforeReplay() {
        val raw = VALID.replace(
            "\"schemaVersion\":\"1.0\"",
            "\"schemaVersion\":\"2.0\"",
        )

        assertRejected(
            ReplayReadRejection.UnsupportedSchema,
            RecordedArSessionReader.read(raw),
        )
    }

    private fun assertRejected(
        expected: ReplayReadRejection,
        result: ReplayReadResult,
    ) {
        assertTrue(result is ReplayReadResult.Rejected)
        assertEquals(
            expected,
            (result as ReplayReadResult.Rejected).reason,
        )
    }

    companion object {
        private val VALID = """
            {
              "schemaVersion":"1.0",
              "sessionId":"replay-1",
              "anchorPose":{
                "translationMeters":[0.0,0.72,0.0],
                "rotationQuaternion":[0.0,0.0,0.0,1.0]
              },
              "frames":[{
                "index":0,
                "timestampNanos":1,
                "trackingState":"tracking",
                "cameraPose":{
                  "translationMeters":[0.0,1.1,0.45],
                  "rotationQuaternion":[0.0,0.0,0.0,1.0]
                },
                "objects":[{
                  "objectId":"wallet",
                  "currentPlanePositionMeters":[0.18,0.12],
                  "targetPlanePositionMeters":[0.0,0.0],
                  "matchState":"accepted",
                  "trackingValid":true
                }]
              }]
            }
        """.trimIndent()
    }
}
