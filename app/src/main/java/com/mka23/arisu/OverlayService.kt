package com.mka23.arisu

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: ImageView? = null
    private var panel: LinearLayout? = null
    private var character: ImageView? = null
    private var halo: ImageView? = null
    private var message: TextView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var sequenceToken = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        showFloatingArisu()
    }

    private fun startAsForeground() {
        val channelId = "arisu_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "아리스 비서", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("아리스가 대기 중입니다")
                .setContentText("아이콘을 눌러 아리스를 불러보세요.")
                .build()
            startForeground(23, notification)
        } else {
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("아리스가 대기 중입니다")
                .build()
            startForeground(23, notification)
        }
    }

    private fun windowParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = dp(16)
            y = dp(160)
        }
    }

    private fun showFloatingArisu() {
        if (bubble != null) return
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.arisu_default)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0xFFEAF7FF.toInt(), 100f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(8).toFloat()
            contentDescription = "아리스 열기"
        }
        icon.setOnClickListener { playLoginSequence() }
        bubble = icon
        windowManager.addView(icon, windowParams(dp(70), dp(70), Gravity.TOP or Gravity.START))
    }

    private fun playLoginSequence() {
        sequenceToken++
        val token = sequenceToken
        showCharacterPanel()
        setCharacter(R.drawable.arisu_default)
        message?.text = "연결 완료..."
        playSound(false)

        handler.postDelayed({
            if (token != sequenceToken || panel == null) return@postDelayed
            setCharacter(R.drawable.arisu_big_smile)
            character?.animate()?.cancel()
            character?.translationY = 0f
            character?.animate()
                ?.translationY(-dp(16).toFloat())
                ?.setDuration(110)
                ?.withEndAction {
                    character?.animate()
                        ?.translationY(dp(7).toFloat())
                        ?.setDuration(100)
                        ?.withEndAction {
                            character?.animate()?.translationY(0f)?.setDuration(110)?.start()
                        }?.start()
                }?.start()

            playSound(true)
            message?.text = "선생님! 아이리스, 로그인 성공입니다!"
        }, 950)
    }

    private fun showCharacterPanel() {
        if (panel != null) {
            panel?.visibility = View.VISIBLE
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFFF4FAFF.toInt(), dp(22).toFloat())
            elevation = dp(16).toFloat()
        }
        val stage = FrameLayout(this)
        val haloView = ImageView(this).apply {
            setImageResource(R.drawable.arisu_halo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        halo = haloView
        stage.addView(haloView, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.arisu_default)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        character = portrait
        stage.addView(portrait, FrameLayout.LayoutParams(-1, dp(270), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(285)))

        val title = TextView(this).apply {
            text = "아리스"
            textSize = 18f
            setTextColor(0xFF17618A.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title)
        message = TextView(this).apply {
            text = "연결 완료..."
            textSize = 15f
            setTextColor(0xFF243746.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        root.addView(message)
        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { closePanel() }
        })
        panel = root
        val params = windowParams(dp(330), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            y = dp(18)
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        windowManager.addView(root, params)
        animateHalo()
    }

    private fun animateHalo() {
        val view = halo ?: return
        view.animate().translationY(-dp(3).toFloat()).setDuration(700).withEndAction {
            view.animate().translationY(dp(3).toFloat()).setDuration(700).withEndAction {
                if (panel != null && panel?.visibility == View.VISIBLE) animateHalo()
            }.start()
        }.start()
    }

    private fun setCharacter(resourceId: Int) {
        val view = character ?: return
        view.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(120).withEndAction {
            view.setImageResource(resourceId)
            view.translationY = -dp(10).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(170).start()
        }.start()
    }

    // Play the original sound files uploaded to res/raw.
    private fun playSound(success: Boolean) {
        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(
                this,
                if (success) R.raw.success else R.raw.confirm
            )?.apply {
                setOnCompletionListener { player ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                }
                start()
            }
        } catch (_: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun closePanel() {
        sequenceToken++
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        panel = null
        character = null
        halo = null
        message = null
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        closePanel()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
