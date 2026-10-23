package com.junxi.padka

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 中南大学统一身份认证（CAS）无界面登录。
 *
 * v1.1 变更：
 *  - 错误解析支持 formErrorTip/showErrorTip 的嵌套 span 结构（实测 CAS 对错误口令返回
 *    HTTP 401 + <div id="formErrorTip" class="hide"><span>您提供的用户名或者密码有误</span></div>）
 *  - 全程记录跳转链与最终页面摘要（诊断信息），供 App 内展示定位问题
 *  - 分类：401 且解析到错误文案 → 账号口令问题；401 无文案 → 疑似验证码/风控，需手动登录一次
 */
object CasClient {

    const val CAS_LOGIN = "https://ca.csu.edu.cn/authserver/login"
    const val SERVICE = "https://zhxg.csu.edu.cn/fdcwonsun/caslogin_h5.jsp"

    /** 登录成功后拿到的两个鉴权字段（网关请求头 + 请求体 DES 密钥）。 */
    class AuthResult(val authHeader: String, val desKey: String)

    /** 所有登录失败都带诊断信息（跳转链 + 服务器返回摘要），App 里可直接展示。 */
    open class LoginError(message: String, val diag: String) : Exception(message) {
        class NeedCaptcha(message: String, diag: String) : LoginError(message, diag)
        class BadCredential(message: String, diag: String) : LoginError(message, diag)
        class NeedInteractive(message: String, diag: String) : LoginError(message, diag)
        class Network(message: String, diag: String) : LoginError(message, diag)
        class Unknown(message: String, diag: String) : LoginError(message, diag)
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT = 20000

    fun login(username: String, pwd: String): AuthResult {
        val cm = CookieManager()
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        val prev = CookieHandler.getDefault()
        CookieHandler.setDefault(cm)
        val log = StringBuilder()
        try {
            // 1. 取登录页
            val pageUrl = "$CAS_LOGIN?service=${enc(SERVICE)}"
            val page = httpGet(cm, pageUrl, log)
            log.appendLine("GET 登录页 HTTP ${page.lastCode} len=${page.body.length}")
            val cachedAuth = page.authHeader
            val cachedKey = page.desKey
            if (cachedAuth != null && cachedKey != null) return AuthResult(cachedAuth, cachedKey)
            val html = page.body
            if (html.isEmpty()) throw LoginError.Network("无法打开统一身份认证登录页（返回内容为空）", log.toString())
            val salt = regexGroup(html, "id=\"pwdEncryptSalt\"\\s+value=\"([^\"]*)\"")
                ?: throw LoginError.Network("登录页解析失败（未找到加密盐）", log.toString() + "\n页面摘要: " + summarize(html))
            val lt = regexGroup(html, "id=\"lt\"\\s+name=\"lt\"\\s+value=\"([^\"]*)\"") ?: ""
            val execution = regexGroup(html, "name=\"execution\"\\s+value=\"([^\"]*)\"")
                ?: throw LoginError.Network("登录页解析失败（未找到 execution）", log.toString() + "\n页面摘要: " + summarize(html))

            // 2. 提交账号口令（口令按页面盐值 AES 加密，与浏览器行为一致）
            val encPwd = Crypto.casPasswordEncrypt(pwd, salt)
            val formBody = buildLoginForm(username, encPwd, lt, execution)
            val posted = httpPostForm(cm, pageUrl, formBody, log)
            log.appendLine("POST 登录 HTTP ${posted.lastCode} 最终URL=${posted.finalUrl.take(120)}")

            val newAuth = posted.authHeader
            val newKey = posted.desKey
            if (newAuth != null && newKey != null) {
                return AuthResult(newAuth, newKey)
            }

            // 3. 未跳转 → 登录页被退回，解析错误原因
            val body = posted.body
            val sum = log.toString() + "\n最终页面摘要: " + summarize(body) + "\n原始头部: " + rawHead(body)
            if (body.isEmpty()) throw LoginError.Network("登录无响应（服务器返回空内容）", sum)
            val err = extractError(body)
            val code = posted.lastCode
            return when {
                err.contains("密码") || err.contains("用户名") || err.contains("账号") ->
                    throw LoginError.BadCredential(err, sum)
                body.contains("请输入验证码") || body.contains("验证码错误") || code == 401 ->
                    throw LoginError.NeedCaptcha(
                        err.ifEmpty { "统一身份认证拒绝了本次自动登录（HTTP $code，可能要求验证码或触发风控）" },
                        sum,
                    )
                body.contains("短信") || body.contains("动态码") || body.contains("手机验证码") ->
                    throw LoginError.NeedInteractive(err.ifEmpty { "本次登录需要短信验证码" }, sum)
                err.isNotEmpty() -> throw LoginError.BadCredential(err, sum)
                else -> throw LoginError.Unknown("登录失败（HTTP $code，服务器未返回跳转）", sum)
            }
        } catch (e: LoginError) {
            throw e
        } catch (e: Exception) {
            throw LoginError.Network("网络连接失败：${e.message ?: e.javaClass.simpleName}", log.toString())
        } finally {
            CookieHandler.setDefault(prev)
        }
    }

    /** CAS 表单（与浏览器提交的字段一致）。 */
    private fun buildLoginForm(username: String, encPwd: String, lt: String, execution: String): String {
        val sb = StringBuilder()
        sb.append("username=").append(enc(username))
        sb.append("&password=").append(enc(encPwd))
        sb.append("&_eventId=submit")
        sb.append("&lt=").append(enc(lt))
        sb.append("&cllt=userNameLogin")
        sb.append("&dllt=generalLogin")
        sb.append("&execution=").append(enc(execution))
        sb.append("&rememberMe=true")
        return sb.toString()
    }

    // ---------- HTTP 基础（手动跟随跳转，跨域各自管 cookie） ----------

    private class Resp(val body: String, val finalUrl: String, val lastCode: Int) {
        val authHeader: String? by lazy { findAuth(finalUrl, body, "token") }
        val desKey: String? by lazy { findAuth(finalUrl, body, "casual") }
    }

    /** 鉴权字段可能出现在：URL query / 页面文本 / localStorage.setItem / JSON 字面量。 */
    private fun findAuth(url: String, body: String, name: String): String? {
        val rx = Regex("(?:[?&#&]|^)$name=([^&#\\s\"'<>]+)")
        rx.find(url)?.let { return decode(it.groupValues[1]) }
        rx.find(body)?.let { return decode(it.groupValues[1]) }
        // caslogin_h5.jsp 的落地页是纯脚本页：token/casual 写在 localStorage.setItem 或 JSON 字面量里
        val patterns = listOf(
            """setItem\(\s*['"]""" + name + """['"]\s*,\s*['"]([^'"]+)['"]""",
            """['"]""" + name + """['"]\s*[:=]\s*['"]([^'"]+)['"]""",
        )
        for (p in patterns) {
            val m = Regex(p).find(body) ?: continue
            val v = m.groupValues[1]
            if (v.isNotEmpty()) return decode(v)
        }
        return null
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun httpGet(cm: CookieManager, urlStr: String, log: StringBuilder): Resp =
        follow(cm, urlStr, null, log)

    private fun httpPostForm(cm: CookieManager, urlStr: String, formBody: String, log: StringBuilder): Resp =
        follow(cm, urlStr, formBody, log)

    /** GET/POST 后手动跟随 302（最多 10 跳）；每一跳的响应里都找鉴权字段。 */
    private fun follow(cm: CookieManager, startUrl: String, formBody: String?, log: StringBuilder): Resp {
        var current = startUrl
        var i = 0
        var body = ""
        var lastCode = 0
        while (i < 10) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (formBody != null && i == 0) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    val bytes = formBody.toByteArray(StandardCharsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                } else {
                    conn.requestMethod = "GET"
                }
                val code = conn.responseCode
                lastCode = code
                val loc = conn.getHeaderField("Location")
                val text = readAll(if (code in 200..399) conn.inputStream else conn.errorStream)
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    val next = resolve(current, loc.trim())
                    log.appendLine("跳转$i: HTTP $code -> ${next.take(120)}")
                    val r0 = Resp(text, next, code)
                    if (r0.authHeader != null && r0.desKey != null) return Resp(text, next, code)
                    current = next
                    i++
                    continue
                }
                log.appendLine("响应$i: HTTP $code len=${text.length}")
                body = text
                return Resp(body, current, code)
            } catch (e: java.io.IOException) {
                log.appendLine("请求$i 异常: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        log.appendLine("跳转次数超过上限")
        return Resp(body, current, lastCode)
    }

    private fun resolve(base: String, loc: String): String {
        return if (loc.startsWith("http")) loc else URL(URL(base), loc).toString()
    }

    /** CAS 错误文案：formErrorTip / showErrorTip 里是嵌套 span 结构。 */
    private fun extractError(html: String): String {
        val patterns = listOf(
            "<div[^>]*id=\"formErrorTip\"[^>]*>\\s*(?:<span[^>]*>)*\\s*([^<]{2,100})",
            "id=\"showErrorTip\"[^>]*>\\s*(?:<span[^>]*>)*\\s*([^<]{2,100})",
            "<span[^>]*id=\"msg\"[^>]*>\\s*(?:<span[^>]*>)*\\s*([^<]{2,100})",
            "class=\"auth_error[^>]*>\\s*([^<]{2,80})",
        )
        for (p in patterns) {
            val m = Regex(p).find(html) ?: continue
            val t = m.groupValues[1].trim().replace("&nbsp;", " ")
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    /** 页面摘要：去标签、压缩空白、截断，用于诊断展示。 */
    fun summarize(html: String): String {
        if (html.isEmpty()) return "(空)"
        val t = html.replace(Regex("<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return t.take(260).ifEmpty { "(无可见文本)" }
    }

    /** 原始内容头部（含脚本，用于诊断脚本页）。 */
    fun rawHead(html: String): String {
        if (html.isEmpty()) return "(空)"
        return html.replace(Regex("\\s+"), " ").trim().take(500)
    }

    private fun regexGroup(s: String, pattern: String): String? =
        Regex(pattern).find(s)?.groupValues?.get(1)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun readAll(input: InputStream?): String = try {
        if (input == null) "" else BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
    } catch (_: Exception) {
        ""
    }
}
