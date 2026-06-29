package com.dawon.autokkk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 폰 앱 ↔ 텔레그램 직접 통신 (방식 B).
 *  - 명령 수신: getUpdates 롱폴링 → 중지(일시정지)/시작/지금녹음/상태
 *  - 알림 전송: 배터리 온도 경고
 * 봇 토큰/chat_id는 SharedPreferences("cfg")에만 저장 — 코드/repo에 없음.
 * 보안: 저장된 chat_id에서 온 명령만 실행(첫 메시지로 chat_id 자동 학습).
 */
class TelegramBridge(private val ctx: Context, private val svc: VoxService) {

    @Volatile private var stop = false
    private var thread: Thread? = null
    private var offset = 0L
    private var lastTempAlertMs = 0L
    private var lastBattAlertMs = 0L

    private fun prefs() = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun token() = prefs().getString("tg_token", "")?.trim() ?: ""
    private fun chatId() = prefs().getString("tg_chat", "")?.trim() ?: ""
    private fun tempThreshold() = prefs().getInt("temp_thresh", 45)

    fun start() {
        stop = false
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stopBridge() {
        stop = true
        thread?.interrupt()
    }

    /** 비동기 알림 전송(호출 스레드를 막지 않음). 토큰/chat_id 없으면 무시. */
    fun notify(text: String) {
        val tk = token()
        val cid = chatId()
        if (tk.isBlank() || cid.isBlank()) return
        Thread { send(tk, text, cid) }.apply { isDaemon = true }.start()
    }

    private fun loop() {
        while (!stop) {
            val tk = token()
            if (tk.isBlank()) { sleep(3000); continue }
            try { pollOnce(tk) } catch (e: Exception) { sleep(3000) }
            try { checkHealth(tk) } catch (_: Exception) {}
        }
    }

    private fun pollOnce(tk: String) {
        val url = "https://api.telegram.org/bot$tk/getUpdates?timeout=45&offset=$offset"
        val resp = httpGet(url, 60000) ?: return
        val obj = JSONObject(resp)
        if (!obj.optBoolean("ok")) return
        val arr: JSONArray = obj.optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val upd = arr.getJSONObject(i)
            offset = upd.getLong("update_id") + 1
            val msg = upd.optJSONObject("message") ?: continue
            val chat = msg.optJSONObject("chat") ?: continue
            val fromId = chat.optLong("id").toString()
            val text = msg.optString("text", "").trim()
            if (chatId().isBlank()) {
                prefs().edit().putString("tg_chat", fromId).apply()
                send(tk, "✅ 연결됐습니다. 이제 이 채팅으로 온도 경고와 명령을 주고받습니다.\n명령: 상태·중지·시작·지금녹음\n설정: 대기시간 10 · 감지시간 3 · 민감도 800 · 휴면 22 9", fromId)
                continue
            }
            if (fromId != chatId()) { send(tk, "허용되지 않은 사용자입니다.", fromId); continue }
            handleCommand(tk, text)
        }
    }

    private fun handleCommand(tk: String, text: String) {
        val t = text.lowercase().replace(" ", "")
        when {
            // ── 원격 설정(prefs 즉시 반영 — VoxService가 매 프레임 prefs를 읽음) ──
            t.startsWith("대기") -> {
                val n = nums(text).firstOrNull()
                if (n != null && n in 2..120) { setInt("hangover_ms", n * 1000); send(tk, "✅ 대기시간 = ${n}초 (무음 ${n}초면 녹음 종료)") }
                else send(tk, "예) 대기시간 10  — 무음 10초면 종료 (2~120)")
            }
            t.startsWith("감지") -> {
                val n = nums(text).firstOrNull()
                if (n != null && n in 0..30) { setInt("trigger_ms", n * 1000); send(tk, "✅ 감지시간 = ${n}초 (소리 ${n}초 지속 시 녹음 시작)") }
                else send(tk, "예) 감지시간 3  — 소리가 3초 지속돼야 시작 (0~30)")
            }
            t.startsWith("민감") -> {
                val n = nums(text).firstOrNull()
                if (n != null && n in 50..3000) { setInt("threshold", n); send(tk, "✅ 민감도(임계값) = $n (낮을수록 민감)") }
                else send(tk, "예) 민감도 800  — 낮을수록 민감 (50~3000)")
            }
            t.startsWith("휴면") -> {
                val ns = nums(text)
                if (ns.size >= 2 && ns[0] in 0..24 && ns[1] in 0..24) {
                    setInt("active_start", ns[1]); setInt("active_end", ns[0])  // 휴면 A~B = 활성 B~A
                    send(tk, "✅ 휴면 ${ns[0]}시~${ns[1]}시(감지 안 함) · 활성 ${ns[1]}시~${ns[0]}시")
                } else send(tk, "예) 휴면 22 9  — 밤 22시~아침 9시는 감지 안 함")
            }
            t.startsWith("활성") -> {
                val ns = nums(text)
                if (ns.size >= 2 && ns[0] in 0..24 && ns[1] in 0..24) {
                    setInt("active_start", ns[0]); setInt("active_end", ns[1])
                    send(tk, "✅ 활성 ${ns[0]}시~${ns[1]}시 (이 시간대만 감지)")
                } else send(tk, "예) 활성 9 22  — 오전 9시~밤 22시만 감지")
            }
            t.contains("지금") || t.contains("강제") || t == "rec" || t == "record" -> {
                svc.requestForceRecord(); send(tk, "🔴 회의 모드 녹음을 시작합니다. (끊김 없이 녹음 · 30초 무음 시 알림 · 5분 무음 시 자동 대기)")
            }
            t.contains("중지") || t.contains("정지") || t.contains("끄") || t == "stop" || t == "off" -> {
                svc.requestStop(); send(tk, "⏸ 녹음을 일시정지했습니다. ('시작'으로 재개)")
            }
            t.contains("시작") || t.contains("켜") || t == "start" || t == "on" -> {
                svc.requestStartVox(); send(tk, "✅ 자동 녹음(VOX)을 재개했습니다.")
            }
            t.contains("상태") || t.contains("온도") || t == "status" -> {
                send(tk, statusText())
            }
            t.contains("목록") || t == "list" -> send(tk, listText())
            t.contains("용량") || t.contains("저장") || t == "storage" -> send(tk, storageText())
            t.contains("오늘") || t == "today" -> send(tk, todayText())
            else -> send(tk, "명령: 상태·중지·시작·지금녹음·녹음목록·용량·오늘\n설정: 대기시간 N · 감지시간 N · 민감도 N · 휴면 22 9")
        }
    }

    /** 문자열에서 정수들 추출(설정 명령용). 예: "휴면 22 9" → [22, 9]. */
    private fun nums(s: String): List<Int> =
        Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun setInt(key: String, v: Int) {
        prefs().edit().putInt(key, v).apply()
    }

    private fun statusText(): String {
        val b = svc.batteryInfo()
        val st = when {
            !VoxService.running -> "꺼짐"
            VoxService.nightPaused -> "🌙 야간 대기(감지 안 함)"
            VoxService.isRecording -> "● 녹음 중"
            !VoxService.voxEnabled -> "⏸ 일시정지"
            else -> "대기"
        }
        val tempStr = if (b.tempC < 0) "?" else "%.1f".format(b.tempC)
        val pctStr = if (b.pct < 0) "?" else "${b.pct}%"
        return "상태: $st\n온도: ${tempStr}℃ (경고 ${tempThreshold()}℃)\n배터리: $pctStr${if (b.charging) " (충전중)" else ""}\n버전: v${VoxService.APP_VER}"
    }

    private fun checkHealth(tk: String) {
        if (chatId().isBlank()) return
        val b = svc.batteryInfo()
        if (b.tempC >= 0) VoxService.lastTempC = b.tempC
        val now = System.currentTimeMillis()
        if (b.tempC >= tempThreshold() && now - lastTempAlertMs > 10 * 60 * 1000) {
            lastTempAlertMs = now
            send(tk, "🌡️ 경고: 폰 배터리 온도 ${"%.1f".format(b.tempC)}℃ — 임계 ${tempThreshold()}℃ 초과. 발열 확인 필요.")
        }
        if (b.pct in 1..20 && !b.charging && now - lastBattAlertMs > 30 * 60 * 1000) {
            lastBattAlertMs = now
            send(tk, "🔋 경고: 배터리 ${b.pct}% (충전 안 됨). 충전기 확인 필요.")
        }
    }

    fun send(tk: String, text: String, toChat: String = chatId()) {
        if (tk.isBlank() || toChat.isBlank()) return
        try {
            val body = "chat_id=" + URLEncoder.encode(toChat, "UTF-8") +
                "&text=" + URLEncoder.encode(text, "UTF-8")
            httpPost("https://api.telegram.org/bot$tk/sendMessage", body)
        } catch (_: Exception) {}
    }

    private fun recDir() = File(VoxService.REC_DIR)

    private fun wavFiles() =
        (recDir().listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: emptyArray())

    private fun listText(): String {
        val files = wavFiles().sortedByDescending { it.name }
        if (files.isEmpty()) return "녹음 없음"
        val sb = StringBuilder("최근 녹음 ${minOf(10, files.size)}개:\n")
        for (f in files.take(10)) sb.append("• ${f.name}  (${mb(f.length())})\n")
        return sb.toString().trimEnd()
    }

    private fun storageText(): String {
        val files = wavFiles()
        val total = files.sumOf { it.length() }
        return "저장 상태\n파일: ${files.size}개\n사용: ${mb(total)}\n남은 공간: ${mb(recDir().usableSpace)}"
    }

    private fun todayText(): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val files = wavFiles().filter { it.name.startsWith("REC_$today") }
        val total = files.sumOf { it.length() }
        val secs = (total / 32000).toInt()  // 16kHz mono 16bit = 32000 B/s
        return "오늘($today): ${files.size}건, 약 ${secs / 60}분 ${secs % 60}초"
    }

    private fun mb(bytes: Long): String {
        val m = bytes / (1024.0 * 1024.0)
        return if (m >= 1024) "%.2f GB".format(m / 1024) else "%.1f MB".format(m)
    }

    private fun httpGet(urlStr: String, readTimeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = readTimeoutMs
            }
            if (conn.responseCode in 200..299)
                conn.inputStream.bufferedReader().use { it.readText() }
            else null
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun httpPost(urlStr: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299)
                conn.inputStream.bufferedReader().use { it.readText() }
            else null
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
