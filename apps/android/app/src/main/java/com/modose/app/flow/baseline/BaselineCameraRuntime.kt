package com.modose.app.flow.baseline

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.ar.coordinates.*
import com.modose.app.ar.image.*
import com.modose.app.ar.render.BaselineCameraFrame
import com.modose.app.network.*
import com.modose.app.network.baseline.BaselineObject
import com.modose.app.vision.quality.*
import com.modose.app.vision.tracking.*
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal class BaselineCaptureRejected(message: String) : RuntimeException(message)

internal data class BaselineImageReview(
    val image: CpuCameraImage,
    val plan: VlmImageEncodingPlan,
    val reviewing: BaselineFlowState.ReviewingBaseline,
)

/** Blocking boundary: every method runs on the dedicated capture worker. */
internal class BaselineCameraRuntime(private val context: Context) {
    fun analyze(packet: BaselineCameraFrame, checkActive: () -> Unit): BaselineImageReview {
        checkActive()
        val frame = packet.frame
        val image = (frame.cpuImageResult as? CpuImageAcquisitionResult.Acquired)?.image
            ?: fail("カメラ画像を取得できません。撮り直してください。")
        if (image.timestampNanos != frame.timestampNanos ||
            frame.sceneAnchorState !is SceneAnchorState.Tracking ||
            image.widthPx <= 0 || image.heightPx <= 0 ||
            image.widthPx.toLong() * image.heightPx > 1920L * 1080L ||
            image.widthPx % 2 != 0 || image.heightPx % 2 != 0
        ) fail("保存に使える画像・アンカーがありません。撮り直してください。")
        val dt = (frame.timestampNanos - packet.previousTimestampNanos) / 1e9
        val previous = packet.previousViewMatrix
        val current = frame.viewMatrix
        if (dt <= 0.0 || dt > 0.2 || previous?.size != 16 || current?.size != 16 ||
            previous.any { !it.isFinite() } || current.any { !it.isFinite() }
        ) fail("カメラの動きを測定できません。少し待って撮り直してください。")
        var trace = 0.0
        for (column in 0..2) for (row in 0..2) {
            trace += previous[column * 4 + row].toDouble() * current[column * 4 + row]
        }
        val angularVelocity = acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)) / dt
        // This capture uses the complete CPU image, so its selected ROI covers the entire image.
        val quality = FrameCaptureQualityAssessor().assess(frame, angularVelocity, 1.0)
        val evaluated = quality as? FrameCaptureQualityResult.Evaluated
            ?: fail("撮影品質を測定できません。撮り直してください。")
        if (!evaluated.quality.captureAllowed) fail("暗さ・ブレ・平面検出を確認し、端末を静止して撮り直してください。")
        val snapshot = (frame.imageViewTransformResult as? ImageViewTransformFrameResult.Available)?.snapshot
            ?: fail("画像座標を取得できません。")
        if (snapshot.frameTimestampNanos != frame.timestampNanos ||
            snapshot.imageWidthPx != image.widthPx || snapshot.imageHeightPx != image.heightPx
        ) fail("画像の座標情報が一致しません。")
        fun view(x: Float, y: Float): ViewPixelPoint =
            (snapshot.transform.imageToView(ImagePixelPoint(x, y)) as? CoordinateTransformResult.Transformed)?.value
                ?: fail("画像の向きを取得できません。")
        val origin = view(0f, 0f)
        val xAxis = view(image.widthPx.toFloat(), 0f)
        val yAxis = view(0f, image.heightPx.toFloat())
        val dx = (xAxis.x - origin.x).toDouble()
        val dy = (xAxis.y - origin.y).toDouble()
        val cross = dx * (yAxis.y - origin.y) - dy * (yAxis.x - origin.x)
        val angle = atan2(dy, dx) * 180.0 / PI
        val quarter = (angle / 90.0).roundToInt()
        if (!cross.isFinite() || cross <= 0.0 || abs(angle - quarter * 90.0) > 0.5) {
            fail("対応していない画像方向です。")
        }
        val rotation = ((quarter % 4 + 4) % 4) * 90
        val plan = (VlmImageEncodingPlanner.create(image.widthPx, image.heightPx,
            PixelRoi(0, 0, image.widthPx, image.heightPx), rotation) as? VlmImagePlanResult.Planned)?.plan
            ?: fail("解析画像を準備できません。")
        val settings = settings()
        // Validate the actual model before paying for a cloud analysis request.
        extractor().use { checkActive() }
        val encoded = (VlmJpegEncoder().encode(image, plan) as? VlmJpegEncodingResult.Encoded)?.image
            ?: fail("解析画像を生成できません。")
        checkActive()
        val firebase = firebase(settings)
        val auth = FirebaseAuth.getInstance(firebase)
        if (auth.currentUser == null) {
            Tasks.await(auth.signInAnonymously(), 2, TimeUnit.SECONDS)
        }
        checkActive()
        val client = AuthenticatedVisionApiClient(settings.getProperty("apiBaseUrl"), "0.1.0",
            FirebaseIdTokenProvider(auth), FirebaseAppCheckTokenProvider(FirebaseAppCheck.getInstance(firebase)))
        val capture = BaselineCapture(UUID.randomUUID().toString(), Instant.now(),
            UUID.randomUUID().toString(), encoded)
        val flow = BaselineAnalysisUseCase(executeRequest = { request ->
            checkActive()
            client.execute(request)
        })
        val result = flow.analyze(capture)
        checkActive()
        if (result !is BaselineRunResult.Completed) fail("画像解析に失敗しました。認証・通信・対象物を確認してください。")
        return BaselineImageReview(image, plan, BaselineFlowState.ReviewingBaseline(capture, result.analysis))
    }

    fun confirm(review: BaselineImageReview, objects: List<BaselineObject>, checkActive: () -> Unit): MeasuredBaseline {
        checkActive()
        val result = extractor().use { engine ->
            BaselineAppearanceBuilder.build(review.reviewing.capture.sceneId, review.image, review.plan, objects, engine)
        }
        checkActive()
        return (result as? BaselineAppearanceResult.Built)?.baseline
            ?: fail("物体の特徴を取得できません。保存せず撮り直してください。")
    }

    private fun extractor(): AppearanceExtractor = when (val opened = MediaPipeAppearanceExtractor.open(
        context, MODEL_ASSET, MODEL_SHA256,
    )) {
        is AppearanceExtractorOpen.Opened -> opened.extractor
        is AppearanceExtractorOpen.Rejected -> fail("埋め込みモデルを読み込めません。モデル同梱とSHA-256を確認してください。")
    }

    private fun settings(): Properties {
        val properties = Properties()
        try {
            context.assets.open("modose-runtime.properties").use { input ->
                val bytes = input.readBounded(8192)
                bytes.inputStream().use { properties.load(it) }
            }
            val uri = URI(properties.getProperty("apiBaseUrl", ""))
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/")
            ) fail("API接続先が未設定または不正です。")
        } catch (failure: BaselineCaptureRejected) {
            throw failure
        } catch (_: Exception) {
            fail("解析設定がありません。アプリのstaging設定を準備してください。")
        }
        return properties
    }

    private fun firebase(settings: Properties): FirebaseApp = synchronized(FIREBASE_LOCK) {
        FirebaseApp.getApps(context).firstOrNull { it.name == FIREBASE_NAME }?.let { return@synchronized it }
        val bytes = context.assets.open("google-services.json").use { it.readBounded(65536) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val clients = json.getJSONArray("client")
        val client = (0 until clients.length()).map { clients.getJSONObject(it) }.firstOrNull {
            it.getJSONObject("client_info").getJSONObject("android_client_info")
                .getString("package_name") == context.packageName
        } ?: fail("このアプリID用のFirebase設定がありません。")
        val options = FirebaseOptions.Builder()
            .setApplicationId(client.getJSONObject("client_info").getString("mobilesdk_app_id"))
            .setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
            .setProjectId(json.getJSONObject("project_info").getString("project_id"))
            .build()
        val app = FirebaseApp.initializeApp(context, options, FIREBASE_NAME)
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance())
        app
    }

    private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count > maximumBytes - output.size()) {
                fail("設定ファイルがサイズ制限を超えています。")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun fail(message: String): Nothing = throw BaselineCaptureRejected(message)

    companion object {
        const val MODEL_ASSET = "models/mobilenet_v3_small.tflite"
        const val MODEL_SHA256 = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40"
        private const val FIREBASE_NAME = "modose-baseline"
        private val FIREBASE_LOCK = Any()
    }
}
