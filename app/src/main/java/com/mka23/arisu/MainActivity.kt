package com.mka23.arisu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import android.content.ContentResolver
import android.view.ViewGroup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import java.security.KeyStore
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var pendingExportPassword: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFFF3F9FF.toInt())
        }
        root.addView(TextView(this).apply {
            text = "아리스 AI 비서"
            textSize = 28f
            setTextColor(0xFF17496A.toInt())
        })
        root.addView(TextView(this).apply {
            text = "아리스가 화면 위에 나타나 선생님을 맞이합니다."
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        root.addView(status)
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
            text = "아리스 비서 시작"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity)) {
                    val service = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    status.text = "아리스가 화면 위에서 대기 중이에요!"
                } else {
                    status.text = "먼저 화면 위에 표시 권한을 허용해 주세요."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "API 키 암호화 백업 만들기"
            setOnClickListener { askBackupPassword() }
        })
        root.addView(Button(this).apply {
            text = "API 키 백업 복원하기"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                }
                startActivityForResult(pick, REQUEST_IMPORT)
            }
        })
        root.addView(TextView(this).apply {
            text = "백업은 복구 비밀번호로 암호화됩니다. 비밀번호를 잊으면 복구할 수 없으니 안전한 곳에 보관하세요."
            textSize = 13f
            setPadding(0, 14, 0, 0)
        })
        setContentView(root)
    }

    private fun askBackupPassword() {
        val apiKey = readStoredApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "먼저 아리스 설정에서 Gemini API 키를 저장해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply {
            hint = "복구 비밀번호 (8자 이상)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialogBuilderCompat.show(this, "백업 암호 설정", "이 비밀번호로 백업 파일을 암호화합니다. 비밀번호는 앱에 저장되지 않습니다.", input) { password ->
            if (password.length < 8) {
                input.error = "8자 이상 입력해주세요."
                false
            } else {
                pendingExportPassword = password
                val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "Arisu_API_Key_Backup.bin")
                }
                startActivityForResult(create, REQUEST_EXPORT)
                true
            }
        }
    }

    @Deprecated("Deprecated by Android, retained for API 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            pendingExportPassword = null
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> {
                val password = pendingExportPassword ?: return
                pendingExportPassword = null
                try {
                    val key = readStoredApiKey() ?: throw IllegalStateException("저장된 API 키가 없습니다.")
                    val bytes = encryptBackup(key, password)
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("백업 파일을 쓸 수 없습니다.")
                    Toast.makeText(this, "암호화된 API 키 백업을 저장했어요.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "백업 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_IMPORT -> {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("백업 파일을 읽을 수 없습니다.")
                    askRestorePassword(bytes)
                } catch (e: Exception) {
                    Toast.makeText(this, "파일 읽기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun askRestorePassword(bytes: ByteArray) {
        val input = EditText(this).apply {
            hint = "백업을 만들 때 설정한 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialogBuilderCompat.show(this, "API 키 백업 복원", "백업 파일을 만들 때 설정한 복구 비밀번호를 입력하세요.", input) { password ->
            try {
                val apiKey = decryptBackup(bytes, password)
                saveStoredApiKey(apiKey)
                Toast.makeText(this, "API 키 복원 완료! 이제 다시 입력하지 않아도 됩니다.", Toast.LENGTH_LONG).show()
                true
            } catch (_: Exception) {
                input.error = "비밀번호가 틀렸거나 백업 파일이 손상되었습니다."
                false
            }
        }
    }

    private fun encryptBackup(apiKey: String, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, 250000, 256)
        val rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(BACKUP_MAGIC)
                out.writeInt(salt.size); out.write(salt)
                out.writeInt(iv.size); out.write(iv)
                out.writeInt(encrypted.size); out.write(encrypted)
            }
            buffer.toByteArray()
        }
    }

    private fun decryptBackup(bytes: ByteArray, password: String): String {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readUTF() != BACKUP_MAGIC) throw IllegalArgumentException("지원하지 않는 백업 파일입니다.")
            val saltSize = input.readInt()
            if (saltSize != 16) throw IllegalArgumentException("잘못된 백업 파일입니다.")
            val salt = ByteArray(saltSize); input.readFully(salt)
            val ivSize = input.readInt()
            if (ivSize != 12) throw IllegalArgumentException("잘못된 백업 파일입니다.")
            val iv = ByteArray(ivSize); input.readFully(iv)
            val encryptedSize = input.readInt()
            if (encryptedSize !in 16..4096) throw IllegalArgumentException("잘못된 백업 파일입니다.")
            val encrypted = ByteArray(encryptedSize); input.readFully(encrypted)
            val spec = PBEKeySpec(password.toCharArray(), salt, 250000, 256)
            val rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted), Charsets.UTF_8).trim()
        }
    }

    private fun deviceSecretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "arisu_gemini_api_key"
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun readStoredApiKey(): String? {
        return try {
            val prefs = getSharedPreferences("arisu_secure_settings", Context.MODE_PRIVATE)
            val iv = android.util.Base64.decode(prefs.getString("api_iv", null), android.util.Base64.NO_WRAP)
            val value = android.util.Base64.decode(prefs.getString("api_value", null), android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deviceSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(value), Charsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun saveStoredApiKey(apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceSecretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        getSharedPreferences("arisu_secure_settings", Context.MODE_PRIVATE).edit()
            .putString("api_iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putString("api_value", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .apply()
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

    companion object {
        private const val REQUEST_EXPORT = 8101
        private const val REQUEST_IMPORT = 8102
        private const val BACKUP_MAGIC = "ARISU_API_BACKUP_V1"
    }
}

private object AlertDialogBuilderCompat {
    fun show(activity: Activity, title: String, message: String, input: EditText, onConfirm: (String) -> Boolean) {
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("확인", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onConfirm(input.text.toString())) dialog.dismiss()
            }
        }
        dialog.show()
    }
}
