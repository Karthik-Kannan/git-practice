package com.scrappy.receipts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scrappy.receipts.databinding.ActivityReceiptsBinding
import com.scrappy.receipts.databinding.ItemReceiptBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptsBinding
    private val receipts = mutableListOf<SavedReceipt>()
    private lateinit var adapter: ReceiptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ReceiptAdapter(receipts) { receipt ->
            startActivity(
                Intent(this, ReceiptDetailActivity::class.java)
                    .putExtra(ReceiptDetailActivity.EXTRA_ID, receipt.id)
            )
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        receipts.clear()
        receipts.addAll(ReceiptStore.load(this))
        adapter.notifyDataSetChanged()
        binding.empty.visibility = if (receipts.isEmpty()) View.VISIBLE else View.GONE

        supportActionBar?.subtitle = if (receipts.isEmpty()) null else {
            val sum = receipts.mapNotNull { it.parsed.total }.sum()
            "${receipts.size} scanned · ${formatMoney(sum)}"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

private class ReceiptAdapter(
    private val items: List<SavedReceipt>,
    private val onClick: (SavedReceipt) -> Unit
) : RecyclerView.Adapter<ReceiptAdapter.ViewHolder>() {

    private val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    class ViewHolder(val binding: ItemReceiptBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemReceiptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val receipt = items[position]
        with(holder.binding) {
            merchant.text = receipt.parsed.merchant ?: "Unknown merchant"
            meta.text = buildString {
                append(receipt.parsed.date ?: stamp.format(Date(receipt.capturedAt)))
                append(" · ")
                append(receipt.parsed.items.size)
                append(" items")
            }
            total.text = formatMoney(receipt.parsed.total)
            thumb.setImageBitmap(Bitmaps.decodeSampled(receipt.imagePath, 200))
            root.setOnClickListener { onClick(receipt) }
        }
    }
}
