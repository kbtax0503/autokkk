package com.dawon.autokkk

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 폰 앱 → kakao-reply-bridge 서버 통신.
 * 설정(서버주소·토큰)은 SharedPreferences("cfg")에만 저장 — 코드/repo에 없음.
 * 현재(step2): 수신 메시지를 /ingest 로 POST. (발송 /pending·/sent 은 step3)
 */
class ServerBridge(private val ctx: Context) {

    private fun prefs() = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun baseUrl() = prefs().getString("server_url", "")?.trim()?.trimEnd('/') ?: ""
    private fun token() = prefs().getString("phone_token", "")?.trim() ?: ""

    /** 수신 메시지를 서버 /ingest 로 비동기 전송. 서버주소 비어있으면 무시. */
    fun ingest(
        msgId: String, room: String, roomKey: String,
        sender: String, text: String, ts: Long
    ) {
        val base = baseUrl()
        if (base.isBlank()) return
        val payload = JSONObject().apply {
            put("msg_id", msgId)
            put("room", room)
            put("room_key", roomKey)
            put("sender", sender)
            put("text", text)
            put("ts", ts)
        }.toString()
        Thread {
            try { postJson("$base/ingest", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    private fun postJson(urlStr: String, body: String, token: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn?.disconnect()
        }
    }
}
