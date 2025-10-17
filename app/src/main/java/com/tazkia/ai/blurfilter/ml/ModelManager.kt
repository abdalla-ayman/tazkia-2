package com.tazkia.ai.blurfilter.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Manages ML models for full body detection and gender classification
 *
 * ARCHITECTURE:
 * - MediaPipe Pose: Full body detection with 33 landmarks
 * - TensorFlow Lite: Gender classification from full body images
 *
 * DETECTION STRATEGY:
 * 1. MediaPipe detects body pose and landmarks (~20-50ms)
 * 2. Calculate bounding box from landmarks
 * 3. Classify gender from full body region (~100ms)
 * 4. Total: ~150ms per person
 */
class ModelManager(private val context: Context) {

    private var bodyDetector: BodyDetectorMediaPipe? = null
    private var genderClassifierInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    companion object {
        // MediaPipe Pose models (choose one):
        // - "pose_landmarker_lite.task" (Fast, ~20ms, good for real-time)
        // - "pose_landmarker_full.task" (Balanced, ~30ms)
        // - "pose_landmarker_heavy.task" (Accurate, ~50ms, better detection)

        // Gender classification model (you must provide)
        // Train on full-body images dataset
        private const val GENDER_CLASSIFIER_MODEL = "mobilenetv3_gender_fullbody.tflite"

        const val GENDER_INPUT_SIZE = 224
    }

    /**
     * Initialize both models
     *
     * @param useGpu Enable GPU acceleration for gender classifier
     * @return true if initialization successful
     */
    fun initializeModels(useGpu: Boolean = true): Boolean {
        return try {
            // Initialize MediaPipe body detector
            bodyDetector = BodyDetectorMediaPipe(context)
            val mediaPipeSuccess = bodyDetector?.initialize() ?: false

            if (!mediaPipeSuccess) {
                return false
            }

            // Initialize TFLite gender classifier
            val options = Interpreter.Options()

            // Configure threading
            options.setNumThreads(4)

            // Try GPU acceleration
            if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            } else {
                options.setUseNNAPI(true)
            }

            // Load gender classifier
            genderClassifierInterpreter = Interpreter(
                loadModelFile(GENDER_CLASSIFIER_MODEL),
                options
            )

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Load TFLite model file from assets
     */
    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Get body detector
     */
    fun getBodyDetector(): BodyDetectorMediaPipe? = bodyDetector

    /**
     * Get gender classifier
     */
    fun getGenderClassifier(): Interpreter? = genderClassifierInterpreter

    /**
     * Release all resources
     */
    fun release() {
        bodyDetector?.close()
        genderClassifierInterpreter?.close()
        gpuDelegate?.close()

        bodyDetector = null
        genderClassifierInterpreter = null
        gpuDelegate = null
    }

    /**
     * Check if models are initialized
     */
    fun isInitialized(): Boolean {
        return bodyDetector != null && genderClassifierInterpreter != null
    }
}