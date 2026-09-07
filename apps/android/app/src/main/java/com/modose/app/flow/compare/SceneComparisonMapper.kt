package com.modose.app.flow.compare

data class CompareMappingContext(
    val sceneId: String,
    val expectedObjectIds: List<String>,
    val expectedModelId: String? = null,
    val expectedPromptVersion: String? = null,
)

data class RawCompareBoundingBox(
    val yMin: Int,
    val xMin: Int,
    val yMax: Int,
    val xMax: Int,
)

data class RawCompareMatch(
    val sceneObjectId: String,
    val state: String,
    val currentBoundingBox: RawCompareBoundingBox?,
    val sameObjectConfidence: Double,
    val orientationDeltaDegrees: Double?,
    val occludedBy: List<String>,
    val reasonCodes: List<String>,
)

data class RawExtraObject(
    val label: String,
    val boundingBox: RawCompareBoundingBox,
    val confidence: Double,
)

data class RawSceneComparison(
    val schemaVersion: String,
    val status: String,
    val modelId: String,
    val promptVersion: String,
    val matches: List<RawCompareMatch>,
    val extraObjects: List<RawExtraObject>,
)

enum class CurrentObjectState {
    Aligned,
    Moved,
    Rotated,
    MovedRotated,
    Missing,
    Occluded,
    Ambiguous,
}

enum class ComparisonReason {
    PositionAligned,
    PositionChanged,
    OrientationChanged,
    ObjectMissing,
    ObjectOccluded,
    ObjectAmbiguous,
}

data class CompareBoundingBox(
    val yMin: Int,
    val xMin: Int,
    val yMax: Int,
    val xMax: Int,
)

data class CurrentObjectMatch(
    val sceneObjectId: String,
    val state: CurrentObjectState,
    val trackingBoundingBox: CompareBoundingBox?,
    val sameObjectConfidence: Double,
    val orientationDeltaDegrees: Double?,
    val occludedBy: List<String>,
    val reasons: Set<ComparisonReason>,
)

data class ExtraCurrentObject(
    val label: String,
    val boundingBox: CompareBoundingBox,
    val confidence: Double,
)

data class CurrentSceneComparison(
    val sceneId: String,
    val modelId: String,
    val promptVersion: String,
    val matches: List<CurrentObjectMatch>,
    val extraObjects: List<ExtraCurrentObject>,
)

enum class CompareMappingFailure {
    InvalidContext,
    UnsupportedSchema,
    InvalidStatus,
    ModelMismatch,
    PromptVersionMismatch,
    InvalidMatchSet,
    UnknownState,
    InvalidBoundingBox,
    InvalidConfidence,
    InvalidOrientation,
    InvalidOcclusion,
    UnknownReason,
    InvalidExtraObject,
}

sealed interface CompareMappingResult {
    data class Mapped(
        val comparison: CurrentSceneComparison,
    ) : CompareMappingResult

    data class Rejected(
        val reason: CompareMappingFailure,
    ) : CompareMappingResult
}

object SceneComparisonMapper {
    fun map(
        context: CompareMappingContext,
        raw: RawSceneComparison,
    ): CompareMappingResult {
        if (
            context.sceneId.isBlank() ||
            context.expectedObjectIds.size !in 1..MAX_OBJECT_COUNT ||
            context.expectedObjectIds.any(String::isBlank) ||
            context.expectedObjectIds.distinct().size != context.expectedObjectIds.size
        ) {
            return reject(CompareMappingFailure.InvalidContext)
        }
        if (raw.schemaVersion != SCHEMA_VERSION) {
            return reject(CompareMappingFailure.UnsupportedSchema)
        }
        if (raw.status != OK_STATUS) {
            return reject(CompareMappingFailure.InvalidStatus)
        }
        if (
            raw.modelId.isBlank() ||
            context.expectedModelId?.let { it != raw.modelId } == true
        ) {
            return reject(CompareMappingFailure.ModelMismatch)
        }
        if (
            raw.promptVersion.isBlank() ||
            context.expectedPromptVersion?.let { it != raw.promptVersion } == true
        ) {
            return reject(CompareMappingFailure.PromptVersionMismatch)
        }

        val rawIds = raw.matches.map { it.sceneObjectId }
        if (
            raw.matches.size != context.expectedObjectIds.size ||
            rawIds.distinct().size != rawIds.size ||
            rawIds.toSet() != context.expectedObjectIds.toSet()
        ) {
            return reject(CompareMappingFailure.InvalidMatchSet)
        }

        val mappedById = mutableMapOf<String, CurrentObjectMatch>()
        for (match in raw.matches) {
            val state = match.state.toState()
                ?: return reject(CompareMappingFailure.UnknownState)
            if (!match.sameObjectConfidence.isFinite() ||
                match.sameObjectConfidence !in 0.0..1.0
            ) {
                return reject(CompareMappingFailure.InvalidConfidence)
            }

            val suppliedBox = match.currentBoundingBox?.toDomain()
            if (match.currentBoundingBox != null && suppliedBox == null) {
                return reject(CompareMappingFailure.InvalidBoundingBox)
            }
            val positioned = state in POSITIONED_STATES
            if (positioned && suppliedBox == null) {
                return reject(CompareMappingFailure.InvalidBoundingBox)
            }
            if (
                state in setOf(CurrentObjectState.Missing, CurrentObjectState.Ambiguous) &&
                suppliedBox != null
            ) {
                return reject(CompareMappingFailure.InvalidBoundingBox)
            }

            val orientation = match.orientationDeltaDegrees
            val orientationRequired =
                state == CurrentObjectState.Rotated ||
                    state == CurrentObjectState.MovedRotated
            if (
                orientationRequired &&
                (orientation == null || !orientation.isFinite() || orientation !in -180.0..180.0)
            ) {
                return reject(CompareMappingFailure.InvalidOrientation)
            }
            if (
                !orientationRequired &&
                orientation != null &&
                (!orientation.isFinite() || orientation !in -180.0..180.0)
            ) {
                return reject(CompareMappingFailure.InvalidOrientation)
            }

            if (
                match.occludedBy.any {
                    it !in context.expectedObjectIds || it == match.sceneObjectId
                } ||
                match.occludedBy.distinct().size != match.occludedBy.size ||
                (state == CurrentObjectState.Occluded && match.occludedBy.isEmpty()) ||
                (state != CurrentObjectState.Occluded && match.occludedBy.isNotEmpty())
            ) {
                return reject(CompareMappingFailure.InvalidOcclusion)
            }

            val reasons = mutableSetOf<ComparisonReason>()
            for (reasonCode in match.reasonCodes) {
                val reason = reasonCode.toReason()
                    ?: return reject(CompareMappingFailure.UnknownReason)
                if (!reasons.add(reason)) {
                    return reject(CompareMappingFailure.UnknownReason)
                }
            }

            mappedById[match.sceneObjectId] = CurrentObjectMatch(
                sceneObjectId = match.sceneObjectId,
                state = state,
                trackingBoundingBox = if (positioned) suppliedBox else null,
                sameObjectConfidence = match.sameObjectConfidence,
                orientationDeltaDegrees = if (orientationRequired) orientation else null,
                occludedBy = match.occludedBy.sorted(),
                reasons = reasons,
            )
        }

        if (raw.extraObjects.size > MAX_OBJECT_COUNT) {
            return reject(CompareMappingFailure.InvalidExtraObject)
        }
        val extras = mutableListOf<ExtraCurrentObject>()
        for (extra in raw.extraObjects) {
            val box = extra.boundingBox.toDomain()
            if (
                extra.label.isBlank() ||
                box == null ||
                !extra.confidence.isFinite() ||
                extra.confidence !in 0.0..1.0
            ) {
                return reject(CompareMappingFailure.InvalidExtraObject)
            }
            extras += ExtraCurrentObject(extra.label, box, extra.confidence)
        }

        return CompareMappingResult.Mapped(
            CurrentSceneComparison(
                sceneId = context.sceneId,
                modelId = raw.modelId,
                promptVersion = raw.promptVersion,
                matches = context.expectedObjectIds.map { checkNotNull(mappedById[it]) },
                extraObjects = extras,
            ),
        )
    }

    private fun RawCompareBoundingBox.toDomain(): CompareBoundingBox? =
        if (
            yMin in 0..1000 &&
            xMin in 0..1000 &&
            yMax in 0..1000 &&
            xMax in 0..1000 &&
            yMin < yMax &&
            xMin < xMax
        ) {
            CompareBoundingBox(yMin, xMin, yMax, xMax)
        } else {
            null
        }

    private fun String.toState(): CurrentObjectState? = when (this) {
        "aligned" -> CurrentObjectState.Aligned
        "moved" -> CurrentObjectState.Moved
        "rotated" -> CurrentObjectState.Rotated
        "moved_rotated" -> CurrentObjectState.MovedRotated
        "missing" -> CurrentObjectState.Missing
        "occluded" -> CurrentObjectState.Occluded
        "ambiguous" -> CurrentObjectState.Ambiguous
        else -> null
    }

    private fun String.toReason(): ComparisonReason? = when (this) {
        "POSITION_ALIGNED" -> ComparisonReason.PositionAligned
        "POSITION_CHANGED" -> ComparisonReason.PositionChanged
        "ORIENTATION_CHANGED" -> ComparisonReason.OrientationChanged
        "OBJECT_MISSING" -> ComparisonReason.ObjectMissing
        "OBJECT_OCCLUDED" -> ComparisonReason.ObjectOccluded
        "OBJECT_AMBIGUOUS" -> ComparisonReason.ObjectAmbiguous
        else -> null
    }

    private fun reject(
        reason: CompareMappingFailure,
    ): CompareMappingResult = CompareMappingResult.Rejected(reason)

    private const val SCHEMA_VERSION = "1.0"
    private const val OK_STATUS = "ok"
    private const val MAX_OBJECT_COUNT = 5
    private val POSITIONED_STATES = setOf(
        CurrentObjectState.Aligned,
        CurrentObjectState.Moved,
        CurrentObjectState.Rotated,
        CurrentObjectState.MovedRotated,
    )
}
