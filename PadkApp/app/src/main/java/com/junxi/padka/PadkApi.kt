package com.junxi.padka

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 智慧学工「平安打卡」接口引擎（服务大厅 → 日常事务 → 平安打卡）。
 *
 * 网关与接口（与 H5 前端行为一致）：
 *   POST https://zhxg.csu.edu.cn/znzhxgpt/qxj/qxj-padkglxx/queryKqDkbc
 *        查当日打卡班次/状态，参数 dklb=PA（平安打卡）
 *   POST https://zhxg.csu.edu.cn/znzhxgpt/qxj/qxj-padkglxx/xspadk
 *        学生打卡，参数 jd/wd（经纬度）、dkbc（班次）、dkdz（地点名）
 *
 * 请求体为 JSON 的 DES-ECB-PKCS7 加密 hex（密钥=casual 前 8 字节），请求头带 token。
 * 响应为 JSON：{code, message, data}，code 200 成功；203/2031 表示登录态失效。
 */
object PadkApi {

    const val GATEWAY = "https://zhxg.csu.edu.cn/znzhxgpt"
    const val PATH_STATUS = "/qxj/qxj-padkglxx/queryKqDkbc"
    const val PATH_PUNCH = "/qxj/qxj-padkglxx/xspadk"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT = 20000

    class ApiResp(val code: Int, val message: String, val data: JSONObject?) {
        val ok: Boolean get() = code == 200
        val authExpired: Boolean get() = code == 203 || code == 2031
    }

    class ApiError(message: String, val httpCode: Int) : Exception(message)

    /** 查询当日打卡状态：班次、是否已打卡、是否可打卡、免打卡等。 */
    fun queryStatus(authHeader: String, desKey: String): JSONObject {
        val payload = JSONObject().put(
            "paramsData", JSONObject().put("dklb", "PA"),
        )
        val resp = postDes(GATEWAY + PATH_STATUS, payload, authHeader, desKey)
        if (!resp.ok) {
            throw ApiError(describe(resp), 200)
        }
        return resp.data ?: JSONObject()
    }

    /** 提交一次打卡（等同页面上点「立即打卡」）。
     *  注意：xspadk 的参数是平铺的 jd/wd/dkbc/dkdz（不包 paramsData，与 H5 源码一致），
     *  多包一层会被服务器解成空值，报「经度/纬度/打卡班次不能为空」。 */
    fun punch(authHeader: String, desKey: String, jd: Double, wd: Double, dkbc: Any, dkdz: String): ApiResp {
        val payload = JSONObject()
            .put("jd", jd)
            .put("wd", wd)
            .put("dkbc", dkbc)
            .put("dkdz", dkdz)
        return postDes(GATEWAY + PATH_PUNCH, payload, authHeader, desKey)
    }

    /** 带登录态失效识别的请求。 */
    fun postDes(url: String, payload: JSONObject, authHeader: String, desKey: String): ApiResp {
        val bodyHex = Crypto.desEcbHex(payload.toString(), desKey)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            conn.setRequestProperty("token", authHeader)
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Origin", "https://zhxg.csu.edu.cn")
            conn.setRequestProperty("Referer", "https://zhxg.csu.edu.cn/znzhxgpt_h5/")
            val bytes = bodyHex.toByteArray(StandardCharsets.US_ASCII)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = readAll(stream)
            if (code !in 200..399) {
                throw ApiError("HTTP $code ${shortErr(text)}", code)
            }
            val o = JSONObject(text)
            val c = when (val cv = o.opt("code") ?: 200) {
                is Int -> cv
                is String -> cv.toIntOrNull() ?: 200
                else -> 200
            }
            return ApiResp(c, o.optString("message", ""), o.optJSONObject("data"))
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun describe(r: ApiResp): String = r.message.ifEmpty { "接口返回 code=${r.code}" }

    private fun shortErr(text: String): String =
        text.replace(Regex("<[^>]+>"), "").trim().take(80)

    private fun readAll(input: InputStream?): String = try {
        if (input == null) "" else BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
    } catch (_: Exception) {
        ""
    }
}
