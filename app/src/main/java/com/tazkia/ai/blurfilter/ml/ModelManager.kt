package com.tazkia.ai.blurfilter.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelManager(private val context: Context) {

    private var bodyDetector: BodyDetectorMediaPipe? = null
    private var genderClassifier: GenderClassifier? = null
    private var genderInterpreter: Interpreter? = null

    companion object {
        private const val TAG = "ModelManager"
        private const val GENDER_MODEL_NAME = "mobilenetv3_gender.tflite"
    }

    fun initializeModels(useGpu: Boolean = true): Boolean {
        return try {
            Log.d(TAG, "Initializing models...")

            // Initialize body detector (REQUIRED)
            bodyDetector = BodyDetectorMediaPipe(context)
            val bodySuccess = bodyDetector?.initialize() ?: false

            if (!bodySuccess) {
                Log.e(TAG, "Failed to initialize body detector")
                return false
            }
            Log.d(TAG, "✅ Body detector initialized")

            // Initialize gender classifier (OPTIONAL - don't fail if missing)
            try {
                genderInterpreter = loadModelFile(GENDER_MODEL_NAME, useGpu)
                if (genderInterpreter != null) {
                    genderClassifier = GenderClassifier(genderInterpreter!!)
                    Log.d(TAG, "✅ Gender classifier initialized")
                } else {
                    Log.w(TAG, "⚠️ Gender classifier not available - will blur all people")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to load gender model - will blur all people", e)
                genderInterpreter = null
                genderClassifier = null
            }

            Log.d(TAG, "Models initialized successfully (body: ✅, gender: ${if (genderClassifier != null) "✅" else "❌"})")
            true // Return true even if gender classifier failed
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing models", e)
            e.printStackTrace()
            false
        }
    }

    private fun loadModelFile(modelName: String, useGpu: Boolean): Interpreter? {
        return try {
            val assetFileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options()
            if (useGpu) {
                // Try GPU delegate if available
                try {
                    options.setUseNNAPI(true)
                    Log.d(TAG, "Using GPU acceleration for $modelName")
                } catch (e: Exception) {
                    Log.w(TAG, "GPU not available, using CPU for $modelName")
                }
            }
            options.setNumThreads(4)

            Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model $modelName", e)
            null
        }
    }

    fun getBodyDetector(): BodyDetectorMediaPipe? = bodyDetector

    fun getGenderClassifier(): GenderClassifier? = genderClassifier

    fun release() {
        bodyDetector?.close()
        bodyDetector = null

        genderInterpreter?.close()
        genderInterpreter = null
        genderClassifier = null

        Log.d(TAG, "Models released")
    }

    fun isInitialized(): Boolean {
        return bodyDetector != null && genderClassifier != null
    }
}