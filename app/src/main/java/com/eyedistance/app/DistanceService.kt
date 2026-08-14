package com.eyedistance.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class DistanceService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastAnalyzedTimestamp = 0L

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlayView()
        startCamera()
    }

    private fun startForegroundNotification() {
        val channelId = "EyeGuardChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Eye Guard Active", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Eye Guard Active")
            .setContentText("Monitoring safe screen distance in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(101, notification)
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B3000000")) // 70% dimming

            val emojiView = TextView(context).apply {
                text = "🦊"
                textSize = 80f
                gravity = Gravity.CENTER
            }
            val titleView = TextView(context).apply {
                text = "SIT BACK!"
                textSize = 34f
                setTextColor(Color.parseColor("#4CAF50"))
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 10)
            }
            val subTextView = TextView(context).apply {
                text = "Please move the screen further away 😊"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            addView(emojiView)
            addView(titleView)
            addView(subTextView)
        }
        overlayView = layout
    }

    private fun getActiveThreshold(): Float {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
        val currentApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""

        // 15% threshold for YouTube (further distance), 25% for Roblox / other games
        return if (currentApp.contains("youtube", ignoreCase = true)) 0.15f else 0.25f
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        // Throttle to roughly 2 frames per second to save battery
        if (currentTimestamp - lastAnalyzedTimestamp < 500) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimestamp = currentTimestamp

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val frameWidth = if (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270) {
                        imageProxy.height
                    } else {
                        imageProxy.width
                    }

                    if (faces.isNotEmpty()) {
                        val primaryFace = faces[0]
                        val faceRatio = primaryFace.boundingBox.width().toFloat() / frameWidth.toFloat()
                        val threshold = getActiveThreshold()

                        if (faceRatio > threshold) {
                            showOverlay()
                        } else {
                            hideOverlay()
                        }
                    } else {
                        hideOverlay()
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun showOverlay() {
        if (!isOverlayShowing && overlayView != null) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    )
                    windowManager?.addView(overlayView, params)
                    isOverlayShowing = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun hideOverlay() {
        if (isOverlayShowing && overlayView != null) {
            Handler(Looper.getMainLooper()).post {
                try {
                    windowManager?.removeView(overlayView)
                    isOverlayShowing = false
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        cameraExecutor.shutdown()
        detector.close()
    }
}
