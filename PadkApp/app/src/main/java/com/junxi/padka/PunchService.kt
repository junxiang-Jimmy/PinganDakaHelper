package com.junxi.padka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 定时打卡执行器：前台服务跑一次完整流程，完成后推送结果通知；失败按策略自动重试。 */
class PunchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val running = Notification.Builder(this, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在自动打卡…")
            .setContentText("平安打卡进行中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, running, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, running)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padka:punch")
        try {
            wl.acquire(90_000L)
        } catch (_: Exception) {
        }

        thread {
            try {
                handleOutcome(PunchEngine.run(this, true))
            } finally {
                try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleOutcome(outcome: PunchEngine.Outcome) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        when (outcome) {
            is PunchEngine.Outcome.Success -> {
                Prefs.putAttempts(this, today, 0)
                notifyResult("平安打卡 ✓ ${outcome.text}", if (outcome.alreadyDone) "今天已打过，无需重复" else "已按计划完成")
            }
            is PunchEngine.Outcome.Failure -> {
                val attempts = Prefs.attempts(this, today) + 1
                if (outcome.retryable && attempts <= MAX_ATTEMPTS) {
                    Prefs.putAttempts(this, today, attempts)
                    AlarmScheduler.scheduleRetry(this, RETRY_DELAY_MS)
                    notifyResult(
                        "打卡未完成，稍后自动重试",
                        "${outcome.message}\n（第 $attempts/$MAX_ATTEMPTS 次尝试，$RETRY_DELAY_MIN 分钟后重试）",
                        outcome.needManualLogin,
                    )
                } else {
                    Prefs.putAttempts(this, today, 0)
                    notifyResult("自动打卡失败", outcome.message, outcome.needManualLogin)
                }
            }
        }
    }

    private fun notifyResult(title: String, text: String, needLogin: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val target = if (needLogin) WebLoginActivity::class.java else MainActivity::class.java
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(RESULT_NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "打卡结果", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "打卡进行中", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CH_RESULT = "padka_result"
        private const val CH_RUNNING = "padka_running"
        private const val NOTIF_ID = 200
        private const val RESULT_NOTIF_ID = 201
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_DELAY_MIN = 5L
        private const val RETRY_DELAY_MS = RETRY_DELAY_MIN * 60_000L
    }
}
