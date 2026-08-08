package com.scrappy.receipts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scrappy persistence: one JSON file plus a folder of JPEGs, all in app-private
 * storage. No database, no migrations.
 */
object ReceiptStore {

    private const val FILE_NAME = "receipts.json"

    fun imageDir(context: Context): File =
        File(context.filesDir, "shots").apply { mkdirs() }

    @Synchronized
    fun load(context: Context): MutableList<SavedReceipt> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return mutableListOf()

        return runCatching {
            val array = JSONArray(file.readText())
            val out = mutableListOf<SavedReceipt>()
            for (i in 0 until array.length()) {
                out += fromJson(array.getJSONObject(i))
            }
            // Newest first.
            out.sortByDescending { it.capturedAt }
            out
        }.getOrElse { mutableListOf() }
    }

    @Synchronized
    fun add(context: Context, receipt: SavedReceipt) {
        val all = load(context)
        all.add(0, receipt)
        persist(context, all)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val all = load(context)
        all.firstOrNull { it.id == id }?.imagePath?.let { path ->
            runCatching { File(path).delete() }
        }
        all.removeAll { it.id == id }
        persist(context, all)
    }

    fun find(context: Context, id: String): SavedReceipt? =
        load(context).firstOrNull { it.id == id }

    private fun persist(context: Context, all: List<SavedReceipt>) {
        val array = JSONArray()
        all.forEach { array.put(toJson(it)) }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }

    // --- (de)serialisation ------------------------------------------------

    private fun toJson(receipt: SavedReceipt): JSONObject {
        val items = JSONArray()
        receipt.parsed.items.forEach {
            items.put(JSONObject().put("label", it.label).put("amount", it.amount))
        }
        return JSONObject().apply {
            put("id", receipt.id)
            put("capturedAt", receipt.capturedAt)
            put("imagePath", receipt.imagePath ?: JSONObject.NULL)
            put("merchant", receipt.parsed.merchant ?: JSONObject.NULL)
            put("date", receipt.parsed.date ?: JSONObject.NULL)
            put("total", receipt.parsed.total ?: JSONObject.NULL)
            put("subtotal", receipt.parsed.subtotal ?: JSONObject.NULL)
            put("tax", receipt.parsed.tax ?: JSONObject.NULL)
            put("items", items)
            put("rawText", receipt.parsed.rawText)
        }
    }

    private fun fromJson(json: JSONObject): SavedReceipt {
        val items = mutableListOf<LineItem>()
        val array = json.optJSONArray("items") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            items += LineItem(item.optString("label"), item.optDouble("amount", 0.0))
        }
        return SavedReceipt(
            id = json.optString("id"),
            capturedAt = json.optLong("capturedAt"),
            imagePath = json.optStringOrNull("imagePath"),
            parsed = ParsedReceipt(
                merchant = json.optStringOrNull("merchant"),
                date = json.optStringOrNull("date"),
                total = json.optDoubleOrNull("total"),
                subtotal = json.optDoubleOrNull("subtotal"),
                tax = json.optDoubleOrNull("tax"),
                items = items,
                rawText = json.optString("rawText")
            )
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
}
