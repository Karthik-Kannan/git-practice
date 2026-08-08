package com.scrappy.receipts

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scrappy.receipts.databinding.ActivityReceiptDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getStringExtra(EXTRA_ID)
        val receipt = id?.let { ReceiptStore.find(this, it) }
        if (receipt == null) {
            finish()
            return
        }

        render(receipt)

        binding.btnDelete.setOnClickListener {
            ReceiptStore.delete(this, receipt.id)
            Bitmaps.forget(receipt.imagePath)
            finish()
        }
    }

    private fun render(receipt: SavedReceipt) {
        val parsed = receipt.parsed

        val photo = Bitmaps.decodeSampled(receipt.imagePath, 1200)
        if (photo != null) binding.photo.setImageBitmap(photo) else binding.photo.visibility = View.GONE

        binding.merchant.text = parsed.merchant ?: "Unknown merchant"
        binding.meta.text = buildString {
            append(parsed.date ?: "no date on receipt")
            append("  ·  scanned ")
            append(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(receipt.capturedAt)))
        }

        parsed.items.forEach { addRow(it.label, formatMoney(it.amount), emphasise = false) }
        parsed.subtotal?.let { addRow("Subtotal", formatMoney(it), emphasise = false) }
        parsed.tax?.let { addRow("Tax", formatMoney(it), emphasise = false) }
        addRow("TOTAL", formatMoney(parsed.total), emphasise = true)

        binding.rawText.text = parsed.rawText
    }

    private fun addRow(label: String, value: String, emphasise: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = if (emphasise) 16f else 14f
            setTextColor(
                ContextCompat.getColor(
                    this@ReceiptDetailActivity,
                    if (emphasise) R.color.white else R.color.text_dim
                )
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(this).apply {
            text = value
            textSize = if (emphasise) 18f else 14f
            setTextColor(
                ContextCompat.getColor(
                    this@ReceiptDetailActivity,
                    if (emphasise) R.color.accent else R.color.white
                )
            )
        }

        row.addView(labelView)
        row.addView(valueView)
        binding.itemsContainer.addView(row)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "receipt_id"
    }
}
