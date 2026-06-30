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

    /** 거래처 자료획득(B): 공유로 받은 첨부를 base64로 서버 /asset 에 비동기 업로드(B1 수동공유). */
    fun uploadAsset(filename: String, bytes: ByteArray, room: String = "") {
        val base = baseUrl()
        if (base.isBlank()) return
        val payload = assetPayload(filename, bytes, room, "kakao share")
        Thread {
            try { postJson("$base/asset", payload, token()) } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /**
     * 무인 자동수집(B2): 동기 업로드. 성공(2xx)이면 true → 호출자가 dedup seen 기록.
     * 호출자(AssetUploader)는 이미 워커스레드라 여기서 스레드 안 띄움.
     */
    fun uploadAssetSync(filename: String, bytes: ByteArray, room: String): Boolean {
        val base = baseUrl()
        if (base.isBlank()) return false
        return try {
            postJson("$base/asset", assetPayload(filename, bytes, room, "auto-capture"), token()) in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun assetPayload(filename: String, bytes: ByteArray, room: String, note: String): String {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return JSONObject().apply {
            put("filename", filename)
            put("data_b64", b64)
            put("note", note)
            if (room.isNotBlank()) put("room", room)
        }.toString()
    }

    /**
     * 무인 자동수집(B2): 서버 GET /capture-config → CaptureConfig(거래처방 allowlist + 킬스위치).
     * 실패 시 null(앱은 마지막 캐시 유지). 호출자는 워커스레드.
     */
    fun fetchCaptureConfig(): CaptureConfig? {
        val base = baseUrl()
        if (base.isBlank()) return null
        return try {
            val resp = httpGet("$base/capture-config", token()) ?: return null
            val o = JSONObject(resp)
            val arr = o.optJSONArray("rooms")
            val rooms = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) arr.optString(i)?.let { rooms.add(it) }
            val exArr = o.optJSONArray("exclude")
            val exclude = ArrayList<String>()
            if (exArr != null) for (i in 0 until exArr.length()) exArr.optString(i)?.let { exclude.add(it) }
            CaptureConfig(o.optBoolean("enabled", false), rooms, exclude)
        } catch (_: Exception) {
            null
        }
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

    /**
     * 회의 녹음 wav를 서버 /recording 으로 직접 업로드(raw body 스트리밍) — Syncthing 대체.
     * 큰 파일도 통째로 메모리에 안 올리도록 고정길이 스트리밍. 성공(2xx)이면 true.
     */
    fun uploadRecording(file: java.io.File): Boolean {
        val base = baseUrl()
        if (base.isBlank()) return false
        val url = "$base/recording?filename=" + java.net.URLEncoder.encode(file.name, "UTF-8")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 180000           // 큰 파일 업로드 여유
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                val tk = token(); if (tk.isNotBlank()) setRequestProperty("Authorization", "Bearer $tk")
                setFixedLengthStreamingMode(file.length())
            }
            file.inputStream().use { input -> conn.outputStream.use { out -> input.copyTo(out, 64 * 1024) } }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
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
