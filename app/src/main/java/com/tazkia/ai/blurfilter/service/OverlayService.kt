package com.tazkia.ai.blurfilter.service

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.*

class OverlayService {

    private var overlayView: OverlayView? = null
    private var windowManager: WindowManager? = null

    fun start(context: Context) {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(context)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlayView, params)
            Log.d("OverlayService", "Overlay started")
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to start overlay", e)
        }
    }

    fun updateBlur(bitmap: Bitmap?, regions: List<RectF>) {
        overlayView?.updateData(bitmap, regions)
    }

    fun clearBlur() {
        overlayView?.updateData(null, emptyList())
    }

    fun stop() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        windowManager = null
    }

    inner class OverlayView(context: Context) : View(context) {

        private var currentBitmap: Bitmap? = null
        private var blurRegions: List<RectF> = emptyList()

        private val debugPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val textPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 40f
            style = Paint.Style.FILL
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun updateData(newBitmap: Bitmap?, newRegions: List<RectF>) {
            Log.d("OverlayView", "updateData: bitmap=${newBitmap?.width}x${newBitmap?.height}, regions=${newRegions.size}")

            val old = currentBitmap
            currentBitmap = newBitmap
            blurRegions = newRegions

            if (old != null && old != newBitmap && !old.isRecycled) {
                post { old.recycle() }
            }

            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            Log.d("OverlayView", "onDraw: regions=${blurRegions.size}, bitmap=${currentBitmap != null}")

            val bitmap = currentBitmap
            if (bitmap == null || bitmap.isRecycled) {
                Log.w("OverlayView", "No valid bitmap")
                return
            }

            if (blurRegions.isEmpty()) {
                Log.w("OverlayView", "No regions")
                return
            }

            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            }

            Log.d("OverlayView", "Screen: ${width}x${height}, Bitmap: ${bitmap.width}x${bitmap.height}")

            // Calculate scale from bitmap to screen
            val scaleX = width.toFloat() / bitmap.width.toFloat()
            val scaleY = height.toFloat() / bitmap.height.toFloat()

            Log.d("OverlayView", "Scale: x=$scaleX, y=$scaleY")

            for ((index, screenRegion) in blurRegions.withIndex()) {
                try {
                    // Map screen region BACK to bitmap coordinates
                    val bitmapRegion = RectF(
                        screenRegion.left / scaleX,
                        screenRegion.top / scaleY,
                        screenRegion.right / scaleX,
                        screenRegion.bottom / scaleY
                    )

                    // Clamp to bitmap bounds
                    bitmapRegion.left = bitmapRegion.left.coerceIn(0f, bitmap.width.toFloat())
                    bitmapRegion.top = bitmapRegion.top.coerceIn(0f, bitmap.height.toFloat())
                    bitmapRegion.right = bitmapRegion.right.coerceIn(0f, bitmap.width.toFloat())
                    bitmapRegion.bottom = bitmapRegion.bottom.coerceIn(0f, bitmap.height.toFloat())

                    if (bitmapRegion.width() <= 0 || bitmapRegion.height() <= 0) {
                        Log.w("OverlayView", "Invalid bitmap region: $bitmapRegion")
                        continue
                    }

                    Log.d("OverlayView", "Region $index: screen=$screenRegion, bitmap=$bitmapRegion")

                    // Draw the blurred region from bitmap to screen
                    canvas.drawBitmap(
                        bitmap,
                        Rect(
                            bitmapRegion.left.toInt(),
                            bitmapRegion.top.toInt(),
                            bitmapRegion.right.toInt(),
                            bitmapRegion.bottom.toInt()
                        ),
                        screenRegion,
                        blurPaint
                    )

                    // DEBUG: Draw border
                    canvas.drawRect(screenRegion, debugPaint)
                    canvas.drawText("BLUR #$index", screenRegion.left + 10, screenRegion.top + 50, textPaint)

                } catch (e: Exception) {
                    Log.e("OverlayView", "Error drawing region $index", e)
                }
            }
        }
    }
}