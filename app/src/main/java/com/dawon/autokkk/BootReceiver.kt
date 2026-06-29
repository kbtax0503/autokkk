package com.dawon.autokkk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 부팅 후 자동 시작(이전에 '켜짐'이었던 경우). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val on = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean("autostart", false)
            if (on) ContextCompat.startForegroundService(
                context, Intent(context, VoxService::class.java)
            )
        }
    }
}
