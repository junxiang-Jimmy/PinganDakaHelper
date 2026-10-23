package com.junxi.padka

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 主页：今日打卡状态 + 一键手动打卡 + 登录/设置入口 + 自动登录诊断。 */
class MainActivity : Activity() {

    private lateinit var meta: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPunch: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        meta = findViewById(R.id.meta)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        progress = findViewById(R.id.progress)
        btnPunch = findViewById(R.id.btn_punch)
        btnPunch.setOnClickListener { runPunch() }
        findViewById<Button>(R.id.btn_login).setOnClickListener {
            startActivity(Intent(this, WebLoginActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_diag).setOnClickListener { copyDiag() }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        requestLocation()
    }

    private fun requestLocation() {
        if (!LocHelper.hasPermission(this)) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ), 2,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMeta()
        runPunch()
    }

    private fun refreshMeta() {
        val loginLine = if (Prefs.loggedIn(this)) {
            if (Prefs.username(this).isNotEmpty()) "已登录 · ${Prefs.username(this)}" else "已登录"
        } else {
            "未登录（点「登录」完成统一身份认证）"
        }
        val auto = if (Prefs.autoEnabled(this)) {
            String.format(
                "每天 %02d:%02d 自动打卡（下次 %s）",
                Prefs.hour(this), Prefs.minute(this), AlarmScheduler.nextRunText(this),
            )
        } else {
            "自动打卡未开启（到设置里打开）"
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val last = if (Prefs.lastOkDay(this) == today) "今天结果：${Prefs.lastOkText(this)}" else "今天还没打卡"
        val diag = Prefs.lastDiag(this)
        val diagLine = if (diag.isEmpty()) "" else "\n上次自动登录有问题（点下方按钮复制诊断）"
        meta.text = loginLine + "\n" + auto + "\n" + last + diagLine
    }

    private fun copyDiag() {
        val diag = Prefs.lastDiag(this)
        if (diag.isEmpty()) {
            Toast.makeText(this, "暂无自动登录诊断（还没遇到过登录失败）", Toast.LENGTH_SHORT).show()
            return
        }
        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("diag", diag))
        Toast.makeText(this, "诊断已复制，可粘贴发给开发者", Toast.LENGTH_LONG).show()
    }

    private fun runPunch() {
        if (running) return
        running = true
        btnPunch.isEnabled = false
        progress.visibility = View.VISIBLE
        statusTitle.text = "正在打卡…"
        statusTitle.setTextColor(0xFF1A2B4A.toInt())
        statusDetail.text = if (Prefs.loggedIn(this)) {
            "打开 App 即自动检查并完成今日打卡"
        } else {
            "未登录，将尝试自动登录后打卡"
        }
        thread {
            val outcome = PunchEngine.run(this, false)
            runOnUiThread {
                running = false
                progress.visibility = View.GONE
                btnPunch.isEnabled = true
                refreshMeta()
                when (outcome) {
                    is PunchEngine.Outcome.Success -> {
                        statusTitle.text = "平安打卡完成"
                        statusTitle.setTextColor(0xFF2E7D32.toInt())
                        statusDetail.text = outcome.text
                    }
                    is PunchEngine.Outcome.Failure -> {
                        statusTitle.text = "本次没有打卡成功"
                        statusTitle.setTextColor(0xFFC62828.toInt())
                        val extra = if (outcome.needManualLogin) "\n→ 点「登录」手动完成一次即可" else ""
                        statusDetail.text = outcome.message + extra
                    }
                }
            }
        }
    }
}
