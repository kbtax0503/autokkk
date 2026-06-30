package com.dawon.autokkk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * VOX 자동 녹음 + 회의 모드(강제) + 활성 시간대 + 원격 제어.
 * 바깥 loop()은 워치독: recordSession()이 죽으면(마이크 분실 등) 재초기화 재시도.
 */
class VoxService : Service() {

    companion object {
        const val APP_VER = "1.1"
        const val CHANNEL_ID = "meetingrec_vox"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 16000
        const val FRAME = 1600          // 100ms @ 16kHz
        const val DEFAULT_HANGOVER_MS = 10000 // VOX 무음 종료(ms)
        const val DEFAULT_THRESHOLD = 800     // 기본 RMS 임계값
        const val DEFAULT_TRIGGER_MS = 3000   // VOX 시작 지연(ms): 이만큼 소리 지속돼야 시작
        const val TRIGGER_DIP_TOL_MS = 800    // 시작 카운트 중 이 이하 짧은 끊김 허용
        const val PREROLL_LEAD_FRAMES = 10    // 트리거 시점 앞 1초 추가 포함
        const val MEETING_NUDGE_MS = 30000    // 회의모드: 무음 30초 → 알림 1회
        const val MEETING_STOP_MS = 300000    // 회의모드: 무음 5분 → 대기 전환
        const val MAX_FILE_MS = 3600000       // 한 파일 최대 1시간(회의모드 롤 / VOX 분할)
        const val REC_DIR = "/storage/emulated/0/MeetingRec"
        @Volatile var running = false
        @Volatile var isRecording = false
        @Volatile var lastRms = 0
        @Volatile var lastTempC = 0f       // 최근 배터리 온도(℃), 미터 표시용
        @Volatile var voxEnabled = true    // 원격 일시정지 시 false
        @Volatile var forceRecord = false  // 원격 '지금녹음'(회의모드 시작) 시 true
        @Volatile var nightPaused = false  // 활성 시간대 밖이면 true
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bridge: TelegramBridge? = null
    private var uploader: RecordingUploader? = null
    @Volatile private var stopFlag = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        stopFlag = false
        val notif = buildNotification("대기 중 · 소리 감지 시 자동 녹음")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meetingrec:vox").apply { acquire() }
        voxEnabled = true
        forceRecord = false
        bridge = TelegramBridge(this, this).also { it.start() }
        uploader = RecordingUploader(this).also { it.start() }  // 녹음 끝난 wav를 서버로 직접 업로드(Syncthing 대체)
        worker = thread(start = true) { loop() }
        thread(start = true) { storageLoop() }   // 폰 저장공간 80/90% 알림(1차 저장소 보호)
        return START_STICKY
    }

    /** 30분마다 깨서 "오늘 점검했나" 확인 → 하루 1회(오전 9시 이후) 저장공간 체크. */
    private fun storageLoop() {
        while (!stopFlag) {
            try { maybeDailyStorageCheck() } catch (_: Exception) {}
            try { Thread.sleep(30 * 60 * 1000L) } catch (_: Exception) {}
        }
    }

    /** 하루에 한 번(오전 9시 이후) 폰 저장공간 확인 → 80/90% 이상이면 회의실 봇으로 알림. */
    private fun maybeDailyStorageCheck() {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 9) return        // 오전 9시 이후에만
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (p.getString("storage_check_date", "") == today) return     // 오늘 이미 점검함
        p.edit().putString("storage_check_date", today).apply()
        @Suppress("DEPRECATION")
        val stat = StatFs(Environment.getExternalStorageDirectory().path)   // 내장 사용자 저장소(휴대폰 용량)
        val total = stat.totalBytes
        val free = stat.availableBytes
        if (total <= 0) return
        val usedPct = (((total - free) * 100) / total).toInt()
        val freeGb = free / (1024L * 1024 * 1024)
        when {
            usedPct >= 90 -> bridge?.notify("🔴 폰 저장공간 ${usedPct}% (여유 ${freeGb}GB). 정리가 필요해요 — 카톡으로 받은 파일을 삭제하세요.")
            usedPct >= 80 -> bridge?.notify("🟡 폰 저장공간 ${usedPct}% (여유 ${freeGb}GB). 곧 정리가 필요해요.")
        }
    }

    private fun threshold(): Int =
        getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("threshold", DEFAULT_THRESHOLD)

    private fun hangoverMs(): Int =
        getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("hangover_ms", DEFAULT_HANGOVER_MS)

    private fun triggerMs(): Int =
        getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("trigger_ms", DEFAULT_TRIGGER_MS)

    /** 원격 명령(TelegramBridge에서 호출) */
    fun requestStop() { voxEnabled = false }
    fun requestStartVox() { voxEnabled = true }
    fun requestForceRecord() { voxEnabled = true; forceRecord = true }

    /** 배터리 상태: 온도(℃)·잔량(%)·충전중. 못 읽으면 (-1,-1,false). */
    data class BatteryInfo(val tempC: Float, val pct: Int, val charging: Boolean)

    fun batteryInfo(): BatteryInfo {
        return try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo(if (t < 0) -1f else t / 10f, pct, charging)
        } catch (_: Exception) { BatteryInfo(-1f, -1, false) }
    }

    fun batteryTempC(): Float = batteryInfo().tempC

    /** 현재 시각이 활성 감지 시간대 안인가. 기본 9~22시(=21:59까지). start>end면 자정 넘김 처리. */
    fun inActiveHours(): Boolean {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val start = p.getInt("active_start", 9)
        val end = p.getInt("active_end", 22)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }

    private fun notifyEnabled(): Boolean =
        getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("notify_enabled", true)

    /** 저장 순환: /MeetingRec가 2GB 초과 또는 여유<1GB면 오래된 파일부터 삭제. */
    private fun enforceQuota() {
        try {
            val dir = outDir()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
                ?.sortedBy { it.name } ?: return
            if (files.size <= 1) return
            val capBytes = 2L * 1024 * 1024 * 1024
            val freeFloor = 1L * 1024 * 1024 * 1024
            var total = files.sumOf { it.length() }
            var i = 0
            while (i < files.size - 1 && (total > capBytes || dir.usableSpace < freeFloor)) {
                val len = files[i].length()
                if (files[i].delete()) total -= len
                i++
            }
        } catch (_: Exception) {}
    }

    private fun outDir(): File {
        val d = File(REC_DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun newWav(): WavWriter = WavWriter(File(outDir(), "REC_" + ts() + ".wav"), SAMPLE_RATE)

    /** 워치독: 녹음 세션이 죽으면(마이크 분실/크래시) 재초기화 재시도. */
    private fun loop() {
        while (!stopFlag) {
            try {
                recordSession()
            } catch (e: Exception) {
                // 마이크 분실/크래시 → 아래에서 재시도
            }
            isRecording = false
            if (stopFlag) break
            updateNotif("⚠️ 녹음 일시 중단 — 마이크 재시도 중…")
            try { Thread.sleep(2000) } catch (_: Exception) {}
        }
    }

    /** 한 번의 녹음 세션: 마이크 열고 프레임 루프. 마이크 실패 시 예외를 던져 loop()이 재시도. */
    private fun recordSession() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed (마이크 사용 중/권한?)")
        }
        rec.startRecording()

        val buf = ShortArray(FRAME)
        val preRoll = ArrayDeque<ShortArray>()
        var wav: WavWriter? = null
        var meeting = false      // 현재 녹음이 회의모드인가
        var silenceMs = 0
        var loudRunMs = 0
        var dipMs = 0
        var recElapsedMs = 0
        var nudged = false       // 회의모드 30초 알림 보냈는지(말소리 나면 리셋)
        var tempTick = 0
        var flushTick = 0
        var wasActive = inActiveHours()

        try {
            while (!stopFlag) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                if (n == 0) continue

                if (tempTick++ % 50 == 0) {
                    val tc = batteryTempC()
                    if (tc >= 0) lastTempC = tc
                }

                // 활성 시간대 경계 알림
                val active = inActiveHours()
                nightPaused = !active
                if (active != wasActive) {
                    wasActive = active
                    if (wav == null) updateNotif(if (active) "대기 중 · 소리 감지 시 자동 녹음" else "🌙 야간 대기 (감지 안 함)")
                    if (notifyEnabled()) {
                        if (active) bridge?.notify("🟢 활성 시간대 — 자동 감지를 시작합니다.")
                        else bridge?.notify("🌙 야간 — 자동 감지를 중지합니다. (다음 활성 시간에 재개)")
                    }
                }

                val rms = rmsOf(buf, n)
                lastRms = rms
                val loud = rms > threshold()

                // 원격 중지: 즉시 정지 + 강제(회의모드) 취소
                if (!voxEnabled) {
                    if (wav != null) {
                        wav.close(); wav = null; meeting = false; isRecording = false; enforceQuota()
                        updateNotif("⏸ 일시정지(원격) · '시작'으로 재개")
                    }
                    forceRecord = false
                    preRoll.clear(); loudRunMs = 0; dipMs = 0
                    continue
                }

                if (wav == null) {
                    // ── 대기(IDLE): 시작 판단 ──
                    preRoll.addLast(buf.copyOf(n))
                    val maxPre = (triggerMs() / 100) + PREROLL_LEAD_FRAMES
                    while (preRoll.size > maxPre) preRoll.removeFirst()

                    if (forceRecord) {
                        // 회의 모드 시작(야간이어도 강제로 됨)
                        forceRecord = false
                        val w = newWav()
                        for (p in preRoll) w.write(p, p.size)
                        preRoll.clear()
                        wav = w; meeting = true
                        silenceMs = 0; recElapsedMs = 0; nudged = false
                        loudRunMs = 0; dipMs = 0; flushTick = 0
                        isRecording = true
                        updateNotif("🔴 회의 모드 녹음 중…")
                        // 시작 알림은 봇 명령 응답으로 충분 → 생략
                    } else if (active && loud) {
                        loudRunMs += 100; dipMs = 0
                        if (loudRunMs >= triggerMs()) {
                            val w = newWav()
                            for (p in preRoll) w.write(p, p.size)
                            preRoll.clear()
                            wav = w; meeting = false
                            silenceMs = 0; recElapsedMs = 0
                            loudRunMs = 0; dipMs = 0; flushTick = 0
                            isRecording = true
                            updateNotif("● 녹음 중…")
                            if (notifyEnabled()) bridge?.notify("🎙 음성이 감지되어 녹음을 시작합니다.")
                        }
                    } else if (active) {
                        dipMs += 100
                        if (dipMs > TRIGGER_DIP_TOL_MS) loudRunMs = 0
                    } else {
                        // 야간: VOX 시작 안 함
                        loudRunMs = 0; dipMs = 0
                    }
                } else {
                    // ── 녹음 중 ── (야간 경계여도 진행 중인 건 끝까지)
                    wav.write(buf, n)
                    recElapsedMs += 100
                    if (flushTick++ % 50 == 0) wav.flushHeader()
                    if (loud) { silenceMs = 0; nudged = false } else silenceMs += 100

                    if (meeting) {
                        // 회의 모드
                        if (silenceMs >= MEETING_NUDGE_MS && !nudged) {
                            nudged = true
                            if (notifyEnabled()) bridge?.notify("🤔 회의가 끝나셨나요? 음성이 인식되지 않는데 녹음을 하고 있어요.")
                        }
                        if (silenceMs >= MEETING_STOP_MS) {
                            wav.close(); wav = null; meeting = false; isRecording = false; enforceQuota()
                            updateNotif("대기 중 · 소리 감지 시 자동 녹음")
                            if (notifyEnabled()) bridge?.notify("⏹️ 5분간 음성이 없어 녹음을 대기 상태로 전환합니다.")
                        } else if (recElapsedMs >= MAX_FILE_MS) {
                            // 1시간 → 새 파일로 롤(연속). 무음/알림 카운터는 유지.
                            wav.close(); enforceQuota()
                            wav = newWav()
                            recElapsedMs = 0; flushTick = 0
                        }
                    } else {
                        // VOX 모드
                        if (silenceMs >= hangoverMs()) {
                            wav.close(); wav = null; isRecording = false; enforceQuota()
                            updateNotif("대기 중 · 소리 감지 시 자동 녹음")
                            if (notifyEnabled()) bridge?.notify("⏹️ 음성이 감지되지 않아 녹음을 종료합니다.")
                        } else if (recElapsedMs >= MAX_FILE_MS) {
                            // 안전: 잡음이 계속돼 안 멈출 때 1시간마다 분할
                            wav.close(); wav = null; isRecording = false; enforceQuota()
                            loudRunMs = 0; dipMs = 0
                        }
                    }
                }
            }
        } finally {
            try { wav?.close() } catch (_: Exception) {}
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            isRecording = false
        }
    }

    private fun rmsOf(buf: ShortArray, n: Int): Int {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n).toInt()
    }

    private fun ts() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "회의 녹음", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("회의 녹음기 (VOX)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopFlag = true
        running = false
        try { bridge?.stopBridge() } catch (_: Exception) {}
        try { uploader?.stop() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        try { worker?.join(1500) } catch (_: Exception) {}
        super.onDestroy()
    }
}
