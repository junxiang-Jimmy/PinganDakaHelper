package com.junxi.padka

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡编排：确保登录 → 查状态 → 提交打卡。
 * 手动打卡和定時自动打卡都走这里。
 */
object PunchEngine {

    sealed class Outcome {
        /** 打卡成功（或今日已打卡/无需打卡）。 */
        class Success(val text: String, val alreadyDone: Boolean) : Outcome()

        /** 失败。needManualLogin=true 表示需要用户打开 App 手动登录（验证码/短信等）。diag 为自动登录诊断信息。 */
        class Failure(val message: String, val retryable: Boolean, val needManualLogin: Boolean = false, val diag: String = "") : Outcome()
    }

    private const val MAX_LOGIN_RETRY = 1

    /** 跑一次完整打卡流程。auto=true 表示定时任务（决定登录失效时是否自动重登）。 */
    fun run(context: Context, auto: Boolean): Outcome {
        return try {
            runInternal(context, auto)
        } catch (e: CasClient.LoginError.NeedCaptcha) {
            Prefs.putLastDiag(context, e.diag)
            Outcome.Failure(e.message ?: "需要图形验证码，请打开 App 手动登录", false, true, e.diag)
        } catch (e: CasClient.LoginError.NeedInteractive) {
            Prefs.putLastDiag(context, e.diag)
            Outcome.Failure(e.message ?: "需要短信验证码，请打开 App 手动登录", false, true, e.diag)
        } catch (e: CasClient.LoginError.BadCredential) {
            Prefs.putLastDiag(context, e.diag)
            Outcome.Failure("账号或口令不正确：${e.message}（请到设置里更新）", false)
        } catch (e: CasClient.LoginError) {
            Prefs.putLastDiag(context, e.diag)
            Outcome.Failure(e.message ?: "登录失败", true, false, e.diag)
        } catch (e: PadkApi.ApiError) {
            Outcome.Failure(e.message ?: "接口请求失败", e.httpCode >= 500)
        } catch (e: Exception) {
            Outcome.Failure("网络连接失败：${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private fun runInternal(context: Context, auto: Boolean): Outcome {
        var auth = Prefs.authHeader(context)
        var key = Prefs.desKey(context)

        // 1. 没登录态：自动补登（保存了学号口令才能补）
        if (auth.isEmpty() || key.isEmpty()) {
            if (Prefs.username(context).isEmpty() || !Prefs.hasPwd(context)) {
                return Outcome.Failure(
                    "登录态已失效，且没有保存学号口令：请到「设置」保存学号口令（供自动登录），或点「登录」重新登录一次", 
                    false, true, Prefs.lastDiag(context),
                )
            }
            val r = CasClient.login(Prefs.username(context), Prefs.pwd(context))
            auth = r.authHeader
            key = r.desKey
            Prefs.putAuth(context, auth, key)
        }

        // 2. 查状态
        var status = try {
            PadkApi.queryStatus(auth, key)
        } catch (e: PadkApi.ApiError) {
            // 登录态失效（网关通常表现为 400/401 或业务码 203）→ 自动补登一次
            val relogin = relogin(context) ?: return Outcome.Failure(
                "登录已失效，请打开 App 重新登录（${e.message}）", false, true,
            )
            auth = relogin.authHeader
            key = relogin.desKey
            PadkApi.queryStatus(auth, key)
        }

        // 兼容：有的部署把失效信息放在 data 里
        if (status.length() == 0) status = JSONObject()

        val sfydk = truthy(status.opt("sfydk"))
        if (sfydk) {
            val dksj = status.optString("dksj", "")
            val timePart = if (dksj.length >= 16) dksj.substring(11, 16) else dksj
            return Outcome.Success("今日已打卡${if (timePart.isNotEmpty()) "（$timePart）" else ""}", true)
        }
        val sfmdk = truthy(status.opt("sfmdk"))
        if (sfmdk) return Outcome.Success("今日无需打卡（免打卡名单）", true)
        val kdk = status.opt("kdk")
        if (kdk != null && !truthy(kdk)) {
            val reason = status.optString("bkyy", "").ifEmpty { "当前不可打卡（可能未到开放时间）" }
            return Outcome.Failure(reason, true)
        }
        val dkbc: Any = status.opt("dkbc") ?: "晚打卡"

        // 3. 取位置：现场定位 → 保存在 App 里的默认位置
        var jd = 0.0
        var wd = 0.0
        var dkdz = Prefs.addr(context)
        val live = LocHelper.getBest(context)
        if (live != null) {
            // H5 里 jd=经度、wd=纬度
            jd = live.lng
            wd = live.lat
            if (dkdz.isEmpty()) dkdz = "已定位"
        } else if (Prefs.hasLoc(context)) {
            jd = Prefs.lng(context)
            wd = Prefs.lat(context)
        }
        if (jd == 0.0 && wd == 0.0 && !Prefs.hasLoc(context)) {
            return Outcome.Failure(
                "拿不到定位。请打开 App 授予定位权限，或在设置里「记录当前位置」保存默认打卡位置", true,
            )
        }

        // 4. 提交打卡
        val resp = try {
            PadkApi.punch(auth, key, jd, wd, dkbc, dkdz)
        } catch (e: PadkApi.ApiError) {
            val relogin = relogin(context) ?: return Outcome.Failure(
                "打卡请求失败：${e.message}", true,
            )
            PadkApi.punch(relogin.authHeader, relogin.desKey, jd, wd, dkbc, dkdz)
        }
        if (resp.ok) {
            val now = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            Prefs.putLastOk(
                context,
                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()),
                "$now · ${dkdz.ifEmpty { "已定位" }}",
            )
            return Outcome.Success("打卡成功（$now）", false)
        }
        if (resp.authExpired) {
            val relogin = relogin(context) ?: return Outcome.Failure("登录已失效，请打开 App 重新登录", false, true)
            val resp2 = PadkApi.punch(relogin.authHeader, relogin.desKey, jd, wd, dkbc, dkdz)
            if (resp2.ok) {
                val now = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
                Prefs.putLastOk(
                    context,
                    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()),
                    "$now · ${dkdz.ifEmpty { "已定位" }}",
                )
                return Outcome.Success("打卡成功（$now）", false)
            }
            return Outcome.Failure("打卡失败：${PadkApi.describe(resp2)}", true)
        }
        return Outcome.Failure("打卡失败：${PadkApi.describe(resp)}", true)
    }

    private fun relogin(context: Context): CasClient.AuthResult? {
        if (Prefs.username(context).isEmpty() || !Prefs.hasPwd(context)) return null
        return try {
            val r = CasClient.login(Prefs.username(context), Prefs.pwd(context))
            Prefs.putAuth(context, r.authHeader, r.desKey)
            r
        } catch (e: CasClient.LoginError) {
            Prefs.putLastDiag(context, e.diag)
            if (e is CasClient.LoginError.NeedInteractive || e is CasClient.LoginError.NeedCaptcha) throw e
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Int -> v == 1
        is Long -> v == 1L
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }
}
