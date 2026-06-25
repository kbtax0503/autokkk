package com.dawon.autokkk

import android.app.Notification
import android.content.ComponentName
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

        /** MainActivity 표시용 최근 상태(프로세스 메모리). */
        @Volatile var lastEvent: String = "(수신 없음)"
        @Volatile var connected: Boolean = false
    }

    private lateinit var bridge: ServerBridge

    override fun onCreate() {
        super.onCreate()
        bridge = ServerBridge(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        lastEvent = "알림 접근 연결됨 — 대기 중"
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        // 일부 기기에서 리스너가 끊기면 재바인드 요청(자동 복구).
        // requestRebind는 정적 메서드 → 클래스명으로 호출.
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, KakaoListenerService::class.java)
            )
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PKG) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            lastEvent = "파싱 오류: ${e.message}"
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
        bridge.debug(structDump(sbn, n, extras, parsed))   // 진단(임시): 단톡 방/보낸이 필드 확인
    }

    /** 진단(임시): 방/보낸이 필드 위치 확인용. 메시지 본문은 제외(이름·방·플래그만). */
    private fun structDump(
        sbn: StatusBarNotification, n: Notification, extras: Bundle, parsed: Parsed
    ): String {
        val sb = StringBuilder()
        sb.append("title=").append(extras.getString("android.title"))
        sb.append(" | subText=").append(extras.getString("android.subText"))
        sb.append(" | summary=").append(extras.getString("android.summaryText"))
        sb.append(" | convTitle=").append(extras.getString("android.conversationTitle"))
        sb.append(" | isGroup=").append(extras.get("android.isGroupConversation"))
        sb.append(" | tag=").append(sbn.tag)
        try {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (style != null) {
                sb.append(" || STYLE conv=").append(style.conversationTitle)
                    .append(" group=").append(style.isGroupConversation)
                    .append(" persons=")
                    .append(style.messages.mapNotNull { it.person?.name?.toString() }.joinToString(","))
            } else {
                sb.append(" || STYLE=null")
            }
        } catch (e: Exception) {
            sb.append(" || STYLE_ERR=").append(e.message)
        }
        sb.append(" >> PARSED room=").append(parsed.room).append(" sender=").append(parsed.sender)
        return sb.toString()
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
