package com.tazkia.ai.blurfilter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.max
import kotlin.math.min

class BodyDetectorMediaPipe(private val context: Context) {

    private var detector: ObjectDetector? = null

    companion object {
        private const val TAG = "BodyDetectorMediaPipe"
        private const val MODEL_NAME = "efficientdet_lite0.tflite"
        private const val MIN_DETECTION_CONFIDENCE = 0.4f
        private const val MAX_RESULTS = 5
    }

    data class BodyDetection(
        val boundingBox: RectF,
        val confidence: Float,
        val id: String
    )

    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing EfficientDet-Lite0 object detector...")

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .build()

            val options = com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(MIN_DETECTION_CONFIDENCE)
                .setMaxResults(MAX_RESULTS)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "✅ Object detector initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize detector", e)
            e.printStackTrace()
            false
        }
    }

    fun detectBodies(bitmap: Bitmap): List<BodyDetection> {
        val mpDetector = detector ?: run {
            Log.e(TAG, "Detector is null!")
            return emptyList()
        }

        return try {
            Log.d(TAG, "Starting detection on ${bitmap.width}x${bitmap.height} image")

            // CRITICAL FIX: Create a copy so MediaPipe doesn't recycle our bitmap
            val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // Convert to MediaPipe image format
            val mpImage = BitmapImageBuilder(bitmapCopy).build()

            // Run object detection
            val startTime = System.currentTimeMillis()
            val result = mpDetector.detect(mpImage)
            val detectionTime = System.currentTimeMillis() - startTime

            Log.d(TAG, "Detection completed in ${detectionTime}ms")

            // NOW it's safe to close - only the copy gets recycled
            mpImage.close()
            bitmapCopy.recycle()

            // Filter for "person" detections only
            val personDetections = result.detections().filter { detection ->
                detection.categories().any { it.categoryName().equals("person", ignoreCase = true) }
            }

            Log.d(TAG, "Found ${personDetections.size} person(s)")

            // Convert to our format
            personDetections.mapIndexed { index, detection ->
                val boundingBox = detection.boundingBox()

                val rect = RectF(
                    boundingBox.left.toFloat(),
                    boundingBox.top.toFloat(),
                    boundingBox.right.toFloat(),
                    boundingBox.bottom.toFloat()
                )

                // Add 10% padding to bounding box for better blur coverage
                val width = rect.width()
                val height = rect.height()
                val paddingX = width * 0.1f
                val paddingY = height * 0.1f

                val paddedRect = RectF(
                    max(0f, rect.left - paddingX),
                    max(0f, rect.top - paddingY),
                    min(bitmap.width.toFloat(), rect.right + paddingX),
                    min(bitmap.height.toFloat(), rect.bottom + paddingY)
                )

                val confidence = detection.categories()
                    .firstOrNull { it.categoryName().equals("person", ignoreCase = true) }
                    ?.score() ?: 0f

                val id = generateId(paddedRect, index)

                Log.d(TAG, "Person $index: bbox=$paddedRect, confidence=$confidence")

                BodyDetection(
                    boundingBox = paddedRect,
                    confidence = confidence,
                    id = id
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed!", e)
            e.printStackTrace()
            emptyList()
        }
    }

    private fun generateId(rect: RectF, index: Int): String {
        return "${rect.centerX().toInt()}_${rect.centerY().toInt()}_${rect.width().toInt()}_$index"
    }

    fun close() {
        detector?.close()
        detector = null
        Log.d(TAG, "Detector closed")
    }
}