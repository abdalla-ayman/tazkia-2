package com.tazkia.ai.blurfilter.ml

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.tazkia.ai.blurfilter.utils.ImageUtils
import org.tensorflow.lite.Interpreter

class GenderClassifier(private val interpreter: Interpreter) {

    companion object {
        private const val INPUT_SIZE = 224
        private const val CONFIDENCE_THRESHOLD = 0.6f
        const val GENDER_FEMALE = 0
        const val GENDER_MALE = 1
        const val GENDER_UNKNOWN = -1
    }

    data class GenderResult(
        val gender: Int,
        val confidence: Float
    )

    // Cache classification results
    private val cache = LruCache<String, GenderResult>(20)

    /**
     * Classify gender from face region
     */
    fun classifyGender(bitmap: Bitmap, faceRect: RectF, faceId: String): GenderResult {
        // Check cache first
        cache.get(faceId)?.let { return it }

        // Crop face region
        val rect = Rect(
            faceRect.left.toInt(),
            faceRect.top.toInt(),
            faceRect.right.toInt(),
            faceRect.bottom.toInt()
        )

        val faceBitmap = try {
            ImageUtils.cropRegion(bitmap, rect)
        } catch (e: Exception) {
            return GenderResult(GENDER_UNKNOWN, 0f)
        }

        // Prepare input
        val inputBuffer = ImageUtils.bitmapToByteBuffer(
            faceBitmap,
            INPUT_SIZE,
            isQuantized = true
        )

        // Prepare output
        val output = Array(1) { FloatArray(2) } // [female_prob, male_prob]

        // Run inference
        interpreter.run(inputBuffer, output)

        // Parse result
        val femaleProb = output[0][0]
        val maleProb = output[0][1]

        val gender = if (femaleProb > maleProb) GENDER_FEMALE else GENDER_MALE
        val confidence = if (femaleProb > maleProb) femaleProb else maleProb

        val result = if (confidence > CONFIDENCE_THRESHOLD) {
            GenderResult(gender, confidence)
        } else {
            GenderResult(GENDER_UNKNOWN, confidence)
        }

        // Cache result
        cache.put(faceId, result)

        faceBitmap.recycle()
        return result
    }

    /**
     * Batch classify multiple faces
     */
    fun classifyBatch(
        bitmap: Bitmap,
        faces: List<FaceDetector.Detection>
    ): Map<String, GenderResult> {
        val results = mutableMapOf<String, GenderResult>()

        for (face in faces) {
            val result = classifyGender(bitmap, face.rect, face.id)
            results[face.id] = result
        }

        return results
    }

    /**
     * Clear cache
     */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * Remove specific entry from cache
     */
    fun removeCacheEntry(faceId: String) {
        cache.remove(faceId)
    }
}