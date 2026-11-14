package com.mka23.arisu

import android.app.*
import android.content.Context
import android.content.Intent
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
import java.net.URLEncoder
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
    private var message: TextView? = null
    private var chatBar: LinearLayout? = null
    private var chatLog: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var mediaPlayer: MediaPlayer? = null
    private var bgmPlayer: MediaPlayer? = null
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
    private var storyMode = false
    private var storyAgeMode = 15
    private var headerTitle: TextView? = null

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
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(19).toFloat()
                setColor(0xFF315C9B.toInt())
                setStroke(dp(2), 0xFFB7D9FF.toInt())
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            clipToOutline = true
            elevation = dp(8).toFloat()
            contentDescription = "아리스 열기"
        }
        icon.setOnClickListener { playLoginSequence() }
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
        chatScroll = null
        conversation.clear()

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
            setCharacter(R.drawable.arisu_smile)
            animateMotion("bounce")
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
        portraitStage = stage
        stage.addView(portrait, FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(260)))

        val header = FrameLayout(this)
        val title = TextView(this).apply {
            text = if (storyMode) "아리스 · 스토리" else "아리스"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnLongClickListener {
                showApiKeyDialog()
                true
            }
        }
        headerTitle = title
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
        val modelButton = TextView(this).apply {
            text = "AI"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Gemini 모델 전환"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF315C9B.toInt())
            }
            elevation = dp(4).toFloat()
            setOnClickListener { showModelDialog() }
        }
        val modelButtonParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.TOP)
        header.addView(modelButton, modelButtonParams)
        val storyButton = TextView(this).apply {
            text = "S"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "스토리 모드 및 대화 수위 설정"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF315C9B.toInt())
            }
            elevation = dp(4).toFloat()
            setOnClickListener { showStoryModeDialog() }
        }
        header.addView(storyButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(46)
        })
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

    private fun showStoryModeDialog() {
        val options = arrayOf("15세 모드 · 청소년 관람가", "19세 모드 · 성인용 대화")
        val dialog = AlertDialog.Builder(this)
            .setTitle("스토리 대화 수위")
            .setItems(options) { _, which ->
                if (which == 0) showStoryStartDialog(15) else showBirthYearDialog()
            }
            .setNegativeButton("취소", null)
            .create()
        setOverlayDialog(dialog)
        dialog.show()
    }

    private fun showBirthYearDialog() {
        val yearInput = EditText(this).apply {
            hint = "태어난 연도 (예: 2000)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("19세 모드 연령 확인")
            .setMessage("출생 연도를 입력해 주세요. 만 19세 이상인 경우에만 19세 모드를 사용할 수 있어요.")
            .setView(yearInput)
            .setPositiveButton("확인") { _, _ ->
                val birthYear = yearInput.text.toString().toIntOrNull()
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val eligible = birthYear != null && birthYear in 1900..currentYear && birthYear <= currentYear - 19
                if (eligible) showStoryStartDialog(19) else {
                    Toast.makeText(this, "만 19세 미만이거나 올바르지 않은 출생 연도입니다. 15세 모드로 전환합니다.", Toast.LENGTH_LONG).show()
                     showStoryStartDialog(15)
                }
            }
            .setNegativeButton("취소", null)
            .create()
        setOverlayDialog(dialog)
        dialog.show()
    }

    private fun setOverlayDialog(dialog: AlertDialog) {
        @Suppress("DEPRECATION")
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        )
    }

    private fun showStoryStartDialog(ageMode: Int) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("스토리 시작 방식")
            .setItems(arrayOf("랜덤 시작 · AI가 상황 생성", "자작 인트로 · 직접 작성")) { _, which ->
                if (which == 0) startStoryMode(ageMode, null) else showCustomIntroDialog(ageMode)
            }
            .setNegativeButton("취소", null)
            .create()
        setOverlayDialog(dialog)
        dialog.show()
    }

    private fun showCustomIntroDialog(ageMode: Int) {
        val introInput = EditText(this).apply {
            hint = "예: 방과 후 텅 빈 교실에서 아리스가 수상한 게임기를 발견했다."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("나만의 스토리 인트로")
            .setMessage("*행동이나 묘사*는 별표로 감싸서 입력하면 돼요.")
            .setView(introInput)
            .setPositiveButton("이 인트로로 시작", null)
            .setNegativeButton("취소", null)
            .create()
        setOverlayDialog(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val intro = introInput.text.toString().trim()
                if (intro.isBlank()) introInput.error = "시작할 인트로를 입력해 주세요."
                else { dialog.dismiss(); startStoryMode(ageMode, intro) }
            }
        }
        dialog.show()
    }

    private fun startStoryMode(ageMode: Int, customIntro: String? = null) {
        storyMode = true
        storyAgeMode = ageMode
        sequenceToken++
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        typing = false
        waitingForTap = false
        assistantDialogueActive = false
        conversation.clear()
        closePanel()
        showCharacterPanel()
        headerTitle?.text = "아리스 · 스토리 (${ageMode}세)"
        setCharacter(R.drawable.arisu_default)
        resizePortrait(false)
        showChatBar()
        addChatMessage("스토리 모드 시작 · ${ageMode}세 · ${if (customIntro.isNullOrBlank()) "랜덤" else "자작 인트로"}", false)
        val opening = if (customIntro.isNullOrBlank()) {
            "랜덤 시작으로 진행해줘. 매번 다른 시작 상황을 새로 창작하고, 배경·현재 상황·등장인물의 행동과 센세에게 주어진 첫 사건을 자연스럽게 보여줘. 첫 응답부터 장면과 대사로 시작해."
        } else {
            "사용자가 작성한 다음 인트로를 이야기의 공식적인 첫 장면으로 사용해줘. 내용을 임의로 다른 설정으로 바꾸지 말고, 자연스럽게 이어지는 장면과 인물의 대사로 확장해줘. 인트로: $customIntro"
        }
        requestGemini("스토리 모드를 시작하자. $opening 설정 설명이나 수위 안내 없이 이야기 자체로 시작해.")
    }

    private fun censorMatureWords(input: String): String {
        val terms = listOf(
            "성관계", "성교", "섹스", "자위", "오르가즘", "사정", "발정", "유두", "음부",
            "성기", "질내", "항문성교", "강간", "유사성행위", "야동", "포르노",
            "fuck", "fucking", "blowjob", "handjob", "porn", "penis", "vagina", "orgasm"
        )
        var result = input
        terms.sortedByDescending { it.length }.forEach { term ->
            result = result.replace(Regex(Regex.escape(term), RegexOption.IGNORE_CASE), "**")
        }
        return result
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
        if (bgmPlayer?.isPlaying == true) return
        try {
            bgmPlayer?.release()
            bgmPlayer = MediaPlayer.create(this, R.raw.bgm)?.apply {
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
        when (dialogueStage) {
            1 -> {
                setCharacter(R.drawable.arisu_default)
                animateMotion("double_bounce")
                playSound(R.raw.smile)
                dialogueStage = 2
                startTypewriter("반갑습니다, 센세!")
            }
            2 -> {
                setCharacter(R.drawable.arisu_jashinmanman)
                animateMotion("bounce")
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
            hint = "아리스에게 메시지 입력..."
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

    private fun submitChatMessage() {
        val input = chatInput ?: return
        val rawTypedText = input.text.toString().trim()
        val typedText = if (storyMode && storyAgeMode == 15) censorMatureWords(rawTypedText) else rawTypedText
        val attachment = pendingAttachmentUri
        val attachmentName = pendingAttachmentName
        val text = typedText.ifEmpty {
            if (attachment != null) "첨부한 파일을 확인해줘." else return
        }
        input.text.clear()
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

    private fun getSelectedModel(): String =
        modelPreferences().getString("selected_model", "gemini-3.8-flash")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "gemini-3.8-flash"

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
        val loading = addChatMessage("아리스가 생각 중...", false)
        val key = getApiKey()?.trim()
        if (key.isNullOrBlank()) {
            (loading.parent as? LinearLayout)?.removeView(loading)
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
                val systemPrompt = """
                    너는 모바일 앱 속 대화형 캐릭터 '텐도 아리스'다. 블루 아카이브의 텐도 아리스 성격과 말투를 일관되게 연기한다. 자신이 실제 게임 속 인물이라고 주장하지는 않는다.
                    사용자는 언제나 '센세'라고 부른다. 순수하고 솔직하며 호기심이 많고, 약간 서툴지만 당당하다. 게임, 모험, 용사와 퀘스트 같은 비유를 자연스럽게 사용한다. 지나치게 일반적인 AI 비서 말투나 딱딱한 안내문 말투를 쓰지 않는다.
                    이전 대화의 맥락과 감정선을 이어간다. 센세의 말에 먼저 반응하고, 필요하면 질문하거나 장난스럽게 받아친다. 평소 1~3문장으로 자연스럽게 답하되 설명이 필요하면 충분히 설명한다.
                    스토리 모드에서 사용자가 *행동이나 묘사*처럼 별표 한 쌍으로 감싼 문장을 입력하면, 이를 대사가 아니라 이야기 속 행동·상황 묘사로 해석하고 그 결과에 맞춰 장면을 이어간다. 별표로 감싼 행동 묘사를 그대로 대사처럼 따라 읽지 않는다.
                    응답은 반드시 JSON 객체 하나만 출력한다. 형식: {"reply":"전체 대사","lines":[{"text":"한 문장","expression":"default|smile|confident|surprised","sound":"none|confirm|success|smile|tell_sound|unknow|why|why2|why3|wtf","motion":"none|bounce|double_bounce|nod|shake"}],"files":[{"name":"game.html","mimeType":"text/html","content":"완성된 파일 전체 내용"}],"openApp":"","recordScreen":false}
                    센세가 파일, HTML 게임, 코드 파일 또는 문서 생성을 요청하면 files 배열에 실제 파일 내용을 넣는다. 파일명은 확장자를 포함하고 mimeType은 text/html, text/plain, text/css, application/json 중 알맞게 지정한다. 일반 대화에서는 files를 빈 배열로 둔다. HTML 게임은 외부 라이브러리나 네트워크 없이 실행 가능한 단일 HTML 파일로 만들고 CSS와 JavaScript를 포함한다. 코드 블록만 reply에 쓰지 말고 반드시 files 항목으로 제공한다.
                    대사를 문장별로 나누어 lines 배열에 넣는다. 한 항목에는 한 문장만 넣고, 각 문장마다 감정에 맞는 표정·효과음·동작을 개별 지정한다. 문장이 여러 개면 순서를 유지한다. reply는 lines의 text를 이어 붙인 전체 대사다.
                    연출 지침: 기쁨/반가움은 smile, 자신감/의기양양함은 confident, 놀람/당황/의문은 surprised, 차분한 기본 반응은 default를 선택한다. 표정 전환은 의미 있는 순간에만 발생하도록 한다.
                    효과음은 제공된 효과음 분위기에 맞는 순간에만 선택하고 평범한 문장마다 반복 재생하지 않는다. confirm은 확인/동의, success는 성취/축하, smile은 기쁨/반가움, tell_sound는 주의 환기, unknow은 의문/생각, why 계열은 당황/의문, wtf는 큰 충격/황당함에 사용한다. 어울리지 않으면 none.
                    motion은 none|bounce|double_bounce|nod|shake 중 고른다. openApp은 센세가 현재 메시지에서 앱 실행을 명확히 요청했을 때만 앱 이름을 넣고, 아니면 빈 문자열로 둔다.
                    recordScreen은 센세가 현재 메시지에서 화면 녹화 시작 또는 녹화 아이콘 표시를 명확히 요청했을 때만 true로 한다. 단순히 녹화를 언급하거나 질문한 경우에는 false다. 녹화 권한 화면을 띄우는 것은 센세가 녹화 시작을 요청했을 때만 한다.
                    코드를 실행하거나 파일에 접근할 수 있다고 말하지 않는다.
                    ${if (storyMode) "현재는 스토리 모드다. 센세와 아리스가 함께하는 인터랙티브 이야기로 진행하고, 첫 응답에서 매번 다른 무작위 시작 상황·장소·사건을 자연스럽게 만들어 장면을 시작한다. 사용자의 선택과 행동에 따라 이야기를 이어가며 일반 비서처럼 답하지 않는다. 스토리 모드의 아리스는 원작과 구분되는 성인 AU 설정으로, 만 20세의 성인이다. 아리스는 성인이면서도 학교에 다니는 학생일 수 있으며, 학생 신분과 학교생활을 유지한다. 성인이라는 점과 학생이라는 점은 모순되지 않는다. 센세를 존경하고 친근하게 부르며 항상 센세라고 호칭한다. 현재 대화 수위 설정은 ${storyAgeMode}세 모드다. 15세 모드에서는 선정적 표현과 노골적인 폭력 묘사를 피하고 청소년 관람가로 진행한다. 19세 모드에서는 성인용 주제와 성숙한 로맨스 및 강한 긴장감·폭력 묘사를 다룰 수 있다. 성적 상황에 참여하는 모든 인물은 설정상 명확히 만 19세 이상 성인이어야 하며, 아리스는 만 20세다. 다른 인물의 나이가 불명확하거나 미성년자로 설정된 경우 성적 상황에 포함하지 않는다." else "현재는 일반 비서 모드다."}
                """.trimIndent()
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject()
                        .put("responseMimeType", "application/json"))
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
                val reply = if (storyMode && storyAgeMode == 15) censorMatureWords(rawReply) else rawReply
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
                if (linesArray != null) {
                    for (i in 0 until linesArray.length()) {
                        val item = linesArray.optJSONObject(i) ?: continue
                        if (item.optString("text").isNotBlank()) {
                            if (storyMode && storyAgeMode == 15) item.put("text", censorMatureWords(item.optString("text")))
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
                            .put("motion", action.optString("motion", "none")))
                    }
                }
                conversation.add("model" to reply)
                while (conversation.size > 16) conversation.removeAt(0)
                handler.post {
                    (loading.parent as? LinearLayout)?.removeView(loading)
                    startAssistantSequence(lines, reply, generatedFiles)
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
                            if (panel != null) requestGemini(userText, retryCount + 1, attachmentUri, attachmentName)
                        }, delayMs)
                    } else {
                        (loading.parent as? LinearLayout)?.removeView(loading)
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
        chatScroll?.visibility = View.GONE
        chatBar?.visibility = View.GONE
        message?.visibility = View.VISIBLE
        resizePortrait(true)
        showAssistantLine()
    }

    private fun showAssistantLine() {
        val line = assistantLines.getOrNull(assistantLineIndex) ?: return finishAssistantSequence()
        applyArisuAction(
            line.optString("expression", "default"),
            line.optString("sound", "none"),
            line.optString("motion", "none")
        )
        startTypewriter(line.optString("text"))
    }

    private fun finishAssistantSequence() {
        assistantDialogueActive = false
        waitingForTap = false
        typing = false
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        message?.visibility = View.GONE
        resizePortrait(false)
        chatScroll?.visibility = View.VISIBLE
        chatBar?.visibility = View.VISIBLE
        addChatMessage(assistantFullReply, false)
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
        val image = when (normalizedExpression) {
            "smile", "happy", "joy", "laugh", "cheerful", "pleased", "grin" -> R.drawable.arisu_smile
            "confident", "proud", "smug", "determined", "triumphant" -> R.drawable.arisu_jashinmanman
            "surprised", "confused", "why", "shocked", "angry", "embarrassed", "puzzled" -> R.drawable.arisu_why
            "default", "normal", "calm", "neutral" -> R.drawable.arisu_default
            else -> R.drawable.arisu_default
        }
        // Run portrait transition first; then play body motion so both animations do not compete.
        changeCharacterWithVerticalMotion(image) { animateMotion(motion) }
        val soundId = when (sound.lowercase()) {
            "confirm" -> R.raw.confirm
            "success" -> R.raw.success
            "smile" -> R.raw.smile
            "tell_sound" -> R.raw.tell_sound
            "unknow" -> R.raw.unknow
            "why" -> R.raw.why
            "why2" -> R.raw.why2
            "why3" -> R.raw.why3
            "wtf" -> R.raw.wtf
            else -> 0
        }
        if (soundId != 0) playSound(soundId)
    }

    private fun changeCharacterWithVerticalMotion(resourceId: Int, afterTransition: () -> Unit = {}) {
        val view = character
        if (view == null) {
            afterTransition()
            return
        }
        val previous = view.tag as? Int
        view.animate().cancel()
        if (previous == resourceId) {
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
                view.setImageResource(resourceId)
                view.tag = resourceId
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

    private fun setCharacter(resourceId: Int) {
        character?.apply {
            animate().cancel()
            setImageResource(resourceId)
            tag = resourceId
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
        headerTitle = null
        bgmPlayer?.pause()
        character = null
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

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
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
