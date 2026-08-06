package com.scrappy.receipts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Receipt photos are full-resolution JPEGs; never hand one straight to an ImageView. */
object Bitmaps {

    private val cache = HashMap<String, Bitmap>()

    fun decodeSampled(path: String?, maxEdge: Int): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val key = "$path@$maxEdge"
        cache[key]?.let { return it }

        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        if (cache.size > 40) cache.clear()
        cache[key] = bitmap
        return bitmap
    }

    fun forget(path: String?) {
        if (path == null) return
        cache.keys.filter { it.startsWith("$path@") }.forEach { cache.remove(it) }
    }
}
