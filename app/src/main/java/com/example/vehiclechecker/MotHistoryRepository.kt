package com.example.vehiclechecker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that turns a registration into MOT history.
 *
 * Fetching is expensive: it drives an off-screen WebView through the GOV.UK bot-protection
 * challenge. Two screens can want the same plate at the same moment — a scan opens the MOT
 * screen on top of the vehicle report, and both ask for that plate — so calls are serialised
 * and a result fetched moments ago is handed straight back instead of making the site render
 * the same page twice.
 */
object MotHistoryRepository {

    private const val TAG = "MotRepository"

    /** A live result this recent is reused without touching the network again. */
    private const val MEMO_WINDOW_MS = 5 * 60 * 1000L

    /** Keep the memo tiny; it only exists to absorb concurrent callers, not to be a cache. */
    private const val MEMO_LIMIT = 6

    private val mutex = Mutex()
    private val memo = LinkedHashMap<String, Pair<Long, MotHistoryData>>()

    /**
     * Loads MOT history for [registration]: the official DVSA API when it is configured,
     * otherwise (and on any API failure) the public GOV.UK WebView scraper.
     *
     * The returned value may be an unusable [MotHistoryData] carrying an `errorMessage`; callers
     * should check [MotHistoryScraper.isUsable] before trusting or caching it.
     */
    suspend fun load(context: Context, registration: String): MotHistoryData? {
        val reg = registration.replace(" ", "").uppercase()
        if (reg.isEmpty()) return null

        return mutex.withLock {
            val memoised = memo[reg]
            if (memoised != null && System.currentTimeMillis() - memoised.first < MEMO_WINDOW_MS) {
                Log.d(TAG, "Reusing MOT history fetched moments ago for $reg")
                return@withLock memoised.second
            }

            var mot: MotHistoryData? = null
            if (MotApiClient.isConfigured()) {
                Log.d(TAG, "Fetching MOT history via official DVSA API for $reg")
                mot = MotApiClient.fetchMotHistory(reg)
                if (!MotHistoryScraper.isUsable(mot)) {
                    Log.w(TAG, "API result unusable (${mot?.errorMessage}); falling back to WebView scraper")
                }
            } else {
                Log.d(TAG, "API not configured. Using WebView Scraper for $reg")
            }

            val result = if (MotHistoryScraper.isUsable(mot)) {
                mot
            } else {
                MotHistoryScraper.fetchMotHistory(context, reg)
            }

            if (result != null && MotHistoryScraper.isUsable(result)) {
                if (memo.size >= MEMO_LIMIT) {
                    memo.keys.firstOrNull()?.let { memo.remove(it) }
                }
                memo[reg] = System.currentTimeMillis() to result
            }
            result
        }
    }
}
