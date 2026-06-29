package com.sherlock.app.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

object ExifHelper {

    fun extractExifData(context: Context, uri: Uri): Map<String, String> {
        val data = mutableMapOf<String, String>()

        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return data
            val exif = ExifInterface(inputStream)

            val tags = mapOf(
                "תאריך צילום" to ExifInterface.TAG_DATETIME_ORIGINAL,
                "יצרן מצלמה" to ExifInterface.TAG_MAKE,
                "דגם מצלמה" to ExifInterface.TAG_MODEL,
                "רוחב" to ExifInterface.TAG_IMAGE_WIDTH,
                "גובה" to ExifInterface.TAG_IMAGE_LENGTH,
                "ISO" to ExifInterface.TAG_ISO_SPEED_RATINGS,
                "צמצם" to ExifInterface.TAG_F_NUMBER,
                "חשיפה" to ExifInterface.TAG_EXPOSURE_TIME,
                "אורך מוקד" to ExifInterface.TAG_FOCAL_LENGTH,
                "פלאש" to ExifInterface.TAG_FLASH,
                "תוכנה" to ExifInterface.TAG_SOFTWARE,
                "כיוון" to ExifInterface.TAG_ORIENTATION,
                "זכויות יוצרים" to ExifInterface.TAG_COPYRIGHT,
                "אמן" to ExifInterface.TAG_ARTIST,
            )

            for ((label, tag) in tags) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    data[label] = value
                }
            }

            val lat = exif.latLong
            if (lat != null) {
                data["קו רוחב"] = "%.6f".format(lat[0])
                data["קו אורך"] = "%.6f".format(lat[1])
                data["מיקום (Google Maps)"] = "https://maps.google.com/?q=${lat[0]},${lat[1]}"
            }

            inputStream.close()
        } catch (_: Exception) {
        }

        return data
    }
}
