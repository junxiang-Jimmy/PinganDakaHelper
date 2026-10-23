package com.junxi.padka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机、改时间、改时区后恢复定时打卡闹钟。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.autoEnabled(context)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON" -> AlarmScheduler.scheduleDaily(context)
        }
    }
}
