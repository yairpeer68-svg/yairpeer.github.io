package com.sherlock.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

object ImageHashUtil {

    fun computeAverageHash(context: Context, uri: Uri): Long? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val small = Bitmap.createScaledBitmap(original, 8, 8, true)
            if (original != small) original.recycle()

            val grays = IntArray(64)
            var sum = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixel = small.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val gray = (r + g + b) / 3
                    grays[y * 8 + x] = gray
                    sum += gray
                }
            }
            small.recycle()
            val avg = sum / 64

            var hash = 0L
            for (i in 0 until 64) {
                if (grays[i] >= avg) hash = hash or (1L shl i)
            }
            hash
        } catch (_: Exception) {
            null
        }
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun similarityPercent(a: Long, b: Long): Int {
        val distance = hammingDistance(a, b)
        return ((64 - distance) * 100 / 64)
    }

    fun saveImageToInternalStorage(context: Context, uri: Uri, fileName: String): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val dir = java.io.File(context.filesDir, "image_hashes").apply { mkdirs() }
            val outFile = java.io.File(dir, fileName)
            input.use { inStream -> outFile.outputStream().use { outStream -> inStream.copyTo(outStream) } }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
