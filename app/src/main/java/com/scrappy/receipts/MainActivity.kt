package com.scrappy.receipts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
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

    /** Separate client so re-reading a still never contends with the live analyzer. */
    private val stillRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private lateinit var stillExecutor: ExecutorService
    private val accumulator = ReceiptAccumulator()
    private val autoCapture = AutoCapture()

    private var imageCapture: ImageCapture? = null
    private var latest: ReceiptAccumulator.Stable? = null
    private var cameraStarted = false
    private var autoCaptureEnabled = true
    private var captureInFlight = false
    private var pendingCapture: String? = null

    /**
     * Without this, a capture that never calls back leaves [captureInFlight] stuck
     * and both the button and auto-capture go silently dead until a restart.
     */
    private val captureWatchdog = Runnable {
        if (pendingCapture != null) {
            Log.w(TAG, "Capture timed out")
            pendingCapture = null
            captureInFlight = false
            autoCapture.rearm()
            toast("Capture timed out — try again")
        }
    }

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
        stillExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener { capture() }
        binding.btnAuto.setOnClickListener {
            autoCaptureEnabled = !autoCaptureEnabled
            autoCapture.rearm()
            binding.btnAuto.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (autoCaptureEnabled) R.color.accent else R.color.text_dim
                )
            )
            if (!autoCaptureEnabled) binding.overlay.setFrame(null, 0f)
        }
        binding.btnSaved.setOnClickListener {
            startActivity(Intent(this, ReceiptsActivity::class.java))
        }
        // Measurement harness: does ML Kit read a tight, full-resolution crop?
        binding.btnSaved.setOnLongClickListener {
            startActivity(Intent(this, CropProbeActivity::class.java))
            true
        }
        binding.btnGrant.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        stillExecutor.shutdown()
        binding.root.removeCallbacks(captureWatchdog)
        recognizer.close()
        stillRecognizer.close()
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

        // Read chroma here, on the analysis thread, while the frame is still open.
        val saturation = SaturationSampler.from(mediaImage, rotation)

        recognizer.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { text ->
                onTextRecognised(text, saturation, rotation, frameWidth, frameHeight)
            }
            .addOnFailureListener { Log.w(TAG, "OCR failed", it) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Runs on the main thread — ML Kit posts callbacks there by default. */
    private fun onTextRecognised(
        text: Text,
        saturation: SaturationSampler?,
        rotation: Int,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (isDestroyed || isFinishing) return

        val rotated = rotation == 90 || rotation == 270
        val sourceWidth = if (rotated) frameHeight else frameWidth
        val sourceHeight = if (rotated) frameWidth else frameHeight

        // Receipts are single-column, so top-to-bottom is a good enough reading order.
        val scanned = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                ScannedLine(
                    text = line.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    saturation = saturation?.meanSaturation(box) ?: 0
                )
            }
            .sortedWith(compareBy({ it.top }, { it.left }))

        // Everything in frame gets read; only what looks like the receipt gets parsed.
        val classified = FrameFilter.classify(scanned)
        val receiptLines = classified.filter { it.isReceipt }

        binding.overlay.update(
            classified.map { line ->
                BoxOverlay.Box(
                    Rect(line.left, line.top, line.right, line.bottom),
                    when {
                        !line.isReceipt -> BoxOverlay.Style.REJECTED
                        ReceiptParser.moneyIn(line.text).isNotEmpty() -> BoxOverlay.Style.MONEY
                        else -> BoxOverlay.Style.TEXT
                    }
                )
            },
            sourceWidth,
            sourceHeight
        )

        val stable = accumulator.push(ReceiptParser.parse(receiptLines.map { it.text }))
        latest = stable

        var hint: String? = null
        if (autoCaptureEnabled && !captureInFlight) {
            val verdict = autoCapture.observe(
                receiptLines, sourceWidth, sourceHeight, stable.confidence
            )
            hint = verdict.hint
            binding.overlay.setFrame(
                verdict.bounds?.let { Rect(it.left, it.top, it.right, it.bottom) },
                verdict.progress
            )
            if (verdict.fire) {
                binding.overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                capture()
            }
        }

        render(stable, receiptLines.size, hint)
    }

    private fun render(stable: ReceiptAccumulator.Stable, lineCount: Int, hint: String?) {
        val receipt = stable.receipt

        binding.statusPill.text = when {
            hint != null -> hint
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
        if (captureInFlight) return

        val id = UUID.randomUUID().toString()
        captureInFlight = true
        pendingCapture = id
        // Nothing downstream is allowed to strand the shutter closed.
        binding.root.postDelayed(captureWatchdog, CAPTURE_TIMEOUT_MS)
        toast("Captured — re-reading…")

        val photo = File(ReceiptStore.imageDir(this), "$id.jpg")
        val capture = imageCapture
        if (capture == null) {
            persist(id, null, stable.receipt)
            return
        }

        try {
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(photo).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        rescanThenPersist(id, photo, stable.receipt)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // The parse is the valuable part — keep it even without the photo.
                        Log.w(TAG, "Photo capture failed", exception)
                        persist(id, null, stable.receipt)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "takePicture threw", e)
            persist(id, null, stable.receipt)
        }
    }

    /**
     * The preview parse comes from a ~720p analysis frame; the still we just wrote
     * is full sensor resolution. On a long or small-print receipt that is several
     * times more pixels per character, which is the difference between reading the
     * total and guessing it — so read the good image before saving.
     */
    private fun rescanThenPersist(id: String, photo: File, previewParse: ParsedReceipt) {
        stillExecutor.execute {
            val image = try {
                InputImage.fromFilePath(this, Uri.fromFile(photo))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't reopen the still", e)
                runOnUiThread { persist(id, photo.absolutePath, previewParse) }
                return@execute
            }

            stillRecognizer.process(image)
                .addOnSuccessListener { text ->
                    val fromStill = parseRecognised(text)
                    // A blurred still can be worse than the frames we averaged;
                    // keep whichever actually read more of the receipt.
                    val best = if (fromStill.quality() >= previewParse.quality()) {
                        fromStill
                    } else {
                        previewParse
                    }
                    persist(id, photo.absolutePath, best)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Full-resolution OCR failed", error)
                    persist(id, photo.absolutePath, previewParse)
                }
        }
    }

    /**
     * Same pipeline the live path uses, minus the colour rule — a JPEG has no
     * chroma planes to sample, so only the type-size filter applies here.
     */
    private fun parseRecognised(text: Text): ParsedReceipt {
        val lines = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                ScannedLine(line.text, box.left, box.top, box.right, box.bottom)
            }
            .sortedWith(compareBy({ it.top }, { it.left }))

        val kept = FrameFilter.classify(lines).filter { it.isReceipt }
        return ReceiptParser.parse(kept.map { it.text })
    }

    /** Fields found first, then item detail, then sheer volume of text. */
    private fun ParsedReceipt.quality(): Int =
        filledFields * 10_000 + items.size * 100 + (rawText.length / 50).coerceAtMost(99)

    private fun persist(id: String, imagePath: String?, receipt: ParsedReceipt) {
        // The watchdog may have already given up on this one.
        if (pendingCapture != id) return
        pendingCapture = null
        binding.root.removeCallbacks(captureWatchdog)

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
        autoCapture.rearm()
        captureInFlight = false
        toast("Saved ${receipt.merchant ?: "receipt"} · ${formatMoney(receipt.total)}")
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ReceiptSnap"

        /** Generous: full-resolution OCR is slow on entry-level hardware. */
        const val CAPTURE_TIMEOUT_MS = 20_000L
    }
}
