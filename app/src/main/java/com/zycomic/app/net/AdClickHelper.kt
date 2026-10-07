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

    private val bridgeJs = """
        <script>
        window.__adLink = null;
        window.flutter_inappwebview = {
            callHandler: function(name, data) {
                try {
                    if (name === 'adClicked') window.__adLink = typeof data === 'string' ? data : JSON.stringify(data);
                } catch(e) {}
            }
        };
        </script>
    """.trimIndent()

    suspend fun getAdLink(html: String): Result = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = true
            val injected = html.replaceFirst("<head>", "<head>$bridgeJs")
                .takeIf { it.contains("<head>") } ?: (bridgeJs + html)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val clickJs = """
                            (function() {
                                var el = document.querySelector('.ad-area') || document.querySelector('.two-ad-area');
                                if (el) {
                                    var c = el.querySelector('a') || el.querySelector('img') || el;
                                    c.click();
                                }
                                return 'clicked:' + (el ? el.className : 'none');
                            })()
                        """.trimIndent()
                        webView.evaluateJavascript(clickJs) { cr ->
                            Handler(Looper.getMainLooper()).postDelayed({
                                val readJs = """
                                    (function() {
                                        var links = [];
                                        document.querySelectorAll('a').forEach(function(a,i){ if(i<10 && a.href) links.push(a.href); });
                                        return window.__adLink || ('NO_LINK|${cr ?: "null"}|' + links.join(';'));
                                    })()
                                """.trimIndent()
                                webView.evaluateJavascript(readJs) { r ->
                                    val v = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                    val link = v?.takeIf { !it.startsWith("NO_LINK") }
                                    cont.resume(Result(link, v ?: "null"))
                                    webView.destroy()
                                }
                            }, 1500)
                        }
                    }, 4000)
                }
            }
            webView.loadDataWithBaseURL("https://manwa.me/", injected, "text/html", "UTF-8", null)
        }
    }
}
