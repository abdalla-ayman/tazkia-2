package com.tazkia.ai.blurfilter.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import java.nio.ByteBuffer
import kotlin.math.sqrt

object ImageUtils {

    /**
     * Resize bitmap to target width while maintaining aspect ratio
     */
    fun resizeBitmap(bitmap: Bitmap, targetWidth: Int): Bitmap {
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeight = (targetWidth * aspectRatio).toInt()

        return Bitmap.createScaledBitmap(
            bitmap,
            targetWidth,
            targetHeight,
            true
        )
    }

    /**
     * Apply RenderScript blur (GPU-accelerated, fastest option)
     */
    fun applyRenderScriptBlur(rs: RenderScript, bitmap: Bitmap, radius: Int): Bitmap {
        val input = Allocation.createFromBitmap(rs, bitmap)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        try {
            script.setRadius(radius.coerceIn(1, 25).toFloat())
            script.setInput(input)
            script.forEach(output)
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            output.copyTo(result)
            return result
        } finally {
            input.destroy()
            output.destroy()
            script.destroy()
        }
    }
    /**
     * Apply pixelation effect (Alternative to blur)
     */
// In ImageUtils.kt
    fun applyPixelation(bitmap: Bitmap, pixelSize: Int): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // FIX: Use bitmap.width and bitmap.height
        val smallWidth = Math.max(1, bitmap.width / pixelSize)
        val smallHeight = Math.max(1, bitmap.height / pixelSize)

        val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, false)
        canvas.drawBitmap(small, null, Rect(0, 0, bitmap.width, bitmap.height), paint)
        small.recycle()
        return result
    }
    /**
     * Calculate perceptual hash for motion detection
     */
    fun calculateHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)

        val grayscale = pixels.map { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        val avg = grayscale.average()
        var hash = 0L

        grayscale.forEachIndexed { index, value ->
            if (value > avg) {
                hash = hash or (1L shl index)
            }
        }

        small.recycle()
        return hash
    }

    /**
     * Calculate Hamming distance between two hashes
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        var xor = hash1 xor hash2
        var count = 0

        while (xor != 0L) {
            count += (xor and 1L).toInt()
            xor = xor shr 1
        }

        return count
    }

    /**
     * Check if there's significant motion between frames
     */
    fun hasSignificantMotion(
        hash1: Long,
        hash2: Long,
        threshold: Int = 5
    ): Boolean {
        return hammingDistance(hash1, hash2) > threshold
    }

    /**
     * Convert bitmap to ByteBuffer for TFLite
     */
    fun bitmapToByteBuffer(
        bitmap: Bitmap,
        inputSize: Int,
        isQuantized: Boolean = true
    ): ByteBuffer {
        val buffer = if (isQuantized) {
            ByteBuffer.allocateDirect(inputSize * inputSize * 3)
        } else {
            ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        }

        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            if (isQuantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                buffer.putFloat((r - 127.5f) / 127.5f)
                buffer.putFloat((g - 127.5f) / 127.5f)
                buffer.putFloat((b - 127.5f) / 127.5f)
            }
        }

        scaled.recycle()
        buffer.rewind()
        return buffer
    }

    /**
     * Crop region from bitmap
     */
    fun cropRegion(bitmap: Bitmap, rect: Rect): Bitmap {
        val x = rect.left.coerceAtLeast(0)
        val y = rect.top.coerceAtLeast(0)
        val width = rect.width().coerceAtMost(bitmap.width - x)
        val height = rect.height().coerceAtMost(bitmap.height - y)

        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    /**
     * Calculate Euclidean distance between two points
     */
    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = (x2 - x1).toFloat()
        val dy = (y2 - y1).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}