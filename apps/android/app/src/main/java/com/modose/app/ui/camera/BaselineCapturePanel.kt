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
import com.modose.app.ui.review.BaselineObjectReviewScreen
import com.modose.app.vision.tracking.MeasuredBaseline
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

@Composable
internal fun BaselineCapturePanel(view: CameraBackgroundSurfaceView, available: Boolean) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { BaselineCameraRuntime(context) }
    val dispatcher = remember { Executors.newSingleThreadExecutor { task ->
        Thread(task, "modose-baseline")
    }.asCoroutineDispatcher() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<BaselineImageReview?>(null) }
    var prepared by remember { mutableStateOf<MeasuredBaseline?>(null) }

    fun reset() {
        job?.cancel()
        view.cancelBaselineCapture()
        review = null
        prepared = null
        message = null
    }
    DisposableEffect(view, dispatcher) {
        onDispose {
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
                    prepared = result
                    review = null
                }
            },
            onCancel = ::reset,
            confirmationLabel = "特徴を取得",
        )
    } else {
        Column(Modifier.fillMaxWidth().background(Color(0xEEF4F0E6)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                busy -> "画像を処理しています"
                prepared != null -> "物体特徴を取得しました（" + prepared!!.appearances.size + "個）"
                !available -> "平面と追跡が安定するまで待ってください"
                else -> "机の状態を撮影して解析します"
            })
            if (prepared != null) {
                Text("保存確定・復元開始はまだ行っていません。")
                Button(onClick = ::reset) { Text("撮り直す") }
            } else {
                Text("操作すると撮影画像を解析APIへ送信します。")
                Button(enabled = available && !busy, onClick = {
                    runOperation {
                        val packet = suspendCancellableCoroutine<BaselineCameraFrame> { continuation ->
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
