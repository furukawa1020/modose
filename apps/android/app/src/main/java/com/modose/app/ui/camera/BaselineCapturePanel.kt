package com.modose.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.modose.app.ar.render.BaselineCameraFrame
import com.modose.app.ar.render.CameraBackgroundSurfaceView
import com.modose.app.flow.baseline.*
import com.modose.app.flow.compare.CurrentObjectState
import com.modose.app.ui.review.BaselineObjectReviewScreen
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

@Composable
internal fun BaselineCapturePanel(view: CameraBackgroundSurfaceView, available: Boolean) {
    val context = LocalContext.current.applicationContext
    val snapshots = remember(context) { AndroidBaselineSnapshots.get(context) }
    var savedSession by remember { mutableStateOf<BaselineSnapshotSession?>(null) }
    val runtime = remember(context) { BaselineCameraRuntime(context) }
    val dispatcher = remember { Executors.newSingleThreadExecutor { task ->
        Thread(task, "modose-baseline")
    }.asCoroutineDispatcher() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<BaselineImageReview?>(null) }
    var prepared by remember { mutableStateOf<PreparedBaseline?>(null) }
    var comparison by remember { mutableStateOf<PreparedComparison?>(null) }

    fun reset() {
        savedSession?.close()
        savedSession = null
        job?.cancel()
        view.cancelBaselineCapture()
        review = null
        prepared = null
        comparison = null
        message = null
    }
    DisposableEffect(view, dispatcher) {
        onDispose {
            savedSession?.close()
            job?.cancel()
            view.cancelBaselineCapture()
            dispatcher.close()
        }
    }
    LaunchedEffect(available) {
        if (!available) reset()
    }

    fun runOperation(action: suspend () -> Unit) {
        if (busy || !available) return
        busy = true
        message = null
        job = scope.launch {
            try {
                withTimeout(12_000L) { action() }
            } catch (_: TimeoutCancellationException) {
                review = null
                message = "処理が時間内に完了しませんでした。撮り直してください。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BaselineCaptureRejected) {
                review = null
                message = failure.message
            } catch (_: Exception) {
                review = null
                message = "解析を完了できません。設定・認証・通信を確認してください。"
            } finally {
                busy = false
            }
        }
    }

    suspend fun captureFrame(): BaselineCameraFrame = suspendCancellableCoroutine<BaselineCameraFrame> { continuation ->
        continuation.invokeOnCancellation { view.cancelBaselineCapture() }
        val accepted = view.requestBaselineFrame { frame ->
            if (continuation.isActive) {
                if (frame == null) continuation.resumeWithException(
                    BaselineCaptureRejected("撮影が中断されました。"))
                else continuation.resume(frame)
            }
        }
        if (!accepted && continuation.isActive) continuation.resumeWithException(
            BaselineCaptureRejected("カメラは使用できません。"))
    }

    val reviewing = review
    if (reviewing != null && !busy) {
        BaselineObjectReviewScreen(
            reviewing = reviewing.reviewing,
            onConfirmed = { objects ->
                runOperation {
                    val result = withContext(dispatcher) {
                        val operation = currentCoroutineContext()
                        runtime.confirm(reviewing, objects) { operation.ensureActive() }
                    }
                    reviewing.validity.requireCurrent()
                    val write = BaselineSnapshotWriteFactory.create(reviewing, result.measured, objects)
                    val owner = snapshots.openSession()
                    savedSession = owner
                    var published = false
                    try {
                        when (owner.save(write) { reviewing.validity.isCurrent }) {
                            BaselineSnapshotSave.Saved -> Unit
                            BaselineSnapshotSave.Rejected ->
                                throw BaselineCaptureRejected("端末に保存できませんでした。撮り直してください。")
                            BaselineSnapshotSave.CleanupPending ->
                                throw BaselineCaptureRejected("保存に失敗しました。残ったデータの削除を次回も再試行します。")
                        }
                        currentCoroutineContext().ensureActive()
                        reviewing.validity.requireCurrent()
                        prepared = result
                        review = null
                        published = true
                    } finally {
                        if (!published) owner.close()
                    }
                }
            },
            onCancel = ::reset,
            confirmationLabel = "確認して端末に保存",
        )
    } else {
        Column(Modifier.fillMaxWidth().background(Color(0xEEF4F0E6)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                busy -> "画像を処理しています"
                prepared != null -> "画像と物体情報を端末に保存しました（" + prepared!!.targets.size + "個）"
                !available -> "平面と追跡が安定するまで待ってください"
                else -> "机の状態を撮影して解析します"
            })
            if (prepared != null) {
                val saved = prepared!!
                Text("目標座標を準備しました。比較結果だけでは復元完了になりません。")
                Text("比較すると保存画像と現在画像を解析APIへ送信します。")
                Button(enabled = available && !busy && !saved.comparison.hasAttempted, onClick = {
                    runOperation {
                        comparison = null
                        val packet = captureFrame()
                        val result = withContext(dispatcher) {
                            val operation = currentCoroutineContext()
                            runtime.compare(saved, packet) { operation.ensureActive() }
                        }
                        currentCoroutineContext().ensureActive()
                        saved.comparison.validity.requireCurrent()
                        comparison = result
                    }
                }) { Text("現在の状態と比較") }
                comparison?.let { measured ->
                    val result = measured.analysis
                    Text("比較結果（比較撮影時点）")
                    Text("端末内外観照合：" + measured.measurements.objects.size + "候補・" +
                        measured.measurements.pairs.size + "組合せ")
                    result.matches.forEach { match ->
                        val label = saved.comparison.labels.getValue(match.baselineObjectId)
                        val state = when (match.state) {
                            CurrentObjectState.Aligned -> "見た目は一致"
                            CurrentObjectState.Moved -> "移動あり"
                            CurrentObjectState.Rotated -> "回転あり"
                            CurrentObjectState.MovedRotated -> "移動・回転あり"
                            CurrentObjectState.Missing -> "見つかりません"
                            CurrentObjectState.Occluded -> "隠れています"
                            CurrentObjectState.Ambiguous -> "対応が曖昧です"
                        }
                        Text(label + "：" + state)
                        val savedId = saved.measured.objectIds.getValue(match.baselineObjectId)
                        val candidate = measured.measurements.objects.singleOrNull { it.claimedSavedId == savedId }
                        val scores = candidate?.let { current ->
                            measured.measurements.pairs.singleOrNull {
                                it.savedId == savedId && it.currentId == current.currentId
                            }
                        }
                        scores?.let {
                            Text(String.format(java.util.Locale.ROOT,
                                "埋め込み類似度 %.2f / 色特徴類似度 %.2f",
                                it.embeddingSimilarity, it.signatureSimilarity))
                        }
                        candidate?.let { current ->
                            measured.distances.singleOrNull {
                                it.currentId == current.currentId && it.savedId == savedId
                            }?.let { projection ->
                                val meters = projection.distanceMeters
                                if (meters != null) {
                                    Text(String.format(java.util.Locale.ROOT,
                                        "比較撮影時の候補距離：%.1f cm", meters * 100.0))
                                } else {
                                    Text("比較撮影時の候補位置：平面へ投影できません")
                                }
                            }
                        }
                    }
                    if (result.addedObjects.isNotEmpty()) Text("追加物体：" + result.addedObjects.size + "個")
                    Text("類似度だけでは同一物体を確定しません。追跡ガイド・最終確認は未開始です。")
                }
                if (saved.comparison.hasAttempted && comparison == null && !busy) {
                    Text("比較要求は送信済みです。再実行には削除して撮り直してください。")
                }
                Text("画面終了時に画像と目標を破棄します。")
                Button(onClick = ::reset) { Text("削除して撮り直す") }
            } else {
                Text("操作すると撮影画像を解析APIへ送信します。")
                Button(enabled = available && !busy, onClick = {
                    runOperation {
                        val packet = captureFrame()
                        val result = withContext(dispatcher) {
                            val operation = currentCoroutineContext()
                            runtime.analyze(packet) { operation.ensureActive() }
                        }
                        result.validity.requireCurrent()
                        review = result
                    }
                }) { Text("撮影して解析") }
            }
            message?.let { Text(it) }
            if (busy) Button(onClick = ::reset) { Text("中断") }
        }
    }
}
