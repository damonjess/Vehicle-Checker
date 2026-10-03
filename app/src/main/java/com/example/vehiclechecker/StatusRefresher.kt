package com.example.vehiclechecker

/**
 * Decision logic for the periodic tax / MOT status refresh.
 *
 * The expiry dates shown on screen are scraped only when a plate is searched, so the
 * timestamps we store — and therefore the daily reminder notifications — drift out of
 * date after a tax renewal or a fresh MOT test. This decides what a background re-check
 * of DVLA is allowed to overwrite.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
object StatusRefresher {

    /**
     * What the refresh should persist.
     *
     * @param taxDueEpochMs stored tax due date, falling back to the previous value.
     * @param motExpiryEpochMs stored MOT expiry, falling back to the previous value.
     * @param cacheableVehicle fresh payload safe to write over the offline cache, or null
     *   when the scrape was unusable and the existing cache must be left alone.
     */
    data class Outcome(
        val taxDueEpochMs: Long?,
        val motExpiryEpochMs: Long?,
        val cacheableVehicle: VehicleData?
    )

    /**
     * @param existingTaxDueEpochMs date stored from the last successful search.
     * @param existingMotExpiryEpochMs date stored from the last successful search.
     * @param fresh payload just returned by [VehicleScraper].
     */
    fun merge(
        existingTaxDueEpochMs: Long?,
        existingMotExpiryEpochMs: Long?,
        fresh: VehicleData
    ): Outcome {
        // A failed scrape must never wipe dates we already trust.
        if (fresh.errorMessage != null) {
            return Outcome(existingTaxDueEpochMs, existingMotExpiryEpochMs, null)
        }

        val tax = DateUtils.parseFlexible(fresh.taxDueDate) ?: existingTaxDueEpochMs
        val mot = DateUtils.parseFlexible(fresh.motExpiryDate) ?: existingMotExpiryEpochMs

        // Only replace the cache when the response still identifies the vehicle; a markup
        // change on the DVLA side would otherwise blank out details we already hold.
        val cacheable = fresh.takeIf {
            it.registration.isNotBlank() && it.make.isNotBlank()
        }

        return Outcome(tax, mot, cacheable)
    }
}
