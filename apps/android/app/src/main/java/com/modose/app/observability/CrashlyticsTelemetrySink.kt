package com.modose.app.observability

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.modose.app.flow.recovery.ProductFailureCode

interface CrashlyticsReporter {
    fun log(message: String)

    fun setString(
        key: String,
        value: String,
    )

    fun setLong(
        key: String,
        value: Long,
    )

    fun recordException(throwable: Throwable)
}

class FirebaseCrashlyticsReporter(
    private val crashlytics: FirebaseCrashlytics =
        FirebaseCrashlytics.getInstance(),
) : CrashlyticsReporter {
    override fun log(message: String) {
        crashlytics.log(message)
    }

    override fun setString(
        key: String,
        value: String,
    ) {
        crashlytics.setCustomKey(key, value)
    }

    override fun setLong(
        key: String,
        value: Long,
    ) {
        crashlytics.setCustomKey(key, value)
    }

    override fun recordException(throwable: Throwable) {
        crashlytics.recordException(throwable)
    }
}

class CrashlyticsTelemetrySink(
    private val reporter: CrashlyticsReporter,
) {
    fun emit(event: TelemetryEvent) {
        reporter.log("telemetry_event:" + event.name.wireName)
        reporter.setString(EVENT_NAME_KEY, event.name.wireName)

        event.properties.forEach { property ->
            when (property) {
                is TelemetryProperty.AppVersion ->
                    reporter.setString("app_version", property.value)
                is TelemetryProperty.FlowStage ->
                    reporter.setString(
                        "flow_stage",
                        property.value.name.lowercase(),
                    )
                is TelemetryProperty.Result ->
                    reporter.setString(
                        "result",
                        property.value.name.lowercase(),
                    )
                is TelemetryProperty.LatencyMillis ->
                    reporter.setLong("latency_ms", property.value)
                is TelemetryProperty.VlmCallCount ->
                    reporter.setLong(
                        "vlm_call_count",
                        property.value.toLong(),
                    )
                is TelemetryProperty.ModelId ->
                    reporter.setString("model_id", property.value)
                is TelemetryProperty.SchemaVersion ->
                    reporter.setString("schema_version", property.value)
                is TelemetryProperty.FailureCode -> {
                    reporter.setString(
                        "failure_code",
                        property.value.name.lowercase(),
                    )
                    reporter.recordException(
                        SanitizedProductFailure(property.value),
                    )
                }
                is TelemetryProperty.ObjectCount ->
                    reporter.setLong(
                        "object_count",
                        property.value.toLong(),
                    )
                is TelemetryProperty.RetryCount ->
                    reporter.setLong(
                        "retry_count",
                        property.value.toLong(),
                    )
            }
        }
    }

    private val TelemetryEventName.wireName: String
        get() = when (this) {
            TelemetryEventName.AppStarted -> "app_started"
            TelemetryEventName.SceneSaveCompleted ->
                "scene_save_completed"
            TelemetryEventName.VisionRequestCompleted ->
                "vision_request_completed"
            TelemetryEventName.GuidanceCompleted ->
                "guidance_completed"
            TelemetryEventName.VerificationCompleted ->
                "verification_completed"
            TelemetryEventName.SceneResetCompleted ->
                "scene_reset_completed"
            TelemetryEventName.RecoverySelected ->
                "recovery_selected"
        }

    companion object {
        private const val EVENT_NAME_KEY = "telemetry_event"
    }
}

class SanitizedProductFailure(
    failureCode: ProductFailureCode,
) : RuntimeException("product_failure:" + failureCode.name.lowercase())
