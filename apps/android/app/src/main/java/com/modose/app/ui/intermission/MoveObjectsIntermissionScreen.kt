package com.modose.app.ui.intermission

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MoveObjectsIntermissionScreen(
    state: MoveObjectsIntermissionState,
    savedImageBytes: ByteArray,
    onEvent: (MoveObjectsIntermissionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F0E6))
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "SCENE SAVED",
            color = Color(0xFF005A55),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = "物を動かしてください",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "机の上を自由に変えたら、カメラを向けたまま復元を始めます。",
            style = MaterialTheme.typography.bodyLarge,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = state.objects,
                key = { _, item -> item.objectId },
            ) { index, item ->
                SavedObjectThumbnailCard(
                    number = index + 1,
                    item = item,
                    savedImageBytes = savedImageBytes,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = Color(0xFFE3E9E3),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = availabilityTitle(state.startAvailability),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = availabilityMessage(state.startAvailability),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(
            onClick = {
                onEvent(MoveObjectsIntermissionEvent.StartRestoration)
            },
            enabled = state.startAvailability == RestorationStartAvailability.Ready &&
                !state.startInFlight &&
                !state.resetConfirmationVisible,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.startInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "現在の状態を確認中",
                    modifier = Modifier.padding(start = 10.dp),
                )
            } else {
                Text("戻すガイドを始める")
            }
        }

        OutlinedButton(
            onClick = {
                onEvent(MoveObjectsIntermissionEvent.RequestReset)
            },
            enabled = !state.startInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存をやり直す")
        }
    }

    if (state.resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = {
                onEvent(MoveObjectsIntermissionEvent.CancelReset)
            },
            title = { Text("保存した状態を消しますか？") },
            text = {
                Text("この操作は取り消せません。画像と物体情報を削除して撮影へ戻ります。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEvent(MoveObjectsIntermissionEvent.ConfirmReset)
                    },
                ) {
                    Text("削除して撮り直す")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onEvent(MoveObjectsIntermissionEvent.CancelReset)
                    },
                ) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun SavedObjectThumbnailCard(
    number: Int,
    item: SavedObjectThumbnailModel,
    savedImageBytes: ByteArray,
) {
    val thumbnail = remember(savedImageBytes, item) {
        cropThumbnail(savedImageBytes, item)
    }
    Card(
        modifier = Modifier.size(width = 156.dp, height = 196.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            if (thumbnail == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .background(Color(0xFFD9DDD8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("画像なし", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Image(
                    bitmap = thumbnail,
                    contentDescription = item.displayName + "の保存時サムネイル",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f),
                )
            }
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = number.toString().padStart(2, '0'),
                    color = Color(0xFFB45A24),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun cropThumbnail(
    bytes: ByteArray,
    item: SavedObjectThumbnailModel,
): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
        val left = normalizedPixel(item.xMin, source.width).coerceAtMost(source.width - 1)
        val top = normalizedPixel(item.yMin, source.height).coerceAtMost(source.height - 1)
        val right = normalizedPixel(item.xMax, source.width).coerceIn(left + 1, source.width)
        val bottom = normalizedPixel(item.yMax, source.height).coerceIn(top + 1, source.height)
        Bitmap.createBitmap(source, left, top, right - left, bottom - top).asImageBitmap()
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun normalizedPixel(
    coordinate: Int,
    edgePixels: Int,
): Int = (coordinate / 1_000f * edgePixels).roundToInt()

private fun availabilityTitle(
    availability: RestorationStartAvailability,
): String = when (availability) {
    RestorationStartAvailability.Ready -> "準備できました"
    RestorationStartAvailability.AnchorUnavailable -> "机をカメラに映してください"
    RestorationStartAvailability.AnchorPaused -> "スマホをゆっくり動かしてください"
    RestorationStartAvailability.AnchorLost -> "保存した机を見つけ直しています"
    RestorationStartAvailability.AnchorFailed -> "机の位置を取得できません"
}

private fun availabilityMessage(
    availability: RestorationStartAvailability,
): String = when (availability) {
    RestorationStartAvailability.Ready -> "物を動かし終えたら、下のボタンを押してください。"
    RestorationStartAvailability.AnchorUnavailable -> "保存位置が安定するまで復元は始まりません。"
    RestorationStartAvailability.AnchorPaused -> "追跡が戻るまでガイド開始を保留します。"
    RestorationStartAvailability.AnchorLost -> "位置を推測せず、Anchorの再取得を待っています。"
    RestorationStartAvailability.AnchorFailed -> "保存をやり直して新しい位置を取得してください。"
}
