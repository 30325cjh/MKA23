package com.mka23.arisu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == "com.mka23.arisu.PICK_ATTACHMENT") {
            launchAttachmentPicker()
            return
        }
        if (intent?.action == "com.mka23.arisu.SAVE_GENERATED_FILE") {
            launchSavePicker(intent)
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFFF3F9FF.toInt())
        }
        root.addView(TextView(this).apply {
            text = "아리케이 비서"
            textSize = 28f
            setTextColor(0xFF17496A.toInt())
        })
        root.addView(TextView(this).apply {
            text = "아리스와 케이가 화면 위에서 센세를 맞이합니다."
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Gemini API 키"
            textSize = 15f
            setTextColor(0xFF17496A.toInt())
            setPadding(0, 8, 0, 6)
        })
        val apiKeyInput = EditText(this).apply {
            hint = "Google AI Studio에서 발급한 API 키"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(apiKeyInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "API 키 저장"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                if (key.length < 20) {
                    apiKeyInput.error = "올바른 Gemini API 키를 입력해주세요."
                } else {
                    try {
                        saveApiKey(key)
                        apiKeyInput.text?.clear()
                        Toast.makeText(this@MainActivity, "Gemini API 키를 안전하게 저장했어요.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "API 키 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        root.addView(Button(this).apply {
            text = "화면 위에 표시 권한 허용"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    status.text = "권한이 이미 허용되어 있어요."
                }
            }
        })
        root.addView(Button(this).apply {
            text = "아리케이 비서 시작"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity)) {
                    val service = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    status.text = "아리케이가 화면 위에서 대기 중이에요!"
                } else {
                    status.text = "먼저 화면 위에 표시 권한을 허용해 주세요."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        setContentView(root)
    }



    private fun apiPreferences() = getSharedPreferences("arisu_secure_settings", MODE_PRIVATE)

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
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        apiPreferences().edit()
            .putString("api_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("api_value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private var pendingSaveName: String = "arisu_file.txt"
    private var pendingSaveMime: String = "text/plain"
    private var pendingSaveContent: String = ""

    private fun launchSavePicker(source: Intent) {
        pendingSaveName = source.getStringExtra("file_name") ?: "arisu_file.txt"
        pendingSaveMime = source.getStringExtra("file_mime") ?: "text/plain"
        pendingSaveContent = source.getStringExtra("file_content") ?: ""
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = pendingSaveMime
            putExtra(Intent.EXTRA_TITLE, pendingSaveName)
        }
        startActivityForResult(picker, 2308)
    }

    private fun notifyOverlay(action: String, uri: Uri? = null) {
        val service = Intent(this, OverlayService::class.java).apply {
            this.action = action
            if (uri != null) {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try { startService(service) } catch (_: Exception) {}
    }

    private fun launchAttachmentPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "text/html", "application/xhtml+xml"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, 2307)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2307) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    notifyOverlay("com.mka23.arisu.ATTACHMENT_SELECTED", uri)
                } else notifyOverlay("com.mka23.arisu.PICKER_CLOSED")
            } else notifyOverlay("com.mka23.arisu.PICKER_CLOSED")
            finish()
        } else if (requestCode == 2308) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(pendingSaveContent.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("저장 위치를 열 수 없습니다.")
                        Toast.makeText(this, "'$pendingSaveName' 저장 완료", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "파일 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            notifyOverlay("com.mka23.arisu.PICKER_CLOSED")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                "오버레이 권한: 허용됨"
            } else {
                "오버레이 권한: 아직 허용되지 않음"
            }
        }
    }
}