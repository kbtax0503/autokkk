package com.dawon.autokkk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 거래처 자료획득(B) — 안드 "공유" 대상.
 *
 * 카톡(또는 어디서든) 첨부를 "공유 → 카톡 브릿지"로 보내면 이 액티비티가 받아서
 * 파일 바이트를 서버 /asset 으로 직접 업로드(텔레그램 등 외부 클라우드 안 거침).
 * 화면 없이(NoDisplay) 받자마자 업로드하고 토스트 띄운 뒤 종료.
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uris = collectUris(intent)
            if (uris.isEmpty()) {
                toast("받은 파일이 없어요")
            } else {
                val bridge = ServerBridge(this)
                var sent = 0
                for (u in uris) {
                    val pair = readUri(u) ?: continue
                    bridge.uploadAsset(pair.first, pair.second)  // 비동기 업로드
                    sent++
                }
                toast(if (sent > 0) "자료창고로 보냄: ${sent}개" else "파일을 읽지 못했어요")
            }
        } catch (e: Exception) {
            toast("자료 전송 오류: ${e.message}")
        }
        finish()
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(extraStream(intent))
            Intent.ACTION_SEND_MULTIPLE -> extraStreamList(intent)
            else -> emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun extraStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun extraStreamList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    /** uri → (파일명, 바이트). 실패 시 null. */
    private fun readUri(uri: Uri): Pair<String, ByteArray>? {
        return try {
            val name = queryName(uri) ?: "asset_${System.currentTimeMillis()}"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.isEmpty()) return null
            name to bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
