package com.zycomic.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AdClickHelper(private val context: Context) {

    data class Result(val links: List<String>, val debug: String)

    suspend fun getAllAdLinks(html: String, onStatus: (String) -> Unit = {}): Result =
        kotlinx.coroutines.withTimeoutOrNull(10000) {
            onStatus("加载广告中...")
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
                                        var allHrefs = [];
                                        document.querySelectorAll('a').forEach(function(a) {
                                            allHrefs.push(a.href);
                                            if (a.href && a.href.indexOf('manwa.me') < 0 && a.href.indexOf('javascript') < 0 && a.href.indexOf('#') < 0) {
                                                links.push(a.href);
                                            }
                                        });
                                        if (links.length > 0) return 'OK|' + links.join('|||');
                                        return 'EMPTY|' + allHrefs.length + '|' + allHrefs.slice(0,10).join('|||');
                                    })()
                                """.trimIndent()
                                webView.evaluateJavascript(js) { r ->
                                    val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }.orEmpty()
                                    try {
                                        if (v.startsWith("OK|")) {
                                            val l = v.removePrefix("OK|").split("|||").filter { it.isNotBlank() }
                                            onStatus("已获取${l.size}个广告链接")
                                            cont.resume(Result(l, ""))
                                        } else {
                                            val parts = v.split("|")
                                            cont.resume(Result(emptyList(), "NO_LINKS|total=" + parts.getOrNull(1) + "|hrefs=" + parts.getOrNull(2)))
                                        }
                                    } catch (_: Exception) { cont.resume(Result(emptyList(), "parse_err:" + v)) }
                                    webView.destroy()
                                }
                            }, 6000)
                        }
                    }
                    webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
                }
            }
        } ?: Result(emptyList(), "timeout")
}
