package com.tazkia.ai.blurfilter.ml

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.tazkia.ai.blurfilter.utils.ImageUtils
import org.tensorflow.lite.Interpreter

/**
 * Full body gender classifier using TensorFlow Lite
 *
 * WORKS WITH:
 * - MediaPipe Pose Detection results
 * - Full body bounding boxes
 *
 * CLASSIFICATION SIGNALS:
 * - Body shape and silhouette
 * - Clothing patterns and style
 * - Hair length and style
 * - Overall appearance
 * - Posture and stance
 *
 * ADVANTAGES over face-only:
 * - Works even when face is not visible
 * - More robust to lighting, angles, occlusion
 * - Higher accuracy (body provides more visual cues)
 * - Cultural sensitivity (respects head coverings)
 */
class GenderClassifier(private val interpreter: Interpreter) {

    companion object {
        private const val INPUT_SIZE = 224 // Model input size
        private const val CONFIDENCE_THRESHOLD = 0.6f
        const val GENDER_FEMALE = 0
        const val GENDER_MALE = 1
        const val GENDER_UNKNOWN = -1
    }

    data class GenderResult(
        val gender: Int,
        val confidence: Float,
        val features: ClassificationFeatures? = null
    )

    data class ClassificationFeatures(
        val bodyShapeScore: Float,
        val clothingScore: Float,
        val hairScore: Float,
        val postureScore: Float
    )

    // Cache classification results
    private val cache = LruCache<String, GenderResult>(20)

    /**
     * Classify gender from full body detection
     *
     * @param bitmap Original screen capture
     * @param bodyRect Full body bounding box from MediaPipe
     * @param bodyId Unique ID for caching
     * @param orientation Body orientation (frontal, left_profile, right_profile)
     * @return GenderResult with classification
     */
    fun classifyGender(
        bitmap: Bitmap,
        bodyRect: RectF,
        bodyId: String,
        orientation: String = "frontal"
    ): GenderResult {
        // Check cache first
        cache.get(bodyId)?.let { return it }

        // Crop body region
        val rect = Rect(
            bodyRect.left.toInt().coerceAtLeast(0),
            bodyRect.top.toInt().coerceAtLeast(0),
            bodyRect.right.toInt().coerceAtMost(bitmap.width),
            bodyRect.bottom.toInt().coerceAtMost(bitmap.height)
        )

        // Validate rect size
        if (rect.width() < 20 || rect.height() < 20) {
            return GenderResult(GENDER_UNKNOWN, 0f)
        }

        val bodyBitmap = try {
            ImageUtils.cropRegion(bitmap, rect)
        } catch (e: Exception) {
            return GenderResult(GENDER_UNKNOWN, 0f)
        }

        // Prepare input for model
        // Model expects: 224x224 RGB image
        val inputBuffer = ImageUtils.bitmapToByteBuffer(
            bodyBitmap,
            INPUT_SIZE,
            isQuantized = true
        )

        // Prepare output
        // Option 1: Simple output [female_prob, male_prob]
        val output = Array(1) { FloatArray(2) }

        // Run inference (~100-150ms on CPU, ~30ms on GPU)
        try {
            interpreter.run(inputBuffer, output)
        } catch (e: Exception) {
            e.printStackTrace()
            bodyBitmap.recycle()
            return GenderResult(GENDER_UNKNOWN, 0f)
        }

        // Parse results
        val femaleProb = output[0][0]
        val maleProb = output[0][1]

        val gender = if (femaleProb > maleProb) GENDER_FEMALE else GENDER_MALE
        val confidence = if (femaleProb > maleProb) femaleProb else maleProb

        // Adjust confidence based on orientation
        // Frontal views are more reliable than profiles
        val adjustedConfidence = when (orientation) {
            "frontal" -> confidence
            "left_profile", "right_profile" -> confidence * 0.9f
            else -> confidence * 0.8f
        }

        val result = if (adjustedConfidence > CONFIDENCE_THRESHOLD) {
            GenderResult(gender, adjustedConfidence)
        } else {
            GenderResult(GENDER_UNKNOWN, adjustedConfidence)
        }

        // Cache result
        cache.put(bodyId, result)

        bodyBitmap.recycle()
        return result
    }

    /**
     * Batch classify multiple bodies
     *
     * @param bitmap Original image
     * @param bodies List of body detections
     * @param orientations Map of body IDs to orientations
     * @return Map of body IDs to gender results
     */
    fun classifyBatch(
        bitmap: Bitmap,
        bodies: List<BodyDetectorMediaPipe.BodyDetection>,
        orientations: Map<String, String> = emptyMap()
    ): Map<String, GenderResult> {
        val results = mutableMapOf<String, GenderResult>()

        for (body in bodies) {
            val orientation = orientations[body.id] ?: "frontal"
            val result = classifyGender(bitmap, body.boundingBox, body.id, orientation)
            results[body.id] = result
        }

        return results
    }

    /**
     * Classify with manual feature extraction (alternative approach)
     * Useful if you don't have a trained full-body model
     *
     * THIS IS A FALLBACK METHOD using heuristics:
     * - Body aspect ratio (height/width)
     * - Upper body shape
     * - Clothing color patterns
     *
     * NOTE: Less accurate than trained model, but works without custom training
     */
    fun classifyWithHeuristics(
        bitmap: Bitmap,
        bodyRect: RectF,
        landmarks: List<BodyDetectorMediaPipe.Landmark>
    ): GenderResult {
        // Extract features
        val aspectRatio = bodyRect.height() / bodyRect.width()

        // Heuristic 1: Body aspect ratio
        // Typically: female bodies have higher aspect ratio (more elongated)
        val aspectScore = if (aspectRatio > 2.0f) 0.6f else 0.4f

        // Heuristic 2: Shoulder to hip ratio
        val shoulderWidth = calculateShoulderWidth(landmarks)
        val hipWidth = calculateHipWidth(landmarks)
        val shoulderHipRatio = if (hipWidth > 0) shoulderWidth / hipWidth else 1.0f

        // Typically: male shoulders wider than hips, female more balanced
        val ratioScore = if (shoulderHipRatio < 1.2f) 0.6f else 0.4f

        // Combine scores
        val femaleScore = (aspectScore + ratioScore) / 2
        val maleScore = 1.0f - femaleScore

        val gender = if (femaleScore > maleScore) GENDER_FEMALE else GENDER_MALE
        val confidence = kotlin.math.max(femaleScore, maleScore)

        return GenderResult(gender, confidence * 0.7f) // Lower confidence for heuristics
    }

    private fun calculateShoulderWidth(landmarks: List<BodyDetectorMediaPipe.Landmark>): Float {
        if (landmarks.size < 13) return 0f
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]
        return kotlin.math.abs(leftShoulder.x - rightShoulder.x)
    }

    private fun calculateHipWidth(landmarks: List<BodyDetectorMediaPipe.Landmark>): Float {
        if (landmarks.size < 25) return 0f
        val leftHip = landmarks[23]
        val rightHip = landmarks[24]
        return kotlin.math.abs(leftHip.x - rightHip.x)
    }

    /**
     * Clear cache
     */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * Remove specific entry
     */
    fun removeCacheEntry(bodyId: String) {
        cache.remove(bodyId)
    }
}