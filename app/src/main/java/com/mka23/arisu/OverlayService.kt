package com.mka23.arisu

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
    private var message: TextView? = null
    private var chatBar: LinearLayout? = null
    private var chatLog: TextView? = null
    private var chatInput: EditText? = null
    private var mediaPlayer: MediaPlayer? = null
    private var sequenceToken = 0
    private var currentLine = ""
    private var charIndex = 0
    private var typing = false
    private var waitingForTap = false
    private var dialogueStage = 0
    private var typingRunnable: Runnable? = null

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
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("아리스가 대기 중입니다")
                .setContentText("아이콘을 눌러 아리스를 불러보세요.")
                .build()
            startForeground(23, notification)
        } else {
            @Suppress("DEPRECATION")
            val notification = Notification.Builder(this)
                .setSmallIcon(R.drawable.icon)
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
            setImageResource(R.drawable.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Color.TRANSPARENT, dp(16).toFloat())
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
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        dialogueStage = 0
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null

        setCharacter(R.drawable.arisu_default)
        message?.text = "아리스 연결중○○○"
        playSound(R.raw.unknow)

        val progress = arrayOf("아리스 연결중●○○", "아리스 연결중●●○", "아리스 연결중●●●")
        progress.forEachIndexed { index, text ->
            handler.postDelayed({
                if (token == sequenceToken && panel != null) message?.text = text
            }, 500L * (index + 1))
        }
        handler.postDelayed({
            if (token != sequenceToken || panel == null) return@postDelayed
            // Start the expression change, motion, sound and voice line in the same frame.
            setCharacter(R.drawable.arisu_smile)
            animateBounce(1)
            playSound(R.raw.success)
            dialogueStage = 1
            startTypewriter("로그인 성공!")
        }, 1750L)
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
            background = rounded(Color.BLACK, dp(22).toFloat())
            elevation = dp(16).toFloat()
        }
        val stage = FrameLayout(this).apply {
            setOnClickListener { handleDialogueTap() }
        }
        val portrait = ImageView(this).apply {
            setImageResource(R.drawable.arisu_default)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            setOnClickListener { handleDialogueTap() }
        }
        character = portrait
        stage.addView(portrait, FrameLayout.LayoutParams(-1, dp(350), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(365)))

        val title = TextView(this).apply {
            text = "아리스"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title)
        message = TextView(this).apply {
            text = "아리스 연결중○○○"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener { handleDialogueTap() }
        }
        root.addView(message)
        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { closePanel() }
        })
        panel = root
        val panelWidth = minOf(dp(440), resources.displayMetrics.widthPixels - dp(28))
        val params = windowParams(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            y = dp(18)
            // The panel must be focusable so the final chat field can open the keyboard.
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        windowManager.addView(root, params)
    }

    private fun handleDialogueTap() {
        if (typing) {
            typingRunnable?.let { handler.removeCallbacks(it) }
            typingRunnable = null
            message?.text = currentLine
            typing = false
            waitingForTap = true
            return
        }
        if (!waitingForTap) return
        waitingForTap = false
        when (dialogueStage) {
            1 -> {
                // These four actions intentionally begin together, not after the motion ends.
                setCharacter(R.drawable.arisu_default)
                animateBounce(2)
                playSound(R.raw.smile)
                dialogueStage = 2
                startTypewriter("반갑습니다, 센세!")
            }
            2 -> {
                // These four actions intentionally begin together, not after the motion ends.
                setCharacter(R.drawable.arisu_jashinmanman)
                animateBounce(1)
                playSound(R.raw.why)
                dialogueStage = 3
                startTypewriter("명령을 내려주세요!")
            }
            3 -> showChatBar()
        }
    }

    private fun startTypewriter(line: String) {
        currentLine = line
        charIndex = 0
        typing = true
        waitingForTap = false
        message?.text = ""
        val token = sequenceToken
        val task = object : Runnable {
            override fun run() {
                if (token != sequenceToken || panel == null) return
                if (charIndex < currentLine.length) {
                    charIndex++
                    message?.text = currentLine.substring(0, charIndex)
                    handler.postDelayed(this, 38L)
                } else {
                    typing = false
                    waitingForTap = true
                    typingRunnable = null
                }
            }
        }
        typingRunnable = task
        handler.post(task)
    }

    private fun showChatBar() {
        val root = panel ?: return
        if (chatBar != null) return
        val log = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatLog = log
        root.addView(log, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val input = EditText(this).apply {
            hint = "아리스에게 메시지 입력..."
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            background = rounded(0xFF242424.toInt(), dp(12).toFloat())
            setPadding(dp(10), 0, dp(10), 0)
        }
        chatInput = input
        bar.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(Button(this).apply {
            text = "전송"
            setOnClickListener {
                val typedText = input.text.toString().trim()
                if (typedText.isNotEmpty()) {
                    log.text = if (log.text.isNullOrEmpty()) "센세: $typedText" else log.text.toString() + "\n센세: $typedText"
                    input.text.clear()
                }
            }
        }, LinearLayout.LayoutParams(dp(68), dp(48)))
        chatBar = bar
        root.addView(bar)
        root.requestFocus()
    }

    private fun setCharacter(resourceId: Int) {
        // Swap the artwork immediately; any movement starts in the same UI frame.
        character?.apply {
            animate().cancel()
            setImageResource(resourceId)
            alpha = 1f
            translationY = 0f
        }
    }

    private fun animateBounce(count: Int) {
        val view = character ?: return
        fun oneBounce(remaining: Int) {
            if (remaining <= 0) return
            view.animate().cancel()
            view.animate().translationY(-dp(15).toFloat()).setDuration(95).withEndAction {
                view.animate().translationY(dp(6).toFloat()).setDuration(85).withEndAction {
                    view.animate().translationY(0f).setDuration(90).withEndAction {
                        oneBounce(remaining - 1)
                    }.start()
                }.start()
            }.start()
        }
        oneBounce(count)
    }

    private fun playSound(soundId: Int) {
        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, soundId)?.apply {
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
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        panel = null
        character = null
        message = null
        chatBar = null
        chatInput = null
        chatLog = null
        typing = false
        waitingForTap = false
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
