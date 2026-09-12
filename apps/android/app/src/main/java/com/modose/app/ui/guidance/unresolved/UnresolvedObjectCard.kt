package com.modose.app.ui.guidance.unresolved

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun UnresolvedObjectCard(
    model: UnresolvedObjectUiModel,
    thumbnail: ImageBitmap?,
    rediscoveryInFlight: Boolean,
    onRequestRediscovery: (sceneId: String, objectId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF1)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (model.kind == UnresolvedObjectKind.Missing) {
                    UnresolvedObjectCopy.MISSING_TITLE
                } else {
                    UnresolvedObjectCopy.AMBIGUOUS_TITLE
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (thumbnail == null) {
                Text(
                    text = "保存時画像を読み込めません",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .background(
                            color = Color(0xFFE3E9E3),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(16.dp),
                )
            } else {
                Image(
                    bitmap = thumbnail,
                    contentDescription = model.displayName + "の保存時サムネイル",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f),
                )
            }
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = description(model.kind),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = reasonText(model.reason),
                color = Color(0xFF6B4F3A),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    onRequestRediscovery(model.sceneId, model.objectId)
                },
                enabled = model.rediscoveryEnabled && !rediscoveryInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (rediscoveryInFlight) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(UnresolvedObjectCopy.REDISCOVERY_ACTION)
                }
            }
        }
    }
}

private fun description(kind: UnresolvedObjectKind): String =
    if (kind == UnresolvedObjectKind.Missing) {
        UnresolvedObjectCopy.MISSING_DESCRIPTION
    } else {
        UnresolvedObjectCopy.AMBIGUOUS_DESCRIPTION
    }

private fun reasonText(reason: UnresolvedObjectReason): String = when (reason) {
    UnresolvedObjectReason.NotVisible -> "物体が現在の画面に映っていません。"
    UnresolvedObjectReason.TemporarilyOccluded -> "ほかの物体に隠れている可能性があります。"
    UnresolvedObjectReason.MultipleSimilarCandidates -> "似た候補が複数見つかりました。"
    UnresolvedObjectReason.LowIdentityConfidence -> "保存時と同じ物体か確定できません。"
    UnresolvedObjectReason.TrackingUnavailable -> "カメラの追跡が安定していません。"
    UnresolvedObjectReason.Unknown -> "物体を安全に特定できませんでした。"
}
