package com.example.vehiclechecker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fetches the public GOV.UK "Check MOT history" results page through an off-screen WebView.
 *
 * The service sits behind Imperva bot protection, which 403s/blocks plain HTTP clients
 * (Jsoup, OkHttp, curl). A real browser engine runs the protection's JavaScript challenge
 * automatically, after which the page loads normally — so we render it invisibly in a
 * WebView and hand the finished HTML back for parsing.
 */
object MotWebFetcher {

    private const val BASE_URL = "https://www.check-mot.service.gov.uk"
    private const val POLL_INTERVAL_MS = 1000L

    /**
     * Total time we allow the challenge + page render to take. The old poll-count approach
     * (25 × 1s) shared one counter across the homepage challenge AND the results page, so a
     * slow challenge silently burned the budget and the fetch returned null.
     */
    private const val TIMEOUT_MS = 45_000L

    // Returns READY when test records or MOT history content are on the page, EMPTY for "no MOT" pages,
    // CHALLENGE while bot protection is still running, WAITING otherwise.
    private const val STATUS_JS =
        "(function(){try{var t=document.body?document.body.innerText:'';" +
            "if(t.indexOf('Pardon Our Interruption')!==-1)return 'CHALLENGE';" +
            "if(/no MOT history/i.test(t)||/no test history/i.test(t)||/vehicle not found/i.test(t)||/no MOT records/i.test(t))return 'EMPTY';" +
            "if(document.querySelectorAll('[data-test-id=test-history-item], [data-test-id=test-result], .mot-history-item, [id^=mot-history-item]').length>0)return 'READY';" +
            "if(t.indexOf('Date tested')!==-1||t.indexOf('MOT valid until')!==-1||t.indexOf('Odometer')!==-1)return 'READY';" +
            "return 'WAITING';}catch(e){return 'WAITING';}})()"

    class HtmlBridge(private val onHtmlReady: (String) -> Unit) {
        @JavascriptInterface
        @Suppress("unused")
        fun processHTML(html: String) {
            onHtmlReady(html)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchResultsHtml(applicationContext: Context, registration: String): String? =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                var webView: WebView? = null
                var finished = false
                var pollLoopStarted = false
                val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS

                fun finishInternal(html: String?) {
                    if (finished) return
                    finished = true
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (_: Exception) {
                    }
                    webView = null
                    if (cont.isActive) cont.resume(html)
                }

                fun finish(html: String?) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        finishInternal(html)
                    } else {
                        mainHandler.post { finishInternal(html) }
                    }
                }

                fun poll(view: WebView) {
                    if (finished || !cont.isActive) return
                    if (SystemClock.elapsedRealtime() > deadline) {
                        finish(null)
                        return
                    }
                    view.evaluateJavascript(STATUS_JS) { raw ->
                        if (finished || !cont.isActive) return@evaluateJavascript
                        val status = raw?.replace("\"", "")?.trim() ?: "WAITING"
                        when (status) {
                            "READY", "EMPTY" -> view.evaluateJavascript(
                                "window.AndroidBridge.processHTML(document.documentElement.outerHTML);",
                                null,
                            )
                            else -> mainHandler.postDelayed({ poll(view) }, POLL_INTERVAL_MS)
                        }
                    }
                }

                try {
                    webView = WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        addJavascriptInterface(HtmlBridge { html -> finish(html) }, "AndroidBridge")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (finished || !cont.isActive) return
                                if (!pollLoopStarted) {
                                    pollLoopStarted = true
                                    mainHandler.postDelayed({ poll(view) }, 800L)
                                }
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        mainHandler.post { finish(null) }
                    }
                    webView?.loadUrl("$BASE_URL/results?registration=$registration&checkRecalls=true")

                    // Hard backstop: never hang past the deadline even if no page event fires.
                    mainHandler.postDelayed({ finish(null) }, TIMEOUT_MS + 5_000L)
                } catch (_: Exception) {
                    finish(null)
                }
            }
        }
}
