package com.example.vehiclechecker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
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
    private const val MAX_POLLS = 25

    // Returns READY when test records are on the page, EMPTY for "no MOT" pages,
    // CHALLENGE while bot protection is still running, WAITING otherwise.
    private const val STATUS_JS =
        "(function(){try{var t=document.body?document.body.innerText:'';" +
            "if(t.indexOf('Pardon Our Interruption')!==-1)return 'CHALLENGE';" +
            "if(document.querySelectorAll('[data-test-id=test-history-item]').length>0)return 'READY';" +
            "if(/no MOT/i.test(t)||/not found/i.test(t))return 'EMPTY';" +
            "return 'WAITING';}catch(e){return 'WAITING';}})()"

    private const val HTML_JS = "(function(){return document.documentElement.outerHTML;})()"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchResultsHtml(applicationContext: Context, registration: String): String? =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                var webView: WebView? = null
                var polls = 0
                var finished = false
                var navigatedToResults = false

                fun finish(html: String?) {
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

                fun poll(view: WebView) {
                    if (finished || !cont.isActive) return
                    polls++
                    if (polls > MAX_POLLS) {
                        finish(null)
                        return
                    }
                    view.evaluateJavascript(STATUS_JS) { raw ->
                        val status = try {
                            JSONArray("[$raw]").getString(0)
                        } catch (_: Exception) {
                            "WAITING"
                        }
                        when (status) {
                            "READY", "EMPTY" -> view.evaluateJavascript(HTML_JS) { rawHtml ->
                                val html = try {
                                    JSONArray("[$rawHtml]").getString(0)
                                } catch (_: Exception) {
                                    null
                                }
                                finish(html)
                            }
                            else -> mainHandler.postDelayed({ poll(view) }, POLL_INTERVAL_MS)
                        }
                    }
                }

                try {
                    webView = WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (finished || !cont.isActive) return
                                if (url.contains("/results")) {
                                    mainHandler.postDelayed({ poll(view) }, 800L)
                                } else if (!navigatedToResults) {
                                    // Homepage loaded (challenge cookies handled by the engine);
                                    // now go to the results page.
                                    navigatedToResults = true
                                    view.loadUrl(
                                        "$BASE_URL/results?registration=$registration&checkRecalls=true"
                                    )
                                }
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        mainHandler.post { finish(null) }
                    }
                    webView?.loadUrl("$BASE_URL/")
                } catch (_: Exception) {
                    finish(null)
                }
            }
        }
}
