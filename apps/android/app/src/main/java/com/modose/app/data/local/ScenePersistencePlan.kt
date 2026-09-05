package com.modose.app.data.local

import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.ObjectSymmetry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

enum class SceneObjectOrigin(val storageValue: String) {
    Detected("detected"),
    Manual("manual"),
}

data class SceneObjectWrite(
    val objectValue: BaselineObject,
    val origin: SceneObjectOrigin,
)

data class SceneSnapshotWrite(
    val sceneId: String,
    val createdAt: Instant,
    val modelId: String,
    val promptVersion: String,
    val repaired: Boolean,
    val jpeg: ByteArray,
    val objects: List<SceneObjectWrite>,
)

data class ScenePersistencePlan(
    val scene: SceneEntity,
    val objects: List<SceneObjectEntity>,
    val preparedImage: PreparedSceneImage,
)

enum class ScenePlanRejection {
    SceneMismatch,
    ImageMismatch,
    InvalidCreatedAt,
    InvalidMetadata,
    InvalidObjectCount,
    DuplicateObjectId,
    InvalidObject,
}

sealed interface ScenePlanResult {
    data class Planned(val plan: ScenePersistencePlan) : ScenePlanResult
    data class Rejected(val reason: ScenePlanRejection) : ScenePlanResult
}

object ScenePersistencePlanFactory {
    fun create(
        write: SceneSnapshotWrite,
        preparedImage: PreparedSceneImage,
    ): ScenePlanResult {
        if (preparedImage.sceneId != write.sceneId ||
            preparedImage.fileName != write.sceneId + ".jpg"
        ) {
            return rejected(ScenePlanRejection.SceneMismatch)
        }
        val imageSha256 = write.jpeg.sha256()
        if (preparedImage.sha256 != imageSha256) {
            return rejected(ScenePlanRejection.ImageMismatch)
        }
        val createdAtEpochMillis = try {
            write.createdAt.toEpochMilli()
        } catch (_: ArithmeticException) {
            return rejected(ScenePlanRejection.InvalidCreatedAt)
        } catch (_: DateTimeException) {
            return rejected(ScenePlanRejection.InvalidCreatedAt)
        }
        if (createdAtEpochMillis <= 0 ||
            write.modelId.isBlank() ||
            write.modelId.length > 128 ||
            write.promptVersion.isBlank() ||
            write.promptVersion.length > 64
        ) {
            return rejected(ScenePlanRejection.InvalidMetadata)
        }
        if (write.objects.size !in 1..5) {
            return rejected(ScenePlanRejection.InvalidObjectCount)
        }
        if (write.objects.map { it.objectValue.id }.toSet().size != write.objects.size) {
            return rejected(ScenePlanRejection.DuplicateObjectId)
        }
        if (write.objects.any { !it.objectValue.isValid() }) {
            return rejected(ScenePlanRejection.InvalidObject)
        }

        val fingerprint = fingerprint(write, imageSha256)
        val scene = SceneEntity(
            sceneId = write.sceneId,
            schemaVersion = SCHEMA_VERSION,
            createdAtEpochMillis = createdAtEpochMillis,
            committedAtEpochMillis = null,
            modelId = write.modelId,
            promptVersion = write.promptVersion,
            vlmRepaired = write.repaired,
            imageFileName = preparedImage.fileName,
            imageSha256 = imageSha256,
            contentFingerprint = fingerprint,
            storageState = SceneStorageState.STAGING,
        )
        val objects = write.objects.mapIndexed { index, value ->
            val objectValue = value.objectValue
            SceneObjectEntity(
                sceneId = write.sceneId,
                objectId = objectValue.id,
                displayOrder = index,
                displayName = objectValue.displayName,
                appearanceFeaturesJson = JsonArray(
                    objectValue.appearanceFeatures.map(::JsonPrimitive),
                ).toString(),
                yMin = objectValue.boundingBox.yMin,
                xMin = objectValue.boundingBox.xMin,
                yMax = objectValue.boundingBox.yMax,
                xMax = objectValue.boundingBox.xMax,
                orientationImportant = objectValue.orientationImportant,
                symmetry = objectValue.symmetry.storageValue(),
                source = value.origin.storageValue,
            )
        }
        return ScenePlanResult.Planned(
            ScenePersistencePlan(
                scene = scene,
                objects = objects,
                preparedImage = preparedImage,
            ),
        )
    }

    private fun fingerprint(
        write: SceneSnapshotWrite,
        imageSha256: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field(SCHEMA_VERSION)
        digest.field(write.sceneId)
        digest.field(write.createdAt.toString())
        digest.field(write.modelId)
        digest.field(write.promptVersion)
        digest.field(write.repaired.toString())
        digest.field(imageSha256)
        write.objects.forEachIndexed { index, value ->
            val objectValue = value.objectValue
            digest.field(index.toString())
            digest.field(objectValue.id)
            digest.field(objectValue.displayName)
            objectValue.appearanceFeatures.forEach { digest.field(it) }
            digest.field(objectValue.boundingBox.yMin.toString())
            digest.field(objectValue.boundingBox.xMin.toString())
            digest.field(objectValue.boundingBox.yMax.toString())
            digest.field(objectValue.boundingBox.xMax.toString())
            digest.field(objectValue.orientationImportant.toString())
            digest.field(objectValue.symmetry.storageValue())
            digest.field(value.origin.storageValue)
        }
        return digest.digest().toHex()
    }

    private fun BaselineObject.isValid(): Boolean =
        id.isNotBlank() &&
            id.length <= 64 &&
            displayName.isNotBlank() &&
            displayName.length <= 80 &&
            appearanceFeatures.size in 1..8 &&
            appearanceFeatures.all { it.isNotBlank() && it.length <= 120 } &&
            boundingBox.yMin in 0..1000 &&
            boundingBox.xMin in 0..1000 &&
            boundingBox.yMax in 0..1000 &&
            boundingBox.xMax in 0..1000 &&
            boundingBox.yMin < boundingBox.yMax &&
            boundingBox.xMin < boundingBox.xMax

    private fun ObjectSymmetry.storageValue(): String = when (this) {
        ObjectSymmetry.None -> "none"
        ObjectSymmetry.Bilateral -> "bilateral"
        ObjectSymmetry.Rotational -> "rotational"
    }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        update(':'.code.toByte())
        update(bytes)
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it) }

    private fun rejected(reason: ScenePlanRejection) =
        ScenePlanResult.Rejected(reason)

    private const val SCHEMA_VERSION = "1.0"
}
