package com.tazkia.ai.blurfilter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Full body detector using MediaPipe Pose Landmarker
 *
 * DETECTS:
 * - 33 body landmarks (nose to feet)
 * - Full body bounding box
 * - Visibility score for each landmark
 *
 * ADVANTAGES over face-only detection:
 * - Detects people even when face is not visible
 * - Better gender classification (body shape, clothing, posture)
 * - More robust to occlusion
 * - Works with side profiles, back views
 */
class BodyDetectorMediaPipe(private val context: Context) {

    private var detector: PoseLandmarker? = null

    companion object {
        private const val MODEL_NAME = "pose_landmarker_heavy.task" // or "pose_landmarker_lite.task" for faster
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f

        // MediaPipe Pose Landmarks indices
        const val NOSE = 0
        const val LEFT_EYE = 2
        const val RIGHT_EYE = 5
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }

    data class BodyDetection(
        val boundingBox: RectF,
        val landmarks: List<Landmark>,
        val confidence: Float,
        val id: String
    )

    data class Landmark(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float,
        val presence: Float
    )

    /**
     * Initialize MediaPipe pose detector
     */
    fun initialize(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .build()

            val options = PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setNumPoses(5) // Detect up to 5 people
                .setRunningMode(RunningMode.IMAGE)
                .build()

            detector = PoseLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Detect full bodies in image
     *
     * HOW IT WORKS:
     * 1. MediaPipe detects 33 landmarks per person
     * 2. We calculate bounding box from visible landmarks
     * 3. Return full body regions for gender classification
     *
     * @param bitmap Input image
     * @return List of detected bodies with bounding boxes
     */
    fun detectBodies(bitmap: Bitmap): List<BodyDetection> {
        val mpDetector = detector ?: return emptyList()

        return try {
            // Convert to MediaPipe image format
            val mpImage = BitmapImageBuilder(bitmap).build()

            // Run pose detection (~20-30ms for lite, ~50ms for heavy)
            val result = mpDetector.detect(mpImage)

            // Convert results to our format
            val detections = mutableListOf<BodyDetection>()

            result.landmarks().forEachIndexed { index, landmarkList ->
                // Convert MediaPipe landmarks to our format
                val landmarks = landmarkList.map { landmark ->
                    Landmark(
                        x = landmark.x() * bitmap.width,
                        y = landmark.y() * bitmap.height,
                        z = landmark.z(),
                        visibility = landmark.visibility().orElse(1.0f),
                        presence = landmark.presence().orElse(1.0f)
                    )
                }

                // Calculate bounding box from visible landmarks
                val boundingBox = calculateBoundingBox(landmarks, bitmap.width, bitmap.height)

                // Get confidence (average of key landmarks visibility)
                val confidence = calculateConfidence(landmarks)

                // Generate unique ID
                val id = generateId(boundingBox, index)

                detections.add(
                    BodyDetection(
                        boundingBox = boundingBox,
                        landmarks = landmarks,
                        confidence = confidence,
                        id = id
                    )
                )
            }

            detections

        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Calculate bounding box from body landmarks
     *
     * Strategy:
     * 1. Find min/max x,y from all VISIBLE landmarks
     * 2. Add padding (20% on each side)
     * 3. Ensure box doesn't go outside image
     */
    private fun calculateBoundingBox(
        landmarks: List<Landmark>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        // Filter only visible landmarks (visibility > 0.5)
        val visibleLandmarks = landmarks.filter { it.visibility > 0.5f }

        if (visibleLandmarks.isEmpty()) {
            // Fallback: use all landmarks
            return calculateBoundingBoxFromAll(landmarks, imageWidth, imageHeight)
        }

        // Find bounds
        val minX = visibleLandmarks.minOf { it.x }
        val maxX = visibleLandmarks.maxOf { it.x }
        val minY = visibleLandmarks.minOf { it.y }
        val maxY = visibleLandmarks.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        // Add 20% padding
        val paddingX = width * 0.2f
        val paddingY = height * 0.2f

        return RectF(
            max(0f, minX - paddingX),
            max(0f, minY - paddingY),
            min(imageWidth.toFloat(), maxX + paddingX),
            min(imageHeight.toFloat(), maxY + paddingY)
        )
    }

    /**
     * Fallback: calculate bounding box from all landmarks
     */
    private fun calculateBoundingBoxFromAll(
        landmarks: List<Landmark>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val minX = landmarks.minOf { it.x }
        val maxX = landmarks.maxOf { it.x }
        val minY = landmarks.minOf { it.y }
        val maxY = landmarks.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        val paddingX = width * 0.2f
        val paddingY = height * 0.2f

        return RectF(
            max(0f, minX - paddingX),
            max(0f, minY - paddingY),
            min(imageWidth.toFloat(), maxX + paddingX),
            min(imageHeight.toFloat(), maxY + paddingY)
        )
    }

    /**
     * Calculate detection confidence
     * Average visibility of key landmarks (shoulders, hips, face)
     */
    private fun calculateConfidence(landmarks: List<Landmark>): Float {
        if (landmarks.size < 33) return 0f

        val keyLandmarks = listOf(
            landmarks[NOSE],
            landmarks[LEFT_SHOULDER],
            landmarks[RIGHT_SHOULDER],
            landmarks[LEFT_HIP],
            landmarks[RIGHT_HIP]
        )

        return keyLandmarks.map { it.visibility }.average().toFloat()
    }

    /**
     * Generate unique ID for body
     */
    private fun generateId(rect: RectF, index: Int): String {
        return "${rect.centerX().toInt()}_${rect.centerY().toInt()}_${rect.width().toInt()}_$index"
    }

    /**
     * Get specific landmark by index
     */
    fun getLandmark(detection: BodyDetection, index: Int): Landmark? {
        return if (index < detection.landmarks.size) {
            detection.landmarks[index]
        } else null
    }

    /**
     * Check if person is facing camera (useful for filtering)
     */
    fun isFacingCamera(detection: BodyDetection): Boolean {
        val nose = getLandmark(detection, NOSE)
        val leftShoulder = getLandmark(detection, LEFT_SHOULDER)
        val rightShoulder = getLandmark(detection, RIGHT_SHOULDER)

        if (nose == null || leftShoulder == null || rightShoulder == null) return false

        // Check if nose is between shoulders (frontal view)
        val shoulderMidX = (leftShoulder.x + rightShoulder.x) / 2
        val deviation = kotlin.math.abs(nose.x - shoulderMidX)
        val shoulderWidth = kotlin.math.abs(leftShoulder.x - rightShoulder.x)

        return deviation < shoulderWidth * 0.3f
    }

    /**
     * Get body orientation (useful for classification)
     */
    fun getBodyOrientation(detection: BodyDetection): String {
        val leftShoulder = getLandmark(detection, LEFT_SHOULDER)
        val rightShoulder = getLandmark(detection, RIGHT_SHOULDER)
        val leftHip = getLandmark(detection, LEFT_HIP)
        val rightHip = getLandmark(detection, RIGHT_HIP)

        if (leftShoulder == null || rightShoulder == null ||
            leftHip == null || rightHip == null) {
            return "unknown"
        }

        // Calculate if left or right side is more visible
        val leftVisibility = (leftShoulder.visibility + leftHip.visibility) / 2
        val rightVisibility = (rightShoulder.visibility + rightHip.visibility) / 2

        return when {
            leftVisibility > rightVisibility + 0.2f -> "left_profile"
            rightVisibility > leftVisibility + 0.2f -> "right_profile"
            else -> "frontal"
        }
    }

    /**
     * Release resources
     */
    fun close() {
        detector?.close()
        detector = null
    }
}