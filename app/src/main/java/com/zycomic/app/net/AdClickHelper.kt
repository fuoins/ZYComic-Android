package com.zycomic.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdClickHelper(private val context: Context) {

    data class Result(val link: String?, val debug: String)

    suspend fun getAdLink(html: String): Result = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        evaluate(webView, 1) { first ->
                            if (first != null && !first.startsWith("DEBUG")) {
                                cont.resume(Result(first, "ok: $first"))
                                webView.destroy()
                                return@evaluate
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                evaluate(webView, 2) { second ->
                                    cont.resume(Result(second?.takeIf { !it.startsWith("DEBUG") },
                                        "first=$first\nsecond=$second"))
                                    webView.destroy()
                                }
                            }, 2000)
                        }
                    }, 4000)
                }
            }
            webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
        }
    }

    private fun evaluate(webView: WebView, round: Int, cb: (String?) -> Unit) {
        val js = """
            (function() {
                var allLinks = [];
                document.querySelectorAll('a').forEach(function(a){ if(a.href) allLinks.push(a.href); });
                var target = allLinks.find(function(h){ return h.indexOf('chmsrv')>=0 || h.indexOf('click.php')>=0; });
                if (target) return target;
                return 'DEBUG_NO_LINK|r$round|n='+allLinks.length+'|'+document.body.innerHTML.substring(0,300);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
            cb(v)
        }
    }
}
