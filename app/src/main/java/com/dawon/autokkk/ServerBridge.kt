package com.dawon.autokkk

import android.content.Context
import org.json.JSONArray
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

    /** 거래처 자료획득(B): 공유로 받은 첨부를 base64로 서버 /asset 에 비동기 업로드. */
    fun uploadAsset(filename: String, bytes: ByteArray) {
        val base = baseUrl()
        if (base.isBlank()) return
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("filename", filename)
            put("data_b64", b64)
            put("note", "kakao share")
        }.toString()
        Thread {
            try { postJson("$base/asset", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** 진단(임시): 한 줄 로그를 서버 /debug 로 전송. 발송 폴링 흐름 확인용. */
    fun debug(dump: String) {
        val base = baseUrl()
        if (base.isBlank()) return
        val payload = JSONObject().apply { put("dump", dump) }.toString()
        Thread {
            try { postJson("$base/debug", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** 은행 입금SMS를 서버 /deposit-sms 로 전송 → 서버가 tax 입금 webhook으로 중계. */
    fun depositSms(sender: String, text: String) {
        val base = baseUrl()
        if (base.isBlank()) return
        val payload = JSONObject().apply {
            put("sender", sender)
            put("text", text)
        }.toString()
        Thread {
            try { postJson("$base/deposit-sms", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** 서버 발송 대기 목록(GET /pending). 각 항목 {id, room_key, text}. 실패/없음=null. */
    fun fetchPending(): JSONArray? {
        val base = baseUrl()
        if (base.isBlank()) return null
        return try {
            val resp = httpGet("$base/pending", token()) ?: return null
            JSONObject(resp).optJSONArray("pending")
        } catch (_: Exception) {
            null
        }
    }

    /** 발송 결과 콜백(POST /sent). status=sent/no_session/failed 등. */
    fun reportSent(id: Int, status: String) {
        val base = baseUrl()
        if (base.isBlank()) return
        val payload = JSONObject().apply {
            put("id", id)
            put("status", status)
        }.toString()
        Thread {
            try { postJson("$base/sent", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    private fun httpGet(urlStr: String, token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode in 200..299)
                conn.inputStream.bufferedReader().use { it.readText() }
            else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
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
