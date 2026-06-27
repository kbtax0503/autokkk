package com.dawon.autokkk

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 카톡 화면 접근성 트리 덤프(스파이크). 거래처 첨부(사진·파일) 노드를 접근성으로 볼 수 있는지 검증용.
 *
 * - com.kakao.talk 창의 내용/상태 변경 이벤트에만(설정 xml packageNames 1차 제한 + 코드 재확인).
 * - 마지막 덤프 후 3초 이내면 skip(스크롤 노이즈 방지).
 * - rootInActiveWindow부터 순회(깊이 ≤40·노드 ≤400) → 노드별 class/id/text/desc/clickable/bounds 한 줄.
 * - 첨부 힌트 노드(text·desc·id에 사진/파일/확장자, 이미지성 className)는 상단 HINTS에 따로 모음.
 * - 결과(≤16KB)를 ServerBridge.debug("[A11Y] ...")로 전송 → 서버 /debug 로그(docker logs | grep A11Y).
 *
 * v2(저장 자동화)는 이 덤프 분석 후 설계. 지금은 "읽히는가"만 본다.
 */
class KakaoAccessibilityService : AccessibilityService() {

    companion object {
        private const val KAKAO_PKG = "com.kakao.talk"
        private const val THROTTLE_MS = 3000L
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 400
        private const val MAX_DUMP = 16000

        /** MainActivity 상태표시용(프로세스 메모리). */
        @Volatile var lastDumpAt: Long = 0L
        @Volatile var lastDumpNodes: Int = 0
    }

    private lateinit var bridge: ServerBridge
    private var lastDump = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge = ServerBridge(this)
        bridge.debug("[A11Y] service connected")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName != KAKAO_PKG) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {}
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastDump < THROTTLE_MS) return

        val root = rootInActiveWindow ?: return
        if (root.packageName != KAKAO_PKG) return
        lastDump = now

        try {
            val dump = buildDump(root)
            lastDumpAt = now
            bridge.debug(dump)
        } catch (ex: Exception) {
            bridge.debug("[A11Y] dump error: ${ex.message}")
        }
    }

    private fun buildDump(root: AccessibilityNodeInfo): String {
        val lines = StringBuilder()
        val hints = StringBuilder()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || count >= MAX_NODES) return
            count++

            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val txt = node.text?.toString()?.replace('\n', ' ')?.take(60) ?: ""
            val desc = node.contentDescription?.toString()?.replace('\n', ' ')?.take(60) ?: ""
            val vid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val click = if (node.isClickable) "C" else "-"

            val indent = "  ".repeat(depth.coerceAtMost(12))
            val line = "$indent$cls id=$vid txt=\"$txt\" desc=\"$desc\" $click ${rect.toShortString()}"
            lines.append(line).append('\n')

            if (isAttachmentHint(cls, txt, desc, vid)) {
                hints.append(line.trimStart()).append('\n')
            }

            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        lastDumpNodes = count

        val header = "[A11Y] kakao tree nodes=$count depth<=$MAX_DEPTH\n" +
            "==HINTS(attachment-ish)==\n${if (hints.isEmpty()) "(none)\n" else hints}" +
            "==TREE==\n"
        return (header + lines).take(MAX_DUMP)
    }

    /** 첨부(사진/파일)일 가능성이 있는 노드인지 휴리스틱. */
    private fun isAttachmentHint(cls: String, txt: String, desc: String, vid: String): Boolean {
        val blob = "$txt $desc $vid".lowercase()
        val kw = listOf(
            "사진", "파일", "이미지", "image", "photo", "file", "다운", "저장",
            ".pdf", ".xls", ".hwp", ".jpg", ".jpeg", ".png", ".zip", ".doc", ".ppt"
        )
        if (kw.any { blob.contains(it) }) return true
        return cls.contains("Image", ignoreCase = true)
    }
}
