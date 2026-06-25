package com.dawon.autokkk

import android.app.Notification
import java.util.concurrent.ConcurrentHashMap

/**
 * 방(roomKey) → 카톡 알림의 답장 액션(RemoteInput + PendingIntent) 보관소.
 *
 * 메시지를 받은 그 순간에만 알림에 답장 액션이 붙어 오므로, 수신 즉시 방키로 저장해 둔다.
 * step3(승인 후 발송)에서 이 액션을 꺼내 RemoteInput으로 답장을 발사한다.
 * 같은 방의 새 알림이 오면 최신 PendingIntent로 덮어써 항상 유효한 핸들을 유지한다.
 *
 * 프로세스 메모리에만 존재(영속 안 함) — 앱이 죽으면 비워지고, 다음 알림에서 다시 채워진다.
 */
object ReplyStore {
    private val map = ConcurrentHashMap<String, Notification.Action>()

    fun put(roomKey: String, action: Notification.Action) {
        map[roomKey] = action
    }

    fun get(roomKey: String): Notification.Action? = map[roomKey]

    fun rooms(): List<String> = map.keys.toList()
}
