package com.tazkia.ai.blurfilter.ml

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.tazkia.ai.blurfilter.utils.ImageUtils
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class FaceDetector(private val interpreter: Interpreter) {

    companion object {
        private const val INPUT_SIZE = 128
        private const val MAX_DETECTIONS = 896
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val IOU_THRESHOLD = 0.3f
    }

    data class Detection(
        val rect: RectF,
        val confidence: Float,
        val id: String = ""
    )

    /**
     * Detect faces in bitmap
     */
    fun detectFaces(bitmap: Bitmap): List<Detection> {
        // Prepare input
        val inputBuffer = ImageUtils.bitmapToByteBuffer(
            bitmap,
            INPUT_SIZE,
            isQuantized = true
        )

        // Prepare output buffers
        // BlazeFace outputs: [1, 896, 16] for boxes and [1, 896, 1] for scores
        val outputLocations = Array(1) { Array(MAX_DETECTIONS) { FloatArray(16) } }
        val outputScores = Array(1) { Array(MAX_DETECTIONS) { FloatArray(1) } }

        val outputs = HashMap<Int, Any>()
        outputs[0] = outputLocations
        outputs[1] = outputScores

        // Run inference
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        // Parse results
        val detections = mutableListOf<Detection>()

        for (i in 0 until MAX_DETECTIONS) {
            val score = outputScores[0][i][0]

            if (score > CONFIDENCE_THRESHOLD) {
                val locations = outputLocations[0][i]

                // Extract bounding box (normalized coordinates)
                val yMin = locations[0]
                val xMin = locations[1]
                val yMax = locations[2]
                val xMax = locations[3]

                // Convert to pixel coordinates
                val rect = RectF(
                    xMin * bitmap.width,
                    yMin * bitmap.height,
                    xMax * bitmap.width,
                    yMax * bitmap.height
                )

                // Expand box to include more context
                expandRect(rect, bitmap.width, bitmap.height, 1.3f)

                detections.add(Detection(rect, score, generateId(rect)))
            }
        }

        // Apply Non-Maximum Suppression
        return nonMaxSuppression(detections, IOU_THRESHOLD)
    }

    /**
     * Expand rectangle by scale factor
     */
    private fun expandRect(rect: RectF, maxWidth: Int, maxHeight: Int, scale: Float) {
        val width = rect.width()
        val height = rect.height()
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        val newWidth = width * scale
        val newHeight = height * scale

        rect.left = max(0f, centerX - newWidth / 2)
        rect.top = max(0f, centerY - newHeight / 2)
        rect.right = min(maxWidth.toFloat(), centerX + newWidth / 2)
        rect.bottom = min(maxHeight.toFloat(), centerY + newHeight / 2)
    }

    /**
     * Non-Maximum Suppression to remove overlapping detections
     */
    private fun nonMaxSuppression(
        detections: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()

        for (detection in sorted) {
            var shouldSelect = true

            for (selectedDetection in selected) {
                if (calculateIoU(detection.rect, selectedDetection.rect) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }

            if (shouldSelect) {
                selected.add(detection)
            }
        }

        return selected
    }

    /**
     * Calculate Intersection over Union
     */
    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersectLeft = max(rect1.left, rect2.left)
        val intersectTop = max(rect1.top, rect2.top)
        val intersectRight = min(rect1.right, rect2.right)
        val intersectBottom = min(rect1.bottom, rect2.bottom)

        if (intersectRight < intersectLeft || intersectBottom < intersectTop) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val rect1Area = rect1.width() * rect1.height()
        val rect2Area = rect2.width() * rect2.height()
        val unionArea = rect1Area + rect2Area - intersectArea

        return intersectArea / unionArea
    }

    /**
     * Generate unique ID for face based on position
     */
    private fun generateId(rect: RectF): String {
        return "${rect.centerX().toInt()}_${rect.centerY().toInt()}_${rect.width().toInt()}"
    }
}