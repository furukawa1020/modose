package com.modose.app.ar.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ArSessionTransitionPolicyTest {
    @Test
    fun followsCreateResumePauseCloseOrder() {
        val created = ArSessionTransitionPolicy.apply(
            ArSessionPhase.Empty,
            ArSessionCommand.Create,
        )
        val resumed = ArSessionTransitionPolicy.apply(created.phase, ArSessionCommand.Resume)
        val paused = ArSessionTransitionPolicy.apply(resumed.phase, ArSessionCommand.Pause)
        val closed = ArSessionTransitionPolicy.apply(paused.phase, ArSessionCommand.Close)

        assertEquals(ArSessionPhase.Created, created.phase)
        assertEquals(ArSessionPhase.Resumed, resumed.phase)
        assertEquals(ArSessionPhase.Paused, paused.phase)
        assertEquals(ArSessionPhase.Closed, closed.phase)
    }

    @Test
    fun repeatedLifecycleCommandsAreIdempotent() {
        assertEquals(
            ArSessionResult.Applied(ArSessionPhase.Resumed),
            ArSessionTransitionPolicy.apply(ArSessionPhase.Resumed, ArSessionCommand.Resume),
        )
        assertEquals(
            ArSessionResult.Applied(ArSessionPhase.Paused),
            ArSessionTransitionPolicy.apply(ArSessionPhase.Paused, ArSessionCommand.Pause),
        )
        assertEquals(
            ArSessionResult.Applied(ArSessionPhase.Closed),
            ArSessionTransitionPolicy.apply(ArSessionPhase.Closed, ArSessionCommand.Close),
        )
    }

    @Test
    fun resumeBeforeCreateIsRejected() {
        assertEquals(
            ArSessionResult.Rejected(
                phase = ArSessionPhase.Empty,
                reason = ArSessionFailureReason.InvalidTransition,
            ),
            ArSessionTransitionPolicy.apply(ArSessionPhase.Empty, ArSessionCommand.Resume),
        )
    }

    @Test
    fun closedLifecycleCannotBeRevived() {
        assertEquals(
            ArSessionResult.Rejected(
                phase = ArSessionPhase.Closed,
                reason = ArSessionFailureReason.Closed,
            ),
            ArSessionTransitionPolicy.apply(ArSessionPhase.Closed, ArSessionCommand.Create),
        )
    }
}
