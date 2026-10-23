package com.junxi.padka

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * 本机配置与登录态存取。
 * 所有内容只存在手机 App 私有目录里，不随安装包分发，不上传任何服务器
 * （除登录/打卡必需的学工系统请求外）。
 */
object Prefs {
    private const val NAME = "padka"

    const val DEFAULT_HOUR = 19
    const val DEFAULT_MINUTE = 5

    /** 口令仅做可逆混淆后存私有目录（防随手翻看，不是加密）；root/备份恢复场景请自行注意。 */
    private const val OBF_KEY = "PadkaObf2026!"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---- 账号与登录态 ----
    fun username(c: Context): String = sp(c).getString("stu_no", "")!!.trim()
    fun hasPwd(c: Context): Boolean = sp(c).contains("stu_pw")
    fun authHeader(c: Context): String = sp(c).getString("gw_auth", "") ?: ""
    fun desKey(c: Context): String = sp(c).getString("gw_key", "") ?: ""
    fun loggedIn(c: Context): Boolean = authHeader(c).isNotEmpty() && desKey(c).isNotEmpty()

    fun pwd(c: Context): String {
        val raw = sp(c).getString("stu_pw", null) ?: return ""
        return try {
            val mix = Base64.decode(raw, Base64.NO_WRAP)
            val key = OBF_KEY.toByteArray(Charsets.UTF_8)
            val out = ByteArray(mix.size)
            for (i in mix.indices) out[i] = (mix[i].toInt() xor key[i % key.size].toInt()).toByte()
            String(out, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun putAccount(c: Context, user: String, pass: String) {
        val key = OBF_KEY.toByteArray(Charsets.UTF_8)
        val bytes = pass.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        sp(c).edit()
            .putString("stu_no", user.trim())
            .putString("stu_pw", if (pass.isEmpty()) null else Base64.encodeToString(out, Base64.NO_WRAP))
            .apply()
    }

    fun putAuth(c: Context, auth: String, key: String) =
        sp(c).edit().putString("gw_auth", auth).putString("gw_key", key).apply()

    fun clearAuth(c: Context) =
        sp(c).edit().remove("gw_auth").remove("gw_key").apply()

    // ---- 定时 ----
    fun autoEnabled(c: Context): Boolean = sp(c).getBoolean("auto_enabled", false)
    fun hour(c: Context): Int = sp(c).getInt("hour", DEFAULT_HOUR)
    fun minute(c: Context): Int = sp(c).getInt("minute", DEFAULT_MINUTE)
    fun putAuto(c: Context, enabled: Boolean, h: Int, m: Int) =
        sp(c).edit().putBoolean("auto_enabled", enabled).putInt("hour", h).putInt("minute", m).apply()

    // ---- 打卡位置 ----
    fun lat(c: Context): Double = Double.fromBits(sp(c).getLong("loc_lat", 0L))
    fun lng(c: Context): Double = Double.fromBits(sp(c).getLong("loc_lng", 0L))
    fun addr(c: Context): String = sp(c).getString("loc_addr", "") ?: ""
    fun hasLoc(c: Context): Boolean = lat(c) != 0.0 || lng(c) != 0.0
    fun putLoc(c: Context, lat: Double, lng: Double, addr: String) = sp(c).edit()
        .putLong("loc_lat", lat.toBits()).putLong("loc_lng", lng.toBits())
        .putString("loc_addr", addr.trim()).apply()

    // ---- 结果记录 ----
    fun lastOkDay(c: Context): String = sp(c).getString("ok_day", "") ?: ""
    fun lastOkText(c: Context): String = sp(c).getString("ok_text", "") ?: ""
    fun putLastOk(c: Context, day: String, text: String) =
        sp(c).edit().putString("ok_day", day).putString("ok_text", text).apply()

    fun attempts(c: Context, day: String): Int = sp(c).getInt("try_$day", 0)
    fun putAttempts(c: Context, day: String, n: Int) = sp(c).edit().putInt("try_$day", n).apply()

    // ---- 诊断 ----
    fun lastDiag(c: Context): String = sp(c).getString("last_diag", "") ?: ""
    fun putLastDiag(c: Context, d: String) =
        sp(c).edit().putString("last_diag", d.take(4000)).apply()
}
