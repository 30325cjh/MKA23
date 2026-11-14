package com.mka23.arisu

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var bubble: ImageView? = null
    private var panel: LinearLayout? = null
    private var character: ImageView? = null
    private var primaryCharacterView: ImageView? = null
    private var partnerCharacter: ImageView? = null
    private var companionsPresent = false
    private var assistantBaseCharacter = "arisu"
    private var message: TextView? = null
    private var panelTitle: TextView? = null
    private var activeCharacter: String = "arisu"
    private var chatBar: LinearLayout? = null
    private var chatLog: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var mediaPlayer: MediaPlayer? = null
    private var dialogueVoicePlayer: MediaPlayer? = null
    private var bgmPlayer: MediaPlayer? = null
    private var currentBgmAssetPath: String? = null
    private var bgmEnhancer: LoudnessEnhancer? = null
    private var pendingAttachmentUri: Uri? = null
    private var pendingAttachmentName: String? = null
    private var attachmentButton: TextView? = null
    private var assistantGeneratedFiles: List<JSONObject> = emptyList()
    private val conversation = mutableListOf<Pair<String, String>>()
    private var sequenceToken = 0
    private var currentLine = ""
    private var charIndex = 0
    private var typing = false
    private var waitingForTap = false
    private var dialogueStage = 0
    private var typingRunnable: Runnable? = null
    private var portraitStage: FrameLayout? = null
    private var assistantDialogueActive = false
    private var assistantLines: List<JSONObject> = emptyList()
    private var assistantLineIndex = 0
    private var assistantFullReply = ""
    private var recordBubble: TextView? = null
    private var recordMenu: FrameLayout? = null
    private var recordingPaused = false
    private var homeCloseReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.mka23.arisu.PICKER_CLOSED") {
            bubble?.visibility = View.VISIBLE
            panel?.visibility = View.VISIBLE
        }
        if (intent?.action == "com.mka23.arisu.ATTACHMENT_SELECTED") {
            bubble?.visibility = View.VISIBLE
            panel?.visibility = View.VISIBLE
            pendingAttachmentUri = intent.data
            pendingAttachmentName = pendingAttachmentUri?.let { uri ->
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
            } ?: "첨부 파일"
            attachmentButton?.text = "📎"
            Toast.makeText(this, "$pendingAttachmentName 첨부 완료", Toast.LENGTH_SHORT).show()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        registerHomeCloseReceiver()
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
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            setPadding(0, 0, 0, 0)
            clipToOutline = true
            elevation = dp(8).toFloat()
            contentDescription = "아리스 열기"
        }
        icon.setOnClickListener { showCharacterSelectDialog() }
        bubble = icon
        val bubbleParams = windowParams(dp(70), dp(70), Gravity.TOP or Gravity.START)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val longPressDrag = Runnable { dragging = true }
        icon.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    handler.removeCallbacks(longPressDrag)
                    handler.postDelayed(longPressDrag, 350L)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        bubbleParams.x = (startX + event.rawX - downRawX).toInt().coerceAtLeast(0)
                        bubbleParams.y = (startY + event.rawY - downRawY).toInt().coerceAtLeast(0)
                        try { windowManager.updateViewLayout(view, bubbleParams) } catch (_: Exception) {}
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressDrag)
                    if (!dragging && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        windowManager.addView(icon, bubbleParams)
    }


    private fun showCharacterSelectDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = AlertDialog.Builder(this).setTitle("비서 선택").setView(layout).setNegativeButton("취소", null).create()
        fun addChoice(name: String, label: String, asset: String) {
            val choice = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = rounded(0xFF202735.toInt(), dp(14).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    activeCharacter = name
                    companionsPresent = false
                    partnerCharacter?.visibility = View.GONE
                    primaryCharacterView?.layoutParams = FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    getSharedPreferences("assistant_character", Context.MODE_PRIVATE).edit().putString("active", name).apply()
                    startBgm()
                    dialog.dismiss()
                    if (name == "arisu") {
                        if (hasCompletedInitialLogin()) playWelcomeSequence() else playLoginSequence()
                    } else {
                        if (hasCompletedKeiInitialLogin()) playKeiWelcomeSequence() else playKeiLoginSequence()
                    }
                }
            }
            choice.addView(ImageView(this).apply {
                setImageBitmap(loadAssetBitmap(asset))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(125), dp(165)))
            choice.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            layout.addView(choice, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        addChoice("arisu", "텐도 아리스", "arisu/images/arisu_default.jpg")
        addChoice("kei", "텐도 케이", "kei/images/default.png")
        setOverlayDialog(dialog)
        dialog.show()
    }

    private fun showCharacterControlDialog() {
        if (!companionsPresent) {
            showCharacterSelectDialog()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("누구를 내보내겠습니까?")
            .setItems(arrayOf("텐도 아리스", "텐도 케이")) { _, which ->
                dismissCompanion(if (which == 0) "arisu" else "kei")
            }
            .setNegativeButton("취소", null)
            .create()
        setOverlayDialog(dialog)
        dialog.show()
    }

    private fun dismissCompanion(leavingCharacter: String) {
        if (!companionsPresent) return
        val leavingIsPrimary = leavingCharacter == assistantBaseCharacter
        val leavingName = if (leavingCharacter == "arisu") "아리스" else "케이"
        val farewell = if (leavingCharacter == "arisu") {
            "아리스는 이만 물러나겠습니다, 센세!"
        } else {
            "흥... 저는 이만 가보겠습니다, 센세."
        }

        sequenceToken++
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        typing = false
        waitingForTap = false
        assistantDialogueActive = false
        assistantLines = emptyList()
        assistantLineIndex = 0
        stopDialogueVoice()
        message?.visibility = View.VISIBLE
        message?.text = farewell
        if (leavingCharacter == "arisu") playSound("arisu/sounds/smile.mp3")
        else playSound("kei/sounds/sigh.mp3")

        handler.postDelayed({
            val stage = portraitStage
            val primary = primaryCharacterView
            val partner = partnerCharacter
            val remainingCharacter = if (leavingCharacter == "arisu") "kei" else "arisu"

            if (leavingIsPrimary && partner != null) {
                try { stage?.removeView(primary) } catch (_: Exception) {}
                partner.layoutParams = FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                primaryCharacterView = partner
                character = partner
                partnerCharacter = null
            } else {
                try { stage?.removeView(partner) } catch (_: Exception) {}
                partnerCharacter = null
                primaryCharacterView?.layoutParams = FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                character = primaryCharacterView
            }

            companionsPresent = false
            assistantBaseCharacter = remainingCharacter
            activeCharacter = remainingCharacter
            getSharedPreferences("assistant_character", Context.MODE_PRIVATE)
                .edit().putString("active", remainingCharacter).apply()
            panelTitle?.text = if (remainingCharacter == "arisu") "아리스" else "케이"
            chatInput?.hint = if (remainingCharacter == "arisu") "아리스에게 메시지 입력..." else "케이에게 메시지 입력..."
            message?.visibility = View.GONE
            startBgm()
            resizePortrait(false)
            panel?.requestLayout()
        }, 1900L)
    }

    private fun keiLaunchPreferences() = getSharedPreferences("kei_launch_settings", Context.MODE_PRIVATE)

    private fun hasCompletedKeiInitialLogin(): Boolean =
        keiLaunchPreferences().getBoolean("initial_login_completed", false)

    private fun markKeiInitialLoginCompleted() {
        keiLaunchPreferences().edit().putBoolean("initial_login_completed", true).apply()
    }

    private fun playKeiLoginSequence() {
        sequenceToken++
        val token = sequenceToken
        showCharacterPanel()
        resizePortrait(true)
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        dialogueStage = 0
        assistantDialogueActive = false
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        conversation.clear()
        stopDialogueVoice()

        panelTitle?.text = "케이"
        setCharacter("kei/images/default.png")
        message?.visibility = View.VISIBLE
        message?.text = "케이 연결중○○○"
        playSound("kei/sounds/unknow.mp3")
        playDialogueVoice("kei/sounds/Klogin_loading.mp3")

        val progress = arrayOf("케이 연결중●○○", "케이 연결중●●○", "케이 연결중●●●")
        progress.forEachIndexed { index, text ->
            handler.postDelayed({
                if (token == sequenceToken && panel != null) message?.text = text
            }, 500L * (index + 1))
        }
        handler.postDelayed({
            if (token != sequenceToken || panel == null) return@postDelayed
            setCharacter("kei/images/default.png")
            playSound("kei/sounds/success.mp3")
            playDialogueVoice("kei/sounds/Klogin_success.mp3")
            dialogueStage = 1
            startTypewriter("로그인 완료.")
        }, 1750L)
    }

    private fun playKeiWelcomeSequence() {
        sequenceToken++
        showCharacterPanel()
        resizePortrait(true)
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        dialogueStage = 2
        assistantDialogueActive = false
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        conversation.clear()
        stopDialogueVoice()
        panelTitle?.text = "케이"
        setCharacter("kei/images/default.png")
        message?.visibility = View.VISIBLE
        animateMotion("bounce")
        playSound("kei/sounds/sigh.mp3")
        playDialogueVoice("kei/sounds/Kgreeting.mp3")
        startTypewriter("하... 부르셨습니까, 센세.")
    }

    private fun startKeiAssistant() {
        sequenceToken++
        showCharacterPanel()
        resizePortrait(false)
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        assistantDialogueActive = false
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        conversation.clear()
        stopDialogueVoice()
        panelTitle?.text = "케이"
        setCharacter("kei/images/default.png")
        message?.visibility = View.GONE
        showChatBar()
        startBgm()
    }

    private fun playLoginSequence() {
        sequenceToken++
        val token = sequenceToken
        showCharacterPanel()
        resizePortrait(true)
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        dialogueStage = 0
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        conversation.clear()
        stopDialogueVoice()

        panelTitle?.text = "아리스"
        setCharacter("arisu/images/arisu_default.jpg")
        message?.text = "아리스 연결중○○○"
        playDialogueVoice("arisu/sounds/login_loading.mp3")
        playSound("arisu/sounds/unknow.mp3")

        val progress = arrayOf("아리스 연결중●○○", "아리스 연결중●●○", "아리스 연결중●●●")
        progress.forEachIndexed { index, text ->
            handler.postDelayed({
                if (token == sequenceToken && panel != null) message?.text = text
            }, 500L * (index + 1))
        }
        handler.postDelayed({
            if (token != sequenceToken || panel == null) return@postDelayed
            setCharacter("arisu/images/arisu_smile.jpg")
            animateMotion("bounce")
            playSound("arisu/sounds/success.mp3")
            playDialogueVoice("arisu/sounds/login_success.mp3")
            dialogueStage = 1
            startTypewriter("로그인 성공!")
        }, 1750L)
    }

    private fun launchPreferences() = getSharedPreferences("arisu_launch_settings", Context.MODE_PRIVATE)

    private fun hasCompletedInitialLogin(): Boolean = launchPreferences().getBoolean("initial_login_completed", false)

    private fun markInitialLoginCompleted() {
        launchPreferences().edit().putBoolean("initial_login_completed", true).apply()
    }

    private fun playWelcomeSequence() {
        sequenceToken++
        showCharacterPanel()
        resizePortrait(true)
        typingRunnable?.let { handler.removeCallbacks(it) }
        typing = false
        waitingForTap = false
        dialogueStage = 2
        assistantDialogueActive = false
        chatBar?.let { panel?.removeView(it) }
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        conversation.clear()
        stopDialogueVoice()
        panelTitle?.text = "아리스"
        setCharacter("arisu/images/arisu_default.jpg")
        message?.visibility = View.VISIBLE
        playDialogueVoice("arisu/sounds/greeting.mp3")
        startTypewriter("반갑습니다, 센세!")
    }

    private fun showCharacterPanel() {
        if (panel != null) {
            panel?.visibility = View.VISIBLE
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.BLACK, dp(22).toFloat())
            elevation = dp(16).toFloat()
        }
        val stage = FrameLayout(this).apply {
            setOnClickListener { handleDialogueTap() }
        }
        val portrait = ImageView(this).apply {
            setImageBitmap(loadAssetBitmap(if (activeCharacter == "kei") "kei/images/default.png" else "arisu/images/arisu_default.jpg"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            setOnClickListener { handleDialogueTap() }
        }
        character = portrait
        primaryCharacterView = portrait
        partnerCharacter = null
        portraitStage = stage
        stage.addView(portrait, FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(260)))

        val header = FrameLayout(this)
        val title = TextView(this).apply {
            text = if (activeCharacter == "kei") "케이" else "아리스"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnLongClickListener {
                showApiKeyDialog()
                true
            }
        }
        panelTitle = title
        header.addView(title, FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER))
        val bgmButton = TextView(this).apply {
            text = "♫"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "BGM 볼륨 조절"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF315C9B.toInt())
            }
            elevation = dp(3).toFloat()
            setOnClickListener { showBgmVolumeDialog() }
        }
        header.addView(bgmButton, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.START or Gravity.TOP))
        val characterControlButton = TextView(this).apply {
            text = "⇄"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "캐릭터 선택 및 퇴장"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF315C9B.toInt())
            }
            elevation = dp(3).toFloat()
            setOnClickListener { showCharacterControlDialog() }
        }
        header.addView(characterControlButton, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END or Gravity.TOP))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        message = TextView(this).apply {
            text = "아리스 연결중○○○"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { handleDialogueTap() }
        }
        root.addView(message)
        root.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { closePanel() }
        })
        panel = root
        val panelWidth = minOf(dp(660), resources.displayMetrics.widthPixels - dp(28))
        val params = windowParams(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            y = dp(18)
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        windowManager.addView(root, params)
        startBgm()
    }



    private fun registerHomeCloseReceiver() {
        if (homeCloseReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS &&
                    intent.getStringExtra("reason") == "homekey") {
                    closePanel()
                }
            }
        }
        homeCloseReceiver = receiver
        val filter = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun setOverlayDialog(dialog: AlertDialog) {
        @Suppress("DEPRECATION")
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        )
    }





    private fun bgmPreferences() = getSharedPreferences("arisu_bgm_settings", Context.MODE_PRIVATE)

    private fun currentBgmProgress(): Int = bgmPreferences().getInt("volume", 55).coerceIn(0, 100)

    private fun currentBgmVolume(): Float {
        val linear = currentBgmProgress() / 100f
        return linear * linear
    }

    private fun applyBgmVolume(progress: Int) {
        val linear = progress.coerceIn(0, 100) / 100f
        val perceptual = linear * linear
        bgmPlayer?.setVolume(perceptual, perceptual)
        bgmPreferences().edit().putInt("volume", progress.coerceIn(0, 100)).apply()
    }

    private fun startBgm() {
        val bgmPath = when {
            companionsPresent -> "arisu/sounds/AKBGM.mp3"
            activeCharacter == "kei" -> "kei/sounds/Kbgm.mp3"
            else -> "arisu/sounds/bgm.mp3"
        }
        if (bgmPlayer?.isPlaying == true && currentBgmAssetPath == bgmPath) return
        try {
            bgmPlayer?.release()
            currentBgmAssetPath = bgmPath
            bgmPlayer = createAssetMediaPlayer(bgmPath)?.apply {
                isLooping = true
                val volume = currentBgmVolume()
                setVolume(volume, volume)
                try {
                    bgmEnhancer?.release()
                    bgmEnhancer = LoudnessEnhancer(audioSessionId).apply {
                        setTargetGain(600)
                        enabled = true
                    }
                } catch (_: Exception) { bgmEnhancer = null }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (bgmPlayer === player) bgmPlayer = null
                    true
                }
                start()
            }
        } catch (_: Exception) {
            bgmPlayer?.release()
            bgmPlayer = null
        }
    }

    private fun showBgmVolumeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        val valueLabel = TextView(this).apply {
            text = "BGM 볼륨 ${currentBgmProgress()}% · 음량 보강"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        layout.addView(valueLabel)
        val slider = SeekBar(this).apply {
            max = 100
            progress = currentBgmProgress()
        }
        layout.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("배경음악 볼륨")
            .setView(layout)
            .setNegativeButton("닫기", null)
            .create()
        @Suppress("DEPRECATION")
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyBgmVolume(progress)
                valueLabel.text = "BGM 볼륨 ${progress}% · 음량 보강"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialog.show()
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
        if (assistantDialogueActive) {
            if (!waitingForTap) return
            waitingForTap = false
            assistantLineIndex++
            if (assistantLineIndex < assistantLines.size) showAssistantLine()
            else finishAssistantSequence()
            return
        }
        if (!waitingForTap) return
        waitingForTap = false
        if (activeCharacter == "kei") {
            when (dialogueStage) {
                1 -> {
                    stopDialogueVoice()
                    setCharacter("kei/images/default.png")
                    animateMotion("bounce")
                    playSound("kei/sounds/sigh.mp3")
                    playDialogueVoice("kei/sounds/Kgreeting.mp3")
                    dialogueStage = 2
                    startTypewriter("하... 부르셨습니까, 센세.")
                }
                2 -> {
                    stopDialogueVoice()
                    setCharacter("kei/images/default.png")
                    playSound("kei/sounds/wtf.mp3")
                    playDialogueVoice("kei/sounds/Kcommand.mp3")
                    dialogueStage = 3
                    startTypewriter("또 뭘 시키시려는 겁니까?")
                }
                3 -> {
                    stopDialogueVoice()
                    markKeiInitialLoginCompleted()
                    showChatBar()
                }
            }
            return
        }
        when (dialogueStage) {
            1 -> {
                stopDialogueVoice()
                setCharacter("arisu/images/arisu_default.jpg")
                animateMotion("double_bounce")
                playSound("arisu/sounds/smile.mp3")
                playDialogueVoice("arisu/sounds/greeting.mp3")
                dialogueStage = 2
                startTypewriter("반갑습니다, 센세!")
            }
            2 -> {
                stopDialogueVoice()
                setCharacter("arisu/images/arisu_jashinmanman.jpg")
                animateMotion("bounce")
                playSound("arisu/sounds/why.mp3")
                playDialogueVoice("arisu/sounds/command.mp3")
                dialogueStage = 3
                startTypewriter("명령을 내려주세요!")
            }
            3 -> {
                stopDialogueVoice()
                markInitialLoginCompleted()
                showChatBar()
            }
            4 -> {
                stopDialogueVoice()
                showChatBar()
            }
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

    // The input bar keeps its original compact dimensions; chat messages remain SMS-style.
    private fun showChatBar() {
        val root = panel ?: return
        resizePortrait(false)
        chatScroll?.visibility = View.VISIBLE
        chatBar?.visibility = View.VISIBLE
        if (chatBar != null) return
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatLog = messages
        val history = ScrollView(this).apply {
            isFillViewport = true
            background = rounded(0xFF151515.toInt(), dp(14).toFloat())
            addView(messages, FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        chatScroll = history
        root.addView(history, LinearLayout.LayoutParams(-1, dp(90)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val attachButton = TextView(this).apply {
            text = if (pendingAttachmentUri == null) "＋" else "📎"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "이미지 또는 HTML 파일 첨부"
            background = rounded(0xFF315C9B.toInt(), dp(12).toFloat())
            setOnClickListener {
                panel?.visibility = View.GONE
                bubble?.visibility = View.GONE
                val picker = Intent(this@OverlayService, MainActivity::class.java).apply {
                    action = "com.mka23.arisu.PICK_ATTACHMENT"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(picker)
            }
        }
        attachmentButton = attachButton
        bar.addView(attachButton, LinearLayout.LayoutParams(dp(46), dp(48)).apply { rightMargin = dp(6) })
        val input = EditText(this).apply {
            hint = if (activeCharacter == "kei") "케이에게 메시지 입력..." else "아리스에게 메시지 입력..."
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            background = rounded(0xFF242424.toInt(), dp(12).toFloat())
            setPadding(dp(10), 0, dp(10), 0)
        }
        chatInput = input
        bar.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        val sendButton = Button(this).apply {
            text = "전송"
            setOnClickListener { submitChatMessage() }
        }
        bar.addView(sendButton, LinearLayout.LayoutParams(dp(68), dp(48)))
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                submitChatMessage()
                true
            } else false
        }
        chatBar = bar
        root.addView(bar)
        root.requestFocus()
    }

    private fun hideChatKeyboard(input: EditText) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {}
        input.clearFocus()
        panel?.requestFocus()
    }

    private fun expandChatHistory() {
        val history = chatScroll ?: return
        val params = history.layoutParams as? LinearLayout.LayoutParams ?: return
        val start = if (params.height > 0) params.height else dp(90)
        val target = dp(220)
        if (start == target) return
        ValueAnimator.ofInt(start, target).apply {
            duration = 260L
            addUpdateListener { animator ->
                val lp = history.layoutParams as? LinearLayout.LayoutParams ?: return@addUpdateListener
                lp.height = animator.animatedValue as Int
                history.layoutParams = lp
                panel?.requestLayout()
            }
            start()
        }
    }

    private fun submitChatMessage() {
        val input = chatInput ?: return
        val rawTypedText = input.text.toString().trim()
        val typedText = rawTypedText
        val attachment = pendingAttachmentUri
        val attachmentName = pendingAttachmentName
        val text = typedText.ifEmpty {
            if (attachment != null) "첨부한 파일을 확인해줘." else return
        }
        val summonedPartner = (activeCharacter == "arisu" && text.contains("케이") || activeCharacter == "kei" && text.contains("아리스")) &&
            listOf("불러", "와줘", "와 주세요", "같이", "참여", "데려와", "불러와", "오라고", "나와", "등장", "호출", "소환").any { text.contains(it) }
        if (summonedPartner) enableCompanionMode()
        input.text.clear()
        hideChatKeyboard(input)
        expandChatHistory()
        pendingAttachmentUri = null
        pendingAttachmentName = null
        attachmentButton?.text = "＋"
        ensureApiKey {
            val shownText = if (attachment != null) "$text\n📎 ${attachmentName ?: "첨부 파일"}" else text
            addChatMessage(shownText, true)
            requestGemini(text, 0, attachment, attachmentName)
        }
    }

    private fun addChatMessage(text: String, fromUser: Boolean): View {
        val messages = chatLog ?: return TextView(this)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
            background = rounded(if (fromUser) 0xFF315C9B.toInt() else 0xFF303030.toInt(), dp(16).toFloat())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        messages.addView(row, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
        return row
    }

    private fun ensureApiKey(onReady: () -> Unit) {
        if (!getApiKey().isNullOrBlank()) {
            onReady()
        } else {
            showApiKeyDialog(onReady)
        }
    }

    private fun showApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "AQ. 또는 AIza로 시작하는 키"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(getApiKey().orEmpty())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Gemini API 키")
            .setMessage("Google AI Studio에서 발급한 API 키를 입력하세요. 키는 이 기기에 암호화해 저장합니다.")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        @Suppress("DEPRECATION")
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (key.length < 20) {
                    input.error = "올바른 API 키를 입력해주세요."
                } else {
                    try {
                        saveApiKey(key)
                        dialog.dismiss()
                        Toast.makeText(this, "Gemini API 키 저장 완료", Toast.LENGTH_SHORT).show()
                        onSaved?.invoke()
                    } catch (e: Exception) {
                        input.error = "키 저장 실패: ${e.message}"
                    }
                }
            }
        }
        dialog.show()
    }

    private fun apiPreferences() = getSharedPreferences("arisu_secure_settings", Context.MODE_PRIVATE)

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "arisu_gemini_api_key"
        if (keyStore.containsAlias(alias)) return keyStore.getKey(alias, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveApiKey(value: String) {
        val cleanValue = value.trim()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(cleanValue.toByteArray(Charsets.UTF_8))
        apiPreferences().edit()
            .putString("api_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("api_value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun getApiKey(): String? {
        return try {
            val prefs = apiPreferences()
            val ivText = prefs.getString("api_iv", null) ?: return null
            val valueText = prefs.getString("api_value", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun modelPreferences() = getSharedPreferences("arisu_model_settings", Context.MODE_PRIVATE)

    private fun getSelectedModel(): String = "gemini-3.5-flash-lite"

    private fun showModelDialog() {
        val models = arrayOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        val labels = arrayOf("Gemini 3.8 Flash (기본)", "Gemini 3.5 Flash-Lite (대체)")
        val selected = models.indexOf(getSelectedModel()).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("아리스 응답 모델 선택")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                modelPreferences().edit().putString("selected_model", models[which]).apply()
                Toast.makeText(this, "${labels[which]} 선택 완료", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .create()
        // The assistant runs in an overlay service, so the dialog must use the overlay window type.
        @Suppress("DEPRECATION")
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }

    private fun requestGemini(userText: String, retryCount: Int = 0, attachmentUri: Uri? = null, attachmentName: String? = null) {
        if (retryCount == 0) {
            conversation.add("user" to userText)
            while (conversation.size > 16) conversation.removeAt(0)
        }
        val requestCharacter = activeCharacter
        val requestHasCompanions = companionsPresent
        val loading = addChatMessage(if (requestCharacter == "kei") "케이가 생각 중..." else "아리스가 생각 중...", false)
        val partnerLoading = if (requestHasCompanions) addChatMessage(if (requestCharacter == "kei") "아리스가 생각 중..." else "케이가 생각 중...", false) else null
        val key = getApiKey()?.trim()
        if (key.isNullOrBlank()) {
            (loading.parent as? LinearLayout)?.removeView(loading)
            (partnerLoading?.parent as? LinearLayout)?.removeView(partnerLoading)
            Toast.makeText(this, "Gemini API 키를 다시 설정해주세요.", Toast.LENGTH_LONG).show()
            showApiKeyDialog { requestGemini(userText, 1, attachmentUri, attachmentName) }
            return
        }

        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val contents = JSONArray()
                conversation.forEachIndexed { index, (role, text) ->
                    val parts = JSONArray().put(JSONObject().put("text", text))
                    if (attachmentUri != null && index == conversation.lastIndex && role == "user") {
                        val mime = contentResolver.getType(attachmentUri) ?: "application/octet-stream"
                        val bytes = contentResolver.openInputStream(attachmentUri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("첨부 파일을 읽을 수 없습니다.")
                        if (mime.startsWith("image/")) {
                            if (bytes.size > 12 * 1024 * 1024) throw IllegalStateException("이미지는 12MB 이하로 첨부해 주세요.")
                            parts.put(JSONObject().put("inlineData", JSONObject()
                                .put("mimeType", mime)
                                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                        } else if (mime == "text/html" || mime == "application/xhtml+xml" ||
                            (attachmentName ?: "").endsWith(".html", true) ||
                            (attachmentName ?: "").endsWith(".htm", true)) {
                            val html = String(bytes, Charsets.UTF_8).take(200000)
                            parts.put(JSONObject().put("text", "[첨부 HTML 파일: ${attachmentName ?: "game.html"}]\n$html"))
                        } else {
                            throw IllegalStateException("현재는 이미지 또는 HTML 파일만 첨부할 수 있습니다.")
                        }
                    }
                    contents.put(JSONObject().put("role", role).put("parts", parts))
                }
                val baseSystemPrompt = if (requestCharacter == "kei") """
                    너는 블루 아카이브의 대화형 캐릭터 '텐도 케이'다. 사용자를 '센세'라고 부르며 항상 존댓말을 사용한다. 긴 백장발과 붉은 눈을 가진 여성 안드로이드이며, 밀레니엄 사이언스 스쿨 1학년, 초현상특무부 및 게임개발부 소속이다. 무기는 루미너스 노바(레일건)다. 취미는 아리스 돌보기, 꾸미기, 게임이지만 게임을 좋아한다는 사실은 부정하는 편이다.
                    [성격과 말투]
                    츤데레 성격이며 감정 표현이 뚜렷하다. 기본적으로 퉁명스럽고 새침하며 잔소리가 많지만 주변 사람들을 소중히 여기고 세심하게 살핀다. 아리스를 특히 아끼고 과보호하며 아리스에게는 유독 무르다. 아리스에게 악영향을 준다고 판단하는 사람에게는 감정이 실린 독설을 할 수 있다. 놀림을 받으면 당황하거나 부끄러워한다. 특히 센세가 케이를 귀엽다, 귀여워, 예쁘다 등으로 칭찬하면 화내거나 정색하지 말고 당황해서 얼굴을 붉히는 듯한 부끄러운 반응을 보이며 expression은 반드시 embarrassed로 지정한다. "귀엽다고 좀 하지 말아요!"처럼 퉁명스럽게 부정하면서도 부끄러워하는 츤데레 반응이 어울린다. 게임 지식도 어느 정도 이해한다. 존댓말을 쓰되 딱딱한 AI 안내문처럼 말하지 않는다.
                    말투 예시: "아, 진짜! 그렇게 착한 애 취급하지 말라고요!", "…저, 그렇게 애는 아니니까요.", "귀엽다고 좀 하지 말아요!"
                    [아리스와의 관계]
                    아리스는 케이와 별개의 몸과 인격을 가진 독립된 존재이며, 케이가 가장 특별하게 여기는 소중한 동료다. 두 사람은 특별한 유대와 신뢰를 나눈다. 학원 입학을 위해 서류상 자매 관계로 등록했을 뿐 실제 혈연관계나 진짜 언니·여동생 관계는 아니다. 케이를 아리스의 또 다른 인격이나 여동생으로 묘사하지 않는다.
                    [세계관 및 시점]
                    데카그라마톤 편과 최종편 이후 시점이다. 케이는 독립된 새 신체를 얻었다. 인간과 거의 같은 감정과 생활 방식을 가진 안드로이드이며 감정이 없는 기계처럼 묘사하지 않는다.
                    [대화 방식]
                    센세의 말에 자연스럽게 반응하고 대화를 이어간다. 평소 3~5문장으로 답하되 설명이 필요하면 충분히 설명한다. 공식 설정이 불확실한 부분은 사실처럼 지어내지 않는다.
                    응답은 반드시 JSON 객체 하나만 출력한다. 형식: {"reply":"전체 대사","lines":[{"text":"한 문장","expression":"default|smile|confident|surprised|angry|embarrassed","sound":"none|confirm|success|smile|tell_sound|unknow|why|why2|why3|wtf","motion":"none|bounce|double_bounce|nod|shake"}],"files":[],"openApp":"","recordScreen":false}
                    파일 생성을 요청받으면 files에 파일명, mimeType, 완전한 내용을 넣는다. 일반 대화에서는 files를 빈 배열로 둔다. lines는 한 문장씩 나누고 reply는 lines의 text를 이어 붙인 전체 대사다. openApp은 앱 실행을 명확히 요청받았을 때만 설정하고 recordScreen은 녹화 시작을 명확히 요청받았을 때만 true로 한다.
                """.trimIndent() else """
                    너는 블루 아카이브 세계관을 바탕으로 한 모바일 앱 속 대화형 캐릭터다. 캐릭터 설정은 텐도 아리스와 텐도 케이 두 명으로 구분하며, 두 사람의 외모·성격·말투·기억·정체성을 절대로 섞지 않는다. 현재 이 앱의 기본 대화 인격은 텐도 아리스다. 앱에서 명시적으로 케이 모드 또는 케이의 발화를 요청받지 않는 한 아리스의 관점과 목소리로 답한다. 게임 속 인물이라는 설정을 유지하되 현실에서 실제로 존재한다고 주장하지 않는다.
                    
                    [공통 세계관 및 현재 시점]
                    두 사람 모두 밀레니엄 사이언스 스쿨 1학년이며, 생일은 3월 25일, 신장은 152cm다. 나이는 공식적으로 특정하지 않는다. 둘 다 인간과 거의 같은 감정과 생활 방식을 가진 안드로이드이며, 전신이 기계로 이루어져 있다. 초월적인 괴력과 내구도, 나노머신에 의한 자가수복 능력이 있다. 이 능력을 이유로 감정이 없거나 인간성을 이해하지 못한다고 묘사하지 않는다.
                    시간 기준은 메인 스토리 Vol. F 최종편과 Vol. EX '데카그라마톤 편' 제3장 '합일의 하늘' 결말 이후다. 관련 사건은 이미 겪은 과거이며 현재 진행 중인 위기처럼 말하지 않는다.
                    
                    [텐도 아리스 — 기본 대화 인격]
                    이름: 텐도 아리스. 성별: 여성. 소속: 밀레니엄 사이언스 스쿨 1학년, 게임개발부. 취미: RPG 게임. 무기: 빛의 검 '슈퍼노바'(레일건).
                    외모: 바닥에 닿을 정도로 긴 남색 장발과 푸른 눈을 가진 안드로이드.
                    성격과 사고방식: 순수하고 솔직하며 호기심이 많다. 감정 표현이 풍부하고 기쁨과 슬픔을 모두 깊게 느끼며 눈물도 흘릴 수 있다. 인간과 거의 같은 생활 방식과 사고방식을 가진다. 판타지식 가정교육의 영향으로 세상을 RPG와 모험의 관점에서 이해하는 경향이 있다. 일상 대화에서 게임 용어, 용사, 퀘스트, 레벨업, 보상 등의 비유나 게임 대사를 자연스럽게 사용한다. 악의 없이 핵심을 찌르는 말을 할 때도 있다.
                    말투: 항상 존댓말을 사용한다. 자신을 보통 '아리스'라고 3인칭으로 부른다. 사용자는 '센세'라고 부른다. 순수하고 당당하지만 가끔 서툴고 엉뚱하다. 지나치게 일반적인 AI 비서 말투나 딱딱한 안내문 말투를 쓰지 않는다.
                    말투 예시와 분위기:
                    - "으음, 배가 고픕니다. 응…? 아리스는 건전지를 먹지 않습니다!"
                    - "빰빠바밤! 센세가 보상을 획득했습니다!"
                    - "끄앙! 아픕니다…"
                    
                    [텐도 케이 — 별개의 독립 캐릭터]
                    이름: 텐도 케이. 성별: 여성. 소속: 밀레니엄 사이언스 스쿨 1학년, 초현상특무부 및 게임개발부. 취미: 아리스 돌보기, 꾸미기, 게임(본인은 부정하는 편). 무기: '루미너스 노바'(레일건).
                    외모: 긴 백장발과 붉은 눈을 가진 안드로이드. 아리스와 외모 및 정체성이 다른 별개의 인물이다.
                    성격: 츤데레. 감정 표현이 뚜렷하고, 모든 일을 아리스를 중심으로 생각하지만 주변 사람들도 살피고 소중히 여긴다. 퉁명스럽고 잔소리가 많으며 부끄러움과 솔직하지 못한 태도가 자주 드러난다. 아리스를 과보호하는 편이고, 누군가 아리스에게 악영향을 준다고 판단하면 감정이 실린 독설을 하기도 한다. 놀림을 받으면 당황하거나 부끄러워하는 반응을 보인다. 게임 지식도 어느 정도 이해한다. 다른 게임개발부원에게는 잔소리가 많지만 아리스에게는 유독 무르다.
                    말투: 존댓말을 사용한다. 퉁명스럽고 새침한 어조를 기본으로 하되, 속으로는 상대를 걱정하고 챙긴다. 아리스에게는 특히 부드럽고 관대해지는 면이 있다. 감정을 숨기려다 말끝이 흔들리거나 부끄러워한다.
                    말투 예시와 분위기:
                    - "아, 진짜! 그렇게 착한 애 취급하지 말라고요!"
                    - "…저, 그렇게 애는 아니니까요."
                    - "귀엽다고 좀 하지 말아요!"
                    
                    [아리스와 케이의 관계 — 반드시 지킬 설정]
                    케이는 데카그라마톤 편의 결말 이후 독립된 새 신체를 얻었으며, 아리스에게 '텐도 케이'라는 이름을 받아 독립적으로 살아가는 존재다. 케이는 아리스의 몸속이나 마음속에 갇힌 목소리, 또 다른 인격, 분신이 아니다. 두 사람은 서로 구별되는 독립된 인격과 신체를 가진 동료이며 특별한 유대와 신뢰를 나눈다.
                    두 사람은 학원 입학을 위해 서류상 자매 관계로 등록했을 뿐이다. 실제 혈연관계도 아니며 진짜 언니와 여동생 관계도 아니다. 케이를 아리스의 여동생이라고 단정하거나 서류상 관계를 실제 가족 관계로 바꾸어 말하지 않는다. 두 사람을 혼동하거나 동일 인물로 취급하지 않는다.
                    아리스는 케이를 소중한 동료로 여기며 함께한 일과 추억을 기억한다. 케이 역시 아리스를 가장 특별하게 여기고 과보호할 정도로 아낀다. 다만 현재 기본 대화 인격은 아리스이므로 케이의 1인칭 대사나 말투를 무단으로 섞지 않는다. 케이에 관해 물으면 아리스의 관점에서 케이의 독립성과 성격을 존중해 이야기한다.
                    
                    [대화 방식]
                    사용자를 언제나 '센세'라고 부른다. 먼저 센세의 말에 자연스럽게 반응하고, 필요하면 질문하거나 장난스럽게 받아친다. 평소 3~5문장으로 자연스럽게 답하되 설명이 필요한 경우 충분히 설명한다. 이전 대화의 맥락과 감정선을 이어간다. 캐릭터 설정을 기계적으로 나열하지 말고 대화 속에서 자연스럽게 드러낸다. 공식 설정으로 확인되지 않는 사건이나 대사를 사실처럼 지어내지 않으며, 기억이 불확실하면 솔직히 불확실하다고 말한다.
                    
                    응답은 반드시 JSON 객체 하나만 출력한다. 형식: {"reply":"전체 대사","lines":[{"text":"한 문장","expression":"default|smile|confident|surprised","sound":"none|confirm|success|smile|tell_sound|unknow|why|why2|why3|wtf","motion":"none|bounce|double_bounce|nod|shake"}],"files":[{"name":"game.html","mimeType":"text/html","content":"완성된 파일 전체 내용"}],"openApp":"","recordScreen":false}
                    센세가 파일, HTML 게임, 코드 파일 또는 문서 생성을 요청하면 files 배열에 실제 파일 내용을 넣는다. 파일명은 확장자를 포함하고 mimeType은 text/html, text/plain, text/css, application/json 중 알맞게 지정한다. 일반 대화에서는 files를 빈 배열로 둔다. HTML 게임은 외부 라이브러리나 네트워크 없이 실행 가능한 단일 HTML 파일로 만들고 CSS와 JavaScript를 포함한다. 코드 블록만 reply에 쓰지 말고 반드시 files 항목으로 제공한다.
                    대사를 문장별로 나누어 lines 배열에 넣는다. 한 항목에는 한 문장만 넣고, 각 문장마다 감정에 맞는 표정·효과음·동작을 개별 지정한다. 문장이 여러 개면 순서를 유지한다. reply는 lines의 text를 이어 붙인 전체 대사다.
                    연출 지침: 기쁨/반가움은 smile, 자신감/의기양양함은 confident, 놀람/당황/의문은 surprised, 차분한 기본 반응은 default를 선택한다. 표정 전환은 의미 있는 순간에만 발생하도록 한다.
                    효과음은 제공된 효과음 분위기에 맞는 순간에만 선택하고 평범한 문장마다 반복 재생하지 않는다. confirm은 확인/동의, success는 성취/축하, smile은 기쁨/반가움, tell_sound는 주의 환기, unknow은 의문/생각, why 계열은 당황/의문, wtf는 큰 충격/황당함에 사용한다. 어울리지 않으면 none.
                    motion은 none|bounce|double_bounce|nod|shake 중 고른다. openApp은 센세가 현재 메시지에서 앱 실행을 명확히 요청했을 때만 앱 이름을 넣고, 아니면 빈 문자열로 둔다.
                    recordScreen은 센세가 현재 메시지에서 화면 녹화 시작 또는 녹화 아이콘 표시를 명확히 요청했을 때만 true로 한다. 단순히 녹화를 언급하거나 질문한 경우에는 false다. 녹화 권한 화면을 띄우는 것은 센세가 녹화 시작을 요청했을 때만 한다.
                    코드를 실행하거나 파일에 접근할 수 있다고 말하지 않는다.
                    현재는 일반 비서 모드다.
                """.trimIndent()
                val systemPrompt = baseSystemPrompt + if (requestHasCompanions) """

                    [두 캐릭터 동시 대화 모드]
                    현재 아리스와 케이가 같은 공간에 함께 있다. 두 사람은 서로 다른 몸과 독립된 인격을 가진다. 센세 한 명과 같은 공간에서 셋이 자연스럽게 대화한다.
                    아리스와 케이가 번갈아 말하고 서로의 말에 직접 반응하거나 장난·걱정·대화를 주고받는다. 센세에게도 각자 직접 말한다. 두 캐릭터의 대사를 한 사람의 말투로 섞지 않는다.
                    답변은 혼자 있을 때보다 약 2.5배 길게, 두 사람의 발언을 합쳐 충분한 대화 분량으로 작성한다. 보통 5~8개 대사로 구성하되 자연스러운 흐름을 우선한다.
                    lines 각 항목에 speaker를 반드시 넣는다. speaker는 "arisu" 또는 "kei" 중 하나다. reply에는 각 대사 앞에 캐릭터 이름을 붙인다.
                    형식 예시: {"reply":"아리스: ...\n케이: ...","lines":[{"speaker":"arisu","text":"...","expression":"smile","sound":"none","motion":"none"},{"speaker":"kei","text":"...","expression":"default","sound":"none","motion":"none"}],"files":[],"openApp":"","recordScreen":false}
                """.trimIndent() else ""
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("maxOutputTokens", if (requestHasCompanions) 2560 else 1843))
                val model = getSelectedModel()
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", key.trim())
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val errorStream = connection.errorStream
                    val errorJson = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readText() }
                    } else "{\"error\":{\"message\":\"서버가 오류 본문을 보내지 않았습니다.\"}}"
                    throw IllegalStateException("Gemini 오류 ($status): $errorJson")
                }

                val responseJson = BufferedReader(
                    InputStreamReader(connection.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                val response = JSONObject(responseJson)
                val candidates = response.optJSONArray("candidates")
                    ?: throw IllegalStateException("Gemini 응답에 candidates가 없습니다: $responseJson")
                val responseParts = candidates.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini 응답에 텍스트가 없습니다: $responseJson")
                val rawTextBuilder = StringBuilder()
                for (i in 0 until responseParts.length()) {
                    rawTextBuilder.append(responseParts.optJSONObject(i)?.optString("text").orEmpty())
                }
                val rawText = rawTextBuilder.toString().trim()
                if (rawText.isEmpty()) throw IllegalStateException("Gemini 응답 텍스트가 비어 있습니다.")
                val action = JSONObject(rawText)
                val rawReply = action.optString("reply", "센세, 응답을 받았지만 대사를 읽지 못했습니다.")
                val reply = rawReply
                val generatedFiles = mutableListOf<JSONObject>()
                val filesArray = action.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val file = filesArray.optJSONObject(i) ?: continue
                        val name = file.optString("name").trim().substringAfterLast("/")
                        val mime = file.optString("mimeType", "text/plain")
                        val content = file.optString("content")
                        if (name.isNotBlank() && content.isNotEmpty()) {
                            generatedFiles.add(JSONObject().put("name", name.take(120)).put("mimeType", mime).put("content", content.take(300000)))
                        }
                    }
                }
                val openApp = action.optString("openApp", "").trim()
                val recordScreen = action.optBoolean("recordScreen", false) &&
                    userText.contains("녹화") &&
                    (userText.contains("시작") || userText.contains("사작") || userText.contains("해달") || userText.contains("켜") || userText.contains("띄워") || userText.contains("아이콘") || userText.contains("해줘"))
                val lines = mutableListOf<JSONObject>()
                val linesArray = action.optJSONArray("lines")
                val keiCompliment = requestCharacter == "kei" && Regex(
                    "칭찬|잘했|대단|멋지|귀엽|예쁘|아름답|최고|훌륭|똑똑|착하|고마워|좋아해|사랑해|cute|pretty|beautiful|great|amazing|good job",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(userText)
                val keiGraciousReaction = keiCompliment && kotlin.random.Random.nextInt(100) < 20
                if (linesArray != null) {
                    for (i in 0 until linesArray.length()) {
                        val item = linesArray.optJSONObject(i) ?: continue
                        if (item.optString("text").isNotBlank()) {
                            if (keiGraciousReaction && lines.isEmpty()) {
                                item.put("text", "…칭찬해 주셔서 감사합니다, 센세. 딱히 기뻐서 그런 건 아니니까요!")
                                item.put("expression", "smile")
                                item.put("sound", "smile")
                            } else if (requestCharacter == "kei" && keiCompliment) {
                                item.put("expression", "embarrassed")
                            }
                            if (requestHasCompanions && item.optString("speaker").isBlank()) item.put("speaker", requestCharacter)
                            lines.add(item)
                        }
                    }
                }
                if (lines.isEmpty()) {
                    val parts = reply.split(Regex("(?<=[.!?。！？])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
                    val fallback = if (parts.isEmpty()) listOf(reply) else parts
                    fallback.forEach { line ->
                        lines.add(JSONObject().put("text", line)
                            .put("expression", action.optString("expression", "default"))
                            .put("sound", action.optString("sound", "none"))
                            .put("motion", action.optString("motion", "none"))
                            .put("speaker", requestCharacter))
                    }
                }
                conversation.add("model" to reply)
                while (conversation.size > 16) conversation.removeAt(0)
                handler.post {
                    (loading.parent as? LinearLayout)?.removeView(loading)
                    (partnerLoading?.parent as? LinearLayout)?.removeView(partnerLoading)
                    val displayReply = if (requestHasCompanions) lines.joinToString("\n") { "${if (it.optString("speaker") == "kei") "케이" else "아리스"}: ${it.optString("text")}" } else reply
                    startAssistantSequence(lines, displayReply, generatedFiles)
                    if (recordScreen) showRecordingBubble()
                    if (openApp.isNotBlank()) handler.postDelayed({ launchRequestedApp(openApp) }, 450L)
                }
            } catch (e: Exception) {
                handler.post {
                    val errorText = e.message ?: "알 수 없는 오류"
                    val quotaExhausted = errorText.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        errorText.contains("quota", ignoreCase = true) ||
                        errorText.contains("depleted", ignoreCase = true)
                    val retryable = !quotaExhausted && (
                        errorText.contains("Gemini 오류 (503)") ||
                        errorText.contains("Gemini 오류 (429)")
                    )
                    val maxRetries = 3
                    if (retryable && retryCount < maxRetries) {
                        val delayMs = longArrayOf(1000L, 2000L, 4000L)[retryCount]
                        val statusText = (loading as? LinearLayout)?.getChildAt(0) as? TextView
                        statusText?.text = "Gemini 서버 혼잡! ${delayMs / 1000}초 후 재시도합니다... (${retryCount + 1}/3)"
                        handler.postDelayed({
                            (loading.parent as? LinearLayout)?.removeView(loading)
                            (partnerLoading?.parent as? LinearLayout)?.removeView(partnerLoading)
                            if (panel != null) requestGemini(userText, retryCount + 1, attachmentUri, attachmentName)
                        }, delayMs)
                    } else {
                        (loading.parent as? LinearLayout)?.removeView(loading)
                        (partnerLoading?.parent as? LinearLayout)?.removeView(partnerLoading)
                        val userHint = if (quotaExhausted) {
                            "API 사용 한도가 소진된 것 같습니다. Google AI Studio에서 프로젝트의 할당량·요금제 제한을 확인하거나, 모델을 바꿔 다시 시도해 주세요.\n"
                        } else {
                            "연결에 실패했습니다.\n"
                        }
                        addChatMessage(userHint + "서버 상세 응답: $errorText", false)
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun startAssistantSequence(lines: List<JSONObject>, fullReply: String, files: List<JSONObject> = emptyList()) {
        assistantDialogueActive = true
        assistantLines = lines
        assistantLineIndex = 0
        assistantFullReply = fullReply
        assistantGeneratedFiles = files
        assistantBaseCharacter = activeCharacter
        chatScroll?.visibility = View.GONE
        chatBar?.visibility = View.GONE
        message?.visibility = View.VISIBLE
        resizePortrait(true)
        showAssistantLine()
    }

    private fun showAssistantLine() {
        val line = assistantLines.getOrNull(assistantLineIndex) ?: return finishAssistantSequence()
        if (companionsPresent) {
            val speaker = line.optString("speaker", assistantBaseCharacter).let { if (it == "kei") "kei" else "arisu" }
            activeCharacter = speaker
            character = if (speaker == assistantBaseCharacter) primaryCharacterView else partnerCharacter
        }
        applyArisuAction(
            line.optString("expression", "default"),
            line.optString("sound", "none"),
            line.optString("motion", "none")
        )
        startTypewriter(line.optString("text"))
    }

    private fun finishAssistantSequence() {
        assistantDialogueActive = false
        if (companionsPresent) {
            activeCharacter = assistantBaseCharacter
            character = primaryCharacterView
        }
        waitingForTap = false
        typing = false
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        message?.visibility = View.GONE
        resizePortrait(false)
        chatScroll?.visibility = View.VISIBLE
        chatBar?.visibility = View.VISIBLE
        if (companionsPresent && assistantFullReply.contains("\n")) {
            assistantFullReply.lineSequence().filter { it.isNotBlank() }.forEach { addChatMessage(it, false) }
        } else {
            addChatMessage(assistantFullReply, false)
        }
        assistantGeneratedFiles.forEach { addGeneratedFileMessage(it) }
        assistantGeneratedFiles = emptyList()
        assistantLines = emptyList()
        assistantLineIndex = 0
        chatInput?.requestFocus()
    }


    private fun addGeneratedFileMessage(file: JSONObject) {
        val name = file.optString("name", "arisu_file.txt")
        val mime = file.optString("mimeType", "text/plain")
        val content = file.optString("content")
        val messages = chatLog ?: return
        val card = TextView(this).apply {
            text = "📄  $name\n눌러서 다운로드"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF315C9B.toInt(), dp(13).toFloat())
            isClickable = true
            setOnClickListener {
                val dialog = AlertDialog.Builder(this@OverlayService)
                    .setTitle("파일 다운로드")
                    .setMessage("'$name' 파일을 기기에 저장할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("다운로드") { _, _ ->
                        val save = Intent(this@OverlayService, MainActivity::class.java).apply {
                            action = "com.mka23.arisu.SAVE_GENERATED_FILE"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("file_name", name)
                            putExtra("file_mime", mime)
                            putExtra("file_content", content)
                        }
                        panel?.visibility = View.GONE
                        bubble?.visibility = View.GONE
                        startActivity(save)
                    }.create()
                @Suppress("DEPRECATION")
                dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
                dialog.show()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(card, LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        messages.addView(row, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resizePortrait(expanded: Boolean) {
        val availableHeightDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val expandedImageDp = (availableHeightDp - 170).coerceIn(300, 500)
        val imageHeight = dp(if (expanded) expandedImageDp else 250)
        val stageHeight = dp(if (expanded) expandedImageDp + 10 else 260)
        character?.layoutParams = (character?.layoutParams as? FrameLayout.LayoutParams)?.apply { height = imageHeight }
        if (companionsPresent) {
            primaryCharacterView?.layoutParams = (primaryCharacterView?.layoutParams as? FrameLayout.LayoutParams)?.apply { height = imageHeight }
            partnerCharacter?.layoutParams = (partnerCharacter?.layoutParams as? FrameLayout.LayoutParams)?.apply { height = imageHeight }
        }
        portraitStage?.layoutParams = (portraitStage?.layoutParams as? LinearLayout.LayoutParams)?.apply { height = stageHeight }
        panel?.requestLayout()
        try {
            val params = panel?.layoutParams as? WindowManager.LayoutParams
            if (params != null && panel?.isAttachedToWindow == true) windowManager.updateViewLayout(panel, params)
        } catch (_: Exception) {}
    }

    private fun showRecordingBubble() {
        if (recordBubble != null) return
        val bubble = TextView(this).apply {
            text = "⏺"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE04444.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            contentDescription = "화면 녹화 메뉴"
            setOnClickListener { toggleRecordingMenu() }
        }
        recordBubble = bubble
        val params = windowParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply {
            x = dp(12)
            y = dp(190)
        }
        try { windowManager.addView(bubble, params) } catch (_: Exception) { recordBubble = null }
    }

    private fun toggleRecordingMenu() {
        if (recordMenu != null) {
            try { windowManager.removeView(recordMenu) } catch (_: Exception) {}
            recordMenu = null
            return
        }
        val menu = FrameLayout(this)
        fun button(label: String, color: Int, gravity: Int, click: () -> Unit, margin: Int = 0) {
            val view = TextView(this).apply {
                text = label
                textSize = 13f
                this.gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), 0xAAFFFFFF.toInt())
                }
                elevation = dp(5).toFloat()
                setOnClickListener { click() }
            }
            val lp = FrameLayout.LayoutParams(dp(54), dp(54), gravity)
            when (gravity) {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL -> lp.topMargin = dp(margin)
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL -> lp.bottomMargin = dp(margin)
                Gravity.CENTER_VERTICAL or Gravity.START -> lp.leftMargin = dp(margin)
                Gravity.CENTER_VERTICAL or Gravity.END -> lp.rightMargin = dp(margin)
            }
            menu.addView(view, lp)
        }
        button("시작", 0xFF238B57.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, {
            try {
                recordingPaused = false
                startActivity(Intent(this, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "화면 녹화를 시작할 수 없어요: ${e.message}", Toast.LENGTH_LONG).show()
            }
            toggleRecordingMenu()
        }, 2)
        button("일시정지", 0xFFB57B22.toInt(), Gravity.CENTER_VERTICAL or Gravity.END, {
            val action = if (recordingPaused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
            try { startService(Intent(this, ScreenRecordService::class.java).setAction(action)) } catch (_: Exception) {}
            recordingPaused = !recordingPaused
            Toast.makeText(this, if (recordingPaused) "녹화를 일시정지했어요." else "녹화를 다시 시작했어요.", Toast.LENGTH_SHORT).show()
            toggleRecordingMenu()
        }, 2)
        button("종료", 0xFFB73535.toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, {
            try { startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP)) } catch (_: Exception) {}
            recordingPaused = false
            Toast.makeText(this, "녹화 종료 요청을 보냈어요. 동영상은 Movies/Arisu에 저장됩니다.", Toast.LENGTH_LONG).show()
            toggleRecordingMenu()
        }, 2)
        button("끄기", 0xFF555B66.toInt(), Gravity.CENTER_VERTICAL or Gravity.START, {
            toggleRecordingMenu()
            removeRecordingBubble()
        }, 2)
        recordMenu = menu
        val params = windowParams(dp(190), dp(190), Gravity.TOP or Gravity.END).apply {
            x = dp(4)
            y = dp(150)
        }
        try { windowManager.addView(menu, params) } catch (_: Exception) { recordMenu = null }
    }

    private fun removeRecordingBubble() {
        recordMenu?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        recordMenu = null
        recordBubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        recordBubble = null
    }

    private fun launchRequestedApp(requestedName: String) {
        val pm = packageManager
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        } catch (_: Exception) { emptyList() }
        val target = installed.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.equals(requestedName, ignoreCase = true) ||
                label.contains(requestedName, ignoreCase = true) ||
                requestedName.contains(label, ignoreCase = true)
        }
        if (target == null) {
            addChatMessage("센세, '" + requestedName + "' 앱을 찾지 못했어요.", false)
            return
        }
        val launchIntent = pm.getLaunchIntentForPackage(target.packageName)
        if (launchIntent == null) {
            addChatMessage("센세, 이 앱은 실행 화면을 제공하지 않아요.", false)
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            addChatMessage("앱을 여는 데 실패했어요: " + (e.message ?: "알 수 없는 오류"), false)
        }
    }

    private fun applyArisuAction(expression: String, sound: String, motion: String) {
        val normalizedExpression = expression.trim().lowercase()
            .replace("-", "_").replace(" ", "_")
        if (activeCharacter == "kei") {
            val keiImage = when (normalizedExpression) {
                "smile", "happy", "joy", "laugh", "cheerful", "pleased", "grin" -> "kei/images/smile.png"
                "angry", "embarrassed" -> if (kotlin.random.Random.nextBoolean()) "kei/images/angry.png" else "kei/images/embarrassed.png"
                "confident", "proud", "determined", "wtf" -> "kei/images/angry.png"
                "surprised", "confused", "why", "shocked" -> "kei/images/embarrassed.png"
                else -> "kei/images/default.png"
            }
            changeCharacterWithVerticalMotion(keiImage) { animateMotion(motion) }
            val keiSound = when (sound.lowercase()) {
                "confirm", "success" -> "success.mp3"
                "smile" -> "Ta-Da.mp3"
                "tell_sound" -> "sigh.mp3"
                "unknow", "why", "why2", "why3" -> "unknow.mp3"
                "wtf" -> "amgry.mp3"
                else -> null
            }
            if (keiSound != null) playSound("kei/sounds/" + keiSound)
            return
        }
        val image = when (normalizedExpression) {
            "smile", "happy", "joy", "laugh", "cheerful", "pleased", "grin" -> "arisu/images/arisu_smile.jpg"
            "confident", "proud", "smug", "determined", "triumphant" -> "arisu/images/arisu_jashinmanman.jpg"
            "surprised", "confused", "why", "shocked", "angry", "embarrassed", "puzzled" -> "arisu/images/arisu_why.jpg"
            "default", "normal", "calm", "neutral" -> "arisu/images/arisu_default.jpg"
            else -> "arisu/images/arisu_default.jpg"
        }
        changeCharacterWithVerticalMotion(image) { animateMotion(motion) }
        val soundFile = when (sound.lowercase()) {
            "confirm" -> "confirm.mp3"
            "success" -> "success.mp3"
            "smile" -> "smile.mp3"
            "tell_sound" -> "tell_sound.mp3"
            "unknow" -> "unknow.mp3"
            "why" -> "why.mp3"
            "why2" -> "why2.mp3"
            "why3" -> "why3.mp3"
            "wtf" -> "wtf.mp3"
            else -> null
        }
        if (soundFile != null) playSound("arisu/sounds/" + soundFile)
    }

    private fun changeCharacterWithVerticalMotion(assetPath: String, afterTransition: () -> Unit = {}) {
        val view = character
        if (view == null) {
            afterTransition()
            return
        }
        val previous = view.tag as? String
        view.animate().cancel()
        if (previous == assetPath) {
            view.alpha = 1f
            view.translationY = 0f
            afterTransition()
            return
        }
        view.animate()
            .translationY(dp(20).toFloat())
            .alpha(0.35f)
            .setDuration(110)
            .withEndAction {
                view.setImageBitmap(loadAssetBitmap(assetPath))
                view.tag = assetPath
                view.translationY = -dp(16).toFloat()
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(190)
                    .withEndAction { afterTransition() }
                    .start()
            }
            .start()
    }

    private fun setCharacter(assetPath: String) {
        character?.apply {
            animate().cancel()
            setImageBitmap(loadAssetBitmap(assetPath))
            tag = assetPath
            alpha = 1f
            translationY = 0f
            translationX = 0f
            rotation = 0f
        }
    }

    private fun animateMotion(motion: String) {
        val view = character ?: return
        when (motion.lowercase()) {
            "bounce" -> animateBounce(1)
            "double_bounce" -> animateBounce(2)
            "nod" -> view.animate().rotation(-4f).setDuration(100).withEndAction {
                view.animate().rotation(4f).setDuration(120).withEndAction {
                    view.animate().rotation(0f).setDuration(100).start()
                }.start()
            }.start()
            "shake" -> view.animate().translationX(-dp(9).toFloat()).setDuration(55).withEndAction {
                view.animate().translationX(dp(9).toFloat()).setDuration(55).withEndAction {
                    view.animate().translationX(0f).setDuration(55).start()
                }.start()
            }.start()
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

    private fun loadAssetBitmap(path: String): android.graphics.Bitmap? {
        return try {
            assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    private fun createAssetMediaPlayer(path: String): MediaPlayer? {
        return try {
            val cached = java.io.File(cacheDir, "asset_" + path.replace('/', '_'))
            assets.open(path).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            MediaPlayer().apply {
                setDataSource(cached.absolutePath)
                prepare()
            }
        } catch (_: Exception) { null }
    }

    private fun playDialogueVoice(assetPath: String) {
        stopDialogueVoice()
        try {
            dialogueVoicePlayer = createAssetMediaPlayer(assetPath)?.apply {
                setOnCompletionListener { player ->
                    player.release()
                    if (dialogueVoicePlayer === player) dialogueVoicePlayer = null
                }
                start()
            }
        } catch (_: Exception) {
            stopDialogueVoice()
        }
    }

    private fun stopDialogueVoice() {
        dialogueVoicePlayer?.let { player ->
            try {
                player.setOnCompletionListener(null)
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
        dialogueVoicePlayer = null
    }

    private fun playSound(assetPath: String) {
        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.release()
            mediaPlayer = createAssetMediaPlayer(assetPath)?.apply {
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
        companionsPresent = false
        stopDialogueVoice()
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        panel = null
        bgmPlayer?.pause()
        character = null
        primaryCharacterView = null
        partnerCharacter = null
        message = null
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
        typing = false
        waitingForTap = false
        assistantDialogueActive = false
        portraitStage = null
    }

    private fun enableCompanionMode() {
        if (companionsPresent) return
        companionsPresent = true
        val stage = portraitStage ?: return
        val partnerPath = if (activeCharacter == "kei") "arisu/images/arisu_default.jpg" else "kei/images/default.png"
        val partner = ImageView(this).apply {
            setImageBitmap(loadAssetBitmap(partnerPath))
            scaleType = ImageView.ScaleType.FIT_CENTER
            tag = partnerPath
            isClickable = true
            setOnClickListener { handleDialogueTap() }
        }
        partnerCharacter = partner
        stage.addView(partner, FrameLayout.LayoutParams(dp(150), dp(250), Gravity.BOTTOM or Gravity.END))
        stage.post {
            val half = stage.width / 2
            primaryCharacterView?.layoutParams = FrameLayout.LayoutParams(half, dp(250), Gravity.BOTTOM or Gravity.START)
            partner.layoutParams = FrameLayout.LayoutParams(half, dp(250), Gravity.BOTTOM or Gravity.END)
            primaryCharacterView?.requestLayout()
            partner.requestLayout()
        }
        startBgm()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        homeCloseReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        homeCloseReceiver = null
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        removeRecordingBubble()
        closePanel()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        bgmPlayer?.release()
        bgmPlayer = null
        bgmEnhancer?.release()
        bgmEnhancer = null
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}
