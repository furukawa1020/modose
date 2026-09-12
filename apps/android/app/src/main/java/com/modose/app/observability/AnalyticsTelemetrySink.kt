package com.modose.app.observability

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

sealed interface AnalyticsParameterValue {
    data class Text(val value: String) : AnalyticsParameterValue

    data class Number(val value: Long) : AnalyticsParameterValue
}

data class AnalyticsParameter(
    val name: String,
    val value: AnalyticsParameterValue,
)

interface AnalyticsReporter {
    fun logEvent(
        name: String,
        parameters: List<AnalyticsParameter>,
    )
}

class FirebaseAnalyticsReporter(
    private val analytics: FirebaseAnalytics,
) : AnalyticsReporter {
    override fun logEvent(
        name: String,
        parameters: List<AnalyticsParameter>,
    ) {
        val bundle = Bundle()
        parameters.forEach { parameter ->
            when (val value = parameter.value) {
                is AnalyticsParameterValue.Text ->
                    bundle.putString(parameter.name, value.value)
                is AnalyticsParameterValue.Number ->
                    bundle.putLong(parameter.name, value.value)
            }
        }
        analytics.logEvent(name, bundle)
    }

    companion object {
        fun from(context: Context): FirebaseAnalyticsReporter =
            FirebaseAnalyticsReporter(
                FirebaseAnalytics.getInstance(context),
            )
    }
}

class AnalyticsTelemetrySink(
    private val reporter: AnalyticsReporter,
) {
    fun emit(event: TelemetryEvent) {
        reporter.logEvent(
            name = event.name.analyticsName,
            parameters = event.properties.map { it.toParameter() },
        )
    }

    private fun TelemetryProperty.toParameter(): AnalyticsParameter =
        when (this) {
            is TelemetryProperty.AppVersion ->
                text("app_version", value)
            is TelemetryProperty.FlowStage ->
                text("flow_stage", value.analyticsValue)
            is TelemetryProperty.Result ->
                text("result", value.analyticsValue)
            is TelemetryProperty.LatencyMillis ->
                number("latency_ms", value)
            is TelemetryProperty.VlmCallCount ->
                number("vlm_call_count", value.toLong())
            is TelemetryProperty.ModelId ->
                text("model_id", value)
            is TelemetryProperty.SchemaVersion ->
                text("schema_version", value)
            is TelemetryProperty.FailureCode ->
                text("failure_code", value.name.lowercase())
            is TelemetryProperty.ObjectCount ->
                number("object_count", value.toLong())
            is TelemetryProperty.RetryCount ->
                number("retry_count", value.toLong())
        }

    private fun text(
        name: String,
        value: String,
    ) = AnalyticsParameter(name, AnalyticsParameterValue.Text(value))

    private fun number(
        name: String,
        value: Long,
    ) = AnalyticsParameter(name, AnalyticsParameterValue.Number(value))

    private val TelemetryEventName.analyticsName: String
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

    private val TelemetryFlowStage.analyticsValue: String
        get() = when (this) {
            TelemetryFlowStage.Startup -> "startup"
            TelemetryFlowStage.Baseline -> "baseline"
            TelemetryFlowStage.Compare -> "compare"
            TelemetryFlowStage.Guidance -> "guidance"
            TelemetryFlowStage.Verify -> "verify"
            TelemetryFlowStage.Reset -> "reset"
        }

    private val TelemetryResult.analyticsValue: String
        get() = when (this) {
            TelemetryResult.Completed -> "completed"
            TelemetryResult.Failed -> "failed"
            TelemetryResult.Uncertain -> "uncertain"
            TelemetryResult.NeedsCorrection -> "needs_correction"
            TelemetryResult.Pending -> "pending"
        }
}
