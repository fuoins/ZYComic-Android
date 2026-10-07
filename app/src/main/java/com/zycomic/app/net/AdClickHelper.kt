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

    suspend fun getAdLink(html: String): Result =
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val webView = WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
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
                                        if (links.length > 0) return links[0];
                                        return 'NO_LINK|' + links.join(';');
                                    })()
                                """.trimIndent()
                                webView.evaluateJavascript(js) { r ->
                                    val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                    cont.resume(Result(v?.takeIf { !it.startsWith("NO_LINK") }, v ?: "null"))
                                    webView.destroy()
                                }
                            }, 4000)
                        }
                    }
                    webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
                }
            }
        } ?: Result(null, "timeout")
}
