package com.studycheck.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import kotlin.math.max

/**
 * 上传前压缩：长边 1600、WebP q82。
 * 3MB 的相机原图压到 ~200KB，弱网也能传得动，同时省服务端 CPU。
 */
object PhotoCompressor {

    private const val MAX_EDGE = 1600
    private const val QUALITY = 82

    fun compress(source: File, target: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, opts)
            ?: return source

        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }

        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, QUALITY, out)
        }
        bitmap.recycle()
        source.delete()
        return target
    }
}
