package com.junxi.padka

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** 设置页：学号口令、定时开关与时间、默认打卡位置、权限引导。 */
class SettingsActivity : Activity() {

    private lateinit var etUser: EditText
    private lateinit var etPwd: EditText
    private lateinit var etAddr: EditText
    private lateinit var swAuto: Switch
    private lateinit var btnTime: Button
    private lateinit var tvLoc: TextView
    private lateinit var tvStatus: TextView
    private var hour = Prefs.DEFAULT_HOUR
    private var minute = Prefs.DEFAULT_MINUTE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etUser = findViewById(R.id.et_user)
        etPwd = findViewById(R.id.et_pwd)
        etAddr = findViewById(R.id.et_addr)
        swAuto = findViewById(R.id.sw_auto)
        btnTime = findViewById(R.id.btn_time)
        tvLoc = findViewById(R.id.tv_loc)
        tvStatus = findViewById(R.id.tv_status)

        etUser.setText(Prefs.username(this))
        etPwd.hint = if (Prefs.hasPwd(this)) "已保存（输入可覆盖）" else "统一身份认证口令"
        etAddr.setText(Prefs.addr(this))
        swAuto.isChecked = Prefs.autoEnabled(this)
        hour = Prefs.hour(this)
        minute = Prefs.minute(this)
        updateTimeButton()
        updateLocText()

        btnTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h; minute = m; updateTimeButton()
            }, hour, minute, true).show()
        }

        findViewById<Button>(R.id.btn_loc).setOnClickListener { saveCurrentLoc() }

        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            } else tvStatus.text = "当前系统版本无需手动授权通知。"
        }

        val btnExact = findViewById<Button>(R.id.btn_exact)
        if (Build.VERSION.SDK_INT >= 31) {
            btnExact.visibility = View.VISIBLE
            btnExact.setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    } catch (_: Exception) {
                    }
                }
            }
        } else btnExact.visibility = View.GONE

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (_: Exception) {
                tvStatus.text = "该设备不支持直接跳转，请到系统设置里手动允许后台运行。"
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun updateTimeButton() {
        btnTime.text = String.format("打卡时间 %02d:%02d（点击修改）", hour, minute)
    }

    private fun updateLocText() {
        tvLoc.text = if (Prefs.hasLoc(this)) {
            String.format("默认位置：%.5f, %.5f %s", Prefs.lat(this), Prefs.lng(this), Prefs.addr(this))
        } else {
            "默认位置：未设置（定时打卡时会现场定位；建议点下面按钮保存一次）"
        }
    }

    private fun saveCurrentLoc() {
        if (!LocHelper.hasPermission(this)) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ), 2,
            )
            tvStatus.text = "请先允许定位权限，再点「记录当前位置」。"
            return
        }
        tvStatus.text = "正在获取当前位置…"
        thread {
            val loc = LocHelper.getBest(this, 20000)
            runOnUiThread {
                if (loc != null) {
                    Prefs.putLoc(this, loc.lat, loc.lng, etAddr.text.toString().trim())
                    updateLocText()
                    tvStatus.text = "已保存当前位置为默认打卡位置。"
                } else {
                    tvStatus.text = "拿不到定位：请到开阔处或打开系统 GPS 后再试。"
                }
            }
        }
    }

    private fun save() {
        val user = etUser.text.toString().trim()
        val pwd = etPwd.text.toString()
        if (user.isNotEmpty()) {
            if (pwd.isNotEmpty()) Prefs.putAccount(this, user, pwd)
            else if (!Prefs.hasPwd(this)) Prefs.putAccount(this, user, "")
            else Prefs.putAccount(this, user, Prefs.pwd(this))
        }
        Prefs.putAuto(this, swAuto.isChecked, hour, minute)
        Prefs.putLoc(this, Prefs.lat(this), Prefs.lng(this), etAddr.text.toString().trim())
        if (swAuto.isChecked) AlarmScheduler.scheduleDaily(this) else AlarmScheduler.cancel(this)
        tvStatus.text = if (swAuto.isChecked) {
            "已保存。下次自动打卡：${AlarmScheduler.nextRunText(this)}"
        } else {
            "已保存（自动打卡未开启）"
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }
}
