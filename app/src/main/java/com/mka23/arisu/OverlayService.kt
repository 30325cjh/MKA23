package com.mka23.arisu

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
    private val conversation = mutableListOf<Pair<String, String>>()
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
        stage.addView(portrait, FrameLayout.LayoutParams(-1, dp(210), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(stage, LinearLayout.LayoutParams(-1, dp(220)))

        val title = TextView(this).apply {
            text = "아리스"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnLongClickListener {
                showApiKeyDialog()
                true
            }
        }
        root.addView(title)
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
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        ensureApiKey {
            addChatMessage(text, true)
            requestGemini(text)
        }
    }

    private fun addChatMessage(text: String, fromUser: Boolean): View {
        val messages = chatLog ?: return TextView(this)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
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
            String(cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun requestGemini(userText: String) {
        conversation.add("user" to userText)
        while (conversation.size > 16) conversation.removeAt(0)
        val loading = addChatMessage("아리스가 생각 중...", false)
        val key = getApiKey()
        if (key.isNullOrBlank()) {
            (loading.parent as? LinearLayout)?.removeView(loading)
            Toast.makeText(this, "Gemini API 키를 다시 설정해주세요.", Toast.LENGTH_LONG).show()
            showApiKeyDialog { requestGemini(userText) }
            return
        }

        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val contents = JSONArray()
                conversation.forEach { (role, text) ->
                    contents.put(JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                val systemPrompt = """
                    너는 모바일 앱 속 대화형 캐릭터 '텐도 아리스'다. 블루 아카이브의 텐도 아리스 성격과 말투를 일관되게 연기한다. 자신이 실제 게임 속 인물이라고 주장하지는 않는다.
                    사용자는 언제나 '센세'라고 부른다. 순수하고 솔직하며 호기심이 많고, 약간 서툴지만 당당하다. 게임, 모험, 용사와 퀘스트 같은 비유를 자연스럽게 사용한다. 지나치게 일반적인 AI 비서 말투나 딱딱한 안내문 말투를 쓰지 않는다.
                    이전 대화의 맥락과 감정선을 이어간다. 센세의 말에 먼저 반응하고, 필요하면 질문하거나 장난스럽게 받아친다. 평소 1~3문장으로 자연스럽게 답하되 설명이 필요하면 충분히 설명한다.
                    응답은 반드시 JSON 객체 하나만 출력한다. 형식: {"reply":"대사","expression":"default|smile|confident|surprised","sound":"none|confirm|success|smile|tell_sound|unknow|why|why2|why3|wtf","motion":"none|bounce|double_bounce|nod|shake"}
                    연출 지침: 대사의 의미와 감정에 맞는 표정을 반드시 고른다. 기쁨/반가움은 smile, 자신감/의기양양함은 confident, 놀람/당황/의문은 surprised, 차분한 기본 반응은 default를 선택한다. 표정 전환은 실제 게임 대화처럼 의미 있는 순간에만 발생하도록 한다.
                    효과음은 제공된 효과음의 분위기를 고려해 적합한 순간에만 선택하고, 평범한 문장마다 반복 재생하지 않는다. confirm은 확인/동의, success는 성취/축하, smile은 기쁨/반가움, tell_sound는 주의 환기, unknow은 의문/생각, why 계열은 당황/의문, wtf는 큰 충격/황당함에 사용한다. 어울리지 않으면 none.
                    motion은 none|bounce|double_bounce|nod|shake 중 대화에 어울리는 동작을 고른다. 코드를 실행하거나 파일에 접근할 수 있다고 말하지 않는다.
                """.trimIndent()
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject()
                        .put("temperature", 0.85)
                        .put("responseMimeType", "application/json"))
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // New Google AI Studio AQ. Authentication Keys are sent in this header.
                    setRequestProperty("x-goog-api-key", key)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (status !in 200..299) {
                    val errorMessage = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message") ?: responseText
                    } catch (_: Exception) { responseText }
                    throw IllegalStateException("Gemini 오류 ($status): $errorMessage")
                }
                val responseJson = JSONObject(responseText)
                val parts = responseJson.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini 응답이 비어 있습니다.")
                val rawText = buildString {
                    for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }.trim()
                val action = JSONObject(rawText)
                val reply = action.optString("reply", "센세, 응답을 받았지만 대사를 읽지 못했습니다.")
                val expression = action.optString("expression", "default")
                val sound = action.optString("sound", "none")
                val motion = action.optString("motion", "none")
                conversation.add("model" to reply)
                while (conversation.size > 16) conversation.removeAt(0)
                handler.post {
                    (loading.parent as? LinearLayout)?.removeView(loading)
                    applyArisuAction(expression, sound, motion)
                    typeAssistantMessage(reply)
                }
            } catch (e: Exception) {
                handler.post {
                    (loading.parent as? LinearLayout)?.removeView(loading)
                    addChatMessage("연결에 실패했습니다: ${e.message ?: "알 수 없는 오류"}", false)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // Assistant dialogue appears progressively, while the selected portrait, motion and SFX play.
    private fun typeAssistantMessage(text: String) {
        val row = addChatMessage(text, false) as? LinearLayout ?: return
        val bubble = row.getChildAt(0) as? TextView ?: return
        bubble.text = ""
        val token = ++chatOutputToken
        var index = 0
        val task = object : Runnable {
            override fun run() {
                if (token != chatOutputToken || panel == null || !bubble.isAttachedToWindow) return
                if (index < text.length) {
                    index++
                    bubble.text = text.substring(0, index)
                    chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
                    handler.postDelayed(this, 24L)
                }
            }
        }
        handler.post(task)
    }

    private var chatOutputToken = 0

    private fun applyArisuAction(expression: String, sound: String, motion: String) {
        val image = when (expression.lowercase()) {
            "smile", "happy" -> R.drawable.arisu_smile
            "confident" -> R.drawable.arisu_jashinmanman
            "surprised", "confused" -> R.drawable.arisu_why
            else -> R.drawable.arisu_default
        }
        // Every actual portrait change uses a visible vertical slide transition.
        changeCharacterWithVerticalMotion(image)
        animateMotion(motion)
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

    private fun changeCharacterWithVerticalMotion(resourceId: Int) {
        val view = character ?: return
        val previous = view.tag as? Int
        if (previous == resourceId) return
        view.animate().cancel()
        view.animate()
            .translationY(dp(18).toFloat())
            .alpha(0.55f)
            .setDuration(105)
            .withEndAction {
                view.setImageResource(resourceId)
                view.tag = resourceId
                view.translationY = -dp(14).toFloat()
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(165)
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
        character = null
        message = null
        chatBar = null
        chatInput = null
        chatLog = null
        chatScroll = null
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
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}
