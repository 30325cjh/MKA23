package com.mka23.arisu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.FileDescriptor

class ScreenRecordService : Service() {
    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var isRecording = false
    private var isPaused = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (isRecording) stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (isRecording && !isPaused && Build.VERSION.SDK_INT >= 24) {
                    try { recorder?.pause(); isPaused = true } catch (_: Exception) {}
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (isRecording && isPaused && Build.VERSION.SDK_INT >= 24) {
                    try { recorder?.resume(); isPaused = false } catch (_: Exception) {}
                }
                return START_STICKY
            }
            ACTION_START -> startCapture(intent)
        }
        return START_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (isRecording) return
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle("아리스 화면 녹화 중")
            .setContentText("알림에서 녹화를 종료할 수 있습니다.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
            } ?: throw IllegalStateException("화면 캡처 권한 데이터가 없습니다.")

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("화면 캡처 권한을 가져오지 못했습니다.")
            projection!!.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels / 2) * 2
            val height = (metrics.heightPixels / 2) * 2
            val density = metrics.densityDpi

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Arisu_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Arisu")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("녹화 파일을 만들 수 없습니다.")
            outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: throw IllegalStateException("녹화 파일을 열 수 없습니다.")

            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(outputDescriptor!!.fileDescriptor)
                prepare()
            }

            virtualDisplay = projection!!.createVirtualDisplay(
                "ArisuScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
            recorder!!.start()
            isRecording = true
            isPaused = false
        } catch (e: Exception) {
            stopRecording()
            android.util.Log.e("ArisuRecorder", "Screen recording failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "화면 녹화", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            try { recorder?.stop() } catch (_: Exception) {}
        }
        isRecording = false
        isPaused = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        val activeProjection = projection
        projection = null
        try { activeProjection?.stop() } catch (_: Exception) {}
        try { outputDescriptor?.close() } catch (_: Exception) {}
        outputDescriptor = null
        outputUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try { contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
            }
        }
        outputUri = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRecording || projection != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mka23.arisu.RECORD_START"
        const val ACTION_PAUSE = "com.mka23.arisu.RECORD_PAUSE"
        const val ACTION_RESUME = "com.mka23.arisu.RECORD_RESUME"
        const val ACTION_STOP = "com.mka23.arisu.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "arisu_screen_recording"
        private const val NOTIFICATION_ID = 24
    }
}
