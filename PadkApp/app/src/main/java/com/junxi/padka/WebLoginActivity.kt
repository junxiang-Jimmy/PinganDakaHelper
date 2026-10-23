package com.junxi.padka

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast

/**
 * 内置登录页（v1.1 增强）：
 *  - 学号/口令自动填充，无验证码时自动点「登录」；出现验证码/短信验证时人工输入一次
 *  - 成功判定：回调链 URL 携带鉴权参数；或 H5 落地后 localStorage 里有（uni-app 存储）
 *  - 捕获成功自动保存登录态并关闭
 */
class WebLoginActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var hint: TextView
    private var autoFillDone = false
    private var submitted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weblogin)
        web = findViewById(R.id.webview)
        hint = findViewById(R.id.login_hint)

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (checkAuthFromUrl(request.url.toString())) return true
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                checkAuthFromUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                checkAuthFromUrl(url)
                if (url.startsWith(CasClient.CAS_LOGIN) && !autoFillDone) {
                    autoFillDone = true
                    autoFill(view)
                }
                // H5 落地页：从 localStorage 兜底取（uni-app H5 的存储键）
                if (url.contains("zhxg.csu.edu.cn")) {
                    checkAuthFromStorage(view)
                }
            }
        }
        web.loadUrl("${CasClient.CAS_LOGIN}?service=${android.net.Uri.encode(CasClient.SERVICE)}")
    }

    /** URL query/hash 里带鉴权参数即视为登录成功。token 只做 %XX 解码，保留 '+' 原样。 */
    private fun checkAuthFromUrl(url: String): Boolean {
        if (!url.contains("zhxg.csu.edu.cn")) return false
        val auth = paramOf(url, "token") ?: return false
        val key = paramOf(url, "casual") ?: return false
        if (auth.isNotEmpty() && key.isNotEmpty()) {
            saveAndFinish(auth, key)
            return true
        }
        return false
    }

    private fun checkAuthFromStorage(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              try{
                var t = localStorage.getItem('token');
                var c = localStorage.getItem('casual');
                if(!t||!c) return '';
                function clean(s){ if(!s) return s; try{ var v=JSON.parse(s); if(typeof v==='string') return v; }catch(e){} return s; }
                return (clean(t)||'') + '||' + (clean(c)||'');
              }catch(e){ return ''; }
            })();
            """.trimIndent(),
        ) { r ->
            val s = r?.trim()?.removePrefix("\"")?.removeSuffix("\"") ?: ""
            if (s.contains("||") && !s.startsWith("||")) {
                val parts = s.split("||")
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    runOnUiThread { saveAndFinish(parts[0], parts[1]) }
                }
            }
        }
    }

    private fun saveAndFinish(auth: String, key: String) {
        Prefs.putAuth(this, auth, key)
        runOnUiThread {
            try {
                Toast.makeText(this, "登录成功，已保存登录态", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            finish()
        }
    }

    private fun paramOf(url: String, name: String): String? {
        val m = Regex("(?:[?&#]|^)$name=([^&#\\s]+)").find(url) ?: return null
        return decodePct(m.groupValues[1])
    }

    /** 只解 %XX，保留 '+'（token 里的 + 不应变成空格）。 */
    private fun decodePct(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /** 自动填充学号与口令；无验证码要求时自动提交，有验证码则停给用户。 */
    private fun autoFill(view: WebView) {
        val user = Prefs.username(this)
        val pwd = Prefs.pwd(this)
        if (user.isEmpty() || pwd.isEmpty()) {
            runOnUiThread { hint.visibility = View.VISIBLE }
            return
        }
        val u = escape(user)
        val p = escape(pwd)
        view.evaluateJavascript(
            """
            (function(){
              try{
                var f=document.getElementById('pwdFromId');
                if(!f) return 'noform';
                var un=f.querySelector('input[name=username]');
                var pw=f.querySelector('input[name=userPassword]');
                if(!un||!pw) return 'nofield';
                un.value='$u'; un.dispatchEvent(new Event('input',{bubbles:true}));
                pw.value='$p'; pw.dispatchEvent(new Event('input',{bubbles:true}));
                var cap=document.getElementById('captchaDiv');
                var capVisible = cap && cap.offsetHeight>0 && !/display:\s*none/.test(cap.getAttribute('style')||'');
                if(capVisible) return 'captcha';
                var btn=document.getElementById('login_submit');
                if(btn && !window.__padkaSubmitted){ window.__padkaSubmitted=1; setTimeout(function(){btn.click();},300); return 'submitting'; }
                return 'filled';
              }catch(e){return 'err:'+e;}
            })();
            """.trimIndent(),
        ) { r ->
            runOnUiThread {
                when {
                    r.contains("captcha") -> {
                        hint.visibility = View.VISIBLE
                        hint.text = "已填好学号和口令，但本次需要图形验证码：请输入验证码后点「登录」。"
                    }
                    r.contains("submitting") -> {
                        hint.visibility = View.VISIBLE
                        hint.text = "已自动填好并提交登录…若出现验证码/短信验证，输入一次即可。"
                        submitted = true
                    }
                    r.contains("filled") -> {
                        hint.visibility = View.VISIBLE
                        hint.text = "已自动填好学号和口令：检查无误后点页面里的「登录」。"
                    }
                }
            }
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    override fun onDestroy() {
        try {
            web.stopLoading()
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
