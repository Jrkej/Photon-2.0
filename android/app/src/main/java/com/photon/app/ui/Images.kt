package com.photon.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The invite QR code: it encodes the invite link (Design.md "Joining and leaving"). */
object QrCode {
    fun bitmap(content: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = rememberLoaded(content) { QrCode.bitmap(content, 512).asImageBitmap() }
    bitmap?.let { Image(it, contentDescription = content, modifier = modifier) }
}

/**
 * A cached thumbnail or preview file. These were decrypted from another member's upload, which the server
 * can no longer screen (e2ee.md 8), so they are decoded defensively: bounds first, then downsampled to at
 * most [MAX_DECODE_PX], and anything that won't decode stays a placeholder.
 */
@Composable
fun FileImage(path: String?, modifier: Modifier = Modifier) {
    val bitmap = rememberLoaded(path) { path?.let { decodeBounded(it)?.asImageBitmap() } }
    Placeholder(bitmap, modifier)
}

private const val MAX_DECODE_PX = 2048

private fun decodeBounded(path: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_PX) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/** A gallery photo (one of ours), through the system thumbnail. */
@Composable
fun MediaImage(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = rememberLoaded(uri) { loadThumbnail(context, Uri.parse(uri))?.asImageBitmap() }
    Placeholder(bitmap, modifier)
}

/** Loads off the main thread whenever [key] changes; null until loaded. */
@Composable
private fun rememberLoaded(key: Any?, load: () -> ImageBitmap?): ImageBitmap? {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.IO) { load() }
    }
    return bitmap
}

@Composable
private fun Placeholder(bitmap: ImageBitmap?, modifier: Modifier) {
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    } else {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }
} catch (e: Exception) {
    null
}
