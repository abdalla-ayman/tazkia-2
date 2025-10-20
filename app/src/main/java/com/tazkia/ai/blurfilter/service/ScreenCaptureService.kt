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
    private lateinit var overlayService: OverlayService
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
            Log.d(TAG, "Service onCreate started")
            prefManager = PreferenceManager(this)

            // Initialize RenderScript with fallback
            renderScript = try {
                RenderScript.create(this)
            } catch (e: Exception) {
                Log.w(TAG, "RenderScript not available, will use pixelation fallback", e)
                null
            }

            // Initialize models
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

            // Get screen metrics
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")

            // Start capture thread
            captureThread = HandlerThread("CaptureThread").apply {
                start()
            }
            captureHandler = Handler(captureThread!!.looper)

            // Register accessibility receiver
            val filter = IntentFilter().apply {
                addAction(AccessibilityMonitorService.ACTION_SCROLL_EVENT)
                addAction(AccessibilityMonitorService.ACTION_WINDOW_CHANGE)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(accessibilityReceiver, filter)

            // Start overlay
            overlayService = OverlayService()
            overlayService.start(this)

            Log.d(TAG, "Service onCreate completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

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

        startForeground(NOTIFICATION_ID, createNotification())
        startMediaProjection(mediaProjectionData)
        startProcessing()

        return START_STICKY
    }

    private fun startMediaProjection(data: Intent) {
        try {
            Log.d(TAG, "Starting media projection")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data)

            val processingWidth = prefManager.processingResolution
            val processingHeight = (screenHeight * processingWidth / screenWidth)

            Log.d(TAG, "Processing resolution: ${processingWidth}x${processingHeight}")

            imageReader?.close()

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

            Log.d(TAG, "Media projection started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting media projection", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startProcessing() {
        Log.d(TAG, "Starting processing loop")
        processingJob = serviceScope.launch {
            while (isActive) {
                try {
                    val frameDelay = (1000 / currentFps).toLong()
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
                    Log.e(TAG, "Error in processing loop", e)
                    e.printStackTrace()
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
                    isProcessing = false
                    return
                }

                bitmap = imageToBitmap(image)
                image.close()

                val currentHash = ImageUtils.calculateHash(bitmap)

                if (!prefManager.adaptiveFps ||
                    ImageUtils.hasSignificantMotion(lastFrameHash, currentHash, 5)) {

                    lastFrameHash = currentHash
                    processFrame(bitmap)
                } else {
                    overlayService.clearBlur()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in captureAndProcess", e)
                e.printStackTrace()
            } finally {
                image?.close()
                bitmap?.recycle()
                isProcessing = false
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        try {
            val detector = bodyDetector ?: return

            val bodies = detector.detectBodies(bitmap)

            if (bodies.isEmpty()) {
                overlayService.clearBlur()
                return
            }

            Log.d(TAG, "Detected ${bodies.size} bodies")

            // Blur ALL detected bodies (no gender classification)
            val blurredBitmap = applyBlurToRegions(bitmap, bodies)
            overlayService.updateBlur(blurredBitmap, bodies.map { it.boundingBox })

        } catch (e: Exception) {
            Log.e(TAG, "Error in processFrame", e)
            e.printStackTrace()
            overlayService.clearBlur()
        }
    }

    private fun applyBlurToRegions(
        bitmap: Bitmap,
        regions: List<BodyDetectorMediaPipe.BodyDetection>
    ): Bitmap {
        val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

        for (detection in regions) {
            try {
                val rect = Rect(
                    detection.boundingBox.left.toInt().coerceIn(0, bitmap.width - 1),
                    detection.boundingBox.top.toInt().coerceIn(0, bitmap.height - 1),
                    detection.boundingBox.right.toInt().coerceIn(0, bitmap.width),
                    detection.boundingBox.bottom.toInt().coerceIn(0, bitmap.height)
                )

                // Validate rect
                if (rect.width() <= 0 || rect.height() <= 0) {
                    Log.w(TAG, "Invalid rect: $rect")
                    continue
                }

                val regionBitmap = ImageUtils.cropRegion(result, rect)

                val blurred = if (renderScript != null) {
                    // Use RenderScript blur
                    val blurRadius = (prefManager.blurIntensity * 2.5f).toInt().coerceIn(1, 25)
                    ImageUtils.applyRenderScriptBlur(renderScript!!, regionBitmap, blurRadius)
                } else {
                    // Fallback to pixelation
                    ImageUtils.applyPixelation(regionBitmap, prefManager.blurIntensity + 5)
                }

                val canvas = Canvas(result)
                val paint = Paint()
                canvas.drawBitmap(blurred, rect.left.toFloat(), rect.top.toFloat(), paint)

                regionBitmap.recycle()
                blurred.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Error blurring region", e)
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
        Log.d(TAG, "Service onDestroy called")

        try {
            processingJob?.cancel()
            serviceScope.cancel()

            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()

            overlayService.stop()
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