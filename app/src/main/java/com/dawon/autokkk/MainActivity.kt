package com.dawon.autokkk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

/**
 * 설정 화면: 서버 주소·폰 토큰 입력/저장, 알림 접근 권한 열기, 상태 표시, 연결 테스트.
 * 실제 수신은 KakaoListenerService가 백그라운드에서 처리.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serverUrl: EditText
    private lateinit var phoneToken: EditText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** SAF 다운로드폴더 선택 → 영구 읽기권한 저장(download_tree_uri + 프로브용 tree_uri) + 파일 확인. */
    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                prefs().edit()
                    .putString("download_tree_uri", uri.toString())
                    .putString("tree_uri", uri.toString())   // 스파이크 probeFolder 호환
                    .apply()
                Toast.makeText(this, "다운로드 폴더 지정됨 — 파일 확인 보냄", Toast.LENGTH_SHORT).show()
                probeFolder()
            }
        }

    /** SAF 사진 저장폴더 선택 → 영구 읽기권한 저장(photo_tree_uri). 자동수집(B2)이 스캔. */
    private val pickPhotoTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                prefs().edit().putString("photo_tree_uri", uri.toString()).apply()
                Toast.makeText(this, "사진 저장폴더 지정됨", Toast.LENGTH_SHORT).show()
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            val granted = isNotificationAccessGranted()
            val conn = KakaoListenerService.connected
            val st = when {
                !granted -> "❌ 알림 접근 권한 없음 — 아래 '알림 접근 권한 열기'에서 켜세요"
                !conn -> "⏳ 알림 접근 허용됨 · 서비스 연결 대기(카톡 알림 한 번 오면 연결)"
                else -> "✅ 연결됨 · 카톡 수신 대기"
            }
            val a11y = if (isA11yGranted()) "✅ 접근성 켜짐" else "❌ 접근성 꺼짐(아래 버튼에서 켜세요)"
            val dumpInfo = if (KakaoAccessibilityService.lastDumpAt > 0)
                "최근 트리덤프 ${KakaoAccessibilityService.lastDumpNodes}노드"
            else "트리덤프 없음 — 카톡방 열어보세요"
            val photoOk = (prefs().getString("photo_tree_uri", "") ?: "").isNotBlank()
            val dlOk = (prefs().getString("download_tree_uri", "") ?: "").isNotBlank()
            val folder = "📁 사진폴더 ${if (photoOk) "✅" else "❌"} · 다운로드폴더 ${if (dlOk) "✅" else "❌"}"
            val auto = if (prefs().getBoolean("auto_capture_enabled", false)) "🟢 자동수집 ON" else "⚪ 자동수집 OFF"
            val rec = when {
                !VoxService.running -> "⚪ 녹음 꺼짐"
                VoxService.isRecording -> "🔴 녹음 중 (${VoxService.lastRms})"
                !VoxService.voxEnabled -> "⏸ 녹음 일시정지"
                VoxService.nightPaused -> "🌙 녹음 야간대기"
                else -> "🎙 녹음 대기 (소리 ${VoxService.lastRms})"
            }
            status.text = "$st\n$a11y · $dumpInfo\n$folder · $auto\n🎙 $rec\n\n최근 수신: ${KakaoListenerService.lastEvent}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrl = findViewById(R.id.serverUrl)
        phoneToken = findViewById(R.id.phoneToken)
        status = findViewById(R.id.status)

        serverUrl.setText(prefs().getString("server_url", "https://www.dawoncredit.com/kakao"))
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

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "접근성 설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener {
            try {
                pickPhotoTree.launch(null)
            } catch (_: Exception) {
                Toast.makeText(this, "폴더 선택 창을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            try {
                pickTree.launch(null)
            } catch (_: Exception) {
                Toast.makeText(this, "폴더 선택 창을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnScanNow).setOnClickListener {
            Toast.makeText(this, "스캔 시작 — 잠시만요", Toast.LENGTH_SHORT).show()
            Thread {
                val n = try { AssetUploader(this).scanAndUpload("수동") } catch (_: Exception) { -1 }
                runOnUiThread {
                    val msg = when {
                        n < 0 -> "스캔 오류"
                        n == 0 -> "새 파일 없음(이미 올렸거나 폴더 미지정)"
                        else -> "자료창고로 보냄: ${n}개"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }.apply { isDaemon = true }.start()
        }

        findViewById<Button>(R.id.btnProbeFiles).setOnClickListener { probeFolder() }

        // B2 무인 자동수집 — 앱 킬스위치 토글(서버 capture_enabled와 둘 다 ON이어야 작동).
        findViewById<Button>(R.id.btnAutoCapture).setOnClickListener {
            val cur = prefs().getBoolean("auto_capture_enabled", false)
            prefs().edit().putBoolean("auto_capture_enabled", !cur).apply()
            Toast.makeText(this, if (!cur) "자동수집 ON" else "자동수집 OFF", Toast.LENGTH_SHORT).show()
        }

        // 테스트: 5초 후 "현재 열려있는 카톡방"에서 자동 캡처 1회 실행(접근성 워커가 동작).
        findViewById<Button>(R.id.btnTestCapture).setOnClickListener {
            if (!prefs().getBoolean("auto_capture_enabled", false)) {
                Toast.makeText(this, "먼저 '자동수집 ON' 누르세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "5초 후 시작 — 지금 거래처 카톡방을 여세요", Toast.LENGTH_LONG).show()
            handler.postDelayed({
                CaptureQueue.enqueue(
                    CaptureRequest("(테스트)", null, "manual test", System.currentTimeMillis())
                )
            }, 5000)
        }

        // ── 회의 녹음(합친 기능) — VOX 녹음 + 텔레그램 원격제어 + 서버 직접 업로드 ──
        val botToken = findViewById<EditText>(R.id.botToken)
        botToken.setText(prefs().getString("tg_token", ""))
        findViewById<Button>(R.id.btnSaveBot).setOnClickListener {
            prefs().edit().putString("tg_token", botToken.text.toString().trim()).apply()
            Toast.makeText(this, "녹음 봇 토큰 저장됨 — 봇에게 메시지 한 번 보내면 연결", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRecPerms).setOnClickListener { requestRecPerms() }
        findViewById<Button>(R.id.btnRecStart).setOnClickListener {
            prefs().edit().putBoolean("autostart", true).apply()
            ContextCompat.startForegroundService(this, Intent(this, VoxService::class.java))
            Toast.makeText(this, "녹음 시작 — 소리 감지 시 자동 녹음", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRecStop).setOnClickListener {
            prefs().edit().putBoolean("autostart", false).apply()
            stopService(Intent(this, VoxService::class.java))
            Toast.makeText(this, "녹음 중지", Toast.LENGTH_SHORT).show()
        }
    }

    /** 회의 녹음에 필요한 권한: 마이크·알림·모든파일접근·배터리최적화 제외. */
    private fun requestRecPerms() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) ActivityCompat.requestPermissions(this, toAsk.toTypedArray(), 2)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
            }
        }
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
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

    /** 이 앱의 접근성 서비스가 켜져 있는지. */
    private fun isA11yGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return !flat.isNullOrEmpty() && flat.contains(packageName)
    }

    /**
     * 지정된 다운로드 폴더의 최근 파일 목록을 서버 /debug 로 보고([FILES]).
     * 카톡 "저장"이 그 폴더에 떨어져 우리 앱이 읽히는지 검증용(스파이크).
     */
    private fun probeFolder() {
        val uriStr = prefs().getString("tree_uri", "") ?: ""
        if (uriStr.isBlank()) {
            Toast.makeText(this, "먼저 '다운로드 폴더 지정'을 누르세요", Toast.LENGTH_SHORT).show()
            return
        }
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
        val bridge = ServerBridge(this)
        if (tree == null) {
            bridge.debug("[FILES] tree null")
            return
        }
        Thread {
            try {
                val sb = StringBuilder("[FILES] tree=${tree.name}\n")
                var count = 0
                fun walk(dir: DocumentFile, depth: Int) {
                    if (depth > 3 || count >= 60) return
                    for (f in dir.listFiles()) {
                        if (count >= 60) break
                        val indent = "  ".repeat(depth)
                        if (f.isDirectory) {
                            sb.append("$indent[D] ${f.name}\n")
                            walk(f, depth + 1)
                        } else {
                            count++
                            sb.append("$indent${f.name}  ${f.length()}B  mtime=${f.lastModified()}\n")
                        }
                    }
                }
                walk(tree, 0)
                sb.append("(files=$count)")
                bridge.debug(sb.toString().take(16000))
            } catch (e: Exception) {
                bridge.debug("[FILES] probe error: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }
}
