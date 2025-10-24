package com.tazkia.ai.blurfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.ml.BodyDetectorMediaPipe
import com.tazkia.ai.blurfilter.ml.ModelManager
import com.tazkia.ai.blurfilter.ui.MainActivity
import com.tazkia.ai.blurfilter.utils.ImageUtils
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

class ScreenCaptureService : Service() {

    private lateinit var prefManager: PreferenceManager
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

    private var lastFrameHash: Long = 0
    private var currentFps = 0.5f
    private var isScrolling = false
    private var isProcessing = false
    private var frameCount = 0

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tazkia_protection"
    }

    private val accessibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AccessibilityMonitorService.ACTION_SCROLL_EVENT -> {
                    isScrolling = true
                    currentFps = 2f
                }
                AccessibilityMonitorService.ACTION_WINDOW_CHANGE -> {
                    // Clear any caches if needed
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            Log.d(TAG, "========== SERVICE ONCREATE START ==========")
            prefManager = PreferenceManager(this)

            // Initialize RenderScript with fallback
            renderScript = try {
                RenderScript.create(this)
            } catch (e: Exception) {
                Log.w(TAG, "RenderScript not available, will use pixelation fallback", e)
                null
            }

            // Initialize models
            Log.d(TAG, "Initializing ModelManager...")
            modelManager = ModelManager(this)
            if (!modelManager.initializeModels(prefManager.useGpu)) {
                Log.e(TAG, "Failed to initialize models")
                stopSelf()
                return
            }

            bodyDetector = modelManager.getBodyDetector()
            if (bodyDetector == null) {
                Log.e(TAG, "Body detector is null after initialization")
                stopSelf()
                return
            }
            Log.d(TAG, "Body detector initialized successfully")

            // Get screen metrics
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")

            // Start capture thread
            captureThread = HandlerThread("CaptureThread").apply {
                start()
            }
            captureHandler = Handler(captureThread!!.looper)
            Log.d(TAG, "Capture thread started")

            // Register accessibility receiver
            val filter = IntentFilter().apply {
                addAction(AccessibilityMonitorService.ACTION_SCROLL_EVENT)
                addAction(AccessibilityMonitorService.ACTION_WINDOW_CHANGE)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(accessibilityReceiver, filter)
            Log.d(TAG, "Accessibility receiver registered")

            // FIX: Start overlay service properly
            overlayService = OverlayService()
            overlayService?.start(this)
            Log.d(TAG, "Overlay service started")

            Log.d(TAG, "========== SERVICE ONCREATE COMPLETE ==========")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "========== ON START COMMAND ==========")

        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "Started foreground service")

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
            Log.d(TAG, "========== STARTING MEDIA PROJECTION ==========")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection")
                stopSelf()
                return
            }

            // Register callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, captureHandler)
            Log.d(TAG, "MediaProjection callback registered")

            // FIX: Use higher resolution for better detection
            // Use at least 480p width for body detection to work reliably
            val processingWidth = 720 // Increased from 480
            val processingHeight = (screenHeight * processingWidth / screenWidth)

            Log.d(TAG, "Processing resolution: ${processingWidth}x${processingHeight}")
            Log.d(TAG, "Screen resolution: ${screenWidth}x${screenHeight}")

            // Close any existing ImageReader
            imageReader?.close()

            // Create ImageReader
            imageReader = ImageReader.newInstance(
                processingWidth,
                processingHeight,
                PixelFormat.RGBA_8888,
                2
            )
            Log.d(TAG, "ImageReader created: ${processingWidth}x${processingHeight}")

            // Create VirtualDisplay
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
                return
            }

            Log.d(TAG, "========== MEDIA PROJECTION STARTED SUCCESSFULLY ==========")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting media projection", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startProcessing() {
        Log.d(TAG, "========== STARTING PROCESSING LOOP ==========")

        processingJob = serviceScope.launch {
            try {
                // Wait a bit for VirtualDisplay to be ready
                delay(500)

                while (isActive) {
                    try {
                        val frameDelay = (1000 / currentFps).toLong()

                        frameCount++
                        if (frameCount % 10 == 0) {
                            Log.d(TAG, "Processing frame #$frameCount (FPS: $currentFps)")
                        }

                        captureAndProcess()

                        if (isScrolling) {
                            delay(2000)
                            isScrolling = false
                            if (prefManager.adaptiveFps) {
                                currentFps = 0.5f
                            }
                        }

                        delay(frameDelay)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error in processing loop iteration", e)
                        e.printStackTrace()
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in processing loop", e)
                e.printStackTrace()
            }
        }
    }

    private suspend fun captureAndProcess() {
        if (isProcessing) {
            if (frameCount % 20 == 0) {
                Log.d(TAG, "Already processing, skipping frame")
            }
            return
        }

        processingMutex.withLock {
            isProcessing = true
            var image: Image? = null
            var bitmap: Bitmap? = null

            try {
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    if (frameCount % 20 == 0) {
                        Log.w(TAG, "No image available from ImageReader")
                    }
                    return
                }

                if (frameCount % 10 == 0) {
                    Log.d(TAG, "Captured image: ${image.width}x${image.height}")
                }

                bitmap = imageToBitmap(image)
                image.close()

                if (frameCount % 10 == 0) {
                    Log.d(TAG, "Converted to bitmap: ${bitmap.width}x${bitmap.height}")
                }

                // Process the frame
                processFrame(bitmap)

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndProcess", e)
                e.printStackTrace()
                bitmap?.recycle()
            } finally {
                image?.close()
                isProcessing = false
            }
        }
    }
    // BULLETPROOF FIX: Replace these two functions


    private fun processFrame(bitmap: Bitmap) {
        var detectionBitmap: Bitmap? = null
        try {
            if (frameCount % 10 == 0) {
                Log.d(TAG, "========== PROCESS FRAME #$frameCount START ==========")
            }

            // CHECK 1: Is bitmap valid?
            if (bitmap.isRecycled) {
                Log.e(TAG, "ERROR: Bitmap is already recycled before processing!")
                return
            }

            val detector = bodyDetector
            if (detector == null) {
                Log.e(TAG, "Body detector is NULL!")
                return
            }

            // CREATE A FRESH COPY for MediaPipe (it will recycle this one)
            detectionBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(detectionBitmap)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            Log.d(TAG, "Created detection copy: ${detectionBitmap.width}x${detectionBitmap.height}")

            // Give MediaPipe the COPY (not the original)
            val bodies = detector.detectBodies(detectionBitmap)

            // CHECK 2: Original bitmap should still be fine
            if (bitmap.isRecycled) {
                Log.e(TAG, "ERROR: Original bitmap was somehow recycled!")
                return
            }

            if (frameCount % 10 == 0) {
                Log.d(TAG, "Detection returned ${bodies.size} bodies")
            }

            if (bodies.isEmpty()) {
                if (frameCount % 10 == 0) {
                    Log.w(TAG, "No bodies detected, clearing blur")
                }
                overlayService?.clearBlur()
                return
            }

            Log.d(TAG, "✓✓✓ FOUND ${bodies.size} BODIES! Applying blur...")

            bodies.forEachIndexed { index, body ->
                Log.d(TAG, "Body $index: bbox=${body.boundingBox}, confidence=${body.confidence}")
            }

            // CHECK 3: One more check before blur
            if (bitmap.isRecycled) {
                Log.e(TAG, "ERROR: Bitmap recycled before blur!")
                return
            }

            // Use ORIGINAL bitmap for blurring
            val blurredBitmap = applyBlurToRegions(bitmap, bodies)

            Log.d(TAG, "Blur applied, updating overlay...")
            overlayService?.updateBlur(blurredBitmap, bodies.map { it.boundingBox })

            if (frameCount % 10 == 0) {
                Log.d(TAG, "========== PROCESS FRAME #$frameCount END ==========")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in processFrame", e)
            e.printStackTrace()
            overlayService?.clearBlur()
        } finally {
            // detectionBitmap might already be recycled by MediaPipe, that's OK
            if (detectionBitmap?.isRecycled == false) {
                detectionBitmap.recycle()
            }
            // Recycle original bitmap
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    // 3. Keep applyBlurToRegions the same as before
    private fun applyBlurToRegions(
        bitmap: Bitmap,
        regions: List<BodyDetectorMediaPipe.BodyDetection>
    ): Bitmap {
        Log.d(TAG, "========== APPLY BLUR TO REGIONS ==========")
        Log.d(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}, isRecycled: ${bitmap.isRecycled}")

        if (bitmap.isRecycled) {
            Log.e(TAG, "ERROR: Can't blur recycled bitmap!")
            return Bitmap.createBitmap(720, 1760, Bitmap.Config.ARGB_8888)
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Failed to draw bitmap!", e)
            return result
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        Log.d(TAG, "Processing ${regions.size} regions")

        for ((index, detection) in regions.withIndex()) {
            try {
                val rect = Rect(
                    detection.boundingBox.left.toInt().coerceIn(0, bitmap.width - 1),
                    detection.boundingBox.top.toInt().coerceIn(0, bitmap.height - 1),
                    detection.boundingBox.right.toInt().coerceIn(1, bitmap.width),
                    detection.boundingBox.bottom.toInt().coerceIn(1, bitmap.height)
                )

                if (rect.width() <= 0 || rect.height() <= 0) continue

                val regionBitmap = ImageUtils.cropRegion(bitmap, rect)

                // Apply STRONG blur (multiple passes)
                val blurred = if (renderScript != null) {
                    val blurRadius = 25 // Maximum
                    var result = regionBitmap
                    repeat(3) {
                        val temp = ImageUtils.applyRenderScriptBlur(renderScript!!, result, blurRadius)
                        if (result != regionBitmap) result.recycle()
                        result = temp
                    }
                    result
                } else {
                    val pixelSize = (prefManager.blurIntensity * 2) + 10
                    ImageUtils.applyPixelation(regionBitmap, pixelSize)
                }

                canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), paint)

                // Add dark overlay for opacity
                val overlayPaint = Paint().apply {
                    color = android.graphics.Color.argb(80, 0, 0, 0)
                }
                canvas.drawRect(
                    rect.left.toFloat(),
                    rect.top.toFloat(),
                    rect.right.toFloat(),
                    rect.bottom.toFloat(),
                    overlayPaint
                )

                regionBitmap.recycle()
                blurred.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Error blurring region $index", e)
            }
        }

        Log.d(TAG, "========== BLUR COMPLETE ==========")
        return result
    }
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            // Create a clean copy without padding
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }
    private fun createNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
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
        Log.d(TAG, "========== SERVICE ON DESTROY ==========")

        try {
            processingJob?.cancel()
            serviceScope.cancel()

            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()

            overlayService?.stop()
            modelManager.release()
            renderScript?.destroy()

            LocalBroadcastManager.getInstance(this).unregisterReceiver(accessibilityReceiver)

            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null

            prefManager.isProtectionRunning = false

            Log.d(TAG, "Service destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}