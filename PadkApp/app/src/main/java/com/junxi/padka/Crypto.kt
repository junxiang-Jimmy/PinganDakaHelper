package com.junxi.padka

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与智慧学工 H5 前端完全对齐的两组加密算法（已用 Node + crypto-js 对拍验证）。
 *
 * 1) 业务接口请求体：DES/ECB/PKCS7，密钥 = casual 的前 8 个 UTF-8 字节（crypto-js DES 行为），
 *    明文 = JSON 字符串，输出 = 小写 hex 字符串，作为 HTTP body 直接发送。
 * 2) CAS 登录密码：AES/CBC/PKCS7（key=pwdEncryptSalt 16 字节，iv=随机 16 字符），
 *    明文 = 随机 64 字符前缀 + 密码，输出 = base64。
 */
object Crypto {

    private val RANDOM_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private val rng = SecureRandom()

    fun randomString(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(RANDOM_CHARS[rng.nextInt(RANDOM_CHARS.length)])
        return sb.toString()
    }

    fun desEcbHex(json: String, casualKey: String): String {
        val keyBytes = casualKey.toByteArray(Charsets.UTF_8)
        val desKey = if (keyBytes.size >= 8) keyBytes.copyOf(8) else keyBytes
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(desKey, "DES"))
        val enc = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(enc.size * 2)
        for (b in enc) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun casPasswordEncrypt(password: String, salt: String): String {
        val key = salt.toByteArray(Charsets.UTF_8)
        val iv = randomString(16).toByteArray(Charsets.UTF_8)
        val plain = (randomString(64) + password).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = cipher.doFinal(plain)
        return Base64.encodeToString(enc, Base64.NO_WRAP)
    }
}
