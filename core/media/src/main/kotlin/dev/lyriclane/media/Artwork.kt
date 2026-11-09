package dev.lyriclane.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object Artwork {

    /**
     * Album art travels to the car inside MediaMetadata, which crosses a Binder
     * transaction with a hard size limit of roughly a megabyte shared with
     * everything else in the parcel. Spotify hands out art up to 640 px, so it
     * is scaled down and encoded as JPEG rather than PNG: a 512 px JPEG is tens
     * of kilobytes where the PNG would be hundreds.
     */
    private const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 88

    fun toBytes(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longestEdge <= MAX_EDGE_PX) {
            bitmap
        } else {
            val ratio = MAX_EDGE_PX.toFloat() / longestEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        }
    }
}
