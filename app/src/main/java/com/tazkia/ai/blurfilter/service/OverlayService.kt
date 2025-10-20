package com.tazkia.ai.blurfilter.service

import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var isShowing = false

    companion object {
        private const val TAG = "OverlayService"
    }

    fun start(context: Context) {
        try {
            Log.d(TAG, "Starting overlay")
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = OverlayView(context)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
            isShowing = true
            Log.d(TAG, "Overlay started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting overlay", e)
            e.printStackTrace()
            isShowing = false
        }
    }

    fun updateBlur(blurredBitmap: Bitmap?, regions: List<RectF>) {
        overlayView?.updateBlur(blurredBitmap, regions)
    }

    fun clearBlur() {
        overlayView?.clearBlur()
    }

    fun stop() {
        Log.d(TAG, "Stopping overlay")
        if (isShowing && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
                e.printStackTrace()
            } finally {
                isShowing = false
            }
        }
        overlayView?.cleanup()
        overlayView = null
        windowManager = null
    }

    private class OverlayView(context: Context) : View(context) {

        private var blurredBitmap: Bitmap? = null
        private var blurRegions = listOf<RectF>()
        private val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        fun updateBlur(bitmap: Bitmap?, regions: List<RectF>) {
            post {
                try {
                    // Recycle old bitmap
                    blurredBitmap?.recycle()
                    blurredBitmap = bitmap
                    blurRegions = regions.toList()
                    invalidate()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating blur", e)
                    e.printStackTrace()
                }
            }
        }

        fun clearBlur() {
            post {
                try {
                    blurredBitmap?.recycle()
                    blurredBitmap = null
                    blurRegions = emptyList()
                    invalidate()
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing blur", e)
                    e.printStackTrace()
                }
            }
        }

        fun cleanup() {
            blurredBitmap?.recycle()
            blurredBitmap = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val bitmap = blurredBitmap
            if (bitmap == null || bitmap.isRecycled || blurRegions.isEmpty()) {
                return
            }

            try {
                val scaleX = width.toFloat() / bitmap.width
                val scaleY = height.toFloat() / bitmap.height

                for (region in blurRegions) {
                    val scaledRect = RectF(
                        region.left * scaleX,
                        region.top * scaleY,
                        region.right * scaleX,
                        region.bottom * scaleY
                    )

                    val srcRect = android.graphics.Rect(
                        region.left.toInt().coerceIn(0, bitmap.width),
                        region.top.toInt().coerceIn(0, bitmap.height),
                        region.right.toInt().coerceIn(0, bitmap.width),
                        region.bottom.toInt().coerceIn(0, bitmap.height)
                    )

                    if (srcRect.width() > 0 && srcRect.height() > 0) {
                        canvas.drawBitmap(bitmap, srcRect, scaledRect, paint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing blur", e)
                e.printStackTrace()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            cleanup()
        }
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        return null
    }
}