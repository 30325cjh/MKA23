package com.mka23.arisu

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OverlayService: Service() {
 private lateinit var wm:WindowManager
 private var bubble:ImageView?=null
 private var panel:LinearLayout?=null
 private var reply:TextView?=null
 private val handler=Handler(Looper.getMainLooper())
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){super.onCreate();wm=getSystemService(WINDOW_SERVICE) as WindowManager
  if(Build.VERSION.SDK_INT>=26){val ch="arisu";getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch,"아리스 비서",NotificationManager.IMPORTANCE_LOW));startForeground(23,Notification.Builder(this,ch).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("아리스 대기 중").build())}
  else startForeground(23,Notification.Builder(this).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("아리스 대기 중").build())
  showBubble()
 }
 private fun params(w:Int,h:Int,g:Int=Gravity.TOP or Gravity.START)=WindowManager.LayoutParams(w,h,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=g;x=20;y=180}
 private fun showBubble(){val v=ImageView(this).apply{setImageResource(android.R.drawable.star_big_on);background=GradientDrawable().apply{setColor(0xffeaf8ff.toInt());cornerRadius=100f};setPadding(8,8,8,8)};bubble=v;v.setOnClickListener{showPanel()};wm.addView(v,params(dp(60),dp(60)))}
 private fun showPanel(){if(panel!=null){panel?.visibility=View.VISIBLE;return};val root=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(10),dp(12),dp(10));background=GradientDrawable().apply{setColor(0xfff4faff.toInt());cornerRadius=dp(18).toFloat()}}
  root.addView(TextView(this).apply{text="아리스: 선생님! 무엇을 도와드릴까요?";textSize=18f})
  reply=root.getChildAt(0) as TextView
  val input=EditText(this).apply{hint="아리스에게 말하기…";singleLine=true};root.addView(input)
  root.addView(Button(this).apply{text="전송";setOnClickListener{val q=input.text.toString().trim();if(q.isNotEmpty())process(q)}})
  root.addView(Button(this).apply{text="접기";setOnClickListener{root.visibility=View.GONE}})
  panel=root;wm.addView(root,params(dp(320),WindowManager.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{y=dp(20);flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL})
 }
 private fun process(q:String){if(q.startsWith("검색")){val term=q.removePrefix("검색").trim();startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(term))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));reply?.text="검색 페이지를 열었어요!";return}
  if(q.startsWith("앱 실행")||q.startsWith("앱 켜")){val name=q.substringAfter(' ').trim();val pm=packageManager;val a=pm.getInstalledApplications(0).firstOrNull{pm.getApplicationLabel(it).toString().contains(name,true)};val i=a?.let{pm.getLaunchIntentForPackage(it.packageName)};if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);reply?.text="앱을 실행했어요!"}else reply?.text="앱을 찾지 못했어요.";return};ask(q)}
 private fun ask(q:String){val key=getSharedPreferences("prefs",0).getString("key","").orEmpty();if(key.isBlank()){reply?.text="먼저 앱에서 Gemini API 키를 저장해 주세요.";return};reply?.text="아리스가 생각 중입니다…";thread{try{val c=URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="+key).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=15000;c.readTimeout=30000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");val prompt="너는 밝고 순수한 한국어 AI 비서다. 사용자를 선생님이라고 부른다. $q";val body=JSONObject().put("contents",org.json.JSONArray().put(JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",prompt))))).toString();c.outputStream.use{it.write(body.toByteArray())};val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};val ans=JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");handler.post{reply?.text=ans}}catch(e:Exception){handler.post{reply?.text="연결 오류입니다. API 키와 네트워크를 확인해 주세요."}}}}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onDestroy(){bubble?.let{wm.removeView(it)};panel?.let{wm.removeView(it)};handler.removeCallbacksAndMessages(null);super.onDestroy()}
}