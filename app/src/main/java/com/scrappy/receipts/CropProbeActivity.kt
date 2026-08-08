package com.scrappy.receipts

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scrappy.receipts.databinding.ActivityCropProbeBinding
import java.io.File
import java.io.FileInputStream

/**
 * A measurement harness, not a feature.
 *
 * Everything has been failing at whole-page OCR on a ~720p analysis frame. Before
 * committing to a different OCR engine, this answers the question that actually
 * matters: does ML Kit read a *tight crop decoded at full sensor resolution*?
 * Drag a box over a saved receipt, read it, and compare against reading the whole
 * frame. If the crop is clean, the engine was never the problem.
 */
class CropProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropProbeBinding
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val photos = mutableListOf<SavedReceipt>()
    private var index = 0
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var exifRotation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        photos += ReceiptStore.load(this).filter { receipt ->
            receipt.imagePath?.let { File(it).exists() } == true
        }

        if (photos.isEmpty()) {
            binding.results.text = getString(R.string.probe_no_photos)
            listOf(binding.btnPrev, binding.btnNext, binding.btnReadCrop, binding.btnReadFull)
                .forEach { it.isEnabled = false }
            return
        }

        binding.btnPrev.setOnClickListener { step(-1) }
        binding.btnNext.setOnClickListener { step(1) }
        binding.btnReadCrop.setOnClickListener { readCrop() }
        binding.btnReadFull.setOnClickListener { readWhole() }

        show()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun step(delta: Int) {
        index = (index + delta + photos.size) % photos.size
        show()
    }

    private fun show() {
        val path = photos[index].imagePath ?: return

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        sourceWidth = bounds.outWidth
        sourceHeight = bounds.outHeight
        exifRotation = rotationOf(path)

        val preview = Bitmaps.decodeSampled(path, 1400)
        if (preview == null) {
            binding.results.text = getString(R.string.probe_decode_failed)
            return
        }
        binding.image.setImage(preview, sourceWidth, sourceHeight)

        binding.header.text = getString(
            R.string.probe_header,
            index + 1,
            photos.size,
            sourceWidth,
            sourceHeight,
            exifRotation
        )
        binding.results.text = getString(R.string.probe_instructions)
    }

    private fun readCrop() {
        val region = binding.image.selectionInSource()
        if (region == null) {
            Toast.makeText(this, R.string.probe_drag_first, Toast.LENGTH_SHORT).show()
            return
        }
        recognise(region, "CROP")
    }

    private fun readWhole() {
        recognise(Rect(0, 0, sourceWidth, sourceHeight), "FULL FRAME")
    }

    /** Decodes just [region] from the JPEG at full resolution and OCRs it. */
    private fun recognise(region: Rect, label: String) {
        val path = photos[index].imagePath ?: return
        binding.results.text = getString(R.string.probe_reading)

        val megapixels = region.width().toLong() * region.height() / 1_000_000.0
        var sample = 1
        while (megapixels / (sample * sample) > MAX_MEGAPIXELS) sample *= 2

        val bitmap = try {
            @Suppress("DEPRECATION")
            val decoder = FileInputStream(path).use {
                BitmapRegionDecoder.newInstance(it, false)
            } ?: return
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            decoder.decodeRegion(region, options).also { decoder.recycle() }
        } catch (e: Exception) {
            Log.e(TAG, "Region decode failed", e)
            binding.results.text = getString(R.string.probe_decode_failed)
            return
        }

        if (bitmap == null) {
            binding.results.text = getString(R.string.probe_decode_failed)
            return
        }

        val startedAt = System.currentTimeMillis()
        recognizer.process(InputImage.fromBitmap(bitmap, exifRotation))
            .addOnSuccessListener { text ->
                val elapsed = System.currentTimeMillis() - startedAt
                val lines = text.textBlocks.flatMap { it.lines }
                val amounts = lines.flatMap { ReceiptParser.moneyIn(it.text) }

                binding.results.text = buildString {
                    append(label).append("  ")
                    append("${region.width()}×${region.height()} px")
                    if (sample > 1) append(" ÷$sample")
                    append("  ·  decoded ${bitmap.width}×${bitmap.height}")
                    append("\nOCR ${elapsed}ms · ${lines.size} lines · ")
                    append("${amounts.size} amounts ")
                    append(amounts.joinToString(", ") { formatMoney(it) })
                    append("\n────────────────\n")
                    append(
                        lines.joinToString("\n") { it.text }
                            .ifBlank { getString(R.string.probe_nothing_read) }
                    )
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Probe OCR failed", error)
                binding.results.text = getString(R.string.probe_ocr_failed)
            }
    }

    private fun rotationOf(path: String): Int = try {
        when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read EXIF", e)
        0
    }

    private companion object {
        const val TAG = "CropProbe"

        /** Keep a decoded region under this so a full-frame read can't OOM. */
        const val MAX_MEGAPIXELS = 12.0
    }
}
