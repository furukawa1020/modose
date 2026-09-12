package com.modose.app.observability

import com.modose.app.flow.recovery.ProductFailureCode

enum class TelemetryEventName {
    AppStarted,
    SceneSaveCompleted,
    VisionRequestCompleted,
    GuidanceCompleted,
    VerificationCompleted,
    SceneResetCompleted,
    RecoverySelected,
}

enum class TelemetryPropertyKey {
    AppVersion,
    FlowStage,
    Result,
    LatencyMillis,
    VlmCallCount,
    ModelId,
    SchemaVersion,
    FailureCode,
    ObjectCount,
    RetryCount,
}

enum class TelemetryFlowStage {
    Startup,
    Baseline,
    Compare,
    Guidance,
    Verify,
    Reset,
}

enum class TelemetryResult {
    Completed,
    Failed,
    Uncertain,
    NeedsCorrection,
    Pending,
}

sealed interface TelemetryProperty {
    val key: TelemetryPropertyKey

    data class AppVersion(val value: String) : TelemetryProperty {
        override val key = TelemetryPropertyKey.AppVersion
    }

    data class FlowStage(val value: TelemetryFlowStage) : TelemetryProperty {
        override val key = TelemetryPropertyKey.FlowStage
    }

    data class Result(val value: TelemetryResult) : TelemetryProperty {
        override val key = TelemetryPropertyKey.Result
    }

    data class LatencyMillis(val value: Long) : TelemetryProperty {
        override val key = TelemetryPropertyKey.LatencyMillis
    }

    data class VlmCallCount(val value: Int) : TelemetryProperty {
        override val key = TelemetryPropertyKey.VlmCallCount
    }

    data class ModelId(val value: String) : TelemetryProperty {
        override val key = TelemetryPropertyKey.ModelId
    }

    data class SchemaVersion(val value: String) : TelemetryProperty {
        override val key = TelemetryPropertyKey.SchemaVersion
    }

    data class FailureCode(val value: ProductFailureCode) : TelemetryProperty {
        override val key = TelemetryPropertyKey.FailureCode
    }

    data class ObjectCount(val value: Int) : TelemetryProperty {
        override val key = TelemetryPropertyKey.ObjectCount
    }

    data class RetryCount(val value: Int) : TelemetryProperty {
        override val key = TelemetryPropertyKey.RetryCount
    }
}

class TelemetryEvent private constructor(
    val name: TelemetryEventName,
    val properties: List<TelemetryProperty>,
) {
    companion object {
        internal fun accepted(
            name: TelemetryEventName,
            properties: List<TelemetryProperty>,
        ) = TelemetryEvent(name, properties.toList())
    }
}

enum class TelemetryEventRejection {
    DuplicateProperty,
    PropertyNotAllowed,
    InvalidValue,
}

sealed interface TelemetryEventResult {
    data class Accepted(val event: TelemetryEvent) : TelemetryEventResult
    data class Rejected(val reason: TelemetryEventRejection) :
        TelemetryEventResult
}

object TelemetryEventFactory {
    fun create(
        name: TelemetryEventName,
        properties: List<TelemetryProperty>,
    ): TelemetryEventResult {
        val keys = properties.map(TelemetryProperty::key)
        if (keys.distinct().size != keys.size) {
            return rejected(TelemetryEventRejection.DuplicateProperty)
        }
        if (keys.any { it !in allowedProperties.getValue(name) }) {
            return rejected(TelemetryEventRejection.PropertyNotAllowed)
        }
        if (properties.any { !it.isValid() }) {
            return rejected(TelemetryEventRejection.InvalidValue)
        }
        return TelemetryEventResult.Accepted(
            TelemetryEvent.accepted(name, properties),
        )
    }

    private fun TelemetryProperty.isValid(): Boolean = when (this) {
        is TelemetryProperty.AppVersion ->
            SAFE_IDENTIFIER.matches(value)
        is TelemetryProperty.ModelId ->
            SAFE_IDENTIFIER.matches(value)
        is TelemetryProperty.SchemaVersion ->
            SCHEMA_VERSION.matches(value)
        is TelemetryProperty.LatencyMillis ->
            value in 0..MAX_LATENCY_MILLIS
        is TelemetryProperty.VlmCallCount ->
            value in 0..MAX_VLM_CALLS
        is TelemetryProperty.ObjectCount ->
            value in 0..MAX_OBJECTS
        is TelemetryProperty.RetryCount ->
            value in 0..MAX_RETRIES
        is TelemetryProperty.FlowStage,
        is TelemetryProperty.Result,
        is TelemetryProperty.FailureCode,
        -> true
    }

    private fun rejected(reason: TelemetryEventRejection) =
        TelemetryEventResult.Rejected(reason)

    private val common = setOf(
        TelemetryPropertyKey.AppVersion,
        TelemetryPropertyKey.FlowStage,
        TelemetryPropertyKey.Result,
        TelemetryPropertyKey.FailureCode,
        TelemetryPropertyKey.RetryCount,
    )

    private val allowedProperties = mapOf(
        TelemetryEventName.AppStarted to setOf(
            TelemetryPropertyKey.AppVersion,
            TelemetryPropertyKey.Result,
        ),
        TelemetryEventName.SceneSaveCompleted to common + setOf(
            TelemetryPropertyKey.LatencyMillis,
            TelemetryPropertyKey.ObjectCount,
        ),
        TelemetryEventName.VisionRequestCompleted to common + setOf(
            TelemetryPropertyKey.LatencyMillis,
            TelemetryPropertyKey.VlmCallCount,
            TelemetryPropertyKey.ModelId,
            TelemetryPropertyKey.SchemaVersion,
        ),
        TelemetryEventName.GuidanceCompleted to common + setOf(
            TelemetryPropertyKey.LatencyMillis,
            TelemetryPropertyKey.ObjectCount,
        ),
        TelemetryEventName.VerificationCompleted to common + setOf(
            TelemetryPropertyKey.LatencyMillis,
            TelemetryPropertyKey.VlmCallCount,
            TelemetryPropertyKey.ModelId,
            TelemetryPropertyKey.SchemaVersion,
        ),
        TelemetryEventName.SceneResetCompleted to common + setOf(
            TelemetryPropertyKey.LatencyMillis,
        ),
        TelemetryEventName.RecoverySelected to common,
    )

    private const val MAX_LATENCY_MILLIS = 120_000L
    private const val MAX_VLM_CALLS = 6
    private const val MAX_OBJECTS = 5
    private const val MAX_RETRIES = 3
    private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val SCHEMA_VERSION = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){0,2}")
}
