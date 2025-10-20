package com.tazkia.ai.blurfilter.ml

import android.content.Context
import android.util.Log

class ModelManager(private val context: Context) {

    private var bodyDetector: BodyDetectorMediaPipe? = null

    companion object {
        private const val TAG = "ModelManager"
    }

    fun initializeModels(useGpu: Boolean = true): Boolean {
        return try {
            Log.d(TAG, "Initializing body detector...")
            bodyDetector = BodyDetectorMediaPipe(context)
            val bodySuccess = bodyDetector?.initialize() ?: false

            if (!bodySuccess) {
                Log.e(TAG, "Failed to initialize body detector")
                return false
            }

            Log.d(TAG, "Body detector initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing models", e)
            e.printStackTrace()
            false
        }
    }

    fun getBodyDetector(): BodyDetectorMediaPipe? = bodyDetector

    fun release() {
        bodyDetector?.close()
        bodyDetector = null
    }

    fun isInitialized(): Boolean {
        return bodyDetector != null
    }
}