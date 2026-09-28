package com.example.vehiclechecker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fetches the public GOV.UK "Check MOT history" results page through an off-screen WebView.
 *
 * The service sits behind Imperva (Incapsula) bot protection, which answers suspicious requests
 * with an immediate `403` "Access denied / Error 15" block page — text that used to surface in the
 * app as "the site blocks automatic checks on this device".
 *
 * Measured against the live service from the same network as the phone, that block is purely a
 * *request shape* decision, and it is avoidable:
 *
 *  - a Chrome-like User-Agent with no other headers        -> 403 block page
 *  - any User-Agent (even `curl/8`) with a full header set -> 403 block page
 *  - Chrome-like User-Agent + `Accept-Language`            -> 200 "Pardon Our Interruption"
 *
 * That last response is the *solvable* interstitial: it loads a challenge script which sets the
 * `reese84` cookie and reloads, after which the real page renders. A bare WebView sends neither a
 * Chrome-like User-Agent nor an `Accept-Language`, so it was being hard-blocked before the
 * challenge was even offered — you cannot solve a challenge you never receive.
 *
 * So the fetcher now:
 *  1. navigates once, directly to the results URL, with browser-like headers ([NAVIGATION_HEADERS]);
 *  2. waits patiently while the interstitial solves itself (it reloads the page on its own);
 *  3. re-navigates with those headers only if the engine reports a hard block, since a reload can
 *     drop the extra headers, and clears a flagged cookie jar before the last attempt;
 *  4. returns the rendered HTML for Jsoup to parse once test records are on the page.
 *
 * Verified on device: the phone's own browser renders this page fine on this network, so the block
 * is about how the request is shaped, not about the connection or the user's IP.
 */
object MotWebFetcher {

    private const val TAG = "MotWebFetcher"
    private const val BASE_URL = "https://www.check-mot.service.gov.uk"

    private const val POLL_INTERVAL_MS = 750L

    /** Total budget for the challenge plus the rendered results page. */
    private const val FETCH_TIMEOUT_MS = 45_000L

    /** Minimum gap between re-navigations, so we never look like a "power user" to Imperva. */
    private const val RENAVIGATE_GAP_MS = 6_000L

    /** How many times we re-issue the navigation with browser headers before giving up. */
    private const val MAX_NAV_ATTEMPTS = 4

    private const val ACCEPT_HEADER =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    /**
     * Headers applied to every navigation. `Accept-Language` is the one that matters most: without
     * it the service returns the hard 403 block page instead of the solvable challenge.
     */
    private val NAVIGATION_HEADERS: Map<String, String> = mapOf(
        "Accept" to ACCEPT_HEADER,
        "Accept-Language" to "en-GB,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
    )

    /** Human-readable reason for the last failed fetch, surfaced on the card for diagnosis. */
    @Volatile
    private var lastFailureDetail: String? = null

    fun lastFailure(): String? = lastFailureDetail

    /** Sentinel reused by [MotHistoryScraper] to pick the "site refused us" error copy. */
    const val FAILURE_BLOCKED = "blocked by the site's security check"

    // Classifies the current document in one round trip. BLOCKED is the Imperva hard block (its
    // text lives inside the same-origin #main-iframe), INTERSTITIAL is the *solvable* challenge
    // that just needs time, READY/EMPTY mean the real page has rendered.
    private const val PAGE_PROBE_JS =
        "(function(){try{" +
            "var d=document,t=d.body?(d.body.innerText||''):'',h=d.documentElement?(d.documentElement.outerHTML||''):'';" +
            "var f=d.getElementById('main-iframe'),ft='';" +
            "try{if(f&&f.contentDocument){var cd=f.contentDocument;" +
            "ft=(cd.title||'')+' '+((cd.body?cd.body.innerText:'')||'')+' '+((cd.documentElement?cd.documentElement.outerHTML:'').slice(0,400));}}catch(e){}" +
            "var all=t+' '+ft;" +
            "if(/Access denied|Error 15|blocked by our security service|Additional security check is required|Why am I seeing this page/i.test(all))return 'BLOCKED';" +
            "if(d.querySelectorAll('[data-test-id=test-history-item],[data-test-id=test-result],[data-test-id=vehicle-registration]').length>0)return 'READY';" +
            // For a registration DVSA has never seen, the service bounces back to its
            // registration-search page (path "/" plus a registration input) instead of rendering
            // an empty results page. Recognise that as an answer, not as a page still loading.
            "if(location.pathname==='/'&&d.querySelector('input[name=registration],input#registration,form[action*=results]'))return 'NOTFOUND';" +
            "if(/no MOT|not found|no test history/i.test(t))return 'EMPTY';" +
            "if(h.indexOf('initializeProtection')!==-1||h.indexOf('Pardon Our Interruption')!==-1||f)return 'INTERSTITIAL';" +
            "return 'WAITING';}catch(e){return 'WAITING';}})()"

    /** Rich one-shot snapshot of the page, logged while a fetch is stuck. */
    private const val DIAG_JS =
        "(function(){try{" +
            "var f=document.getElementById('main-iframe');" +
            "var out={href:location.href,title:document.title,iframe:!!f," +
            "iframeSrc:f?String(f.getAttribute('src')).slice(0,120):null," +
            "cookies:(document.cookie||'').slice(0,160),wd:navigator.webdriver," +
            "ua:navigator.userAgent.slice(0,110),langs:(navigator.languages||[]).join(',')," +
            "hc:navigator.hardwareConcurrency,ready:document.readyState," +
            "path:location.pathname,forms:d.querySelectorAll('form').length," +
            "inputs:Array.from(d.querySelectorAll('input')).map(function(i){return i.name||i.id||i.type;}).slice(0,5).join('|')," +
            "vis:document.visibilityState,w:window.innerWidth,h:window.innerHeight," +
            "scripts:document.querySelectorAll('script').length," +
            "bodyLen:(document.body?document.body.innerText:'').length," +
            "body:(document.body?document.body.innerText:'').slice(0,140)};" +
            "try{if(f&&f.contentDocument){var d=f.contentDocument;out.iframeAccessible=true;" +
            "out.iframeTitle=d.title||'';out.iframeText=(d.body?d.body.innerText:'').slice(0,160);" +
            "out.iframeLen=d.documentElement?d.documentElement.outerHTML.length:0;}}catch(e){out.iframeErr=String(e).slice(0,60);}" +
            "return JSON.stringify(out);}catch(e){return 'DIAG_ERR:'+e;}})()"

    class HtmlBridge(private val onHtmlReady: (String) -> Unit) {
        @JavascriptInterface
        @Suppress("unused")
        fun processHTML(html: String) {
            onHtmlReady(html)
        }
    }

    /**
     * Attaches the fetch WebView to the activity window as a nearly invisible view.
     *
     * Chromium throttles timers and animation frames for WebViews that are not attached to a
     * visible window and reports the page as hidden — bot-protection challenge scripts never
     * finish under those conditions. Attached (even at alpha 0.01) the engine treats the page as
     * visible and the challenge completes normally. The view is deliberately given a realistic
     * size so the challenge script does not see a 1x1 viewport.
     */
    private fun attachInvisible(context: Context, view: WebView) {
        val activity = context as? Activity
        if (activity == null) {
            Log.w(TAG, "attach skipped: context is ${context.javaClass.simpleName}, not an Activity")
            return
        }
        try {
            val root = activity.window?.decorView as? ViewGroup
            if (root == null) {
                Log.w(TAG, "attach skipped: no decor view")
                return
            }
            view.alpha = 0.01f
            view.setBackgroundColor(Color.TRANSPARENT)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.isFocusable = false
            root.addView(
                view,
                FrameLayout.LayoutParams(360, 640).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            )
            view.bringToFront()
            Log.d(TAG, "webview attached (attached=${view.isAttachedToWindow}, shown=${view.isShown})")
        } catch (e: Exception) {
            Log.w(TAG, "attach failed: ${e.message}")
        }
    }

    private fun detach(view: WebView?) {
        try {
            (view?.parent as? ViewGroup)?.removeView(view)
        } catch (_: Exception) {
        }
    }

    /**
     * Applies a browser-consistent user agent. The stock WebView UA contains the "; wv" token,
     * which declares "I am a WebView" to bot protection, while the engine version has to stay in
     * step with the installed build or the mismatch itself becomes a bot signal.
     */
    private fun applyConsistentUserAgent(webView: WebView) {
        try {
            val stock = webView.settings.userAgentString ?: return
            val cleaned = stock
                .replace("; wv", "")
                .replace(" Version/4.0", "")
                .replace(Regex(" Build/[^;)]*"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            if (cleaned != stock) webView.settings.userAgentString = cleaned
            Log.d(TAG, "ua=$cleaned")
        } catch (_: Exception) {
        }
    }

    /** Drops a possibly flagged session so the next navigation gets a fresh challenge. */
    private fun clearSiteCookies() {
        try {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            Log.d(TAG, "cleared webview cookies after a hard block")
        } catch (e: Exception) {
            Log.w(TAG, "cookie clear failed: ${e.message}")
        }
    }

    private fun dumpDiagnostics(view: WebView) {
        try {
            view.evaluateJavascript(DIAG_JS) { raw -> Log.d(TAG, "diag=${raw?.take(900)}") }
        } catch (_: Exception) {
        }
    }

    /** Builds the fetch WebView: JS enabled, cookies shared, browser UA and headers applied. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newFetchWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        applyConsistentUserAgent(this)
    }

    /**
     * Loads the results page for [registration] and returns the rendered HTML, or null on failure
     * (with [lastFailure] explaining why).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchResultsHtml(context: Context, registration: String): String? =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val cleanReg = registration.replace(" ", "").uppercase().trim()
                val url = "$BASE_URL/results?registration=$cleanReg&checkRecalls=true"
                lastFailureDetail = null

                var webView: WebView? = null
                var finished = false
                var polls = 0
                var attempts = 0
                var cookiesCleared = false
                var pollLoopStarted = false
                var lastState: String? = null
                var lastNavigateAt = 0L
                val deadline = SystemClock.elapsedRealtime() + FETCH_TIMEOUT_MS

                fun finishInternal(html: String?) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(null)
                    detach(webView)
                    try { webView?.stopLoading(); webView?.destroy() } catch (_: Exception) {}
                    webView = null
                    if (cont.isActive) cont.resume(html)
                }

                fun finish(html: String?) {
                    if (Looper.myLooper() == Looper.getMainLooper()) finishInternal(html)
                    else mainHandler.post { finishInternal(html) }
                }

                fun navigate(view: WebView) {
                    attempts++
                    lastNavigateAt = SystemClock.elapsedRealtime()
                    Log.d(TAG, "navigation #$attempts headers=${NAVIGATION_HEADERS.keys}")
                    try {
                        view.loadUrl(url, NAVIGATION_HEADERS)
                    } catch (e: Exception) {
                        Log.w(TAG, "loadUrl failed: ${e.message}")
                    }
                }

                fun poll(view: WebView) {
                    if (finished || !cont.isActive) return
                    if (SystemClock.elapsedRealtime() > deadline) {
                        Log.d(TAG, "timed out after $polls polls / $attempts navigations, lastState=$lastState")
                        dumpDiagnostics(view)
                        if (lastFailureDetail == null) lastFailureDetail = "timeout"
                        finish(null)
                        return
                    }
                    polls++
                    if (polls == 5 || polls % 20 == 0) dumpDiagnostics(view)
                    view.evaluateJavascript(PAGE_PROBE_JS) { raw ->
                        if (finished || !cont.isActive) return@evaluateJavascript
                        val state = raw?.trim()?.trim('"')
                        if (state != lastState) {
                            Log.d(TAG, "state=$state url=${view.url} (poll#$polls)")
                            lastState = state
                        }
                        when (state) {
                            "READY", "EMPTY", "NOTFOUND" -> view.evaluateJavascript(
                                "window.AndroidBridge.processHTML(document.documentElement.outerHTML);",
                                null,
                            )
                            "BLOCKED" -> {
                                // Imperva's hard block page. The extra navigation headers are
                                // usually to blame when this reappears: the challenge interstitial
                                // reloads the page itself, and a reload can drop them. Re-navigate
                                // with the headers, and clear the cookie jar once in case the
                                // session itself was flagged by an earlier failed attempt.
                                val sinceLast = SystemClock.elapsedRealtime() - lastNavigateAt
                                if (attempts >= MAX_NAV_ATTEMPTS || sinceLast < RENAVIGATE_GAP_MS) {
                                    Log.d(TAG, "hard block persists (attempts=$attempts) — giving up")
                                    lastFailureDetail = FAILURE_BLOCKED
                                    dumpDiagnostics(view)
                                    finish(null)
                                } else {
                                    if (attempts >= 2 && !cookiesCleared) {
                                        cookiesCleared = true
                                        clearSiteCookies()
                                    }
                                    navigate(view)
                                    mainHandler.postDelayed({ poll(view) }, POLL_INTERVAL_MS)
                                }
                            }
                            else -> {
                                // INTERSTITIAL is the solvable challenge: it loads its script, sets
                                // the reese84 cookie and reloads on its own, so leave it alone and
                                // just keep watching. Touching the page here is what used to abort
                                // the challenge before it could finish.
                                mainHandler.postDelayed({ poll(view) }, POLL_INTERVAL_MS)
                            }
                        }
                    }
                }

                try {
                    val view = newFetchWebView(context)
                    view.addJavascriptInterface(HtmlBridge { html -> finish(html) }, "AndroidBridge")
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.isForMainFrame) {
                                Log.d(TAG, "outgoing main-frame ${request.method} headers=${request.requestHeaders}")
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Log.d(TAG, "pageFinished url=$url")
                            if (finished || !cont.isActive) return
                            if (!pollLoopStarted) {
                                pollLoopStarted = true
                                mainHandler.postDelayed({ poll(view) }, 600L)
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            if (request.isForMainFrame) {
                                Log.d(TAG, "main-frame HTTP ${errorResponse.statusCode} for ${request.url}")
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                Log.d(TAG, "main-frame error: ${error.errorCode} ${error.description}")
                                lastFailureDetail = "network error"
                                finish(null)
                            }
                        }
                    }
                    webView = view
                    attachInvisible(context, view)
                    cont.invokeOnCancellation { mainHandler.post { finish(null) } }
                    navigate(view)
                    mainHandler.postDelayed({ finish(null) }, FETCH_TIMEOUT_MS + 3_000L)
                } catch (e: Exception) {
                    Log.w(TAG, "fetch setup failed: ${e.message}")
                    finish(null)
                }
            }
        }
}
