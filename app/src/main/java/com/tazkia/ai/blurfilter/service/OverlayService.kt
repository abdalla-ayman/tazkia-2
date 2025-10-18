package com.tazkia.ai.blurfilter.service

import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowManager

class OverlayService :Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var isShowing = false

    fun start(context: Context) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(context)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlayView, params)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateBlur(blurredBitmap: Bitmap?, regions: List<RectF>) {
        overlayView?.updateBlur(blurredBitmap, regions)
    }

    fun clearBlur() {
        overlayView?.clearBlur()
    }

    fun stop() {
        if (isShowing && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                isShowing = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        windowManager = null
    }

    private class OverlayView(context: Context) : View(context) {

        private var blurredBitmap: Bitmap? = null
        private var blurRegions = listOf<RectF>()
        private val paint = Paint().apply {
            isAntiAlias = true
        }

        fun updateBlur(bitmap: Bitmap?, regions: List<RectF>) {
            blurredBitmap?.recycle()

            blurredBitmap = bitmap
            blurRegions = regions
            postInvalidate()
        }

        fun clearBlur() {
            blurredBitmap?.recycle()
            blurredBitmap = null
            blurRegions = emptyList()
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (blurredBitmap == null || blurRegions.isEmpty()) {
                return
            }

            val scaleX = width.toFloat() / (blurredBitmap?.width ?: 1)
            val scaleY = height.toFloat() / (blurredBitmap?.height ?: 1)

            for (region in blurRegions) {
                val scaledRect = RectF(
                    region.left * scaleX,
                    region.top * scaleY,
                    region.right * scaleX,
                    region.bottom * scaleY
                )

                try {
                    val srcRect = android.graphics.Rect(
                        region.left.toInt(),
                        region.top.toInt(),
                        region.right.toInt(),
                        region.bottom.toInt()
                    )

                    blurredBitmap?.let { bitmap ->
                        canvas.drawBitmap(bitmap, srcRect, scaledRect, paint)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            blurredBitmap?.recycle()
            blurredBitmap = null
        }
    }
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        return null
    }

}