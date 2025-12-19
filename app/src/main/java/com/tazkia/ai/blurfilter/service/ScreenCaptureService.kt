package com.tazkia.ai.blurfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.renderscript.RenderScript
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.ml.ModelManager
import com.tazkia.ai.blurfilter.ui.MainActivity
import com.tazkia.ai.blurfilter.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.tazkia.ai.blurfilter.ml.BodyDetectorMediaPipe
import com.tazkia.ai.blurfilter.utils.ImageUtils

class ScreenCaptureService : Service() {
    private lateinit var prefManager: PreferenceManager

    private var lastProcessedBitmap: Bitmap? = null
    private lateinit var modelManager: ModelManager
    private var bodyDetector: BodyDetectorMediaPipe? = null
    private var overlayService: OverlayService? = null
    private var renderScript: RenderScript? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var processingJob: Job? = null
    private val processingMutex = Mutex()
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var processingWidth = 0
    private var processingHeight = 0

    // Scale factors for coordinate conversion
    private var scaleX = 1f
    private var scaleY = 1f

    private var isProcessing = false
    private var frameCount = 0

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tazkia_protection"
        private const val TARGET_FPS = 2f // 2 frames per second
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d(TAG, "Service onCreate")
            prefManager = PreferenceManager(this)

            renderScript = try {
                RenderScript.create(this)
            } catch (e: Exception) {
                Log.w(TAG, "RenderScript unavailable", e)
                null
            }

            modelManager = ModelManager(this)
            if (!modelManager.initializeModels(prefManager.useGpu)) {
                Log.e(TAG, "Failed to initialize models")
                stopSelf()
                return
            }

            bodyDetector = modelManager.getBodyDetector()
            if (bodyDetector == null) {
                Log.e(TAG, "Body detector is null")
                stopSelf()
                return
            }

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}")

            captureThread = HandlerThread("CaptureThread").apply { start() }
            captureHandler = Handler(captureThread!!.looper)

            overlayService = OverlayService()
            overlayService?.start(this)

            Log.d(TAG, "Service created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val mediaProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("mediaProjectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("mediaProjectionData")
        }

        if (mediaProjectionData == null) {
            Log.e(TAG, "Media projection data is null")
            stopSelf()
            return START_NOT_STICKY
        }

        startMediaProjection(mediaProjectionData)
        startProcessing()
        return START_STICKY
    }

    private fun startMediaProjection(data: Intent) {
        try {
            Log.d(TAG, "Starting media projection")
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection")
                stopSelf()
                return
            }

            // CRITICAL: Register callback BEFORE creating virtual display (Android requirement)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, captureHandler)

            // EfficientDet-Lite0 is optimized for ~320px input
            // Using 360px width for better aspect ratio compatibility
            processingWidth = 360
            processingHeight = (screenHeight * processingWidth / screenWidth)

            // Calculate scale factors for coordinate mapping
            scaleX = screenWidth.toFloat() / processingWidth
            scaleY = screenHeight.toFloat() / processingHeight

            Log.d(TAG, "Processing: ${processingWidth}x${processingHeight}")
            Log.d(TAG, "Scale factors: scaleX=$scaleX, scaleY=$scaleY")

            imageReader = ImageReader.newInstance(
                processingWidth,
                processingHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TazkiaCapture",
                processingWidth,
                processingHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay")
                stopSelf()
            }

            Log.d(TAG, "Media projection started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting media projection", e)
            stopSelf()
        }
    }

    private fun startProcessing() {
        Log.d(TAG, "Starting processing loop")
        processingJob = serviceScope.launch {
            delay(500) // Initial delay
            while (isActive) {
                try {
                    captureAndProcess()
                    delay((1000 / TARGET_FPS).toLong())
                } catch (e: Exception) {
                    Log.e(TAG, "Error in processing loop", e)
                    delay(1000)
                }
            }
        }
    }

    private suspend fun captureAndProcess() {
        if (isProcessing) return

        processingMutex.withLock {
            isProcessing = true
            var image: Image? = null
            var bitmap: Bitmap? = null

            try {
                image = imageReader?.acquireLatestImage() ?: return
                bitmap = imageToBitmap(image)
                image.close()

                frameCount++
                processFrame(bitmap) // processFrame handles bitmap recycling
            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndProcess", e)
                bitmap?.recycle() // Only recycle on error
            } finally {
                image?.close()
                isProcessing = false
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        try {
            if (bitmap.isRecycled) return

            val detector = bodyDetector ?: return

            // 1. Detection (performed at 360px resolution)
            val detectionBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val bodies = detector.detectBodies(detectionBitmap)
            detectionBitmap.recycle()

            if (bodies.isEmpty()) {
                overlayService?.clearBlur()
                bitmap.recycle() // Recycle if nothing to blur
                return
            }

            // 2. Generate the blurred bitmap
            // We do this on the 360px bitmap for speed
            val blurredBitmap = applyBlurToRegions(bitmap, bodies)

            // 3. SCALE coordinates for the Overlay (360px -> Full Screen)
            // FIX: Use RectF here to match your BodyDetection class type
            val screenRegions = bodies.map { body ->
                RectF(
                    body.boundingBox.left * scaleX,
                    body.boundingBox.top * scaleY,
                    body.boundingBox.right * scaleX,
                    body.boundingBox.bottom * scaleY
                )
            }

            // 4. Update the overlay with the processed frame
            overlayService?.updateBlur(blurredBitmap, screenRegions)

            // 5. Managed Recycling:
            // Your capture 'bitmap' is no longer needed because we have 'blurredBitmap'
            bitmap.recycle()

            // NOTE: 'blurredBitmap' is recycled by the OverlayService
            // when the NEXT frame arrives, so we don't recycle it here.

        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in processFrame", e)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    private fun applyBlurToRegions(
        bitmap: Bitmap,
        regions: List<BodyDetectorMediaPipe.BodyDetection>
    ): Bitmap {
        // 1. Create a result bitmap from the original capture
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        for (detection in regions) {
            try {
                // 2. Map coordinates (ensure they stay inside the bitmap bounds)
                val rect = Rect(
                    detection.boundingBox.left.toInt().coerceIn(0, bitmap.width - 1),
                    detection.boundingBox.top.toInt().coerceIn(0, bitmap.height - 1),
                    detection.boundingBox.right.toInt().coerceIn(1, bitmap.width),
                    detection.boundingBox.bottom.toInt().coerceIn(1, bitmap.height)
                )

                if (rect.width() <= 0 || rect.height() <= 0) continue

                // 3. Crop the region to be blurred
                val regionBitmap = ImageUtils.cropRegion(bitmap, rect)

                // 4. Apply Blur
                val blurred = if (renderScript != null) {
                    val blurRadius = 25
                    var temp = regionBitmap
                    repeat(2) { // Double pass for stronger effect
                        val blurredTemp = ImageUtils.applyRenderScriptBlur(renderScript!!, temp, blurRadius)
                        // Safety: only recycle if a new instance was created
                        if (temp != regionBitmap) temp.recycle()
                        temp = blurredTemp
                    }
                    temp
                } else {
                    val pixelSize = (prefManager.blurIntensity * 2) + 10
                    ImageUtils.applyPixelation(regionBitmap, pixelSize)
                }

                // 5. Draw the blurred region onto our canvas
                canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), paint)

                // 6. Cleanup local temporary bitmaps
                if (blurred != regionBitmap) {
                    blurred.recycle()
                }
                regionBitmap.recycle()

            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error blurring region", e)
            }
        }

        return result
    }
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height

        // Calculate stride padding
        val rowPadding = rowStride - pixelStride * width

        // Create the full bitmap (includes potential empty padding on the right)
        val fullBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)

        // Extract the "clean" image area (removing the padding)
        val cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)

        // Recycle the temporary padded bitmap immediately
        fullBitmap.recycle()

        return cleanBitmap
    }
    private fun createNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_description)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        try {
            processingJob?.cancel()
            serviceScope.cancel()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            overlayService?.stop()
            modelManager.release()
            renderScript?.destroy()
            captureThread?.quitSafely()

            prefManager.isProtectionRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}