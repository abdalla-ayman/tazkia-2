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
import com.tazkia.ai.blurfilter.ml.BodyDetectorMediaPipe
import com.tazkia.ai.blurfilter.ml.GenderClassifier
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
import android.os.Looper
import android.util.Log
import com.tazkia.ai.blurfilter.utils.ImageUtils

class ScreenCaptureService : Service() {
    private lateinit var prefManager: PreferenceManager

    private var lastProcessedBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var modelManager: ModelManager
    private var bodyDetector: BodyDetectorMediaPipe? = null
    private var genderClassifier: GenderClassifier? = null
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
            genderClassifier = modelManager.getGenderClassifier()

            if (bodyDetector == null) {
                Log.e(TAG, "Body detector is null - cannot continue")
                stopSelf()
                return
            }

            if (genderClassifier == null) {
                Log.w(TAG, "⚠️ Gender classifier not available - will blur ALL detected people")
            } else {
                Log.d(TAG, "✅ Gender classifier available - will filter by gender")
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

        // CRITICAL: Extract data BEFORE starting foreground
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

        // Start foreground BEFORE media projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        Log.d(TAG, "Service is now in foreground")

        // CRITICAL: Add delay to ensure foreground status is fully registered
        // Android 14+ requires this delay before starting media projection
        mainHandler.postDelayed({
            startMediaProjection(mediaProjectionData)
            startProcessing()
        }, 100) // 100ms delay

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
            e.printStackTrace()

            // Don't call stopSelf() here - service is already in foreground
            // Just show error to user via notification
            showErrorNotification("Failed to start screen capture: ${e.message}")
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
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "No image available")
                    return
                }

                bitmap = imageToBitmap(image)
                image.close()
                image = null

                frameCount++
                Log.d(TAG, "Processing frame $frameCount, bitmap: ${bitmap.width}x${bitmap.height}")

                processFrame(bitmap) // processFrame handles bitmap recycling
                bitmap = null // Mark as transferred to processFrame

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndProcess", e)
                e.printStackTrace()
                bitmap?.recycle() // Only recycle on error
            } finally {
                image?.close()
                isProcessing = false
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        try {
            Log.d(TAG, "processFrame: bitmap ${bitmap.width}x${bitmap.height}, isRecycled=${bitmap.isRecycled}")

            // Detect all people
            val allBodies = bodyDetector?.detectBodies(bitmap) ?: emptyList()

            Log.d(TAG, "After detection: bitmap isRecycled=${bitmap.isRecycled}")

            if (allBodies.isEmpty()) {
                mainHandler.post {
                    overlayService?.clearBlur()
                }
                bitmap.recycle()
                return
            }

            // Classify gender and filter based on user preference
            val filteredBodies = if (genderClassifier != null) {
                Log.d(TAG, "Classifying gender for ${allBodies.size} people")

                val genderResults = genderClassifier!!.classifyBatch(bitmap, allBodies)

                // Get target gender from settings
                val targetGender = when (prefManager.filterTarget) {
                    PreferenceManager.FILTER_WOMEN -> GenderClassifier.GENDER_FEMALE
                    PreferenceManager.FILTER_MEN -> GenderClassifier.GENDER_MALE
                    else -> GenderClassifier.GENDER_FEMALE
                }

                // Filter: keep only people matching target gender
                allBodies.filter { body ->
                    val genderResult = genderResults[body.id]
                    val shouldBlur = genderResult?.gender == targetGender

                    Log.d(TAG, "Person ${body.id}: gender=${genderResult?.gender}, confidence=${genderResult?.confidence}, blur=$shouldBlur")

                    shouldBlur
                }
            } else {
                Log.w(TAG, "No gender classifier - blurring all people")
                allBodies
            }

            Log.d(TAG, "Filtering: ${allBodies.size} detected → ${filteredBodies.size} to blur (target: ${prefManager.filterTarget})")

            if (filteredBodies.isEmpty()) {
                mainHandler.post {
                    overlayService?.clearBlur()
                }
                bitmap.recycle()
                return
            }

            // Create blurred bitmap - bitmap must NOT be recycled here
            val blurredResult = applyBlurToRegions(bitmap, filteredBodies)

            // NOW recycle the original
            bitmap.recycle()

            // Calculate screen coordinates
            val displayMetrics = this.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            val scaleX = screenWidth / blurredResult.width.toFloat()
            val scaleY = screenHeight / blurredResult.height.toFloat()

            val screenRegions = filteredBodies.map { detection ->
                val box = detection.boundingBox
                RectF(
                    (box.left * scaleX).coerceAtLeast(0f),
                    (box.top * scaleY).coerceAtLeast(0f),
                    (box.right * scaleX).coerceAtMost(screenWidth),
                    (box.bottom * scaleY).coerceAtMost(screenHeight)
                )
            }

            val paddedScreenRegions = screenRegions.map { region ->
                val paddingX = region.width() * 0.2f
                val paddingY = region.height() * 0.2f
                RectF(
                    (region.left - paddingX).coerceAtLeast(0f),
                    (region.top - paddingY).coerceAtLeast(0f),
                    (region.right + paddingX).coerceAtMost(screenWidth),
                    (region.bottom + paddingY).coerceAtMost(screenHeight)
                )
            }

            mainHandler.post {
                Log.d(TAG, "Updating overlay: ${paddedScreenRegions.size} regions")
                overlayService?.updateBlur(blurredResult, paddedScreenRegions)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in processFrame", e)
            e.printStackTrace()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun applyBlurToRegions(
        bitmap: Bitmap,
        regions: List<BodyDetectorMediaPipe.BodyDetection>
    ): Bitmap {
        Log.d(TAG, "applyBlurToRegions: bitmap ${bitmap.width}x${bitmap.height}, isRecycled=${bitmap.isRecycled}, regions=${regions.size}")

        if (bitmap.isRecycled) {
            throw IllegalStateException("Cannot apply blur to recycled bitmap!")
        }

        // Create a copy to work with
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw original as base
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        for ((index, detection) in regions.withIndex()) {
            try {
                val box = detection.boundingBox

                val paddingX = box.width() * 0.25f
                val paddingY = box.height() * 0.25f

                val paddedRect = Rect(
                    (box.left - paddingX).coerceAtLeast(0f).toInt(),
                    (box.top - paddingY).coerceAtLeast(0f).toInt(),
                    (box.right + paddingX).coerceAtMost(bitmap.width.toFloat()).toInt(),
                    (box.bottom + paddingY).coerceAtMost(bitmap.height.toFloat()).toInt()
                )

                if (paddedRect.width() <= 0 || paddedRect.height() <= 0) {
                    Log.w(TAG, "Region $index has invalid size")
                    continue
                }

                Log.d(TAG, "Processing region $index: $paddedRect")

                // Crop from the ORIGINAL bitmap (not result)
                val regionBitmap = ImageUtils.cropRegion(bitmap, paddedRect)

                val processedBitmap = if (renderScript != null) {
                    var temp = regionBitmap
                    repeat(3) {
                        val blurred = ImageUtils.applyRenderScriptBlur(renderScript!!, temp, 25)
                        if (temp != regionBitmap) temp.recycle()
                        temp = blurred
                    }
                    temp
                } else {
                    ImageUtils.applyPixelation(regionBitmap, 40)
                }

                // Draw blurred region
                canvas.drawBitmap(processedBitmap, null, RectF(paddedRect), paint)

                // Cleanup
                if (processedBitmap != regionBitmap && !processedBitmap.isRecycled) {
                    processedBitmap.recycle()
                }
                regionBitmap.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to blur region $index", e)
            }
        }

        Log.d(TAG, "Blur complete, returning result bitmap")
        return result
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        // Calculate row padding
        val rowPadding = rowStride - (pixelStride * width)

        // Create bitmap matching the stride
        val strideBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        strideBitmap.copyPixelsFromBuffer(buffer)

        // CRITICAL FIX: Copy pixels to a new bitmap, don't use createBitmap reference
        val cleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cleanBitmap)
        canvas.drawBitmap(strideBitmap, 0f, 0f, null)

        // NOW it's safe to recycle the stride bitmap
        strideBitmap.recycle()

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

    private fun showErrorNotification(errorMessage: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tazkia Error")
            .setContentText(errorMessage)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID + 1, notification)

        // Stop service after showing error
        mainHandler.postDelayed({
            stopSelf()
        }, 3000) // Give user time to see the error
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