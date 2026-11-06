package com.photon.app.upload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.photon.app.core.ImageTypes
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The thumbnail and preview that go, encrypted, into the photo file (e2ee.md 6.1): WebP, longest side at most
 * 320 and 1,600 px, EXIF orientation applied, no metadata. The preview is empty when the original is no larger
 * than that. Same sizes and qualities as the reference client.
 */
object Thumbnails {
    const val THUMB_PX = 320
    const val PREVIEW_PX = 1600
    private const val THUMB_QUALITY = 70
    private const val PREVIEW_QUALITY = 80

    /** [width] and [height] are as a viewer shows the original, after its EXIF orientation. */
    class Images(val width: Int, val height: Int, val thumb: ByteArray, val preview: ByteArray)

    sealed interface Outcome {
        data class Ready(val images: Images) : Outcome

        /** Not decodable here, or over [ImageTypes.MAX_PIXELS]. */
        data class Refused(val reason: String) : Outcome
    }

    fun make(file: File): Outcome {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return Outcome.Refused("undecodable")
        if (w.toLong() * h > ImageTypes.MAX_PIXELS) return Outcome.Refused("too many pixels")

        val (rotation, flipped) = orientation(file)
        val longest = maxOf(w, h)
        val withPreview = longest > PREVIEW_PX
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, if (withPreview) PREVIEW_PX else THUMB_PX) }
        val decoded = try {
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return Outcome.Refused("undecodable")
        return try {
            val preview = if (withPreview) webp(decoded, rotation, flipped, PREVIEW_PX, PREVIEW_QUALITY) else ByteArray(0)
            val thumb = webp(decoded, rotation, flipped, THUMB_PX, THUMB_QUALITY)
            if (thumb.isEmpty()) return Outcome.Refused("no thumbnail")
            val quarterTurn = rotation == 90 || rotation == 270
            Outcome.Ready(Images(if (quarterTurn) h else w, if (quarterTurn) w else h, thumb, preview))
        } catch (e: OutOfMemoryError) {
            Outcome.Refused("undecodable")
        } finally {
            decoded.recycle()
        }
    }

    /** The largest power of two that keeps the decoded longest side at least [target]. */
    fun sampleSize(longest: Int, target: Int): Int {
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Rotation in degrees and whether to mirror first (androidx ExifInterface's convention). */
    private fun orientation(file: File): Pair<Int, Boolean> = try {
        val exif = ExifInterface(file)
        exif.rotationDegrees to exif.isFlipped
    } catch (e: Exception) {
        0 to false
    }

    private fun webp(src: Bitmap, rotation: Int, flipped: Boolean, maxSide: Int, quality: Int): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            if (rotation != 0) postRotate(rotation.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        try {
            // A new bitmap, compressed without EXIF: nothing of the original's metadata survives.
            return ByteArrayOutputStream().also { out.compress(webpFormat(), quality, it) }.toByteArray()
        } finally {
            if (out !== src) out.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}
