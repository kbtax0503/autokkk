package com.dawon.autokkk

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 캡처(저장)된 파일을 자료창고로 올린다 — B2 무인수집의 "적재" 절반.
 *
 * - cfg의 사진폴더(photo_tree_uri)·다운로드폴더(download_tree_uri)를 스캔(SAF, 매니페스트 권한 없음).
 * - seen-set(name|size)에 없는 새 파일만 동기 업로드 → 성공 시 seen 기록(중복 재업로드 방지).
 * - 직렬 캡처라 scanAndUpload(room) 호출 시점의 room으로 귀속. 반드시 워커스레드에서 호출(네트워크).
 *
 * 접근성 없이도 동작 가능(형이 수동저장 → 스캔) → T2에서 적재경로를 먼저 검증한 뒤 T4 워커가 호출.
 */
class AssetUploader(private val ctx: Context) {

    private fun prefs() = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val bridge = ServerBridge(ctx)

    /** 지정 폴더들을 스캔해 새 파일을 room 꼬리표로 업로드. 업로드 성공 개수 반환. */
    fun scanAndUpload(room: String): Int {
        var sent = 0
        for (uri in folderUris()) {
            val dir = try { DocumentFile.fromTreeUri(ctx, uri) } catch (_: Exception) { null } ?: continue
            for (f in dir.listFiles()) {
                if (!f.isFile) continue
                val name = f.name ?: continue
                val key = "$name|${f.length()}"
                if (isSeen(key)) continue
                val bytes = readBytes(f.uri) ?: continue
                if (bytes.isEmpty()) continue
                if (bridge.uploadAssetSync(name, bytes, room)) {
                    markSeen(key)
                    sent++
                }
            }
        }
        return sent
    }

    private fun folderUris(): List<Uri> =
        listOf("photo_tree_uri", "download_tree_uri")
            .mapNotNull { k -> prefs().getString(k, "")?.takeIf { it.isNotBlank() } }
            .mapNotNull { s -> try { Uri.parse(s) } catch (_: Exception) { null } }

    private fun readBytes(uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) { null }

    private fun isSeen(key: String): Boolean =
        prefs().getStringSet("asset_seen", emptySet())?.contains(key) == true

    private fun markSeen(key: String) {
        val cur = prefs().getStringSet("asset_seen", emptySet()) ?: emptySet()
        val next = HashSet(cur)                 // 반환 set 직접 수정 금지(안드 규약) → 복사본
        if (next.size > 5000) next.clear()       // 폭주 방지(드묾) — 비우고 재시작
        next.add(key)
        prefs().edit().putStringSet("asset_seen", next).apply()
    }
}
