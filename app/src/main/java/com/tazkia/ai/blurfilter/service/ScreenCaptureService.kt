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
import com.tazkia.ai.blurfilter.ui.MainActivity // Add import
import com.tazkia.ai.blurfilter.utils.ImageUtils
import com.tazkia.ai.blurfilter.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    private lateinit var prefManager: PreferenceManager
    private lateinit var modelManager: ModelManager
    private lateinit var bodyDetector: BodyDetectorMediaPipe
    private lateinit var genderClassifier: GenderClassifier
    private lateinit var overlayService: OverlayService
    private lateinit var renderScript: RenderScript

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var processingJob: Job? = null

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var lastFrameHash: Long = 0
    private var currentFps = 0.5f
    private var isScrolling = false

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tazkia_protection"
        private const val RECEIVER_NOT_EXPORTED = 0x00000004
    }

    // Broadcast receiver for accessibility events
    private val accessibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AccessibilityMonitorService.ACTION_SCROLL_EVENT -> {
                    isScrolling = true
                    currentFps = 2f // Increase FPS during scroll
                }
                AccessibilityMonitorService.ACTION_WINDOW_CHANGE -> {
                    // Clear cache when app changes
                    genderClassifier.clearCache()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        prefManager = PreferenceManager(this)
        renderScript = RenderScript.create(this)

        // Initialize models
        modelManager = ModelManager(this)
        if (!modelManager.initializeModels(prefManager.useGpu)) {
            stopSelf()
            return
        }

        bodyDetector = modelManager.getBodyDetector()!!
        genderClassifier = GenderClassifier(modelManager.getGenderClassifier()!!)

        // Get screen metrics
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Setup capture thread
        captureThread = HandlerThread("CaptureThread")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        // Register accessibility receiver - FIXED
        val filter = IntentFilter()
        filter.addAction(AccessibilityMonitorService.ACTION_SCROLL_EVENT)
        filter.addAction(AccessibilityMonitorService.ACTION_WINDOW_CHANGE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accessibilityReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(accessibilityReceiver, filter)
        }

        // Start overlay service
        overlayService = OverlayService()
        overlayService.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fix: Add explicit type
        val mediaProjectionData: Intent? = intent?.getParcelableExtra("mediaProjectionData")

        if (mediaProjectionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startMediaProjection(mediaProjectionData)
        startProcessing()

        return START_STICKY
    }

    private fun startMediaProjection(data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data)

        // Calculate processing resolution
        val processingWidth = prefManager.processingResolution
        val processingHeight = (screenHeight * processingWidth / screenWidth)

        // Create ImageReader
        imageReader = ImageReader.newInstance(
            processingWidth,
            processingHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // Create virtual display
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
    }

    private fun startProcessing() {
        processingJob = serviceScope.launch {
            while (isActive) {
                try {
                    val frameDelay = (1000 / currentFps).toLong()

                    // Capture and process frame
                    captureAndProcess()

                    // Reset scroll flag after processing
                    if (isScrolling) {
                        delay(2000) // Keep high FPS for 2 seconds after scroll
                        isScrolling = false
                        if (prefManager.adaptiveFps) {
                            currentFps = 0.5f // Return to low FPS
                        }
                    }

                    delay(frameDelay)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun captureAndProcess() {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val bitmap = imageToBitmap(image)
            image.close()

            // Quick motion detection
            val currentHash = ImageUtils.calculateHash(bitmap)

            if (!prefManager.adaptiveFps ||
                ImageUtils.hasSignificantMotion(lastFrameHash, currentHash, 5)) {

                lastFrameHash = currentHash
                processFrame(bitmap)
            } else {
                // No significant change, clear overlay
                overlayService.clearBlur()
            }

            bitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
            image.close()
        }
    }

//    private fun processFrame(bitmap: Bitmap) {
//        // Detect full bodies (not just faces!)
//        val bodies = bodyDetector.detectBodies(bitmap)
//
//        if (bodies.isEmpty()) {
//            overlayService.clearBlur()
//            return
//        }
//
//        // Get orientations for better classification
//        val orientations = bodies.associate { body ->
//            body.id to bodyDetector.getBodyOrientation(body)
//        }
//
//        // Classify genders
//        val genderResults = genderClassifier.classifyBatch(bitmap, bodies, orientations)
//
//        // Filter bodies based on user preference
//        val targetGender = when (prefManager.filterTarget) {
//            PreferenceManager.FILTER_WOMEN -> GenderClassifier.GENDER_FEMALE
//            PreferenceManager.FILTER_MEN -> GenderClassifier.GENDER_MALE
//            else -> return
//        }
//
//        val bodiesToBlur = bodies.filter { body ->
//            val result = genderResults[body.id]
//            result?.gender == targetGender
//        }
//
//        if (bodiesToBlur.isEmpty()) {
//            overlayService.clearBlur()
//            return
//        }
//
//        // Apply blur to matching bodies
//        val blurredBitmap = applyBlurToRegions(bitmap, bodiesToBlur)
//
//        // Update overlay
//        overlayService.updateBlur(blurredBitmap, bodiesToBlur.map { it.boundingBox })
//    }
private fun processFrame(bitmap: Bitmap) {
    // Detect full bodies (not just faces!)
    val bodies = bodyDetector.detectBodies(bitmap)

    // DEBUG: Print detection results
    println("DEBUG: Found ${bodies.size} bodies in frame")

    if (bodies.isEmpty()) {
        overlayService.clearBlur()
        return
    }

    // TEMPORARY: Blur ALL detected bodies (for testing)
    // Remove this when classification model is available
    val bodiesToBlur = bodies // Blur all instead of filtering by gender

    // DEBUG: Print blur targets
    println("DEBUG: Blurring ${bodiesToBlur.size} bodies")

    // Apply blur to ALL matching bodies
    val blurredBitmap = applyBlurToRegions(bitmap, bodiesToBlur)

    // Update overlay
    overlayService.updateBlur(blurredBitmap, bodiesToBlur.map { it.boundingBox })
}
    private fun applyBlurToRegions(
        bitmap: Bitmap,
        regions: List<BodyDetectorMediaPipe.BodyDetection>
    ): Bitmap {
        val result = bitmap.copy(bitmap.config, true)

        for (detection in regions) {
            try {
                val rect = android.graphics.Rect(
                    detection.boundingBox.left.toInt(),
                    detection.boundingBox.top.toInt(),
                    detection.boundingBox.right.toInt(),
                    detection.boundingBox.bottom.toInt()
                )

                // Crop region
                val regionBitmap = ImageUtils.cropRegion(result, rect)

                // Apply blur using RenderScript (fastest)
                val blurRadius = (prefManager.blurIntensity * 2.5f).toInt().coerceIn(1, 25)
                val blurred = ImageUtils.applyRenderScriptBlur(
                    renderScript,
                    regionBitmap,
                    blurRadius
                )

                // Draw blurred region back
                val canvas = android.graphics.Canvas(result)
                canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), null)

                regionBitmap.recycle()
                blurred.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        // Fix: Use correct package name
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
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        processingJob?.cancel()
        serviceScope.cancel()

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        overlayService.stop()
        modelManager.release()
        renderScript.destroy()

        unregisterReceiver(accessibilityReceiver)

        captureThread.quitSafely()

        prefManager.isProtectionRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}