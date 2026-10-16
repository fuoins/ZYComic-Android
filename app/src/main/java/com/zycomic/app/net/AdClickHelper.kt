package com.zycomic.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class AdClickHelper(private val context: Context) {

    data class ExtractionResult(val links: List<String>, val diagnostics: String)

    companion object {
        private const val POLL_TIMEOUT_MS = 8_000L
        private const val POLL_INTERVAL_MS = 400L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getAllAdLinks(html: String): ExtractionResult {
        val latestDiagnostics = AtomicReference("webview_load_pending")
        val result = withTimeoutOrNull(POLL_TIMEOUT_MS) {
            suspendCancellableCoroutine<ExtractionResult> { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                val startedAt = SystemClock.elapsedRealtime()
                var webView: WebView? = null
                var pollTask: Runnable? = null
                var pollingStarted = false
                var cleanedUp = false

                fun cleanup() {
                    if (cleanedUp) return
                    cleanedUp = true
                    pollTask?.let(mainHandler::removeCallbacks)
                    webView?.let { view ->
                        webView = null
                        view.stopLoading()
                        view.webViewClient = WebViewClient()
                        view.destroy()
                    }
                }

                fun finish(extractionResult: ExtractionResult) {
                    val token = continuation.tryResume(extractionResult)
                    cleanup()
                    if (token != null) continuation.completeResume(token)
                }

                continuation.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        cleanup()
                    } else {
                        mainHandler.post { cleanup() }
                    }
                }

                fun schedulePoll() {
                    if (cleanedUp || !continuation.isActive) return
                    val task = Runnable {
                        val view = webView
                        if (cleanedUp || view == null || !continuation.isActive) {
                            cleanup()
                        } else {
                            val script = """
                                (function() {
                                    var anchors = Array.from(document.querySelectorAll('a'));
                                    var links = anchors.map(function(a) { return a.href; }).filter(function(href) {
                                        return href && href.indexOf('manwa.me') < 0 && href.indexOf('javascript') < 0 && href.indexOf('#') < 0;
                                    });
                                    return {
                                        links: links,
                                        anchorCount: anchors.length,
                                        readyState: document.readyState
                                    };
                                })()
                            """.trimIndent()
                            try {
                                view.evaluateJavascript(script) { value ->
                                    if (cleanedUp || !continuation.isActive) {
                                        cleanup()
                                    } else {
                                        try {
                                            val json = JSONObject(value ?: "{}")
                                            val linkArray = json.optJSONArray("links")
                                            val links = buildList {
                                                if (linkArray != null) {
                                                    for (index in 0 until linkArray.length()) {
                                                        linkArray.optString(index)
                                                            .takeIf(String::isNotBlank)
                                                            ?.let(::add)
                                                    }
                                                }
                                            }
                                            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                                            val diagnostics = "readyState=${json.optString("readyState", "unknown")}, " +
                                                "anchors=${json.optInt("anchorCount", -1)}, candidates=${links.size}, elapsed=${elapsedMs}ms"
                                            latestDiagnostics.set(diagnostics)
                                            if (links.isNotEmpty()) {
                                                finish(ExtractionResult(links, diagnostics))
                                            } else {
                                                schedulePoll()
                                            }
                                        } catch (error: Exception) {
                                            latestDiagnostics.set("dom_result_parse_error=${error.javaClass.simpleName}")
                                            schedulePoll()
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                latestDiagnostics.set("dom_poll_error=${error.javaClass.simpleName}")
                                schedulePoll()
                            }
                        }
                    }
                    pollTask = task
                    mainHandler.postDelayed(task, POLL_INTERVAL_MS)
                }

                mainHandler.post {
                    if (!continuation.isActive) return@post
                    try {
                        latestDiagnostics.set("webview_loading")
                        val view = WebView(context)
                        webView = view
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.onResume()
                        view.resumeTimers()
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                latestDiagnostics.set("page_finished_waiting_for_dom")
                                if (!pollingStarted) {
                                    pollingStarted = true
                                    schedulePoll()
                                }
                            }
                        }
                        view.loadDataWithBaseURL("https://manwa.me/", html, "text/html", "UTF-8", null)
                    } catch (error: Exception) {
                        finish(ExtractionResult(emptyList(), "webview_load_error=${error.javaClass.simpleName}"))
                    }
                }
            }
        }
        return result ?: ExtractionResult(
            emptyList(),
            "poll_timeout=${POLL_TIMEOUT_MS}ms; last=${latestDiagnostics.get()}",
        )
    }
}
