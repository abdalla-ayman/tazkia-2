package com.tazkia.ai.blurfilter.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelManager(private val context: Context) {

    private var bodyDetector: BodyDetectorMediaPipe? = null
    private var genderClassifierInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    companion object {
        private const val GENDER_CLASSIFIER_MODEL = "mobilenetv3_gender_fullbody.tflite"
        const val GENDER_INPUT_SIZE = 224
    }

    fun initializeModels(useGpu: Boolean = true): Boolean {
        return try {
            bodyDetector = BodyDetectorMediaPipe(context)
            val bodySuccess = bodyDetector?.initialize() ?: false

            if (!bodySuccess) {
                return false
            }

            val options = Interpreter.Options()
            options.setNumThreads(4)

            if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            } else {
                options.setUseNNAPI(true)
            }

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

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun getBodyDetector(): BodyDetectorMediaPipe? = bodyDetector

    fun getGenderClassifier(): Interpreter? = genderClassifierInterpreter

    fun release() {
        bodyDetector?.close()
        genderClassifierInterpreter?.close()
        gpuDelegate?.close()

        bodyDetector = null
        genderClassifierInterpreter = null
        gpuDelegate = null
    }

    fun isInitialized(): Boolean {
        return bodyDetector != null && genderClassifierInterpreter != null
    }
}