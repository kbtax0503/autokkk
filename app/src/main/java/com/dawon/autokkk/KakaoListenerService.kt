package com.dawon.autokkk

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * 카톡(com.kakao.talk) 알림만 수신 → 방/보낸이/본문 파싱 → 서버 /ingest 전송.
 *
 * - 다른 앱(은행·개인 등) 알림은 패키지 필터로 무시(정보 최소).
 * - 단톡 보낸이 분리는 MessagingStyle 우선, 없으면 extras 폴백.
 * - 답장 액션(RemoteInput)은 받은 즉시 ReplyStore에 방키로 보관(step3 발송용).
 *
 * 시스템이 "알림 접근" 허용 후 이 서비스를 바인드한다(부팅 후 자동 재바인드).
 */
class KakaoListenerService : NotificationListenerService() {

    companion object {
        const val KAKAO_PKG = "com.kakao.talk"

        /** 문자(SMS) 앱 패키지 — 은행 입금문자 수신용. */
        val MESSAGING_PKGS = setOf(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.android.mms",
        )

        /** MainActivity 표시용 최근 상태(프로세스 메모리). */
        @Volatile var lastEvent: String = "(수신 없음)"
        @Volatile var connected: Boolean = false
    }

    private lateinit var bridge: ServerBridge

    @Volatile private var polling = false
    private var pollThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        bridge = ServerBridge(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        lastEvent = "알림 접근 연결됨 — 대기 중"
        startPolling()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        stopPolling()
        // 일부 기기에서 리스너가 끊기면 재바인드 요청(자동 복구).
        // requestRebind는 정적 메서드 → 클래스명으로 호출.
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, KakaoListenerService::class.java)
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    // ── 발송 폴링: 서버 /pending 의 승인된 답장을 가져와 그 방에 RemoteInput으로 발사 ──

    private fun startPolling() {
        if (polling) return
        polling = true
        pollThread = Thread {
            while (polling) {
                try { pollOnce() } catch (_: Exception) {}
                try { Thread.sleep(6000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun pollOnce() {
        val arr = bridge.fetchPending() ?: return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id < 0) continue
            val roomKey = item.optString("room_key")
            val text = item.optString("text")
            val action = ReplyStore.get(roomKey)
            val ok = action != null && sendReply(action, text)
            bridge.reportSent(id, if (ok) "sent" else "no_session")
            lastEvent = if (ok) "답장 발송: [$roomKey] ${text.take(20)}"
                        else "발송 실패(세션 없음): $roomKey"
        }
    }

    /** 저장해둔 답장 액션의 RemoteInput에 text를 채워 PendingIntent 발사 = 그 방에 카톡 답장 발송. */
    private fun sendReply(action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val fillIn = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        return try {
            action.actionIntent.send(this, 0, fillIn)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        when {
            pkg == KAKAO_PKG -> try {
                handle(sbn)
            } catch (e: Exception) {
                lastEvent = "파싱 오류: ${e.message}"
            }
            pkg in MESSAGING_PKGS -> try {
                handleBankSms(sbn)
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val parsed = parse(n, extras)
        if (parsed.text.isBlank()) return  // 본문 없는 알림(읽음표시·미디어 등) 무시

        // 방 식별키: 카톡이 방마다 tag를 다르게 주는 경우 우선, 없으면 방 이름.
        val roomKey = sbn.tag ?: parsed.room

        // 답장 액션 보관(받은 순간 최신 PendingIntent 유지) — step3 발송에서 사용.
        findReplyAction(n)?.let { ReplyStore.put(roomKey, it) }

        val msgId = "${roomKey}_${sbn.postTime}_${parsed.text.hashCode()}"
        val ts = sbn.postTime / 1000  // epoch seconds

        lastEvent = "[${parsed.room}] ${parsed.sender}: ${parsed.text.take(40)}"
        bridge.ingest(msgId, parsed.room, roomKey, parsed.sender, parsed.text, ts)
    }

    /** 은행 입금/출금 SMS 알림 → 본문 추출 → 서버 /deposit-sms 중계. 은행 문자만 통과(광고 제외). */
    private fun handleBankSms(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val sender = extras.getString("android.title")?.trim() ?: ""
        // SMS 전문은 bigText에 들어옴(text는 미리보기라 잘릴 수 있음).
        val body = (extras.getCharSequence("android.bigText")
            ?: extras.getCharSequence("android.text"))?.toString()?.trim() ?: ""
        if (body.isBlank()) return
        if (!isBankSms(sender, body)) return
        lastEvent = "입금문자: $sender"
        bridge.depositSms(sender, body)
    }

    /** 은행 입금문자 판별: 발신자=은행(기업은행/IBK) 또는 본문에 입/출금+잔액. */
    private fun isBankSms(sender: String, body: String): Boolean {
        if (sender.contains("기업은행") || sender.contains("IBK")) return true
        return (body.contains("입금") || body.contains("출금")) && body.contains("잔액")
    }

    private data class Parsed(val room: String, val sender: String, val text: String)

    /**
     * MessagingStyle 우선(단톡 보낸이·방 구분이 정확), 없으면 extras 폴백.
     * - 단톡: conversationTitle=방 이름, 마지막 메시지 person=보낸이.
     * - 1:1:  conversationTitle 없을 수 있어 android.title(상대 이름)로 방·보낸이 대체.
     */
    private fun parse(n: Notification, extras: Bundle): Parsed {
        val title = extras.getString("android.title")?.trim()
        val sub = extras.getString("android.subText")?.trim()
        val bodyExtra = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""

        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (_: Exception) {
            null
        }

        if (style != null) {
            val last = style.messages.lastOrNull()
            val convTitle = style.conversationTitle?.toString()?.trim()
            val sender = last?.person?.name?.toString()?.trim() ?: title ?: "?"
            val body = last?.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: bodyExtra
            // 방 이름: conversationTitle > subText > title.
            // 카톡 단톡은 conversationTitle이 비고 title=보낸이·subText=방이름이라, subText 우선.
            val room = convTitle ?: sub ?: title ?: sender
            return Parsed(room, sender, body)
        }

        // 폴백(MessagingStyle 없음): 단톡=title이 보낸이·subText가 방, 1:1=title이 상대.
        val sender = title ?: "?"
        val room = sub ?: title ?: "?"
        return Parsed(room, sender, bodyExtra)
    }

    /** 알림에 붙은 답장 액션(RemoteInput 보유) 탐색. 없으면 null. */
    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val inputs = a.remoteInputs
            if (inputs != null && inputs.isNotEmpty()) return a
        }
        return null
    }
}
