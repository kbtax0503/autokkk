package com.dawon.autokkk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * B2 무인 자동수집 워커 — 거래처 첨부를 접근성으로 "공유 → 카톡 브릿지"까지 자동 클릭.
 *
 * KakaoAccessibilityService가 onServiceConnected에서 start(). CaptureQueue를 폴해 1건씩 처리(직렬).
 *
 * 흐름(형 실기기 검증한 순서):
 *   방 열기 → 최신 첨부(사진/파일, 링크·텍스트 제외) 찾기 →
 *   (파일이면) 눌러서 다운로드 → 길게 눌러 메뉴 → '공유' →
 *   카톡 공유창에서 '더보기' → 시스템 공유창에서 '카톡 브릿지' → ShareReceiver가 업로드.
 *
 * 안전 원칙(오발송 방지):
 *   - 누르는 건 오직 ①첨부 버블 ②메뉴 '공유' ③'더보기' ④시스템 공유창 '카톡 브릿지' 뿐.
 *   - 친구/채팅방 체크박스, 카톡 직접공유 타깃은 절대 안 누름.
 *   - 각 단계 못 찾으면 즉시 중단(BACK으로 빠져나옴, 아무것도 안 보냄).
 *   - 시스템 공유창(android Chooser)이고 '카톡 브릿지' 노드가 있을 때만 마지막 클릭.
 *   - 앱 킬스위치(auto_capture_enabled) OFF면 큐를 비우되 작동 안 함.
 *
 * 모든 단계를 [CAP] 로그로 서버 /debug에 남겨 실기기서 단계별 진단(켜고 끄기 전 점검).
 */
class CaptureWorker(private val svc: AccessibilityService) {

    companion object {
        private const val KAKAO = "com.kakao.talk"
        private val FILE_EXT = listOf(
            ".pdf", ".xls", ".xlsx", ".hwp", ".hwpx", ".doc", ".docx",
            ".ppt", ".pptx", ".zip", ".csv", ".txt", ".jpg", ".jpeg", ".png", ".gif", ".heic"
        )
    }

    private val bridge = ServerBridge(svc)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                try {
                    val req = CaptureQueue.poll()
                    if (req == null) { sleep(1500); continue }
                    if (!autoEnabled()) {
                        bridge.debug("[CAP] 큐에 1건 있으나 앱 자동수집 OFF — 폐기 room=${req.roomTitle}")
                        continue
                    }
                    runCapture(req)
                } catch (e: Exception) {
                    bridge.debug("[CAP] worker error: ${e.message}")
                    sleep(1500)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    private fun prefs() = svc.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun autoEnabled() = prefs().getBoolean("auto_capture_enabled", false)

    private fun runCapture(req: CaptureRequest) {
        bridge.debug("[CAP] ▶ start room=${req.roomTitle} hint=${req.hint.take(30)}")
        // ShareReceiver가 이 캡처의 업로드에 방 꼬리표를 달도록 사전 저장(신선도 ts 포함).
        prefs().edit()
            .putString("pending_capture_room", req.roomTitle)
            .putLong("pending_capture_room_ts", System.currentTimeMillis())
            .apply()
        var ok = false
        try {
            ok = doCapture(req)
        } catch (e: Exception) {
            bridge.debug("[CAP] 예외: ${e.message}")
        } finally {
            if (ok) {
                bridge.debug("[CAP] ✓ 공유 완료 room=${req.roomTitle}")
            } else {
                prefs().edit().remove("pending_capture_room").remove("pending_capture_room_ts").apply()
                bridge.debug("[CAP] ✗ 중단 room=${req.roomTitle} — 빠져나감(아무것도 안 보냄)")
                backOut(3)
            }
            sleep(1200)
        }
    }

    /** 상태머신 본체. 성공 시 true. 어느 단계든 못 찾으면 false(=안전 중단). */
    private fun doCapture(req: CaptureRequest): Boolean {
        // 1. 방 열기 — contentIntent 있으면 발사, 없으면(테스트) 현재 화면 사용.
        if (req.contentIntent != null) {
            try {
                req.contentIntent.send()
            } catch (e: Exception) {
                bridge.debug("[CAP] 방 열기 실패: ${e.message}"); return false
            }
            if (!waitFor(5000) { rootPkg() == KAKAO }) { bridge.debug("[CAP] 방 안 열림(카톡 아님)"); return false }
        }
        sleep(900) // 메시지 렌더 대기

        // 1b. 최신 메시지로 스크롤('아래로' FAB) — 첨부가 화면 밖이면 못 누름.
        clickScrollToBottom(); sleep(800)

        // 2. 최신 첨부 버블(사진/파일) 찾기 — 링크·텍스트 제외.
        var target = findNewestAttachment()
        if (target != null && !onScreen(target)) {
            // 화면 밖이면 한 번 더 스크롤 후 재탐색.
            clickScrollToBottom(); sleep(800)
            target = findNewestAttachment()
        }
        if (target == null) { bridge.debug("[CAP] 첨부 노드 못 찾음 — 중단"); return false }
        bridge.debug("[CAP] 대상=${target.kind} \"${target.label.take(30)}\" @${target.bounds}")
        if (!onScreen(target)) { bridge.debug("[CAP] 대상이 화면 밖 — 중단(스크롤 필요)"); return false }

        // 3. 파일이면 먼저 눌러서 다운로드(형 검증: 안 받으면 공유에 파일 안 실림).
        if (target.kind == "file") {
            if (!click(target.node)) bridge.debug("[CAP] 파일 다운로드 탭 실패(계속 시도)")
            else bridge.debug("[CAP] 파일 다운로드 탭 → 대기")
            sleep(4500) // 다운로드 대기(넉넉히). 큰 파일이면 부족할 수 있음 → 로그 보고 조정.
            // 다운로드가 뷰어를 열었을 수 있으니 채팅으로 복귀 보장.
            if (rootPkg() != KAKAO) { backOut(1); sleep(700) }
        }

        // 4. 공유 트리거: 메시지 옆 '공유' 화살표 우선(안정적), 실패 시 길게누르기 메뉴 '공유'.
        //    → 카톡 공유창의 '더보기'가 뜰 때까지.
        if (!triggerShare(target)) { bridge.debug("[CAP] 공유 트리거 실패 — 중단"); return false }

        // 5. 카톡 공유창 → '더보기'(시스템 공유창으로). 친구 체크박스는 절대 안 누름.
        val more = findExactDesc("더보기") ?: findExactText("더보기")
        if (more == null || !click(more)) { bridge.debug("[CAP] '더보기' 클릭 실패 — 중단"); return false }

        // 6. 시스템 공유창(android Chooser)에서 '카톡 브릿지'만 클릭. 그 외엔 무조건 중단.
        if (!waitFor(5000) { isChooser() && findExactText("카톡 브릿지") != null }) {
            bridge.debug("[CAP] 시스템 공유창/'카톡 브릿지' 안 뜸 — 중단(안전)"); return false
        }
        val app = findExactText("카톡 브릿지")
        if (app == null || !click(app)) { bridge.debug("[CAP] '카톡 브릿지' 클릭 실패 — 중단"); return false }
        sleep(1500) // ShareReceiver 업로드 처리 대기
        return true
    }

    /**
     * 공유 트리거 — 메시지 옆 인라인 '공유' 화살표(같은 줄) 우선 클릭, 실패 시 길게누르기 메뉴 '공유'.
     * 둘 다 카톡 공유창(친구 picker + '더보기')으로 이어짐. '더보기'가 뜨면 성공.
     */
    private fun triggerShare(target: Target): Boolean {
        val arrow = findShareButtonNear(target)
        if (arrow != null) {
            bridge.debug("[CAP] 인라인 '공유' 화살표 클릭")
            if (click(arrow) && waitFor(3500) { hasMore() }) return true
            bridge.debug("[CAP] 인라인 공유 후 '더보기' 안 뜸 → 길게누르기 폴백")
        } else {
            bridge.debug("[CAP] 인라인 '공유' 못 찾음 → 길게누르기")
        }
        val node = refind(target) ?: target.node
        if (!longClick(node)) { bridge.debug("[CAP] 길게누르기 실패"); return false }
        if (!waitFor(3000) { findExactDesc("공유") != null || findExactText("공유") != null }) {
            bridge.debug("[CAP] 메뉴 '공유' 안 뜸"); return false
        }
        val s = findExactDesc("공유") ?: findExactText("공유")
        if (s == null || !click(s)) { bridge.debug("[CAP] 메뉴 '공유' 클릭 실패"); return false }
        return waitFor(3500) { hasMore() }
    }

    private fun hasMore(): Boolean = findExactDesc("더보기") != null || findExactText("더보기") != null

    private fun onScreen(t: Target): Boolean = t.bottom > t.top && t.top >= 0 && t.bottom <= 2600

    /** '맨 아래로' FAB(desc="아래로")가 있으면 눌러 최신 메시지로 스크롤. 없으면(이미 맨 아래) 무시. */
    private fun clickScrollToBottom() {
        val fab = findExactDesc("아래로") ?: return
        click(fab)
    }

    /** 메시지 옆 인라인 '공유' 화살표(desc="공유", 화면 안) 중 타깃과 세로로 같은 줄(±250px)인 것. */
    private fun findShareButtonNear(target: Target): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val targetCy = (target.top + target.bottom) / 2
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 45) return
            if (n.contentDescription?.toString()?.trim() == "공유") {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.bottom > r.top && r.top >= 0) {
                    val d = kotlin.math.abs((r.top + r.bottom) / 2 - targetCy)
                    if (d < bestDist) { bestDist = d; best = n }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return if (bestDist <= 250) best else null
    }

    // ───────── 첨부 탐색 ─────────

    private data class Target(
        val node: AccessibilityNodeInfo,
        val kind: String,     // "photo" | "file"
        val label: String,
        val bounds: String,
        val top: Int,
        val bottom: Int
    )

    /** 채팅 트리에서 사진(desc="사진"/"동영상")·파일(text "파일:"/확장자) 버블 중 가장 아래(최신) 1개. */
    private fun findNewestAttachment(): Target? {
        val root = svc.rootInActiveWindow ?: return null
        val cands = ArrayList<Target>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val desc = n.contentDescription?.toString()?.trim() ?: ""
            val txt = n.text?.toString()?.trim() ?: ""
            val r = Rect().also { n.getBoundsInScreen(it) }
            when {
                desc == "사진" || desc == "동영상" ->
                    cands.add(Target(n, "photo", desc, r.toShortString(), r.top, r.bottom))
                txt.startsWith("파일:") || isFileName(txt) ->
                    cands.add(Target(n, "file", txt, r.toShortString(), r.top, r.bottom))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (cands.isEmpty()) return null
        // 화면 안(보이는) 후보 우선, 그 중 가장 아래(최신). 없으면 전체에서 가장 아래.
        val onScreen = cands.filter { it.bottom in 1..3200 }
        val pool = if (onScreen.isNotEmpty()) onScreen else cands
        return pool.maxByOrNull { it.bottom }
    }

    private fun isFileName(txt: String): Boolean {
        if (txt.length < 4 || txt.length > 120) return false
        val low = txt.lowercase()
        return FILE_EXT.any { low.endsWith(it) }
    }

    /** 길게누르기 직전 같은 좌표의 노드를 다시 찾는다(트리 갱신 대비). 실패 시 원래 노드. */
    private fun refind(t: Target): AccessibilityNodeInfo? {
        val again = findNewestAttachment() ?: return null
        return if (again.kind == t.kind) again.node else t.node
    }

    // ───────── 노드 검색/클릭 헬퍼 ─────────

    private fun rootPkg(): String = svc.rootInActiveWindow?.packageName?.toString() ?: ""

    /** 시스템 공유창(android ChooserActivity/ResolverActivity)인가. */
    private fun isChooser(): Boolean = rootPkg() == "android"

    private fun findExactDesc(desc: String): AccessibilityNodeInfo? =
        findNode { it.contentDescription?.toString()?.trim() == desc }

    private fun findExactText(text: String): AccessibilityNodeInfo? =
        findNode { it.text?.toString()?.trim() == text }

    private fun findNode(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        return dfs(root, pred, 0)
    }

    private fun dfs(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 45) return null
        if (try { pred(node) } catch (_: Exception) { false }) return node
        for (i in 0 until node.childCount) {
            val r = dfs(node.getChild(i), pred, depth + 1)
            if (r != null) return r
        }
        return null
    }

    /**
     * node 또는 가장 가까운 클릭가능 조상에 ACTION_CLICK. 그래도 안 되면 노드 중심 좌표를
     * 제스처로 직접 탭한다(시스템 공유창 앱 아이콘처럼 클릭가능 조상이 멀거나 없는 항목 대응).
     */
    private fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var n: AccessibilityNodeInfo? = node; var hop = 0
        while (n != null && hop < 10) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent; hop++
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return gestureTap(node)
    }

    /** 노드 중심 좌표를 짧게 탭(제스처). 클릭가능 조상이 없는 시스템 UI 항목용 폴백. */
    private fun gestureTap(node: AccessibilityNodeInfo): Boolean {
        return try {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() <= 0 || r.height() <= 0 || r.centerY() < 0) return false
            val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
            val ok = svc.dispatchGesture(g, null, null)
            bridge.debug("[CAP] 좌표 탭(제스처) @${r.toShortString()} → $ok")
            ok
        } catch (e: Exception) {
            bridge.debug("[CAP] 제스처탭 예외: ${e.message}"); false
        }
    }

    /** node 또는 가장 가까운 롱클릭가능 조상에 ACTION_LONG_CLICK. */
    private fun longClick(node: AccessibilityNodeInfo?): Boolean {
        var n = node; var hop = 0
        while (n != null && hop < 8) {
            if (n.isLongClickable) return n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            n = n.parent; hop++
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
    }

    /** cond가 true가 될 때까지 200ms 간격 폴(최대 timeoutMs). */
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (try { cond() } catch (_: Exception) { false }) return true
            sleep(200)
        }
        return false
    }

    private fun backOut(times: Int) {
        repeat(times) {
            try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) {}
            sleep(400)
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
