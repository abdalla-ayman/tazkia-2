package com.tazkia.ai.blurfilter.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
            Log.d(TAG, "Overlay started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting overlay", e)
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
                Log.e(TAG, "Error removing overlay", e)
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
        private var blurRegions = emptyList<RectF>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        fun updateBlur(bitmap: Bitmap?, regions: List<RectF>) {
            post {
                try {
                    // Clean up old bitmap
                    blurredBitmap?.recycle()

                    blurredBitmap = bitmap
                    blurRegions = regions.toList()

                    if (regions.isNotEmpty()) {
                        Log.d(TAG, "Updated blur with ${regions.size} regions")
                    }

                    invalidate()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating blur", e)
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
                }
            }
        }

        fun cleanup() {
            blurredBitmap?.recycle()
            blurredBitmap = null
            blurRegions = emptyList()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val bitmap = blurredBitmap ?: return
            if (bitmap.isRecycled || blurRegions.isEmpty()) return

            try {
                // Calculate scale from processing bitmap to screen
                val bitmapScaleX = width.toFloat() / bitmap.width
                val bitmapScaleY = height.toFloat() / bitmap.height

                for (region in blurRegions) {
                    // Convert screen-space region back to bitmap coordinates
                    val bitmapLeft = (region.left / bitmapScaleX).toInt().coerceIn(0, bitmap.width - 1)
                    val bitmapTop = (region.top / bitmapScaleY).toInt().coerceIn(0, bitmap.height - 1)
                    val bitmapRight = (region.right / bitmapScaleX).toInt().coerceIn(1, bitmap.width)
                    val bitmapBottom = (region.bottom / bitmapScaleY).toInt().coerceIn(1, bitmap.height)

                    if (bitmapRight <= bitmapLeft || bitmapBottom <= bitmapTop) continue

                    val srcRect = Rect(bitmapLeft, bitmapTop, bitmapRight, bitmapBottom)

                    // Draw to screen-space region directly
                    canvas.drawBitmap(bitmap, srcRect, region, paint)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing blur", e)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            cleanup()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}