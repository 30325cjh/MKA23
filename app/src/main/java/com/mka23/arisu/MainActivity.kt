package com.mka23.arisu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

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
        setContentView(root)
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