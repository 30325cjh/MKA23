package com.mka23.arisu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=1; setPadding(24,32,24,24) }
  root.addView(TextView(this).apply { text="아리스 AI 비서"; textSize=28f })
  root.addView(TextView(this).apply { text="화면 위에 아리스를 표시하려면 권한을 허용하세요." })
  val key=EditText(this).apply { hint="Gemini API 키"; singleLine=true; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(getSharedPreferences("prefs",0).getString("key","")) }
  root.addView(key)
  root.addView(Button(this).apply { text="API 키 저장"; setOnClickListener { getSharedPreferences("prefs",0).edit().putString("key",key.text.toString().trim()).apply(); Toast.makeText(this@MainActivity,"저장 완료",Toast.LENGTH_SHORT).show() } })
  root.addView(Button(this).apply { text="오버레이 권한 허용"; setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) } })
  root.addView(Button(this).apply { text="아리스 시작"; setOnClickListener { if(Settings.canDrawOverlays(this@MainActivity)) { val i=Intent(this@MainActivity,OverlayService::class.java); if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i) } else Toast.makeText(this@MainActivity,"먼저 오버레이 권한을 허용하세요.",Toast.LENGTH_LONG).show() } })
  setContentView(ScrollView(this).apply { addView(root) })
 }
}