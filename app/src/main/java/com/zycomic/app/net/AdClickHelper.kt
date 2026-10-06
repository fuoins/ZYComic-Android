package com.zycomic.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdClickHelper(private val context: Context) {

    suspend fun getAdLink(html: String): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val js = """
                            (function() {
                                var selectors = ['.ad-area a', 'ins a', '[data-zoneid] a', 'a[href*="click.php"]'];
                                for (var i = 0; i < selectors.length; i++) {
                                    var el = document.querySelector(selectors[i]);
                                    if (el && el.href) return el.href;
                                }
                                return null;
                            })()
                        """.trimIndent()
                        webView.evaluateJavascript(js) { result ->
                            val link = result?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                            cont.resume(link)
                            webView.destroy()
                        }
                    }, 2000)
                }
            }
            webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
        }
    }
}
