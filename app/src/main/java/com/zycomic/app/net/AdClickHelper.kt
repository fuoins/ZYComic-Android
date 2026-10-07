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
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                extract(webView) { links ->
                                    if (links.isNotEmpty()) { cont.resume(links); webView.destroy(); return@extract }
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        extract(webView) { links2 ->
                                            cont.resume(links2)
                                            webView.destroy()
                                        }
                                    }, 3000)
                                }
                            }, 3000)
                        }
                    }
                    webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
                }
            }
        } ?: emptyList()

    private fun extract(webView: WebView, cb: (List<String>) -> Unit) {
        val js = """
            (function() {
                var links = [];
                document.querySelectorAll('a').forEach(function(a) {
                    if (a.href && a.href.indexOf('manwa.me') < 0 && a.href.indexOf('javascript') < 0 && a.href.indexOf('#') < 0) {
                        links.push(a.href);
                    }
                });
                return JSON.stringify(links);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            try {
                val arr = org.json.JSONArray(r?.removePrefix("\"")?.removeSuffix("\"") ?: "[]")
                cb((0 until arr.length()).map { arr.getString(it) })
            } catch (_: Exception) { cb(emptyList()) }
        }
    }
}
