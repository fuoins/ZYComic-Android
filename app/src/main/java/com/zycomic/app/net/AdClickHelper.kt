package com.zycomic.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdClickHelper(private val context: Context) {

    suspend fun getAllAdLinks(html: String): List<String> =
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.onResume()
                    webView.resumeTimers()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                val js = """
                                    (function() {
                                        var links = [];
                                        document.querySelectorAll('a').forEach(function(a) {
                                            if (a.href && a.href.indexOf('manwa.me') < 0 && a.href.indexOf('javascript') < 0 && a.href.indexOf('#') < 0) {
                                                links.push(a.href);
                                            }
                                        });
                                        if (links.length > 0) return 'OK|' + links.join('|||');
                                        return 'EMPTY|' + document.querySelectorAll('a').length;
                                    })()
                                """.trimIndent()
                                webView.evaluateJavascript(js) { r ->
                                    val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }.orEmpty()
                                    cont.resume(if (v.startsWith("OK|")) v.removePrefix("OK|").split("|||").filter { it.isNotBlank() } else emptyList())
                                    webView.destroy()
                                }
                            }, 4000)
                        }
                    }
                    webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
                }
            }
        } ?: emptyList()
}
