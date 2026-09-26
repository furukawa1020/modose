package com.modose.app.flow.baseline

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.ar.plane.CapturedTableGeometry
import com.modose.app.ar.coordinates.*
import com.modose.app.ar.image.*
import com.modose.app.ar.render.BaselineCameraFrame
import com.modose.app.ar.render.BaselineCaptureValidity
import com.modose.app.core.*
import com.modose.app.flow.compare.*
import com.modose.app.flow.verification.*
import com.modose.app.network.compare.CompareAnalysis
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
    val validity: BaselineCaptureValidity,
    val tableGeometry: CapturedTableGeometry,
    val imageCapture: NativeImageCapture,
    val image: CpuCameraImage,
    val plan: VlmImageEncodingPlan,
    val reviewing: BaselineFlowState.ReviewingBaseline,
    val anchor: SceneAnchorSnapshot,
)

internal data class PreparedBaseline(
    val measured: MeasuredBaseline,
    val targets: NativeBaselineTargets,
    val comparison: SavedSceneComparison,
    val anchor: SceneAnchorSnapshot,
    val orientationPolicy: ConfirmedOrientationPolicy? = null,
)

internal data class PreparedComparison(
    val analysis: CompareAnalysis,
    val measurements: MeasuredComparison,
    val distances: List<NativeCandidateDistance>,
)

internal data class CameraVerificationOutcome(val state: CoreRestoreState, val unavailable: Boolean)

private data class BaselineCaptureInput(
    val validity: BaselineCaptureValidity,
    val tableGeometry: CapturedTableGeometry,
    val imageCapture: NativeImageCapture,
    val image: CpuCameraImage,
    val plan: VlmImageEncodingPlan,
    val anchor: SceneAnchorSnapshot,
)

/** Blocking boundary: every method runs on the dedicated capture worker. */
internal class BaselineCameraRuntime(private val context: Context) {
    private fun prepare(
        packet: BaselineCameraFrame, sceneId: String, checkActive: () -> Unit,
    ): BaselineCaptureInput {
        val validity = packet.validity ?: fail("撮影時のアンカーを確認できません。撮り直してください。")
        fun checkCurrent() {
            checkActive()
            if (!validity.isCurrent) fail("撮影時のアンカーが失効しました。撮り直してください。")
        }
        checkCurrent()
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
        val tableGeometry = frame.tableGeometry
            ?: fail("実測した平面境界を取得できません。平面を映して撮り直してください。")
        if (tableGeometry.copyFor(image.timestampNanos, validity.anchorId) == null) {
            fail("画像と平面境界の時刻・アンカーが一致しません。")
        }
        val imageCapture = NativeImageCaptureFactory.capture(
            frame, NativeGuidanceEpoch(sceneId), frame.timestampNanos / 1_000_000,
        ) ?: fail("撮影時のカメラ座標を取得できません。撮り直してください。")
        val anchor = (frame.sceneAnchorState as SceneAnchorState.Tracking).anchor
        return BaselineCaptureInput(validity, tableGeometry, imageCapture, image, plan, anchor)
    }

    fun analyze(packet: BaselineCameraFrame, checkActive: () -> Unit): BaselineImageReview {
        val input = prepare(packet, UUID.randomUUID().toString(), checkActive)
        val (validity, tableGeometry, imageCapture, image, plan) = input
        val sceneId = imageCapture.epoch.sceneId
        fun checkCurrent() {
            checkActive()
            if (!validity.isCurrent) fail("撮影時のアンカーが失効しました。撮り直してください。")
        }
        val settings = settings()
        // Validate the actual model before paying for a cloud analysis request.
        extractor().use { checkCurrent() }
        val encoded = (VlmJpegEncoder().encode(image, plan) as? VlmJpegEncodingResult.Encoded)?.image
            ?: fail("解析画像を生成できません。")
        checkCurrent()
        val firebase = firebase(settings)
        val auth = FirebaseAuth.getInstance(firebase)
        if (auth.currentUser == null) {
            Tasks.await(auth.signInAnonymously(), 2, TimeUnit.SECONDS)
        }
        checkCurrent()
        val client = AuthenticatedVisionApiClient(settings.getProperty("apiBaseUrl"), "0.1.0",
            FirebaseIdTokenProvider(auth), FirebaseAppCheckTokenProvider(FirebaseAppCheck.getInstance(firebase)))
        val capture = BaselineCapture(sceneId, Instant.now(),
            UUID.randomUUID().toString(), encoded)
        val flow = BaselineAnalysisUseCase(executeRequest = { request ->
            checkCurrent()
            client.execute(request)
        })
        val result = flow.analyze(capture)
        checkCurrent()
        if (result !is BaselineRunResult.Completed) fail("画像解析に失敗しました。認証・通信・対象物を確認してください。")
        return BaselineImageReview(validity, tableGeometry, imageCapture, image, plan, BaselineFlowState.ReviewingBaseline(capture, result.analysis), input.anchor)
    }

    fun confirm(review: BaselineImageReview, objects: List<BaselineObject>, checkActive: () -> Unit): PreparedBaseline {
        fun checkCurrent() {
            checkActive()
            if (!review.validity.isCurrent) fail("撮影時のアンカーが失効しました。撮り直してください。")
        }
        checkCurrent()
        val result = extractor().use { engine ->
            BaselineAppearanceBuilder.build(review.reviewing.capture.sceneId, review.image, review.plan, objects, engine)
        }
        checkCurrent()
        val measured = (result as? BaselineAppearanceResult.Built)?.baseline
            ?: fail("物体の特徴を取得できません。保存せず撮り直してください。")
        val geometry = review.tableGeometry.copyFor(review.image.timestampNanos, review.validity.anchorId)
            ?: fail("画像と平面境界が一致しません。保存せず撮り直してください。")
        val boxes = objects.map { item ->
            val id = measured.objectIds[item.id]
                ?: fail("物体IDが一致しません。保存せず撮り直してください。")
            val box = BaselineCpuBoxMapper.map(review.image, review.plan, item.boundingBox)
                ?: fail("物体の画像座標が不正です。保存せず撮り直してください。")
            NativeImageBox(id, box.left.toDouble(), box.top.toDouble(),
                box.right.toDouble(), box.bottom.toDouble(), false)
        }
        val rays = review.imageCapture.project(NativeImageRecognition(
            review.imageCapture.epoch, review.image.timestampNanos, boxes, emptyList(),
        )) as? NativeImageProjection.Projected
            ?: fail("撮影画像から物体のレイを生成できません。保存せず撮り直してください。")
        val targets = try {
            NativeBaselineTargets.project(geometry, rays.detections)
        } catch (_: IllegalArgumentException) {
            fail("物体の投影情報が不正です。保存せず撮り直してください。")
        } catch (_: IllegalStateException) {
            fail("全物体を平面上へ投影できません。平面全体を映して撮り直してください。")
        }
        checkCurrent()
        return PreparedBaseline(measured, targets, SavedSceneComparison(
            measured.sceneId, measured.imageTimestampNanos, review.validity,
            review.reviewing.capture.image, objects,
        ), review.anchor, ConfirmedOrientationPolicy.create(measured.sceneId, objects, measured.objectIds))
    }

    fun compare(saved: PreparedBaseline, packet: BaselineCameraFrame, checkActive: () -> Unit): PreparedComparison {
        fun checkCurrent() {
            checkActive()
            if (!saved.comparison.validity.isCurrent || packet.validity !== saved.comparison.validity) {
                fail("保存時の追跡が失効しました。削除して撮り直してください。")
            }
        }
        checkCurrent()
        if (saved.comparison.hasAttempted) fail("この保存状態では比較済みです。削除して撮り直してください。")
        val input = prepare(packet, saved.measured.sceneId, ::checkCurrent)
        val rebase = NativeAnchorRebaseFactory.create(saved.anchor, input.anchor)
            ?: fail("保存時と比較時のアンカーを対応付けできません。撮り直してください。")
        // Freeze this frame's camera-to-saved-world mapping BEFORE asynchronous cloud work.
        val comparisonCapture = NativeImageCaptureFactory.capture(packet.frame,
            input.imageCapture.epoch, input.imageCapture.observedAtMs, rebase)
            ?: fail("比較画像の投影情報を取得できません。撮り直してください。")
        val encoded = (VlmJpegEncoder().encode(input.image, input.plan) as? VlmJpegEncodingResult.Encoded)?.image
            ?: fail("比較画像を生成できません。")
        checkCurrent()
        val settings = settings()
        val app = firebase(settings)
        val client = AuthenticatedVisionApiClient(
            settings.getProperty("apiBaseUrl"), "0.1.0",
            FirebaseIdTokenProvider(FirebaseAuth.getInstance(app)),
            FirebaseAppCheckTokenProvider(FirebaseAppCheck.getInstance(app)),
            transport = UrlConnectionVisionHttpTransport(),
            maximumResponseBytes = 64_000,
        )
        val result = saved.comparison.execute(input.validity, input.image.timestampNanos,
            encoded, ::checkCurrent) { request ->
            checkCurrent()
            client.execute(request)
        }
        checkCurrent()
        val analysis = when (result) {
            is SavedCompareResult.Compared -> result.analysis
            is SavedCompareResult.TransportFailure ->
                fail("比較APIの認証・通信に失敗しました。自動再送はしません。削除して撮り直してください。")
            SavedCompareResult.InvalidCapture -> fail("比較画像と保存状態が一致しません。撮り直してください。")
            SavedCompareResult.InvalidResponse -> fail("比較結果が契約に適合しません。削除して撮り直してください。")
            SavedCompareResult.AlreadyAttempted -> fail("この保存状態では比較済みです。削除して撮り直してください。")
        }
        checkCurrent()
        val measured = extractor().use { engine ->
            CompareAppearanceBuilder.build(saved.measured, analysis, input.image, input.plan, engine, ::checkCurrent)
        }
        checkCurrent()
        val measurements = (measured as? CompareAppearanceResult.Built)?.measurements
            ?: fail("比較画像の物体特徴を確定できません。対応を保留し、削除して撮り直してください。")
        val candidates = measurements.objects.map { item ->
            val box = item.box
            NativeComparedBox(item.claimedSavedId, NativeImageBox(item.currentId,
                box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble(), false))
        }
        val projected = NativeComparisonProjector.project(measurements.sceneId,
            measurements.imageTimestampNanos, comparisonCapture, saved.targets, candidates)
            as? NativeComparisonProjection.Projected
            ?: fail("比較画像と候補の座標情報が一致しません。撮り直してください。")
        checkCurrent()
        return PreparedComparison(analysis, measurements, projected.distances)
    }

    fun verify(
        saved: PreparedBaseline, packet: BaselineCameraFrame,
        pipeline: NativeGuidancePipeline, checkActive: () -> Unit,
    ): CameraVerificationOutcome {
        fun checkCurrent() {
            checkActive()
            if (!saved.comparison.validity.isCurrent || packet.validity !== saved.comparison.validity ||
                pipeline.epoch.sceneId != saved.measured.sceneId
            ) fail("最終確認の保存状態・追跡が失効しました。成功にはしません。")
        }
        checkCurrent()
        if (saved.comparison.verificationAttempts >= 3) fail("最終確認の上限に達しました。手動で確認してください。")
        val input = prepare(packet, saved.measured.sceneId, ::checkCurrent)
        val encoded = (VlmJpegEncoder().encode(input.image, input.plan) as? VlmJpegEncodingResult.Encoded)?.image
            ?: fail("最終確認用の画像を生成できません。")
        val settings = settings()
        val app = firebase(settings)
        val client = AuthenticatedVisionApiClient(settings.getProperty("apiBaseUrl"), "0.1.0",
            FirebaseIdTokenProvider(FirebaseAuth.getInstance(app)),
            FirebaseAppCheckTokenProvider(FirebaseAppCheck.getInstance(app)),
            transport = UrlConnectionVisionHttpTransport(), maximumResponseBytes = 64_000)
        checkCurrent()
        val ticket = try {
            pipeline.beginVerification(pipeline.epoch)
        } catch (_: RuntimeException) {
            fail("局所完了の状態を確認できません。ガイドを再開してください。")
        }
        var completed = false
        try {
            val request = saved.comparison.reserveVerification(input.validity, input.image.timestampNanos, encoded)
                ?: fail("最終確認画像が無効、または試行上限です。成功にはしません。")
            val result = ExecuteFinalVerificationUseCase(executeRequest = {
                checkCurrent()
                client.execute(it)
            }).execute(request)
            checkCurrent()
            val verdict = NativeVerificationDecisionMapper(saved.measured.objectIds).map(result)
            val state = pipeline.completeVerification(pipeline.epoch, ticket, verdict)
            completed = true
            checkCurrent()
            return CameraVerificationOutcome(state, verdict === NativeVerificationResult.Unavailable)
        } finally {
            if (!completed) {
                // Cancellation, exceptions and revoked captures never leave a pending success.
                runCatching { pipeline.completeVerification(pipeline.epoch, ticket, NativeVerificationResult.Unavailable) }
            }
        }
    }

    /** Creates a lazy source; model creation/inference/close stay on the detector worker. */
    fun movementEvidence(saved: PreparedBaseline, compared: PreparedComparison): CpuImageEvidenceSource {
        if (!saved.comparison.validity.isCurrent ||
            saved.measured.sceneId != compared.measurements.sceneId ||
            compared.measurements.imageTimestampNanos <= saved.measured.imageTimestampNanos ||
            compared.measurements.objects.any { it.claimedSavedId !in saved.measured.objectIds.values }
        ) fail("比較と保存状態が一致しません。撮り直してください。")
        val semantics = try {
            CompareFrameSemantics(compared.measurements, saved.orientationPolicy)
        } catch (_: IllegalArgumentException) {
            fail("比較候補がない、または重なっています。ガイドを開始できません。")
        }
        return saved.measured.createEvidenceSource(semantics) {
            MediaPipeAppearanceExtractor.open(context, MODEL_ASSET, MODEL_SHA256)
        }
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
                val bytes = input.readBaselineSettings(8192)
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
        val bytes = context.assets.open("google-services.json").use { it.readBaselineSettings(65536) }
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

    private fun fail(message: String): Nothing = throw BaselineCaptureRejected(message)

    companion object {
        const val MODEL_ASSET = "models/mobilenet_v3_small.tflite"
        const val MODEL_SHA256 = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40"
        private const val FIREBASE_NAME = "modose-baseline"
        private val FIREBASE_LOCK = Any()
    }
}

internal fun java.io.InputStream.readBaselineSettings(maximumBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > maximumBytes - output.size()) {
            throw BaselineCaptureRejected("設定ファイルがサイズ制限を超えています。")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

