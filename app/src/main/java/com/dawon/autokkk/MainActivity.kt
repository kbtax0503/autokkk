package com.dawon.autokkk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 설정 화면: 서버 주소·폰 토큰 입력/저장, 알림 접근 권한 열기, 상태 표시, 연결 테스트.
 * 실제 수신은 KakaoListenerService가 백그라운드에서 처리.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serverUrl: EditText
    private lateinit var phoneToken: EditText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val granted = isNotificationAccessGranted()
            val conn = KakaoListenerService.connected
            val st = when {
                !granted -> "❌ 알림 접근 권한 없음 — 아래 '알림 접근 권한 열기'에서 켜세요"
                !conn -> "⏳ 알림 접근 허용됨 · 서비스 연결 대기(카톡 알림 한 번 오면 연결)"
                else -> "✅ 연결됨 · 카톡 수신 대기"
            }
            status.text = "$st\n\n최근 수신: ${KakaoListenerService.lastEvent}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrl = findViewById(R.id.serverUrl)
        phoneToken = findViewById(R.id.phoneToken)
        status = findViewById(R.id.status)

        serverUrl.setText(prefs().getString("server_url", "http://192.168.45.77:8195"))
        phoneToken.setText(prefs().getString("phone_token", ""))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs().edit()
                .putString("server_url", serverUrl.text.toString().trim())
                .putString("phone_token", phoneToken.text.toString().trim())
                .apply()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            } else {
                Toast.makeText(this, "알림 권한 OK", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            ServerBridge(this).ingest(
                msgId = "test_${System.currentTimeMillis()}",
                room = "테스트방",
                roomKey = "test",
                sender = "앱테스트",
                text = "연결 테스트 메시지",
                ts = System.currentTimeMillis() / 1000
            )
            Toast.makeText(this, "테스트 전송함 — 서버 로그/DB 확인", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** 알림 접근(NotificationListener) 권한이 이 앱에 부여됐는지. */
    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return !flat.isNullOrEmpty() && flat.contains(packageName)
    }
}
