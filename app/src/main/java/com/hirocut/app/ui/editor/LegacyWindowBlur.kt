package com.hirocut.app.ui.editor

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.exp

/** API29/30 fallback: PixelCopy + a separable Gaussian, off the UI thread. */
suspend fun captureWindowSnapshot(activity: Activity): Bitmap? {
    val width = minOf(400, activity.window.decorView.width)
    val height = (activity.window.decorView.height.toLong() * width / activity.window.decorView.width.coerceAtLeast(1)).toInt()
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val captured = suspendCancellableCoroutine<Bitmap?> { continuation ->
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (continuation.isActive && result == PixelCopy.SUCCESS) continuation.resume(bitmap)
                else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    } ?: return null
    return captured
}

internal fun gaussianBlur(bitmap: Bitmap): Bitmap {
    val width = bitmap.width; val height = bitmap.height
    val source = IntArray(width * height); val temp = IntArray(source.size); val output = IntArray(source.size)
    bitmap.getPixels(source, 0, width, 0, 0, width, height)
    val radius = 8
    val weights = FloatArray(radius * 2 + 1) { i -> exp(-((i - radius) * (i - radius)) / 32.0).toFloat() }
    val sum = weights.sum(); for (i in weights.indices) weights[i] /= sum
    fun pass(input: IntArray, target: IntArray, horizontal: Boolean) {
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0f; var green = 0f; var blue = 0f
            for (k in -radius..radius) {
                val xx = if (horizontal) (x + k).coerceIn(0, width - 1) else x
                val yy = if (horizontal) y else (y + k).coerceIn(0, height - 1)
                val pixel = input[yy * width + xx]; val weight = weights[k + radius]
                red += (pixel shr 16 and 255) * weight; green += (pixel shr 8 and 255) * weight; blue += (pixel and 255) * weight
            }
            target[y * width + x] = (255 shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
        }
    }
    pass(source, temp, true); pass(temp, output, false)
    bitmap.setPixels(output, 0, width, 0, 0, width, height)
    return bitmap
}
