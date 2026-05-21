package com.lnsgroup.elise.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "EliseFaceScanner"

data class FaceResult(
    val name: String,
    val confidence: Float,
    val known: Boolean,
    val bbox: List<Int>,
)

/**
 * Scanner facial : CameraX front cam + ML Kit détection + serveur reconnaissance.
 * Usage :
 *   val scanner = EliseFaceScanner(context)
 *   scanner.start(lifecycleOwner, previewView) { result -> ... }
 *   scanner.stop()
 */
class EliseFaceScanner(private val ctx: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var onResult: ((List<FaceResult>) -> Unit)? = null
    private var lastSendMs = 0L
    private val sendIntervalMs = 1200L   // une reconnaissance max toutes les 1.2s

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(
        owner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        onFaceResult: (List<FaceResult>) -> Unit,
    ) {
        onResult = onFaceResult
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            cameraProvider = future.get()
            bindCamera(owner, previewView)
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        scope.cancel()
        faceDetector.close()
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun bindCamera(owner: LifecycleOwner, previewView: androidx.camera.view.PreviewView) {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build().apply {
                setAnalyzer(Executors.newSingleThreadExecutor(), ::analyzeFrame)
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysisUseCase,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind error: ${e.message}")
        }
    }

    // ── Analyse frame ─────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastSendMs > sendIntervalMs) {
                        lastSendMs = now
                        val bitmap = proxy.toBitmap()
                        if (bitmap != null) {
                            scope.launch(Dispatchers.IO) {
                                sendToServer(bitmap)
                            }
                        }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    // ── Envoi au serveur ──────────────────────────────────────────────────────

    private fun sendToServer(bitmap: Bitmap) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val jpegBytes = out.toByteArray()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "face.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/face/recognize")
            .header("Authorization", "Bearer $TOKEN")
            .post(body)
            .build()

        try {
            val response = http.newCall(request).execute()
            val text = response.body?.string() ?: return
            if (response.isSuccessful) {
                parseResult(text)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Server error: ${e.message}")
        }
    }

    private fun parseResult(json: String) {
        try {
            val obj = JSONObject(json)
            val facesArr = obj.optJSONArray("faces") ?: return
            val results = (0 until facesArr.length()).map { i ->
                val f = facesArr.getJSONObject(i)
                val bbox = f.optJSONArray("bbox")
                FaceResult(
                    name = f.optString("name", "inconnu"),
                    confidence = f.optDouble("confidence", 0.0).toFloat(),
                    known = f.optBoolean("known", false),
                    bbox = if (bbox != null)
                        List(bbox.length()) { j -> bbox.getInt(j) }
                    else emptyList(),
                )
            }
            scope.launch(Dispatchers.Main) { onResult?.invoke(results) }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    // ── Enrôlement ────────────────────────────────────────────────────────────

    fun enroll(name: String, bitmaps: List<Bitmap>, onDone: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            builder.addFormDataPart("name", name)
            bitmaps.forEachIndexed { i, bmp ->
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                builder.addFormDataPart(
                    "files", "face_$i.jpg",
                    out.toByteArray().toRequestBody("image/jpeg".toMediaType())
                )
            }
            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}/face/enroll")
                .header("Authorization", "Bearer $TOKEN")
                .post(builder.build())
                .build()
            try {
                val resp = http.newCall(request).execute()
                val msg = if (resp.isSuccessful) "Enrôlement réussi ✓" else "Erreur: ${resp.code}"
                withContext(Dispatchers.Main) { onDone(msg) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone("Erreur réseau: ${e.message}") }
            }
        }
    }
}

// Extension : ImageProxy → Bitmap
@androidx.camera.core.ExperimentalGetImage
private fun ImageProxy.toBitmap(): Bitmap? {
    val image = this.image ?: return null
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 80, out)
    return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
}
