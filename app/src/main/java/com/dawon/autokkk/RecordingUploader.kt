package com.dawon.autokkk

import android.content.Context
import java.io.File

/**
 * 회의 녹음(VOX) wav를 서버로 직접 업로드 — Syncthing 대체.
 *
 * VoxService.REC_DIR을 주기 스캔해 "안정된"(마지막 수정 후 일정시간 경과 = 녹음 종료) 새 wav를
 * 서버 /recording 으로 POST. 성공 시 seen-set 기록(재업로드 방지). 로컬 파일은 VoxService 쿼터가 정리.
 *
 * VoxService.onStartCommand에서 start(). 네트워크는 전용 워커 스레드.
 */
class RecordingUploader(private val ctx: Context) {

    companion object {
        private const val STABLE_MS = 25_000L       // 마지막 수정 후 이 시간 지나야 업로드(녹음 끝 판정)
        private const val MIN_BYTES = 8_000L         // 너무 작은 파일(빈/잡음)은 건너뜀
        private const val SCAN_INTERVAL_MS = 20_000L
    }

    private val bridge = ServerBridge(ctx)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                try { scanOnce() } catch (_: Exception) {}
                sleep(SCAN_INTERVAL_MS)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    private fun prefs() = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun serverSet() = !(prefs().getString("server_url", "")?.trim().isNullOrBlank())

    private fun scanOnce() {
        if (!serverSet()) return
        val dir = File(VoxService.REC_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return
        val now = System.currentTimeMillis()
        for (f in files.sortedBy { it.name }) {
            if (now - f.lastModified() < STABLE_MS) continue    // 아직 녹음 중일 수 있음 → 다음 스캔
            val name = f.name
            if (isSeen(name)) continue
            if (f.length() < MIN_BYTES) { markSeen(name); continue }  // 빈/잡음 — 표시만, 업로드 안 함
            if (bridge.uploadRecording(f)) markSeen(name)
        }
    }

    private fun isSeen(name: String): Boolean =
        prefs().getStringSet("rec_uploaded", emptySet())?.contains(name) == true

    private fun markSeen(name: String) {
        val cur = prefs().getStringSet("rec_uploaded", emptySet()) ?: emptySet()
        val next = HashSet(cur)             // 반환 set 직접 수정 금지(안드 규약) → 복사본
        if (next.size > 3000) next.clear()  // 폭주 방지 — 비우고 재시작
        next.add(name)
        prefs().edit().putStringSet("rec_uploaded", next).apply()
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
