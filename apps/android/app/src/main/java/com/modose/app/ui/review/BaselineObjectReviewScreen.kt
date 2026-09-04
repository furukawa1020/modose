package com.modose.app.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.modose.app.flow.baseline.BaselineFlowState
import com.modose.app.network.baseline.BaselineObject

@Composable
fun BaselineObjectReviewScreen(
    reviewing: BaselineFlowState.ReviewingBaseline,
    onConfirmed: (List<BaselineObject>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val captureImage = reviewing.capture.image
    val bitmap = remember(captureImage) {
        BitmapFactory.decodeByteArray(captureImage.bytes, 0, captureImage.bytes.size)
            ?.asImageBitmap()
    }
    var reviewState by remember(reviewing.analysis) {
        mutableStateOf(BaselineReviewState.from(reviewing.analysis))
    }
    var selectingManualBox by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    if (bitmap == null) {
        ReviewImageFailure(onCancel = onCancel, modifier = modifier)
        return
    }

    fun dispatch(action: BaselineReviewAction) {
        when (val update = BaselineReviewReducer.reduce(reviewState, action)) {
            is BaselineReviewUpdate.Updated -> {
                reviewState = update.state
                message = null
            }
            is BaselineReviewUpdate.Rejected -> {
                message = update.reason.userMessage()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F0E6))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "保存する物を確認",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "枠と名前が合っている物だけを対象にします",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onCancel) {
                Text("撮り直す")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 220.dp)
                .background(Color.Black)
                .onSizeChanged { viewportSize = it }
                .manualBoxInput(
                    enabled = selectingManualBox,
                    sourceWidthPx = captureImage.widthPx,
                    sourceHeightPx = captureImage.heightPx,
                    viewportSize = viewportSize,
                    onDragChanged = { start, end ->
                        dragStart = start
                        dragEnd = end
                    },
                    onDragFinished = { result ->
                        when (result) {
                            is ManualBoxMappingResult.Mapped -> {
                                dispatch(BaselineReviewAction.AddManualObject(result.boundingBox))
                                selectingManualBox = false
                            }
                            is ManualBoxMappingResult.Rejected -> {
                                message = result.reason.userMessage()
                            }
                        }
                        dragStart = null
                        dragEnd = null
                    },
                ),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "保存した机の画像",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            BaselineBoundingBoxOverlay(
                state = reviewState,
                sourceWidthPx = captureImage.widthPx,
                sourceHeightPx = captureImage.heightPx,
                modifier = Modifier.fillMaxSize(),
            )
            ManualSelectionPreview(
                start = dragStart,
                end = dragEnd,
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = reviewState.entries,
                key = { it.objectValue.id },
            ) { entry ->
                FilterChip(
                    selected = entry.selected,
                    onClick = {
                        dispatch(
                            BaselineReviewAction.ToggleSelection(entry.objectValue.id),
                        )
                    },
                    label = {
                        Text("${entry.number}. ${entry.objectValue.displayName}")
                    },
                )
            }
        }

        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasManualObject = reviewState.entries.any {
                it.source == ReviewObjectSource.Manual
            }
            OutlinedButton(
                onClick = {
                    if (hasManualObject) {
                        dispatch(BaselineReviewAction.RemoveManualObject)
                    } else {
                        selectingManualBox = true
                        message = "画像上で追加する物を囲んでください"
                    }
                },
                enabled = hasManualObject || reviewState.canAddManualObject,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (hasManualObject) "手動枠を削除" else "手動で追加")
            }
            Button(
                onClick = {
                    val confirmation = BaselineReviewReducer.confirm(reviewState)
                    if (confirmation is BaselineReviewConfirmation.Confirmed) {
                        onConfirmed(confirmation.objects)
                    }
                },
                enabled = reviewState.canConfirm && !selectingManualBox,
                modifier = Modifier.weight(1f),
            ) {
                Text("この内容で保存")
            }
        }
    }
}

private fun Modifier.manualBoxInput(
    enabled: Boolean,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    viewportSize: IntSize,
    onDragChanged: (Offset, Offset) -> Unit,
    onDragFinished: (ManualBoxMappingResult) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(sourceWidthPx, sourceHeightPx, viewportSize) {
        var start: Offset? = null
        var end: Offset? = null
        detectDragGestures(
            onDragStart = {
                start = it
                end = it
                onDragChanged(it, it)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                end = (end ?: change.position) + dragAmount
                val initial = start
                val current = end
                if (initial != null && current != null) {
                    onDragChanged(initial, current)
                }
            },
            onDragEnd = {
                val transform = ReviewImageFitGeometry.calculate(
                    sourceWidthPx = sourceWidthPx,
                    sourceHeightPx = sourceHeightPx,
                    viewportWidthPx = viewportSize.width.toFloat(),
                    viewportHeightPx = viewportSize.height.toFloat(),
                )
                val initial = start
                val current = end
                if (transform == null || initial == null || current == null) {
                    onDragFinished(
                        ManualBoxMappingResult.Rejected(ManualBoxRejection.EmptyBox),
                    )
                } else {
                    onDragFinished(ManualBoxGestureMapper.map(transform, initial, current))
                }
                start = null
                end = null
            },
            onDragCancel = {
                start = null
                end = null
            },
        )
    }
}

@Composable
private fun ManualSelectionPreview(
    start: Offset?,
    end: Offset?,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        if (start == null || end == null) return@Canvas
        val rect = Rect(
            left = minOf(start.x, end.x),
            top = minOf(start.y, end.y),
            right = maxOf(start.x, end.x),
            bottom = maxOf(start.y, end.y),
        )
        drawRect(
            color = Color(0xFFFFB000),
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun ReviewImageFailure(
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "保存画像を表示できません",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("画像を推測せず、撮り直してください。")
        Button(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
            Text("撮り直す")
        }
    }
}

private fun BaselineReviewRejection.userMessage(): String = when (this) {
    BaselineReviewRejection.ObjectNotFound -> "対象物が見つかりません"
    BaselineReviewRejection.MaximumSelectedObjects -> "保存できる物は5個までです"
    BaselineReviewRejection.ManualObjectAlreadyExists -> "手動追加できる枠は1個までです"
    BaselineReviewRejection.ManualObjectNotFound -> "削除する手動枠がありません"
    BaselineReviewRejection.InvalidBoundingBox -> "物を囲むように枠を引いてください"
}

private fun ManualBoxRejection.userMessage(): String = when (this) {
    ManualBoxRejection.OutsideImage -> "画像の内側で枠を引いてください"
    ManualBoxRejection.EmptyBox -> "物を囲む大きさで枠を引いてください"
}
