package com.dawon.autokkk

import android.app.PendingIntent
import java.util.concurrent.ConcurrentLinkedQueue

/** 서버 GET /capture-config 응답 = 거래처방 allowlist + 서버측 킬스위치. */
data class CaptureConfig(val enabled: Boolean, val rooms: List<String>)

/**
 * 캡처 1건 요청. 알림 리스너(생산) → 접근성 워커(소비).
 * contentIntent = 그 알림이 가리키는 카톡 방을 정확히 여는 PendingIntent(워커가 발사).
 */
data class CaptureRequest(
    val roomTitle: String,
    val contentIntent: PendingIntent?,
    val hint: String,
    val ts: Long
)

/**
 * 무인 자동수집(B2)의 인메모리 FIFO. KakaoListenerService(생산) → KakaoAccessibilityService(소비).
 * 앱 재시작 시 미처리분 유실 허용(무인이라 다음 자료 도착 때 재가동). 폭주 상한으로 폭발 방지.
 */
object CaptureQueue {
    private const val MAX = 50
    private val q = ConcurrentLinkedQueue<CaptureRequest>()

    fun enqueue(r: CaptureRequest) {
        if (q.size < MAX) q.add(r)
    }

    fun poll(): CaptureRequest? = q.poll()

    fun isEmpty(): Boolean = q.isEmpty()

    fun size(): Int = q.size
}
