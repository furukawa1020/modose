package com.modose.app.observability

import com.modose.app.flow.recovery.ProductFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPrivacyTest {
    @Test
    fun factoryAcceptsOnlyAllowedPropertiesForEvent() {
        val result = TelemetryEventFactory.create(
            TelemetryEventName.VisionRequestCompleted,
            listOf(
                TelemetryProperty.FlowStage(
                    TelemetryFlowStage.Baseline,
                ),
                TelemetryProperty.Result(TelemetryResult.Completed),
                TelemetryProperty.LatencyMillis(950),
                TelemetryProperty.VlmCallCount(1),
                TelemetryProperty.ModelId("gemini-3.5-flash"),
                TelemetryProperty.SchemaVersion("1.0"),
            ),
        )

        assertTrue(result is TelemetryEventResult.Accepted)
    }

    @Test
    fun factoryRejectsDuplicateProperties() {
        val result = TelemetryEventFactory.create(
            TelemetryEventName.AppStarted,
            listOf(
                TelemetryProperty.AppVersion("1.0.0"),
                TelemetryProperty.AppVersion("1.0.1"),
            ),
        )

        assertRejected(
            TelemetryEventRejection.DuplicateProperty,
            result,
        )
    }

    @Test
    fun factoryRejectsPropertyNotAllowedForEvent() {
        val result = TelemetryEventFactory.create(
            TelemetryEventName.AppStarted,
            listOf(TelemetryProperty.ObjectCount(1)),
        )

        assertRejected(
            TelemetryEventRejection.PropertyNotAllowed,
            result,
        )
    }

    @Test
    fun factoryRejectsIdentifierContainingPersonalTextSeparators() {
        val result = TelemetryEventFactory.create(
            TelemetryEventName.VisionRequestCompleted,
            listOf(TelemetryProperty.ModelId("wallet@owner.example")),
        )

        assertRejected(
            TelemetryEventRejection.InvalidValue,
            result,
        )
    }

    @Test
    fun factoryRejectsValuesOutsideProductLimits() {
        val result = TelemetryEventFactory.create(
            TelemetryEventName.SceneSaveCompleted,
            listOf(TelemetryProperty.ObjectCount(6)),
        )

        assertRejected(
            TelemetryEventRejection.InvalidValue,
            result,
        )
    }

    @Test
    fun telemetryKeysContainNoForbiddenPayloadFields() {
        val keys = TelemetryPropertyKey.entries.map { it.name }

        assertEquals(
            listOf(
                "AppVersion",
                "FlowStage",
                "Result",
                "LatencyMillis",
                "VlmCallCount",
                "ModelId",
                "SchemaVersion",
                "FailureCode",
                "ObjectCount",
                "RetryCount",
            ),
            keys,
        )
        val forbidden = listOf(
            "image",
            "objectname",
            "prompt",
            "embedding",
            "sceneid",
        )
        keys.forEach { key ->
            forbidden.forEach { token ->
                assertFalse(
                    "$key must not contain $token",
                    key.lowercase().contains(token),
                )
            }
        }
    }

    @Test
    fun crashlyticsReceivesOnlyStableFailureCode() {
        val reporter = FakeCrashlyticsReporter()
        val event = accepted(
            TelemetryEventName.RecoverySelected,
            listOf(
                TelemetryProperty.FlowStage(TelemetryFlowStage.Startup),
                TelemetryProperty.Result(TelemetryResult.Failed),
                TelemetryProperty.FailureCode(
                    ProductFailureCode.DeviceUnsupported,
                ),
            ),
        )

        CrashlyticsTelemetrySink(reporter).emit(event)

        assertEquals(
            "recovery_selected",
            reporter.strings.getValue("telemetry_event"),
        )
        assertEquals(
            "deviceunsupported",
            reporter.strings.getValue("failure_code"),
        )
        assertEquals(1, reporter.exceptions.size)
        assertEquals(
            "product_failure:deviceunsupported",
            reporter.exceptions.single().message,
        )
    }

    @Test
    fun analyticsReceivesFixedEventAndParameterNames() {
        val reporter = FakeAnalyticsReporter()
        val event = accepted(
            TelemetryEventName.VisionRequestCompleted,
            listOf(
                TelemetryProperty.FlowStage(TelemetryFlowStage.Compare),
                TelemetryProperty.Result(TelemetryResult.Completed),
                TelemetryProperty.LatencyMillis(1_250),
                TelemetryProperty.VlmCallCount(1),
                TelemetryProperty.ModelId("gemini-3.5-flash"),
                TelemetryProperty.SchemaVersion("1.0"),
            ),
        )

        AnalyticsTelemetrySink(reporter).emit(event)

        assertEquals("vision_request_completed", reporter.eventName)
        assertEquals(
            listOf(
                "flow_stage",
                "result",
                "latency_ms",
                "vlm_call_count",
                "model_id",
                "schema_version",
            ),
            reporter.parameters.map { it.name },
        )
        assertEquals(
            AnalyticsParameterValue.Text("compare"),
            reporter.parameters.first().value,
        )
        assertEquals(
            AnalyticsParameterValue.Number(1_250),
            reporter.parameters[2].value,
        )
    }

    private fun accepted(
        name: TelemetryEventName,
        properties: List<TelemetryProperty>,
    ): TelemetryEvent {
        val result = TelemetryEventFactory.create(name, properties)
        assertTrue(result is TelemetryEventResult.Accepted)
        return (result as TelemetryEventResult.Accepted).event
    }

    private fun assertRejected(
        expected: TelemetryEventRejection,
        result: TelemetryEventResult,
    ) {
        assertTrue(result is TelemetryEventResult.Rejected)
        assertEquals(
            expected,
            (result as TelemetryEventResult.Rejected).reason,
        )
    }
}

private class FakeCrashlyticsReporter : CrashlyticsReporter {
    val strings = mutableMapOf<String, String>()
    val longs = mutableMapOf<String, Long>()
    val exceptions = mutableListOf<Throwable>()
    val logs = mutableListOf<String>()

    override fun log(message: String) {
        logs += message
    }

    override fun setString(
        key: String,
        value: String,
    ) {
        strings[key] = value
    }

    override fun setLong(
        key: String,
        value: Long,
    ) {
        longs[key] = value
    }

    override fun recordException(throwable: Throwable) {
        exceptions += throwable
    }
}

private class FakeAnalyticsReporter : AnalyticsReporter {
    var eventName: String? = null
    var parameters: List<AnalyticsParameter> = emptyList()

    override fun logEvent(
        name: String,
        parameters: List<AnalyticsParameter>,
    ) {
        eventName = name
        this.parameters = parameters
    }
}
