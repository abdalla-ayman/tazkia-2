package com.tazkia.ai.blurfilter.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelManager(private val context: Context) {

    private var faceDetectorInterpreter: Interpreter? = null
    private var genderClassifierInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    companion object {
        private const val FACE_DETECTOR_MODEL = "blazeface.tflite"
        private const val GENDER_CLASSIFIER_MODEL = "mobilenetv3_gender.tflite"

        const val FACE_INPUT_SIZE = 128
        const val GENDER_INPUT_SIZE = 224
    }

    /**
     * Initialize models
     */
    fun initializeModels(useGpu: Boolean = true): Boolean {
        return try {
            val options = Interpreter.Options()

            // Configure threading
            options.setNumThreads(4)

            // Try GPU acceleration if requested and available
            if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            } else {
                // Use NNAPI as fallback
                options.setUseNNAPI(true)
            }

            // Load models
            faceDetectorInterpreter = Interpreter(
                loadModelFile(FACE_DETECTOR_MODEL),
                options
            )

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
     * Load model file from assets
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
     * Get face detector interpreter
     */
    fun getFaceDetector(): Interpreter? = faceDetectorInterpreter

    /**
     * Get gender classifier interpreter
     */
    fun getGenderClassifier(): Interpreter? = genderClassifierInterpreter

    /**
     * Release resources
     */
    fun release() {
        faceDetectorInterpreter?.close()
        genderClassifierInterpreter?.close()
        gpuDelegate?.close()

        faceDetectorInterpreter = null
        genderClassifierInterpreter = null
        gpuDelegate = null
    }

    /**
     * Check if models are initialized
     */
    fun isInitialized(): Boolean {
        return faceDetectorInterpreter != null && genderClassifierInterpreter != null
    }
}