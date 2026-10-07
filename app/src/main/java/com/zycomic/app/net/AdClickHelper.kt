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
                                        var hrefs = [];
                                        document.querySelectorAll('a').forEach(function(a) {
                                            hrefs.push(a.href);
                                            if (a.href && a.href.indexOf('manwa.me') < 0 && a.href.indexOf('javascript') < 0 && a.href.indexOf('#') < 0) {
                                                links.push(a.href);
                                            }
                                        });
                                        return JSON.stringify({links: links, total: hrefs.length, hrefs: hrefs.slice(0,10)});
                                    })()
                                """.trimIndent()
                                webView.evaluateJavascript(js) { r ->
                                    try {
                                        val o = org.json.JSONObject(r?.removePrefix("\"")?.removeSuffix("\"") ?: "{}")
                                        val arr = o.getJSONArray("links")
                                        val l = (0 until arr.length()).map { arr.getString(it) }
                                        val dbg = if (l.isEmpty()) "NO_LINKS|total=" + o.optInt("total") + "|hrefs=" + o.optJSONArray("hrefs")?.join(";") else ""
                                        onStatus("已获取${l.size}个广告链接")
                                        cont.resume(Result(l, dbg))
                                    } catch (_: Exception) { cont.resume(Result(emptyList(), "parse_err:" + r)) }
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
