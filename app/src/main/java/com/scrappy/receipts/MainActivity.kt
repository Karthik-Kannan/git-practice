package com.scrappy.receipts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scrappy.receipts.databinding.ActivityMainBinding
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live receipt reader: every camera frame goes through on-device OCR, gets parsed,
 * and is voted into a stable result you can freeze with one tap.
 */
@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val accumulator = ReceiptAccumulator()

    private var imageCapture: ImageCapture? = null
    private var latest: ReceiptAccumulator.Stable? = null
    private var cameraStarted = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionGate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener { capture() }
        binding.btnSaved.setOnClickListener {
            startActivity(Intent(this, ReceiptsActivity::class.java))
        }
        binding.btnGrant.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun showPermissionGate() {
        binding.permissionGate.visibility = View.VISIBLE
    }

    // --- camera ----------------------------------------------------------

    private fun startCamera() {
        binding.permissionGate.visibility = View.GONE
        if (cameraStarted) return
        cameraStarted = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: run {
                toast("Couldn't open the camera")
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            // 480p is too coarse for receipt print; ask for ~720p to analyse.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture
                )
            }.onFailure {
                Log.e(TAG, "Use case binding failed", it)
                toast("Couldn't start the camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val frameWidth = proxy.width
        val frameHeight = proxy.height

        recognizer.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { text -> onTextRecognised(text, rotation, frameWidth, frameHeight) }
            .addOnFailureListener { Log.w(TAG, "OCR failed", it) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Runs on the main thread — ML Kit posts callbacks there by default. */
    private fun onTextRecognised(text: Text, rotation: Int, frameWidth: Int, frameHeight: Int) {
        if (isDestroyed || isFinishing) return

        val rotated = rotation == 90 || rotation == 270
        val sourceWidth = if (rotated) frameHeight else frameWidth
        val sourceHeight = if (rotated) frameWidth else frameHeight

        // Receipts are single-column, so top-to-bottom is a good enough reading order.
        val lines = text.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))

        binding.overlay.update(
            lines.mapNotNull { line ->
                line.boundingBox?.let {
                    BoxOverlay.Box(it, ReceiptParser.moneyIn(line.text).isNotEmpty())
                }
            },
            sourceWidth,
            sourceHeight
        )

        val stable = accumulator.push(ReceiptParser.parse(lines.map { it.text }))
        latest = stable
        render(stable, lines.size)
    }

    private fun render(stable: ReceiptAccumulator.Stable, lineCount: Int) {
        val receipt = stable.receipt

        binding.statusPill.text = when {
            lineCount == 0 -> getString(R.string.scanning)
            else -> "Reading $lineCount lines"
        }

        binding.merchant.text = receipt.merchant ?: "—"
        binding.subline.text = buildString {
            append(receipt.date ?: "no date")
            append(" · ")
            append(receipt.items.size)
            append(if (receipt.items.size == 1) " item" else " items")
            receipt.tax?.let { append(" · tax ").append(formatMoney(it)) }
        }
        binding.total.text = formatMoney(receipt.total)

        val percent = (stable.confidence * 100).toInt()
        binding.lockLevel.text = "$percent%"
        binding.lockBar.progress = percent
        binding.btnCapture.isEnabled = !receipt.isEmpty
        binding.btnCapture.alpha = if (receipt.isEmpty) 0.5f else 1f
    }

    // --- capture ---------------------------------------------------------

    private fun capture() {
        val stable = latest
        if (stable == null || stable.receipt.isEmpty) {
            toast("Nothing to capture yet")
            return
        }

        val id = UUID.randomUUID().toString()
        val photo = File(ReceiptStore.imageDir(this), "$id.jpg")
        val capture = imageCapture

        if (capture == null) {
            persist(id, null, stable.receipt)
            return
        }

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photo).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    persist(id, photo.absolutePath, stable.receipt)
                }

                override fun onError(exception: ImageCaptureException) {
                    // The parse is the valuable part — keep it even without the photo.
                    Log.w(TAG, "Photo capture failed", exception)
                    persist(id, null, stable.receipt)
                }
            }
        )
    }

    private fun persist(id: String, imagePath: String?, receipt: ParsedReceipt) {
        ReceiptStore.add(
            this,
            SavedReceipt(
                id = id,
                capturedAt = System.currentTimeMillis(),
                imagePath = imagePath,
                parsed = receipt
            )
        )
        // Start clean so the next receipt doesn't inherit this one's votes.
        accumulator.reset()
        binding.overlay.clear()
        toast("Saved ${receipt.merchant ?: "receipt"} · ${formatMoney(receipt.total)}")
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ReceiptSnap"
    }
}
