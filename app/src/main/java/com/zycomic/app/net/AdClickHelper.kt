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
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val js = """
                            (function() {
                                var result = {links: [], attrs: [], scripts: []};
                                document.querySelectorAll('a').forEach(function(a) {
                                    if (a.href && a.href.indexOf('manwa.me') < 0 && a.href.indexOf('javascript') !== 0) result.links.push(a.href);
                                    for (var i = 0; i < a.attributes.length; i++) {
                                        var attr = a.attributes[i];
                                        if (attr.name.indexOf('data-') === 0 || attr.value.indexOf('chmsrv') >= 0 || attr.value.indexOf('click.php') >= 0) {
                                            result.attrs.push(attr.name + '=' + attr.value);
                                        }
                                    }
                                });
                                document.querySelectorAll('script').forEach(function(s) {
                                    if (s.textContent && (s.textContent.indexOf('chmsrv') >= 0 || s.textContent.indexOf('click.php') >= 0)) {
                                        result.scripts.push(s.textContent.substring(0, 200));
                                    }
                                });
                                var ch = result.links.find(function(h){ return h.indexOf('chmsrv') >= 0 || h.indexOf('click.php') >= 0; });
                                if (ch) return ch;
                                if (result.links.length > 0) return result.links[0];
                                return 'NO_LINK|' + JSON.stringify(result).substring(0, 500);
                            })()
                        """.trimIndent()
                        webView.evaluateJavascript(js) { r ->
                            val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                            val link = v?.takeIf { !it.startsWith("NO_LINK") }
                            cont.resume(Result(link, v ?: "null"))
                            webView.destroy()
                        }
                    }, 4000)
                }
            }
            webView.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
        }
    }
}
